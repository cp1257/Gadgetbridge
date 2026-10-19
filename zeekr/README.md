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

One callback and one main-looper timer restart the scan approximately every 30 seconds,
stopping the old scan before starting the new one. Failures retry after 5 seconds.
Bluetooth OFF/ON is handled by a runtime receiver. Service destruction cancels the timer,
stops scanning and unregisters the receiver. LOW_LATENCY, ALL_MATCHES and zero report delay
are requested; Android may reduce the scan duty cycle or delay timers while the device sleeps.
There is no wake lock or exact alarm. Force-stop and vendor battery restrictions are controlled
by Android; real phone/vehicle testing remains necessary.

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
