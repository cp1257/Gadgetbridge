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
Use the Stop button to stop intentionally. Reopen the app and press Start to resume.

The only scan filter is advertised Complete/Short Local Name `Zeekr862`. The cached device name
and a fixed MAC are never used. Every matching result sends the public action
`nodomain.freeyourgadget.gadgetbridge.ZEEKR_NEAR` with `EXTRA_DEVICE_NAME`,
`EXTRA_DEVICE_ADDRESS` (current address), and integer `EXTRA_RSSI`. There is no debounce.

The main filtered scan is continuous. There is no unconditional 30-second restart anymore.
`PowerManager.isInteractive()` initializes the screen state when the foreground service starts;
a dynamic, non-exported screen receiver handles SCREEN_ON/OFF. Screen-on cancels the diagnostic
scan, pending health check and pending failure retry. Each new screen-off period starts a fresh
five-minute grace period. Bluetooth-off pauses checks and does not count as a scanner timeout.

Only with the screen off, a five-second unfiltered diagnostic scan is requested every 60 seconds,
using its own stable callback alongside the main filtered callback. It never stops the main scan
and never stores or displays foreign devices. The first device per check produces the public
action `nodomain.freeyourgadget.gadgetbridge.BLE_SCANNER_ALIVE` with address, RSSI and optional
advertised name extras. A Zeekr diagnostic result also immediately produces ZEEKR_NEAR.
`lastAnyBleSeen` and `lastZeekrSeen` are monotonic in-memory timestamps.

After more than five minutes without any result during the current screen-off period, the
watchdog stops both registrations, cancels pending work, reacquires the adapter/scanner and
starts the main scan again. Watchdog restarts are at least 120 seconds apart. Scan failures
request a five-second retry only while the screen is off (subject to that restart cooldown).
No aggressive retries run while the screen is on. Bluetooth ON always restores the main scan.

Each watchdog restart sends `nodomain.freeyourgadget.gadgetbridge.BLE_SCANNER_RESTART` with:
- `EXTRA_RESTART_REASON`: `no_ble_results`, `scan_failed`, `main_scan_inactive`, or `scanner_unavailable`.
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
