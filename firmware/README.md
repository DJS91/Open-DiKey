# ESP32-C3 USB / BLE bridge

The bridge maintains separate DiKey and opt-in EVAKOOL BLE central connections.
DiKey discovery starts at boot. Fridge discovery is **disabled at boot**:
Android must send `FRIDGE ON` when it owns the USB fridge transport and
`FRIDGE OFF` before using its direct-phone BLE fallback. Losing the USB
connection releases only the fridge. A 35-second host lease, refreshed by
`FRIDGE ON` or `FRIDGE STATUS`, also releases it if the Android process dies
without closing USB cleanly. The host should request status every ten seconds.

## USB protocol

Lines are LF terminated at 115200 baud. Existing DiKey commands and responses
remain separate: `PING`, `ID`, `STATUS`, `SCAN`, `TX <hex>`, `STATE`, `RX`,
`TXOK`, `TXERR`, and `HB`. `SCAN` resets only DiKey. DiKey TX remains limited
to 64 bytes.

Fridge commands (case insensitive):

| Host command | Effect |
| --- | --- |
| `FRIDGE ON` | Enable discovery and status polling; idempotent |
| `FRIDGE OFF` | Disconnect/stop only the fridge; DiKey continues |
| `FRIDGE STATUS` | Report capability and current fridge state |
| `FRIDGE SET <1\|2> <temp>` | Write one compartment setpoint (1 = left `/SC3`, 2 = right `/SC5`), whole number -99..99 in the fridge's units; only while READY. Replies `FRIDGE SETOK <c> <temp>` or `FRIDGE SETERR <reason>`, then polls status ~0.4 s later so the host can confirm |
| `FRIDGE MODE ECO\|MAX` | Write the compressor mode (`/SC6/1/001` Eco, `/SC6/1/002` Max); only while READY. Replies `FRIDGE MODEOK ECO\|MAX` or `FRIDGE MODEERR <reason>`, then polls status ~0.4 s later (status field 9: 2 = Max) |
| `FRIDGE POWER ON\|OFF` | Switch the fridge itself on/off (`/SC1/1/001` / `/SC1/1/000`); only while READY. Not the same as `FRIDGE ON/OFF`, which only controls the bridge's link. Replies `FRIDGE POWEROK ON\|OFF` or `FRIDGE POWERERR <reason>`, then polls status ~0.4 s later (status field 1) |
| `FRIDGE DIAG` | Report every advertiser the ESP32 has received since the last diagnostic scan (up to 40) |
| `FRIDGE DIAG SCAN` | Clear the list, keep scanning 15 s (even if nothing else needs it), then report automatically |

Diagnostic output (never connects or writes to any device):

```text
FRIDGE DIAG SCANNING 15
FRIDGE DIAG BEGIN scanning=1 diagScan=0 fridgeSearch=1 dikeySearch=0 adv=N unique=N windowS=N scanFail=N dikey=READY fridge=SCANNING
FRIDGE SEEN <address> <rssi> <C|N connectable> <M fridge match|-> <count> <name>
FRIDGE DIAG END
```

The scan's duplicate filter is reset whenever DiKey or the fridge starts
searching, so a device first seen while it was not wanted is reported again.
The app shows these results in Debug → Bluetooth / fridge diagnostics →
ESP32 scan (15s).

The initial USB announcement and `FRIDGE STATUS` include
`FRIDGE CAP CONTROL_V3` (older firmware sent `READONLY_V1`, `CONTROL_V1` or
`CONTROL_V2`; the app accepts all four, shows target controls from `CONTROL_V1`,
Eco/Max from `CONTROL_V2` and the power switch only for `CONTROL_V3`). The bridge
can send only the status query, `FRIDGE SET` setpoints, `FRIDGE MODE` and
`FRIDGE POWER`; battery
protection and other settings are never written. Fridge output is exclusively
namespaced:

```text
FRIDGE CAP CONTROL_V3
FRIDGE STATE OFF
FRIDGE STATE SCANNING
FRIDGE STATE CONNECTING <detail>
FRIDGE STATE READY <advertised name> <BLE address>
FRIDGE STATE DISCONNECTED <reason>
FRIDGE STATE ERROR <explanation>
FRIDGE RX <uppercase hex bytes>
FRIDGE SETOK <1|2> <temp>
FRIDGE SETERR <reason>
FRIDGE MODEOK <ECO|MAX>
FRIDGE MODEERR <reason>
FRIDGE POWEROK <ON|OFF>
FRIDGE POWERERR <reason>
```

`ON` also reports capability/state. Errors never emit DiKey `STATE`,
`TXOK`, or `TXERR`. Notifications are forwarded individually and unchanged,
including partial ASCII frames: the host must assemble/decode them.
Every output line is protected by a mutex, including notification callbacks;
advertised names have control characters removed from their displayed form.
USB input is limited to 200 characters; overlong lines are discarded through
LF, not executed as truncated commands.

## Discovery and read-only operation

Matches are case-insensitive names containing `downunder_`, names containing
`coolride` after whitespace removal, or advertisements containing service
`0000e530-1212-efde-1523-785feabcd123`. The **first matching fridge** is selected,
not a remembered/paired identity; its name and address appear in CONNECTING
and READY output. Use only one matching fridge nearby when identity matters.
This is not an authenticated device selection mechanism.

After connecting, the bridge verifies service E530, writable E531, and
notifiable E532 (all using suffix `-1212-efde-1523-785feabcd123`). It subscribes
to E532 **before** polling with ASCII `/SC0/1/-01\n` every five seconds; the
only other writes are the explicit host `SET`, `MODE` and `POWER` commands
below. Write-without-response is preferred;
write-with-response is used only if that is the supported property.
There are no battery-protection or arbitrary-fridge-write commands.

READY means subscribed and at least one nonempty notification received; it
does not certify a complete/valid application status frame. Initial or ongoing
notification silence for 20 seconds produces ERROR and reconnects. Failed
connections, discovery/subscription/poll failures, and disconnections retry
after five seconds. Both endpoints share the scanner but have independent
clients, state, retry timers, and GATT characteristics. The two client objects are retained and reused across reconnects (NimBLE
refreshes cached GATT attributes on connection). Client creation,
disconnection and cleanup run only on the main loop, never inside BLE
callbacks. OFF is acknowledged only after the actual BLE connection is down;
until then DISCONNECTED with a stopping detail (or ERROR if disconnect fails)
is reported, and the host must not start its BLE fallback.
Synchronous GATT/connection operations can briefly delay USB and
poll scheduling (connection timeout five seconds).

## Build and flash

From `firmware`, run:

```powershell
python -m platformio run -e c3_super_mini
```

The pinned environment uses pioarduino Arduino-ESP32 3.x and NimBLE-Arduino
2.5.0 (2.3.8+ is required: 2.3.0-2.3.7 crash during BLE init on Arduino
core 3.3.7+, which caused an "Instruction access fault" boot loop). `CONFIG_BT_NIMBLE_MAX_CONNECTIONS=3` provides capacity for both centrals;
a compile-time guard rejects configurations below two. The selected C3 SDK
provides six controller BLE activities; a second guard requires room for two
connections plus scanning. Native C3 USB CDC is
enabled. The repository post-build script merges bootloader, partition table,
boot-app marker and application into `dikey-usb-bridge-c3.bin`. Flash that
merged image at `0x0` (4 MB DIO flash).

### Build verification (2026-10-30)

The normal PlatformIO build succeeded with Arduino-ESP32 3.3.11 / ESP-IDF
5.5.5, NimBLE-Arduino 2.5.0 and RISC-V GCC 14.2.0. RAM usage was 24,032 /
327,680 bytes; program usage was 593,045 / 1,310,720 bytes. Esptool confirmed
ESP32-C3, DIO, 4 MB, and valid application checksum and validation hash.
The 683,008-byte merged image's bootloader, partition table, boot-app marker
and application were checked against their inputs at their flash offsets.

The generated Windows esptool console launcher was denied execution in this
environment. The build/merge script now uses esptool's supported Python-module
entrypoint for image conversion and merging and propagates merge failures.
The platform's additional, optional `firmware.factory.bin` hook still reports
that launcher restriction; the repository's requested
`dikey-usb-bridge-c3.bin` is successfully generated and verified instead.
No SDK sources or system installations were modified. Upstream NimBLE /
IDF 5.5.5 emits macro-redefinition warnings for MSYS/ACL buffer settings; these
were not suppressed or patched.

No physical ESP32-C3/DiKey/fridge was available for runtime validation. The
multi-link, polling, USB ownership and reconnection checks below remain
required on hardware.

## Hardware acceptance checks

Compilation is not a substitute for these checks on an ESP32-C3 and fridge:

1. Boot: observe HELLO, DiKey state and `FRIDGE CAP CONTROL_V3` / OFF.
   DiKey connects and accepts existing PING/ID/STATUS/SCAN/TX commands.
2. Send `FRIDGE STATUS`: capability plus OFF; no fridge BLE connection.
3. Send `FRIDGE ON` with DiKey already READY. Verify both remain connected,
   named/addressed fridge READY and unchanged uppercase notification fragments.
   Capture BLE traffic to confirm E532 subscription precedes E531 polling and
   the only unsolicited fridge command is `/SC0/1/-01\n`.
4. Repeat with fridge first and DiKey appearing later. Disconnect either
   peripheral and verify only that endpoint retries; the other still works.
5. Stop fridge notifications: ERROR after 20 seconds, retry after five.
   Test missing service/characteristics and rejected subscription.
6. Send `FRIDGE OFF` while scanning, connected and retrying. It must remain OFF,
   with no later fridge polls or reconnect, while DiKey continues. Repeated
   ON/OFF commands are safe.
7. Interleave DiKey and fridge notifications with USB commands: no mixed lines.
   Send >200-character TX/fridge lines and then PING; overlong commands must
   not execute and PING must recover.
8. Reboot: fridge returns to OFF. Switch Android to direct BLE only after
   OFF is acknowledged. With multiple matching fridges, verify the displayed
   identity; selection is deliberately first-match, not user binding.
9. Drop USB or stop sending `FRIDGE STATUS`/`ON` for 35 seconds: only the
   fridge disconnects, with OFF reported after teardown. Reopening USB
   reports OFF until another explicit ON.
10. `FRIDGE SET 1 3` while READY: SETOK, then the next status shows left
    target 3 and the fridge display agrees. While OFF/SCANNING, or with
    `FRIDGE SET 3 1`, `FRIDGE SET 1 abc` or `FRIDGE SET 1 150`, expect SETERR
    and no BLE write.
11. `FRIDGE MODE MAX` then `FRIDGE MODE ECO` while READY: MODEOK, the next
    status field 9 changes (2 = Max) and the fridge display agrees. While not
    READY, or with `FRIDGE MODE TURBO`, expect MODEERR and no BLE write.
12. `FRIDGE POWER OFF` then `FRIDGE POWER ON` while READY: POWEROK, status
    field 1 follows, the fridge display agrees and the BLE link stays up while
    the fridge is off. `FRIDGE POWER MAYBE` gives POWERERR and no BLE write.
