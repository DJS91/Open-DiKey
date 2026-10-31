# Open DiKey

Open-source Android app for the **DiKey** aftermarket center-console controller.

Replaces the vendor app DiKey Smart Link (`迪铠智联`). Runs on DiLink 5 head units, controls DiKey lighting and climate, and provides vehicle information, ADAS settings, Sentry integration, and Pet Mode through local BYD APIs.

> **Note:** The commercial DiKey hardware is sold as _BYD Shark 6 DiKey Vehicle Intelligent Keys_ on [Alibaba](https://www.alibaba.com/product-detail/BYD-Shark-6-DiKey-Vehicle-Intelligent_1601923176905.html). Not affiliated with DiKey, BYD, or the seller. Use at your own risk.

<img width="400" alt="image" src="https://github.com/user-attachments/assets/203e3468-4319-4792-8115-004902720a83" /><img width="400" alt="image" src="https://github.com/user-attachments/assets/38c9f9ac-c14b-47fd-9aca-f03c557aff9c" />
<img width="400" alt="image" src="https://github.com/user-attachments/assets/332d7a0f-0cef-4ad0-888a-090547291e89" /><img width="400" alt="image" src="https://github.com/user-attachments/assets/62957600-bbc8-445e-ad51-03ba85a635f3" />
<img width="400" alt="image" src="https://github.com/user-attachments/assets/3d2ea2da-9456-43e0-b53a-2b65fdb2ad40" />

---

## Compatibility

| Vehicle     | Status    |
| ----------- | --------- |
| BYD Shark 6 | Supported |

Other vehicles are untested.

---

## Installation

### What you need

- DiLink 5 head unit with USB debugging enabled
- **ESP32-C3 Super Mini board** — [AliExpress](https://www.aliexpress.com/item/1005012631245655.html) or [Amazon AU](https://www.amazon.com.au/dp/B0GR475MP4)
- USB cable

### Steps

**1. Flash the USB bridge**

On a PC in Chrome or Edge, download `dikey-usb-bridge-c3.bin` from the [latest release](https://github.com/DJS91/Open-DiKey/releases), then open **[ESPWebTool](https://esptool.spacehuhn.com/)**:

1. Connect — choose **USB JTAG/serial debug unit**. If it doesn't show up, hold **BOOT** while plugging it in.
2. Delete every existing address slot (there will be about 4)
3. Add a new slot at **`0x0`**
4. Choose `dikey-usb-bridge-c3.bin`

It should look like this before clicking Program:

<img width="400" alt="image" src="https://github.com/user-attachments/assets/1a431a46-d842-4cae-9d60-45c0a3e60526" />

Then click Program.

**2. Install the app**

Sideload the APK from the [latest release](https://github.com/DJS91/Open-DiKey/releases) onto the head unit and launch it **once**.

**3. Grant permissions**

- Accept **Allow USB debugging**
- Allow the hidden-API exemption (app restarts)
- Plug the C3 board into the car's **USB-C port** (USB-A ports may not register)
- If USB permission doesn't pop up, unplug and replug the board while staying in the app
- If using the BLE connection instead, allow **Nearby devices / Bluetooth**
- Allow Open DiKey in any DiLink **auto-start / run in background** menu if available

Open DiKey once after each cold boot or deep sleep. A foreground listener then keeps the USB/BLE connection alive while the app is in the background (see [autostart.md](autostart.md)).

---

## Features

**Colors** — Set ambient bars (left, middle, right) and DiKey backlight with HSV wheels. Pick modes (solid / breath / blink / flow / off). Day and night profiles switch automatically from the vehicle ambient-light sensor (same signal as auto headlights / tunnels).

**Button mapping** — Down click keeps climate (as printed) unless remapped. Down long, up, and up long can open apps or run Android Intents (activity / broadcast).

**Dials** — Left dial = passenger, right dial = driver. Click to switch between **temp** and **fan**, rotate to adjust.

**Vehicle info** — Live dashboard for battery, range, fuel, driving data, tyre pressures, vehicle attitude, and head-unit resource usage.

**ADAS** — View current driver-assistance settings or save and apply a custom ELKA, LDA, AEB, and DMS profile. Long-press the ADAS tile to configure automatic application when the app starts.

**Sentry** — Install, update, and open the separate [Strike](https://github.com/sp-hy/Strike) sentry app.

**Pet Mode** — Keeps the display awake and periodically enforces a configurable climate profile while the vehicle is **READY**. Fan speed, temperature, recirculation, and wind direction can be configured; the previous climate and brightness settings are restored on exit.

**Camping fridge (experimental)** — The home tile defaults to **ESP32 USB (read-only)**. Flash the updated ESP32-C3 bridge firmware as well as installing the APK. Once the app has opened the home page, the bridge independently discovers an EVAKOOL Down Under / CoolRide fridge, subscribes to notifications and queries status every five seconds alongside its existing DiKey connection. The tile shows left/right temperatures and targets in the fridge's reported units. There are no temperature or power controls on this transport. The ESP32 fridge link stays connected while the app runs (including on other pages), so readings appear immediately on return; the bridge drops it 35 seconds after the app stops polling or USB disconnects. Opening or reconnecting never changes fridge power or targets.

For the first ESP32 test, disconnect the official fridge app (temporarily turn off the phone's Bluetooth if necessary), power the fridge on nearby, connect the bridge using the existing DiKey USB connection, and open the home page. No Android Bluetooth or Location permissions are needed for the USB fridge path. Use only one matching fridge in range: the bridge automatically chooses the first matching advertisement and shows its identity in **Connection details**. Confirm temperatures and targets against the fridge, then exercise the DiKey dials/buttons while watching fridge readings. Test fridge power loss/recovery, USB reconnect and leaving/returning to home. Physical hardware and stable simultaneous connections still need validation; this is not a certified firmware release.

Tap the tile and choose **Use Android Bluetooth backup** to retain the original direct-BLE path. This explicit choice is saved; there is no automatic failover that could make both radios compete for the fridge. With USB attached, the app waits for the bridge to acknowledge **FRIDGE OFF** before starting Android BLE. If old/unresponsive bridge firmware cannot acknowledge it, update the firmware or unplug USB before using the Android backup. Switch back using **Use ESP32 USB (read-only)**. The Android selection is retained across transport changes.

On the Android backup, select the fridge once using **Select / change fridge**. Enable head-unit Bluetooth, allow Nearby devices (Location permission and system Location on Android 11), and keep home visible for automatic connection/retries. This path retains independent target controls with pending/confirmed results. Use only targets supported by your model; the -99 to 99 input bound is a test-client guard, not a model rating. Both paths clear live readings after disconnect or stale status. The protocol is based on the Down Under II 2.0.0 APK (E530 service, E531 writes without response, E532 notifications); the Android backup requires usable head-unit BLE GATT, whereas ESP32 USB bypasses it.

The fridge picker shows scan progress, nearby/visible device counts, and explicit empty/error messages. Use **Scan again** after a scan finishes or after enabling Bluetooth/Location. If nearby devices are detected but the fridge is not listed, enable **Show all nearby BLE devices** to include renamed or unrecognized devices; select only your fridge. Expected DownUnder/CoolRide names or the advertised EVAKOOL service are marked as matches. Unknown devices still have to pass the EVAKOOL GATT service/property check before any commands are sent.

Tap **Connection details** in the tile menu to see the last received frame and connection error when testing. The parser accepts fragmented/coalesced newline-delimited frames, or complete CSV frames after a 500 ms notification gap. Unit codes 1 (C) and 2 (F) are accepted; unknown codes are shown as errors rather than guessed. Capture the diagnostic frame if your firmware differs. Automatic retries never resend a target change.

For head-unit scan problems, open **Debug (bug icon) > Bluetooth / fridge diagnostics**. It checks SDK version, OS-advertised BLE support, adapter/scanner availability, permission grants, system Location, Location AppOp and Android-reported GATT connections, alongside the DiKey connection/USB state and ESP32 fridge firmware/status, live readings and recent ESP32 fridge events. **ESP32 scan (15s)** lists every advertiser the bridge hears (address, RSSI, connectable, fridge match, name) without connecting to anything; **ESP32 report** shows the latest list. Buttons request the appropriate runtime permissions or open system settings; returning refreshes the checks. On Android 11 there is no Bluetooth runtime permission toggle: grant Location and enable system Location.

**Run BLE scan (15s)** counts raw advertisement callbacks *before* fridge-name/connectability filters, shows elapsed time, sample names/addresses/RSSI/service UUIDs and Android scan error codes, and distinguishes a scan request from confirmed reception. A request returning without exception is not proof of radio activity; zero callbacks is inconclusive, while any callback confirms BLE reception. The test stops when leaving the page and never disconnects DiKey, connects to a peripheral or sends commands. Wait 30 seconds between scan starts to avoid throttling. Use **Copy** or **Share** to export the report; review nearby device identities before sharing. Third-party BLE restrictions or inaccurate feature reporting in BYD firmware may still need ADB investigation.

**Background listener** — After you open Open DiKey, a foreground service keeps the USB/BLE DiKey link alive while the UI is in the background. Open the app once after power-on (see [autostart.md](autostart.md) for DiLink limitations).

**In-app updates** — Home gear icon → **Check for updates** downloads the latest `open-dikey.apk` from [GitHub Releases](https://github.com/DJS91/Open-DiKey/releases).

---

## Button reference

| Button | Downward press                |
| ------ | ----------------------------- |
| 1      | Climate on/off                |
| 2      | A/C compressor                |
| 3      | Cycle wind                    |
| 4      | Recirc                        |
| 5      | Auto                          |
| 6      | Front defog                   |
| 7      | Rear window / mirrors         |
| 8      | Air only                      |
| 9      | Sync (driver/passenger temps) |
| 10     | Max cool                      |

---

## For developers

### Source structure

Android paths below are relative to `app/src/main/java/com/sphy/airconcontroller/`.

| Path                              | Purpose                       |
| --------------------------------- | ----------------------------- |
| `MainActivity.kt`                 | Home screen                   |
| `ColorConfigActivity.kt`          | Ambient + backlight           |
| `ButtonMappingActivity.kt`        | Button config                 |
| `VehicleInfoActivity.kt`          | Live vehicle dashboard        |
| `AdasActivity.kt`                 | ADAS profiles                 |
| `SentryActivity.kt`               | Strike installer and launcher |
| `PetModeActivity.kt`              | Pet Mode runtime              |
| `PetModeConfigActivity.kt`        | Pet Mode profile              |
| `SettingsHubActivity.kt`          | Settings and app updates      |
| `dikey/DiKeySession.kt`           | USB/BLE session               |
| `dikey/DiKeyClimateMapper.kt`     | Climate controls              |
| `dikey/DiKeyUpMapper.kt`          | App launcher                  |
| `usb/DiKeyUsbBridge.kt`           | USB serial                    |
| `boot/DiKeyListenService.kt`      | Background listener           |
| `adb/AdbPermissionManager.kt`     | Grants + hidden-API           |
| `byd/Dilink5SdkInjector.kt`       | OEM SDK loader                |
| `byd/BydAcController.kt`          | Climate API                   |
| `byd/BydAdasController.kt`        | ADAS API                      |
| `byd/BydVehicleInfoController.kt` | Vehicle data API              |
| `firmware/src/DiKeyUsbBridge/`    | C3 bridge firmware            |

### Assets

- [`assets/Docs/vendor-re/PROTOCOL.md`](assets/Docs/vendor-re/PROTOCOL.md) — DiKey BLE/USB protocol
- [`assets/Docs/Offline/`](assets/Docs/Offline/) — Offline docs portal
- [`assets/BYD API/`](assets/BYD%20API/) — BYD Auto API V1.0.5

---

## Credits

DiLink 5 permission and SDK loading follow **[BYD Trip Stats](https://github.com/angoikon/byd-trip-stats)** by Angelos Oikonomou and contributors.

Built with [dadb](https://github.com/mobile-dev-inc/dadb).

Vendor manuals redistributed for offline use with purchased hardware; copyright remains with respective owners.

### Icons

- [Wheel](https://thenounproject.com/icon/wheel-8052406/) by Asiah from [Noun Project](https://thenounproject.com/) (CC BY)
- [Color wheel](https://thenounproject.com/icon/color-wheel-8403265/) by Fahad Hashmi from [Noun Project](https://thenounproject.com/) (CC BY)
- [Button](https://thenounproject.com/icon/button-8215836/) by Larea from [Noun Project](https://thenounproject.com/) (CC BY)
- [Dial](https://thenounproject.com/icon/dial-4575189/) by Zach Bogart from [Noun Project](https://thenounproject.com/) (CC BY)
- [adas](https://thenounproject.com/icon/adas-4256808/) by Arijit Dey from [Noun Project](https://thenounproject.com/) (CC BY)
- [recording](https://thenounproject.com/icon/recording-7103165/) by Jonathan Wong from [Noun Project](https://thenounproject.com/) (CC BY)
- [Paw](https://thenounproject.com/icon/paw-626047/) by Rafiico Creative Studio from [Noun Project](https://thenounproject.com/) (CC BY)
