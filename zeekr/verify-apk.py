"""Fail the build if a final APK adds permissions/components outside the scan-only app."""
import re
import subprocess
import sys

EXPECTED = {
    "android.permission.BLUETOOTH_SCAN",
    "android.permission.BLUETOOTH_CONNECT",
    "android.permission.FOREGROUND_SERVICE",
    "android.permission.FOREGROUND_SERVICE_CONNECTED_DEVICE",
}

aapt, apk = sys.argv[1:]
permissions = subprocess.check_output([aapt, "dump", "permissions", apk], text=True)
actual = set(re.findall(r"uses-permission(?:-sdk-23)?: name='([^']+)'", permissions))
if actual != EXPECTED:
    raise SystemExit(f"Permission mismatch: extra={actual - EXPECTED}, missing={EXPECTED - actual}")
manifest = subprocess.check_output([aapt, "dump", "xmltree", apk, "AndroidManifest.xml"], text=True)
if re.search(r"E: (receiver|provider|uses-permission-sdk-23)\b", manifest):
    raise SystemExit("Unexpected receiver/provider or version-specific permission in APK")
if len(re.findall(r"E: activity(?:-alias)?\b", manifest)) != 1:
    raise SystemExit("Expected exactly one scan-only launcher activity")
if len(re.findall(r"E: service\b", manifest)) != 1:
    raise SystemExit("Expected exactly one foreground BLE service")
for unwanted in ("GBApplication", "PermissionsActivity", "NotificationCollector", "ControlCenterv2"):
    if unwanted in manifest:
        raise SystemExit(f"Unexpected wearable component: {unwanted}")
for permission in sorted(actual):
    print(permission)
print("PASS: exactly four permissions, one activity, one service, no receivers/providers.")
