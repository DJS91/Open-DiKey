// ESP32-C3 native USB CDC bridge: DiKey control plus opt-in read-only EVAKOOL.
#include <Arduino.h>
#include <NimBLEDevice.h>
#include <atomic>
#include <ctype.h>
#include <freertos/FreeRTOS.h>
#include <freertos/semphr.h>

#if CONFIG_BT_NIMBLE_MAX_CONNECTIONS < 2
#error "DiKey and EVAKOOL require at least two NimBLE central connections"
#endif
#if defined(CONFIG_BT_CTRL_BLE_MAX_ACT) && CONFIG_BT_CTRL_BLE_MAX_ACT < 3
#error "Controller needs capacity for two BLE connections plus scanning"
#endif

namespace {
const NimBLEUUID kDiKeyService((uint16_t)0xFF10);
const NimBLEUUID kDiKeyWrite((uint16_t)0xFF11);
const NimBLEUUID kDiKeyNotify((uint16_t)0xFF12);
const NimBLEUUID kFridgeService("0000e530-1212-efde-1523-785feabcd123");
const NimBLEUUID kFridgeWrite("0000e531-1212-efde-1523-785feabcd123");
const NimBLEUUID kFridgeNotify("0000e532-1212-efde-1523-785feabcd123");
constexpr uint32_t kRetryMs = 5000;
constexpr uint32_t kPollMs = 5000;
constexpr uint32_t kStatusTimeoutMs = 20000;
constexpr uint32_t kHostLeaseMs = 35000;
constexpr size_t kMaxLine = 200;
const uint8_t kStatusPoll[] = "/SC0/1/-01\n";
SemaphoreHandle_t serialMutex;
SemaphoreHandle_t discoveryMutex;

enum class State { Off, Idle, Scanning, Connecting, Ready, Disconnected, Error };
struct Endpoint {
  explicit Endpoint(bool isFridge) : fridge(isFridge) {}
  const bool fridge;
  bool enabled = false;
  State state = State::Off;
  String detail;
  NimBLEClient *client = nullptr;
  NimBLERemoteCharacteristic *write = nullptr;
  NimBLEAddress address;
  String name;
  // Only these fields cross the BLE host/main-loop boundary.
  std::atomic<bool> discover{false};
  bool pending = false;  // Protected by discoveryMutex, along with address/name.
  std::atomic<bool> disconnected{false};
  std::atomic<int> disconnectReason{0};
  std::atomic<bool> acceptRx{false};
  std::atomic<bool> received{false};
  std::atomic<uint32_t> lastRxMs{0};
  uint32_t retryAt = 0;
  uint32_t connectedAt = 0;
  uint32_t lastPollMs = 0;
  bool stopping = false;
  uint32_t lastDisconnectMs = 0;
};
Endpoint dikey(false);
Endpoint fridge(true);

// Scan diagnostics: every advertisement the ESP32 receives, not only matches.
// Protected by discoveryMutex (written from the NimBLE host task).
struct SeenDevice {
  NimBLEAddress address;
  char name[25];
  int rssi;
  bool connectable;
  bool fridgeMatch;
  uint16_t hits;
};
constexpr size_t kMaxSeen = 40;
constexpr uint32_t kDiagScanMs = 15000;
SeenDevice seen[kMaxSeen];
size_t seenCount = 0;
uint32_t advCount = 0;
uint32_t seenSinceMs = 0;
uint32_t scanStartFailures = 0;
bool diagScanActive = false;
uint32_t diagScanUntil = 0;
bool scanRestart = false;
String lineBuf;
bool lineOverflow = false;
bool usbAnnounced = false;
uint32_t lastHbMs = 0;
uint32_t fridgeHostMs = 0;

const char *stateName(State s) {
  switch (s) {
    case State::Off: return "OFF";
    case State::Scanning: return "SCANNING";
    case State::Connecting: return "CONNECTING";
    case State::Ready: return "READY";
    case State::Disconnected: return "DISCONNECTED";
    case State::Error: return "ERROR";
    default: return "IDLE";
  }
}

void usbPrint(const String &line) {
  xSemaphoreTake(serialMutex, portMAX_DELAY);
  Serial.println(line);
  xSemaphoreGive(serialMutex);
}

void printState(const Endpoint &e) {
  String line = e.fridge ? "FRIDGE STATE " : "STATE ";
  line += stateName(e.state);
  if (!e.detail.isEmpty()) line += " " + e.detail;
  usbPrint(line);
}

void setState(Endpoint &e, State state, const String &detail = "") {
  e.state = state;
  e.detail = detail;
  printState(e);
}

void printFridgeStatus() {
  usbPrint("FRIDGE CAP READONLY_V1");
  printState(fridge);
}

void printRx(const char *prefix, uint8_t *data, size_t len) {
  if (data == nullptr || len == 0) return;
  // ATT values are bounded by the negotiated MTU; preserve each fragment intact.
  String line(prefix);
  line.reserve(line.length() + 1 + len * 2);
  line += ' ';
  constexpr char hex[] = "0123456789ABCDEF";
  for (size_t i = 0; i < len; ++i) {
    line += hex[data[i] >> 4];
    line += hex[data[i] & 15];
  }
  usbPrint(line);
}

void dikeyNotify(NimBLERemoteCharacteristic *, uint8_t *data, size_t len, bool) {
  if (dikey.acceptRx.load()) printRx("RX", data, len);
}

void fridgeNotify(NimBLERemoteCharacteristic *, uint8_t *data, size_t len, bool) {
  if (!fridge.acceptRx.load() || data == nullptr || len == 0) return;
  fridge.lastRxMs.store(millis());
  fridge.received.store(true);
  printRx("FRIDGE RX", data, len);
}

class ClientCallbacks : public NimBLEClientCallbacks {
 public:
  explicit ClientCallbacks(Endpoint &endpoint) : e(endpoint) {}
  void onDisconnect(NimBLEClient *, int reason) override {
    e.acceptRx.store(false);
    e.disconnectReason.store(reason);
    e.disconnected.store(true);
  }
  void onConnectFail(NimBLEClient *, int reason) override {
    e.disconnectReason.store(reason);
    e.disconnected.store(true);
  }
 private:
  Endpoint &e;
};
ClientCallbacks dikeyCallbacks(dikey);
ClientCallbacks fridgeCallbacks(fridge);

bool matchesName(const std::string &name, bool isFridge) {
  String lower(name.c_str());
  lower.toLowerCase();
  if (!isFridge) return lower.indexOf("dikey") >= 0;
  if (lower.indexOf("downunder_") >= 0) return true;
  String compact;
  for (unsigned i = 0; i < lower.length(); ++i) {
    if (!isspace(static_cast<unsigned char>(lower[i]))) compact += lower[i];
  }
  return compact.indexOf("coolride") >= 0;
}

void recordSeen(const NimBLEAdvertisedDevice *dev) {
  const NimBLEAddress address = dev->getAddress();
  const std::string name = dev->getName();
  const bool match = matchesName(name, true) || dev->isAdvertisingService(kFridgeService);
  xSemaphoreTake(discoveryMutex, portMAX_DELAY);
  ++advCount;
  SeenDevice *slot = nullptr;
  for (size_t i = 0; i < seenCount; ++i) {
    if (seen[i].address == address) { slot = &seen[i]; break; }
  }
  if (slot == nullptr && seenCount < kMaxSeen) {
    slot = &seen[seenCount++];
    slot->address = address;
    slot->name[0] = '\0';
    slot->fridgeMatch = false;
    slot->hits = 0;
  }
  if (slot != nullptr) {
    if (!name.empty()) {
      size_t n = 0;
      for (; n < name.size() && n < sizeof(slot->name) - 1; ++n) {
        const uint8_t c = static_cast<uint8_t>(name[n]);
        slot->name[n] = (c < 32 || c > 126) ? '?' : static_cast<char>(c);
      }
      slot->name[n] = '\0';
    }
    slot->rssi = dev->getRSSI();
    slot->connectable = dev->isConnectable();
    slot->fridgeMatch = slot->fridgeMatch || match;
    if (slot->hits < UINT16_MAX) ++slot->hits;
  }
  xSemaphoreGive(discoveryMutex);
}

void clearSeen() {
  xSemaphoreTake(discoveryMutex, portMAX_DELAY);
  seenCount = 0;
  advCount = 0;
  seenSinceMs = millis();
  xSemaphoreGive(discoveryMutex);
}

class ScanCallbacks : public NimBLEScanCallbacks {
  void onResult(const NimBLEAdvertisedDevice *dev) override {
    recordSeen(dev);
    for (Endpoint *e : {&dikey, &fridge}) {
      if (!e->discover.load()) continue;
      const NimBLEUUID &svc = e->fridge ? kFridgeService : kDiKeyService;
      if (!matchesName(dev->getName(), e->fridge) && !dev->isAdvertisingService(svc)) continue;
      xSemaphoreTake(discoveryMutex, portMAX_DELAY);
      if (e->discover.load() && !e->pending) {
        e->address = dev->getAddress();
        e->name = dev->getName().c_str();
        for (unsigned i = 0; i < e->name.length(); ++i) {
          const uint8_t c = static_cast<uint8_t>(e->name[i]);
          if (c < 32 || c == 127) e->name.setCharAt(i, ' ');
        }
        e->name.trim();
        if (e->name.isEmpty()) e->name = "(no name)";
        e->pending = true;
        e->discover.store(false);
      }
      xSemaphoreGive(discoveryMutex);
    }
  }
};
ScanCallbacks scanCallbacks;

// Retain the two client objects across reconnects. In NimBLE 2.3 deleteClient()
// on a connected client schedules deletion INSIDE its disconnect callback.
// Never use that path: stop in loop(), then reuse the disconnected client.
void releaseClient(Endpoint &e) {
  e.acceptRx.store(false);
  e.write = nullptr;
  e.stopping = true;
  e.lastDisconnectMs = millis();
  if (e.client != nullptr && e.client->isConnected()) e.client->disconnect();
}

void clearCandidate(Endpoint &e) {
  e.discover.store(false);
  xSemaphoreTake(discoveryMutex, portMAX_DELAY);
  e.pending = false;
  xSemaphoreGive(discoveryMutex);
}

void retry(Endpoint &e, const String &reason, bool error) {
  clearCandidate(e);
  releaseClient(e);
  e.retryAt = millis() + kRetryMs;
  setState(e, error ? State::Error : State::Disconnected, reason);
}

void refreshScan() {
  NimBLEScan *scan = NimBLEDevice::getScan();
  const bool needed = dikey.discover.load() || fridge.discover.load() || diagScanActive;
  if (needed && scan->isScanning() && scanRestart) {
    // The duplicate filter hides devices already seen in this scan run, e.g. a fridge
    // seen while only DiKey was wanted. Restart so newly wanted targets are reported.
    scan->stop();
  }
  scanRestart = false;
  if (needed && !scan->isScanning()) {
    // Reset duplicate cache on each restart so previously seen devices can retry.
    if (!scan->start(0, false, true)) {
      ++scanStartFailures;
      usbPrint("LOG BLE scan start failed");
      for (Endpoint *e : {&dikey, &fridge}) {
        if (e->discover.load()) retry(*e, "scan start failed", true);
      }
    }
  } else if (!needed && scan->isScanning()) {
    scan->stop();
  }
}

void printDiag() {
  NimBLEScan *scan = NimBLEDevice::getScan();
  xSemaphoreTake(discoveryMutex, portMAX_DELAY);
  const size_t count = seenCount;
  static SeenDevice copy[kMaxSeen];
  for (size_t i = 0; i < count; ++i) copy[i] = seen[i];
  const uint32_t adv = advCount;
  const uint32_t since = seenSinceMs;
  xSemaphoreGive(discoveryMutex);
  String head = "FRIDGE DIAG BEGIN scanning=";
  head += scan->isScanning() ? "1" : "0";
  head += " diagScan=" + String(diagScanActive ? 1 : 0);
  head += " fridgeSearch=" + String(fridge.discover.load() ? 1 : 0);
  head += " dikeySearch=" + String(dikey.discover.load() ? 1 : 0);
  head += " adv=" + String(adv) + " unique=" + String(count);
  head += " windowS=" + String((millis() - since) / 1000);
  head += " scanFail=" + String(scanStartFailures);
  head += " dikey=" + String(stateName(dikey.state));
  head += " fridge=" + String(stateName(fridge.state));
  usbPrint(head);
  for (size_t i = 0; i < count; ++i) {
    String line = "FRIDGE SEEN ";
    line += copy[i].address.toString().c_str();
    line += " " + String(copy[i].rssi);
    line += copy[i].connectable ? " C" : " N";
    line += copy[i].fridgeMatch ? " M " : " - ";
    line += String(copy[i].hits) + " ";
    line += copy[i].name[0] ? copy[i].name : "(no name)";
    usbPrint(line);
  }
  usbPrint("FRIDGE DIAG END");
}

void connectEndpoint(Endpoint &e) {
  NimBLEDevice::getScan()->stop();
  setState(e, State::Connecting, e.name + " " + e.address.toString().c_str());
  if (e.client == nullptr) e.client = NimBLEDevice::createClient();
  if (e.client == nullptr) {
    retry(e, "no BLE client available", true);
    return;
  }
  e.client->setClientCallbacks(e.fridge ? &fridgeCallbacks : &dikeyCallbacks, false);
  e.client->setConnectTimeout(5000);
  if (!e.client->connect(e.address)) {
    retry(e, "BLE connection failed", true);
    return;
  }
  NimBLERemoteService *svc = e.client->getService(e.fridge ? kFridgeService : kDiKeyService);
  if (svc == nullptr) {
    retry(e, "required GATT service missing", true);
    return;
  }
  e.write = svc->getCharacteristic(e.fridge ? kFridgeWrite : kDiKeyWrite);
  auto *notify = svc->getCharacteristic(e.fridge ? kFridgeNotify : kDiKeyNotify);
  if (e.write == nullptr || notify == nullptr || !notify->canNotify() ||
      (!e.write->canWrite() && !e.write->canWriteNoResponse())) {
    retry(e, "required writable/notify characteristics missing", true);
    return;
  }
  e.received.store(false);
  e.acceptRx.store(true);
  if (!notify->subscribe(true, e.fridge ? fridgeNotify : dikeyNotify, true)) {
    retry(e, "notification subscription failed", true);
    return;
  }
  e.connectedAt = millis();
  e.lastPollMs = e.connectedAt - kPollMs;
  if (!e.fridge) setState(e, State::Ready, e.address.toString().c_str());
  else setState(e, State::Connecting, "subscribed; waiting for status " +
                e.name + " " + e.address.toString().c_str());
}

void serviceEndpoint(Endpoint &e) {
  if (e.stopping) {
    if (e.client != nullptr && e.client->isConnected()) {
      if (millis() - e.lastDisconnectMs >= 1000) {
        e.lastDisconnectMs = millis();
        if (!e.client->disconnect()) {
          setState(e, State::Error, "disconnect pending; BLE fallback not safe yet");
        }
      }
      return;
    }
    // isConnected becomes false only AFTER NimBLE's onDisconnect callback.
    // OFF is the host's permission to start its direct BLE fallback.
    e.stopping = false;
    if (e.client != nullptr) e.client->deleteServices();
    e.disconnected.store(false);
    e.received.store(false);
    if (!e.enabled) setState(e, State::Off);
  }
  if (!e.enabled) return;
  if (e.disconnected.exchange(false)) {
    retry(e, "BLE disconnected " + String(e.disconnectReason.load()), false);
    return;
  }
  const uint32_t now = millis();
  if (e.write == nullptr) {
    if (static_cast<int32_t>(now - e.retryAt) < 0) return;
    if (e.state != State::Scanning) {
      setState(e, State::Scanning);
      e.discover.store(true);
      scanRestart = true;
    }
    bool pending;
    xSemaphoreTake(discoveryMutex, portMAX_DELAY);
    pending = e.pending;
    e.pending = false;
    xSemaphoreGive(discoveryMutex);
    if (pending) connectEndpoint(e);
    return;
  }
  if (!e.fridge || !e.client->isConnected()) return;
  const bool received = e.received.load();
  if (received && e.state != State::Ready) {
    setState(e, State::Ready, e.name + " " + e.address.toString().c_str());
  }
  const uint32_t lastStatus = received ? e.lastRxMs.load() : e.connectedAt;
  if (millis() - lastStatus >= kStatusTimeoutMs) {
    retry(e, "status notification timeout (20s)", true);
    return;
  }
  if (now - e.lastPollMs >= kPollMs) {
    e.lastPollMs = now;
    // This is the ONLY fridge write path: fixed read-only status request.
    if (!e.write->writeValue(kStatusPoll, sizeof(kStatusPoll) - 1,
                            !e.write->canWriteNoResponse())) {
      retry(e, "status poll failed", true);
    }
  }
}

int hexVal(char c) {
  if (c >= '0' && c <= '9') return c - '0';
  if (c >= 'a' && c <= 'f') return c - 'a' + 10;
  if (c >= 'A' && c <= 'F') return c - 'A' + 10;
  return -1;
}

void handleTx(const String &hex) {
  if (dikey.state != State::Ready || dikey.write == nullptr ||
      dikey.client == nullptr || !dikey.client->isConnected()) {
    usbPrint("TXERR not ready");
    return;
  }
  uint8_t data[64];
  size_t len = 0;
  int hi = -1;
  for (unsigned i = 0; i < hex.length(); ++i) {
    if (hex[i] == ' ' || hex[i] == '\t') continue;
    int v = hexVal(hex[i]);
    if (v < 0 || (hi >= 0 && len >= sizeof(data))) {
      usbPrint("TXERR bad hex");
      return;
    }
    if (hi < 0) hi = v;
    else { data[len++] = (hi << 4) | v; hi = -1; }
  }
  if (hi >= 0 || len == 0) { usbPrint("TXERR bad hex"); return; }
  usbPrint(dikey.write->writeValue(data, len, dikey.write->canWrite()) ?
           "TXOK" : "TXERR gatt write");
}

void announceUsb() {
  usbPrint("HELLO ODK-BRIDGE");
  printState(dikey);
  printFridgeStatus();
}

void stopFridge(const char *reason) {
  if (!fridge.enabled && !fridge.stopping && fridge.state == State::Off) {
    printState(fridge);
    return;
  }
  fridge.enabled = false;
  clearCandidate(fridge);
  releaseClient(fridge);
  setState(fridge, State::Disconnected, reason);
}

void handleLine(String line) {
  line.trim();
  if (line.isEmpty()) return;
  if (line.equalsIgnoreCase("FRIDGE STATUS")) {
    fridgeHostMs = millis();
    printFridgeStatus();
    return;
  }
  if (line.equalsIgnoreCase("FRIDGE ON")) {
    fridgeHostMs = millis();
    if (!fridge.enabled) {
      fridge.enabled = true;
      fridge.retryAt = millis();
      setState(fridge, State::Disconnected, "enabled; selecting first matching fridge");
    } else if (fridge.state == State::Error) {
      fridge.retryAt = millis();
    }
    printFridgeStatus();
    return;
  }
  if (line.equalsIgnoreCase("FRIDGE OFF")) {
    stopFridge("stopping; waiting for BLE disconnect");
    return;
  }
  if (line.equalsIgnoreCase("FRIDGE DIAG")) {
    printDiag();
    return;
  }
  if (line.equalsIgnoreCase("FRIDGE DIAG SCAN")) {
    clearSeen();
    diagScanActive = true;
    diagScanUntil = millis() + kDiagScanMs;
    scanRestart = true;
    usbPrint("FRIDGE DIAG SCANNING " + String(kDiagScanMs / 1000));
    return;
  }
  String upper = line;
  upper.toUpperCase();
  if (upper.startsWith("FRIDGE")) {
    usbPrint("FRIDGE STATE ERROR unsupported command; use ON, OFF, STATUS, DIAG or DIAG SCAN");
    return;
  }
  if (line.equalsIgnoreCase("PING")) { usbPrint("PONG"); return; }
  if (line.equalsIgnoreCase("ID")) {
    usbPrint("ID ODK-BRIDGE");
    printState(dikey);
    return;
  }
  if (line.equalsIgnoreCase("STATUS")) { printState(dikey); return; }
  if (line.equalsIgnoreCase("SCAN")) {
    clearCandidate(dikey);
    releaseClient(dikey);
    dikey.retryAt = millis();
    setState(dikey, State::Idle);
    return;
  }
  if (line.startsWith("TX ") || line.startsWith("tx ")) {
    handleTx(line.substring(3));
    return;
  }
  usbPrint("ERR unknown");
}
}  // namespace

void setup() {
  serialMutex = xSemaphoreCreateMutex();
  discoveryMutex = xSemaphoreCreateMutex();
  configASSERT(serialMutex != nullptr && discoveryMutex != nullptr);
  Serial.begin(115200);
  const uint32_t start = millis();
  while (!Serial && millis() - start < 2000) delay(10);
  lineBuf.reserve(kMaxLine);
  seenSinceMs = millis();
  NimBLEDevice::init("ODK-Bridge");
  auto *scan = NimBLEDevice::getScan();
  scan->setScanCallbacks(&scanCallbacks, true);
  scan->setActiveScan(true);
  scan->setInterval(80);
  scan->setWindow(40);
  scan->setMaxResults(0);
  dikey.enabled = true;
  dikey.state = State::Idle;
  serviceEndpoint(dikey);
  refreshScan();
  if (Serial) { announceUsb(); usbAnnounced = true; }
}

void loop() {
  const bool usbUp = static_cast<bool>(Serial);
  if (usbUp && !usbAnnounced) { announceUsb(); usbAnnounced = true; }
  else if (!usbUp && usbAnnounced) {
    usbAnnounced = false;
    if (fridge.enabled) stopFridge("USB disconnected; waiting for BLE disconnect");
  }
  for (size_t budget = 0; budget < 256 && Serial.available() > 0; ++budget) {
    const char c = static_cast<char>(Serial.read());
    if (c == '\n') {
      if (lineOverflow) {
        String upper = lineBuf;
        upper.trim();
        upper.toUpperCase();
        usbPrint(upper.startsWith("FRIDGE") ? "FRIDGE STATE ERROR input line too long" :
                 "ERR input line too long");
      } else handleLine(lineBuf);
      lineBuf = "";
      lineOverflow = false;
    } else if (c != '\r' && !lineOverflow) {
      if (lineBuf.length() < kMaxLine) lineBuf += c;
      else lineOverflow = true;  // Discard the WHOLE line, never execute a truncated TX.
    }
  }
  if (fridge.enabled && millis() - fridgeHostMs >= kHostLeaseMs) {
    stopFridge("host lease expired (35s); waiting for BLE disconnect");
  }
  serviceEndpoint(dikey);
  serviceEndpoint(fridge);
  if (diagScanActive && static_cast<int32_t>(millis() - diagScanUntil) >= 0) {
    diagScanActive = false;
    printDiag();
  }
  refreshScan();
  const uint32_t now = millis();
  if (usbUp && now - lastHbMs >= 8000) {
    lastHbMs = now;
    usbPrint(String("HB ") + stateName(dikey.state));
  }
  delay(5);
}
