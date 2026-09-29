# Autostart on DiLink 5 — status and limitations

Open DiKey restarts itself after DiLink force-stops it, using a system-bound
`NotificationListenerService` as a wake hook. Once the app has been opened once
with ADB available (so its local-ADB setup can approve the listener), no user
action is needed to bring the process back.

Verified on Shark 6 / DiLink 5 (Android 11, `ro.build.type=user`) with
`am force-stop`, which is the same `ActivityManager.forceStopPackage` call DiLink
uses. **Not yet verified:** a real vehicle standby cycle and a full head-unit
cold boot.

---

## Current expected behaviour

| Situation | What happens |
| --- | --- |
| User opens Open DiKey | `DiKeyListenService` starts; USB/BLE connect; local ADB setup re-approves the listener |
| App in background (same session) | Foreground service keeps listening |
| Vehicle standby (CarPowerService force-stop) | system_server sees the listener binding die and immediately re-creates the process (~50 ms in tests); `OpenDiKeyApp.onCreate` restarts `DiKeyListenService` and `AdbKeepAlive` |
| Cold boot | Expected: NotificationManagerService binds approved listeners after boot, starting the process. Needs a real reboot test |
| App reinstalled (`adb install -r`) | Process comes back on its own; approval survives |
| App uninstalled | Approval is removed; open the app once with ADB on to re-approve |

---

## Who force-stops the app

`com.ts.appservice.power` (`/system/app/CarPowerService`):

- `CarPowerOnStandbyState.setOtherState()` → `CarPowerBinder.forceStopThirdApp()`
- Force-stops every installed package that is not `FLAG_SYSTEM`,
  `FLAG_UPDATED_SYSTEM_APP` or already `FLAG_STOPPED`.
- The only exemption is a hard-coded `FORCE_STOP_WHITELIST` of
  `tunein.player` and `com.byd.tunein`.

`ssc_whitelist` and `persist.sys.acc.whitelist` are **not read** by this code
(or anywhere in `services.jar` / `framework.jar`), which is why adding the
package to them never helped.

After a force-stop, `stopped=true` blocks all broadcasts (`BOOT_COMPLETED`,
ACC/IGN, alarms, jobs). A service **bind** from system_server is not blocked
and clears the stopped state, which is what the listener relies on.

---

## ADB after boot

`com.byd.ota` (`UpdatePresenter.init`) writes `adb_enabled=0` and
`adb_wifi_enabled=0` on every start on non-`userdebug` builds. `AdbKeepAlive`
(uses the ADB-granted `WRITE_SECURE_SETTINGS`) writes `adb_enabled=1` whenever
the process starts and whenever the setting flips back to 0 while it runs.
Because the listener brings the process back, this also runs without the user
opening the app.

---

## Approaches tried

| Approach | Result |
| --- | --- |
| Boot / ACC / IGN / quickboot receivers + AlarmManager re-kicks | No effect while `stopped=true` |
| `persist.sys.acc.whitelist` + SSC / appstartup / deviceidle grants | Not read by CarPowerService; no effect |
| Shell-uid ACC daemon (`app_process` + `DiKeyWakeActivity`) | Works between force-stops; dies on deep sleep |
| Local Dadb to relaunch daemon after wake | Often `127.0.0.1:5555` broken pipe / not open |
| Accessibility service keep-alive (Overdrive's approach) | Overdrive's service sits in "Crashed services" and is not rebound after force-stop |
| **Notification listener wake hook** | **Process re-created immediately after every force-stop (current approach)** |

Related: [BYD-ADB-Unlock](https://github.com/DottoreTozzi/BYD-ADB-Unlock)
(boot receivers + `adb_enabled`, same force-stop limitation).

---

## Cleanup notes (dev)

```text
adb shell pkill -f DiKeyAccDaemon
adb shell rm -f /sdcard/dikey-acc-daemon.log /sdcard/dikey-start-daemon.sh
```

Revoke the wake hook manually if needed:

```text
adb shell cmd notification disallow_listener com.sphy.airconcontroller/com.sphy.airconcontroller.boot.KeepAliveNotificationListener
```

---

## Related code (current)

- `boot/KeepAliveNotificationListener.kt` — system-bound wake hook
- `boot/DiKeyListenService.kt` — foreground USB/BLE listener
- `adb/AdbKeepAlive.kt` — restores `adb_enabled` after the OTA app clears it
- `adb/AdbPermissionManager.kt` — on-open grants, including `cmd notification allow_listener`
- `OpenDiKeyApp` — starts the keep-alive and listener service whenever the process is created
