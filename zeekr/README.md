# Zeekr BLE — scan-only build (Android 12+)

This standalone module is part of the Gadgetbridge repository and follows its AGPL-3.0 license.
It replaces the full Gadgetbridge application for the Zeekr detection use case. It does not
depend on `:app`, merge its manifest, initialize `GBApplication`, or include wearable libraries.
The original `app/.../service/btle/BLEScanService.java` remains available to the full application.

Build: `./gradlew :zeekr:assembleDebug :zeekr:testDebugUnitTest`

APK: `zeekr/build/outputs/apk/debug/zeekr-debug.apk`

Application ID: `nodomain.freeyourgadget.gadgetbridge.zeekr` (separate from the full Gadgetbridge).

## Permissions in the final APK

| Permission | Required for |
| --- | --- |
| `android.permission.BLUETOOTH_SCAN` | Scan advertisements. `neverForLocation` avoids location permission; no physical location is calculated. |
| `android.permission.BLUETOOTH_CONNECT` | Query `BluetoothAdapter.isEnabled()` to recover from Bluetooth OFF/ON. No GATT or pairing/connection APIs are used. |
| `android.permission.FOREGROUND_SERVICE` | Keep the scan service running when the activity is closed. |
| `android.permission.FOREGROUND_SERVICE_CONNECTED_DEVICE` | Android 14+ permission for the Bluetooth foreground service type. |

Only SCAN and CONNECT are requested at runtime, together as Nearby devices. There is no
Gadgetbridge permission overview, startup permission helper, notification listener, or
"request all permissions" screen. There is no location, background location, Internet,
POST_NOTIFICATIONS, contacts, SMS, call, calendar, DND, overlay, storage/media, fitness,
camera, microphone, boot, exact-alarm, companion-device or wake-lock permission.

The foreground notification is mandatory, but Android 13+ does not require POST_NOTIFICATIONS
to start an FGS. Without that permission its entry is shown in Android's active-app/task-manager
UI instead of the notification drawer. The application does not request that optional permission.

## Operation

Open Zeekr BLE and grant Nearby devices. Scanning starts automatically. The activity can then
be removed from Recents: the service has `stopWithTask=false` and returns `START_STICKY`.
The activity is informational only: title, current service status and a short explanation.
It contains no buttons and never stops the service. There is no saved manual enabled/disabled
setting; the obsolete setting from previous versions is removed automatically.

With Bluetooth OFF, the foreground service waits for its dynamic Bluetooth receiver, with no
scan registrations, diagnostics or pending restart/health-check timers. Bluetooth ON restores
the main scan and watchdog without opening the activity. START_STICKY recreation checks the
current adapter state and follows the same automatic behavior. Reopening the UI after granting
Nearby devices in Android Settings also ensures the service is started.

An initial app launch and permission grant are required. Force-stop and reboot are outside
START_STICKY recovery; there is no boot receiver or extra permission in this build.

The only scan filter is advertised Complete/Short Local Name `Zeekr862`. The cached device name
and a fixed MAC are never used. Every matching result sends the public action
`nodomain.freeyourgadget.gadgetbridge.ZEEKR_NEAR` with `EXTRA_DEVICE_NAME`,
`EXTRA_DEVICE_ADDRESS` (current address), and integer `EXTRA_RSSI`. There is no debounce.

The main filtered scan is continuous. There is no unconditional 30-second restart anymore.
`PowerManager.isInteractive()` initializes the screen state when the foreground service starts;
a dynamic, non-exported screen receiver handles SCREEN_ON/OFF. The watchdog runs with either
screen state: every 30 seconds it registers a five-second unfiltered diagnostic scan with its
own stable callback alongside the continuous main scan. Screen transitions do not cancel checks,
diagnostics or scan-failure recovery. Foreign devices are never stored or displayed.

The first device per check sends public action
`nodomain.freeyourgadget.gadgetbridge.BLE_SCANNER_ALIVE` with address, RSSI and optional advertised
name extras. Every diagnostic result updates `lastAnyBleSeen`; Zeekr results from either scan
also update `lastZeekrSeen` and immediately send ZEEKR_NEAR, without debounce.

After more than 60 seconds without any BLE result, a completed diagnostic triggers a timeout.
With the screen on it only logs suppression; with the screen off it stops both registrations,
cancels pending work, reacquires the adapter/scanner and restarts the main scan. SCREEN_OFF
evaluates an already overdue timeout immediately, without a new grace period. Actual interactive
power state is rechecked before timeout recovery. Restarts have a shared 60-second cooldown.
Main scan failures request recovery after five seconds with either screen state, subject to
the cooldown. Diagnostic failures only stop the diagnostic and await the next cycle.
Bluetooth OFF pauses scanning without a restart loop; ON restores the main scan, resets the
monitoring baseline and resumes checks.

Each restart sends `nodomain.freeyourgadget.gadgetbridge.BLE_SCANNER_RESTART` with:
- `EXTRA_RESTART_REASON`: `watchdog_timeout`, `scan_failed`, `bluetooth_reenabled`, or `service_recovery`.
- `EXTRA_TIMESTAMP`: Unix timestamp in milliseconds (long).
- `EXTRA_ERROR_CODE`: Android scan error code (int), only when available.

Android explicitly suppresses unfiltered scanning with the screen off on some implementations.
Consequently a missing diagnostic heartbeat can also mean OS suppression or an empty environment,
not a proven scanner failure. Timeout recovery is intentionally a heuristic. The filtered main
scan remains active throughout the diagnostic. No extra permissions, location, WakeLock or alarms
are added. Handler checks can be delayed in deep sleep; no screen wakeup is requested. Real
phone/vehicle testing, including vendor power-saving restrictions, remains necessary.

Service destruction cancels every runnable, stops both scan registrations and unregisters both
runtime receivers. LOW_LATENCY, ALL_MATCHES and zero report delay are requested for the main scan.

`neverForLocation` can filter some BLE beacons on Android. Check Zeekr advertisements on the
target phone; the app does not add location permissions as an unrequested fallback.

## Verification and CI

`verify-apk.py <aapt> <apk>` inspects the packaged APK, requiring exactly the four permissions
above, one Activity and one Service, with no manifest receivers or providers. It rejects
Gadgetbridge startup/permission/wearable components. CI runs this audit after the unit tests.

The workflow `.github/workflows/build-zeekr-apk.yml` supports `workflow_dispatch` and pushes
to the module. Artifact `zeekr-ble-minimal-apk` contains `zeekr-debug.apk` and `permissions.txt`.
This is a debug-signed APK; a fresh CI runner can generate a different signing key per run.

Android references:
- https://developer.android.com/develop/connectivity/bluetooth/bt-permissions
- https://developer.android.com/develop/ui/views/notifications/notification-permission
- https://developer.android.com/develop/background-work/services/fgs/service-types#connected-device
- https://developer.android.com/reference/android/bluetooth/le/BluetoothLeScanner
