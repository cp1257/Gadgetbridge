package nodomain.freeyourgadget.gadgetbridge.zeekr;

import android.annotation.SuppressLint;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothManager;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanFilter;
import android.bluetooth.le.ScanResult;
import android.bluetooth.le.ScanSettings;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;
import android.util.Log;

import java.util.Collections;
import java.util.List;

/** Standalone scan-only version of Gadgetbridge's BLEScanService. No device connection path. */
public class BLEScanService extends Service {
    public static final String ACTION_ZEEKR_NEAR = "nodomain.freeyourgadget.gadgetbridge.ZEEKR_NEAR";
    public static final String EXTRA_DEVICE_NAME = "EXTRA_DEVICE_NAME";
    public static final String EXTRA_DEVICE_ADDRESS = "EXTRA_DEVICE_ADDRESS";
    public static final String EXTRA_RSSI = "EXTRA_RSSI";
    public static final String ACTION_SCANNER_ALIVE = "nodomain.freeyourgadget.gadgetbridge.BLE_SCANNER_ALIVE";
    public static final String ACTION_SCANNER_RESTART = "nodomain.freeyourgadget.gadgetbridge.BLE_SCANNER_RESTART";
    public static final String EXTRA_RESTART_REASON = "EXTRA_RESTART_REASON";
    public static final String EXTRA_TIMESTAMP = "EXTRA_TIMESTAMP";
    public static final String EXTRA_ERROR_CODE = "EXTRA_ERROR_CODE";
    static final String DEVICE_NAME = "Zeekr862";
    private static final String TAG = "ZeekrBLE";
    private static final String CHANNEL = "zeekr_scan";
    private static final int NOTIFICATION_ID = 1;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private static final long CHECK_INTERVAL = 60_000;
    private static final long DIAGNOSTIC_DURATION = 5_000;
    private static final long NO_RESULTS_TIMEOUT = 300_000;
    private static final long RESTART_COOLDOWN = 120_000;
    private final Runnable watchdogCheck = this::checkScanner;
    private final Runnable diagnosticEnd = this::finishDiagnostic;
    private BluetoothManager bluetoothManager;
    private BluetoothLeScanner scanner;
    private boolean destroyed;
    private boolean receiverRegistered;
    private boolean screenReceiverRegistered;
    private boolean screenOff;
    private boolean bluetoothOff;
    private boolean mainScanStarted;
    private boolean diagnosticActive;
    private boolean diagnosticSawDevice;
    private long screenOffSince;
    private long lastAnyBleSeen = -1;
    private long lastZeekrSeen = -1;
    private long lastWatchdogRestart = -RESTART_COOLDOWN;
    private Integer lastErrorCode;
    private final Runnable failureRetry = () -> watchdogRestart("scan_failed", lastErrorCode);

    private final ScanCallback scanCallback = new ScanCallback() {
        @Override
        public void onScanResult(int type, ScanResult result) {
            if (destroyed || result.getScanRecord() == null
                    || !DEVICE_NAME.equals(result.getScanRecord().getDeviceName())) {
                return;
            }
            String address = result.getDevice().getAddress();
            lastZeekrSeen = SystemClock.elapsedRealtime();
            lastAnyBleSeen = lastZeekrSeen;
            Log.i(TAG, "Zeekr found: " + address + " RSSI=" + result.getRssi());
            sendBroadcast(new Intent(ACTION_ZEEKR_NEAR)
                    .putExtra(EXTRA_DEVICE_NAME, DEVICE_NAME)
                    .putExtra(EXTRA_DEVICE_ADDRESS, address)
                    .putExtra(EXTRA_RSSI, result.getRssi()));
        }

        @Override
        public void onBatchScanResults(List<ScanResult> results) {
            for (ScanResult result : results) {
                onScanResult(ScanSettings.CALLBACK_TYPE_ALL_MATCHES, result);
            }
        }

        @Override
        public void onScanFailed(int code) {
            mainScanStarted = false;
            scanFailed(code);
        }
    };

    // A second, stable callback owns only the five-second diagnostic registration.
    // Never stop/re-register the main callback just to perform a health check.
    private final ScanCallback diagnosticCallback = new ScanCallback() {
        @Override
        public void onScanResult(int type, ScanResult result) {
            if (destroyed || !screenOff || !diagnosticActive) {
                return;
            }
            lastAnyBleSeen = SystemClock.elapsedRealtime();
            if (!diagnosticSawDevice) {
                diagnosticSawDevice = true;
                String address = result.getDevice().getAddress();
                Log.i(TAG, "BLE watchdog alive: " + address + " RSSI=" + result.getRssi());
                Intent alive = new Intent(ACTION_SCANNER_ALIVE)
                        .putExtra(EXTRA_DEVICE_ADDRESS, address).putExtra(EXTRA_RSSI, result.getRssi());
                if (result.getScanRecord() != null && result.getScanRecord().getDeviceName() != null) {
                    alive.putExtra(EXTRA_DEVICE_NAME, result.getScanRecord().getDeviceName());
                }
                sendBroadcast(alive);
            }
            // A diagnostic result for Zeekr is still a real detection; never suppress it.
            scanCallback.onScanResult(type, result);
        }

        @Override
        public void onBatchScanResults(List<ScanResult> results) {
            for (ScanResult result : results) {
                onScanResult(ScanSettings.CALLBACK_TYPE_ALL_MATCHES, result);
            }
        }

        @Override
        public void onScanFailed(int code) {
            if (diagnosticActive) {
                stopDiagnostic();
                scanFailed(code);
            }
        }
    };

    private final BroadcastReceiver screenReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (Intent.ACTION_SCREEN_OFF.equals(intent.getAction())) {
                setScreenOff(true);
            } else if (Intent.ACTION_SCREEN_ON.equals(intent.getAction())) {
                setScreenOff(false);
            }
        }
    };

    private final BroadcastReceiver bluetoothReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (!BluetoothAdapter.ACTION_STATE_CHANGED.equals(intent.getAction()) || destroyed) {
                return;
            }
            int state = intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR);
            if (state == BluetoothAdapter.STATE_ON) {
                bluetoothOff = false;
                cancelWatchdog();
                stopMainScan();
                scanner = null;
                screenOffSince = SystemClock.elapsedRealtime();
                startZeekrScan();
                if (screenOff) {
                    scheduleCheck();
                }
            } else if (state == BluetoothAdapter.STATE_OFF || state == BluetoothAdapter.STATE_TURNING_OFF) {
                bluetoothOff = true;
                cancelWatchdog();
                stopMainScan();
                scanner = null;
                status("Ожидание включения Bluetooth");
            }
        }
    };

    @Override
    public void onCreate() {
        super.onCreate();
        if (!MainActivity.hasScanPermissions(this)) {
            status("Нет разрешения на BLE-сканирование");
            stopSelf();
            return;
        }
        NotificationManager manager = getSystemService(NotificationManager.class);
        manager.createNotificationChannel(new NotificationChannel(CHANNEL, "Zeekr BLE scan",
                NotificationManager.IMPORTANCE_LOW));
        PendingIntent open = PendingIntent.getActivity(this, 0, new Intent(this, MainActivity.class),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        Notification notification = new Notification.Builder(this, CHANNEL)
                .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
                .setContentTitle("Zeekr BLE")
                .setContentText("Поиск Zeekr862 в фоне")
                .setContentIntent(open).setOngoing(true).build();
        // POST_NOTIFICATIONS is not required to run an FGS; no notification permission prompt.
        startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE);
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(bluetoothReceiver, new IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED),
                    Context.RECEIVER_EXPORTED);
        } else {
            registerReceiver(bluetoothReceiver, new IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED));
        }
        receiverRegistered = true;
        IntentFilter screens = new IntentFilter();
        screens.addAction(Intent.ACTION_SCREEN_ON);
        screens.addAction(Intent.ACTION_SCREEN_OFF);
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(screenReceiver, screens, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(screenReceiver, screens);
        }
        screenReceiverRegistered = true;
        bluetoothManager = getSystemService(BluetoothManager.class);
        setScreenOff(!getSystemService(PowerManager.class).isInteractive());
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (!MainActivity.hasScanPermissions(this)
                || !getSharedPreferences("zeekr", MODE_PRIVATE).getBoolean("enabled", true)) {
            stopSelf();
            return START_NOT_STICKY;
        }
        startZeekrScan();
        return START_STICKY;
    }

    private void setScreenOff(boolean off) {
        if (destroyed || screenOff == off) {
            return;
        }
        screenOff = off;
        cancelWatchdog();
        if (off) {
            screenOffSince = SystemClock.elapsedRealtime();
            Log.i(TAG, "Screen off: BLE watchdog enabled");
            if (!bluetoothOff) {
                scheduleCheck();
                if (!mainScanStarted && lastErrorCode != null) {
                    scheduleFailureRetry();
                }
            }
        } else {
            Log.i(TAG, "Screen on: BLE watchdog paused");
        }
    }

    private void cancelWatchdog() {
        handler.removeCallbacks(watchdogCheck);
        handler.removeCallbacks(failureRetry);
        stopDiagnostic();
    }

    private boolean watchdogEnabled() {
        // A queued timer can run just before SCREEN_ON is delivered. Recheck the
        // actual power state so it cannot diagnose/restart an already interactive phone.
        return screenOff && !destroyed && !bluetoothOff
                && !getSystemService(PowerManager.class).isInteractive();
    }

    private void scheduleCheck() {
        handler.removeCallbacks(watchdogCheck);
        if (watchdogEnabled()) {
            handler.postDelayed(watchdogCheck, CHECK_INTERVAL);
        }
    }

    @SuppressLint("MissingPermission")
    private boolean bluetoothEnabled() {
        BluetoothAdapter adapter = bluetoothManager == null ? null : bluetoothManager.getAdapter();
        return adapter != null && adapter.isEnabled();
    }

    private void scanFailed(int code) {
        if (destroyed) {
            return;
        }
        lastErrorCode = code;
        Log.e(TAG, "BLE scan failed: " + code);
        status("Ошибка BLE " + code);
        if (watchdogEnabled()) {
            scheduleFailureRetry();
        }
    }

    private void scheduleFailureRetry() {
        handler.removeCallbacks(failureRetry);
        if (watchdogEnabled()) {
            long cooldownLeft = RESTART_COOLDOWN - (SystemClock.elapsedRealtime() - lastWatchdogRestart);
            handler.postDelayed(failureRetry, Math.max(5000, cooldownLeft));
        }
    }

    @SuppressLint("MissingPermission")
    private void checkScanner() {
        if (!watchdogEnabled()) {
            return;
        }
        if (!MainActivity.hasScanPermissions(this)) {
            cancelWatchdog();
            stopSelf();
            return;
        }
        scheduleCheck();
        try {
            if (!bluetoothEnabled()) {
                // Powered-off Bluetooth and time spent waiting for it are not a scanner timeout.
                screenOffSince = SystemClock.elapsedRealtime();
                return;
            }
            if (scanner == null) {
                watchdogRestart("scanner_unavailable", lastErrorCode);
                return;
            }
            if (!mainScanStarted) {
                watchdogRestart("main_scan_inactive", lastErrorCode);
                return;
            }
            stopDiagnostic();
            diagnosticSawDevice = false;
            diagnosticActive = true;
            Log.i(TAG, "BLE watchdog check started");
            handler.postDelayed(diagnosticEnd, DIAGNOSTIC_DURATION);
            // Android may suppress unfiltered results with the screen off. Silence is
            // suspicious, not proof of failure; no additional permissions/wake locks.
            scanner.startScan(null, new ScanSettings.Builder()
                    .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
                    .setReportDelay(0).setLegacy(false).build(), diagnosticCallback);
        } catch (RuntimeException e) {
            stopDiagnostic();
            Log.e(TAG, "BLE watchdog diagnostic failed", e);
            if (watchdogEnabled()) {
                scheduleFailureRetry();
            }
        }
    }

    private void finishDiagnostic() {
        if (!watchdogEnabled()) {
            stopDiagnostic();
            return;
        }
        if (!diagnosticActive) {
            return;
        }
        boolean sawDevice = diagnosticSawDevice;
        stopDiagnostic();
        if (!sawDevice) {
            Log.i(TAG, "BLE watchdog no devices detected");
        }
        if (SystemClock.elapsedRealtime() - Math.max(screenOffSince, lastAnyBleSeen) > NO_RESULTS_TIMEOUT) {
            Log.w(TAG, "BLE scanner watchdog timeout");
            watchdogRestart("no_ble_results", null);
        }
    }

    private void watchdogRestart(String reason, Integer errorCode) {
        if (!watchdogEnabled()) {
            return;
        }
        try {
            if (!bluetoothEnabled()) {
                return;
            }
            long now = SystemClock.elapsedRealtime();
            if (now - lastWatchdogRestart < RESTART_COOLDOWN) {
                return;
            }
            lastWatchdogRestart = now;
            cancelWatchdog();
            stopMainScan();
            scanner = null; // startZeekrScan obtains the adapter and scanner again.
            Log.i(TAG, "BLE scanner watchdog restart");
            Intent event = new Intent(ACTION_SCANNER_RESTART)
                    .putExtra(EXTRA_RESTART_REASON, reason)
                    .putExtra(EXTRA_TIMESTAMP, System.currentTimeMillis());
            if (errorCode != null) {
                event.putExtra(EXTRA_ERROR_CODE, errorCode.intValue());
            }
            sendBroadcast(event);
            startZeekrScan();
            scheduleCheck();
        } catch (RuntimeException e) {
            Log.e(TAG, "BLE watchdog restart failed", e);
            scheduleFailureRetry();
        }
    }

    @SuppressLint("MissingPermission")
    private void startZeekrScan() {
        if (destroyed || mainScanStarted) {
            return;
        }
        if (!MainActivity.hasScanPermissions(this)) {
            status("Нет разрешения на BLE-сканирование");
            stopSelf();
            return;
        }
        try {
            BluetoothAdapter adapter = bluetoothManager == null ? null : bluetoothManager.getAdapter();
            if (adapter == null || !adapter.isEnabled()) {
                scanner = null;
                status("Ожидание включения Bluetooth");
                return;
            }
            if (scanner == null) {
                scanner = adapter.getBluetoothLeScanner();
            }
            if (scanner == null) {
                status("BLE scanner недоступен");
                if (watchdogEnabled()) {
                    scheduleFailureRetry();
                }
                return;
            }
            scanner.startScan(Collections.singletonList(new ScanFilter.Builder()
                            .setDeviceName(DEVICE_NAME).build()),
                    new ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
                            .setCallbackType(ScanSettings.CALLBACK_TYPE_ALL_MATCHES)
                            .setMatchMode(ScanSettings.MATCH_MODE_AGGRESSIVE)
                            .setReportDelay(0).setLegacy(false).build(), scanCallback);
            mainScanStarted = true;
            lastErrorCode = null;
            Log.i(TAG, "Zeekr scan started");
            status("Сканирование Zeekr862 активно");
        } catch (RuntimeException e) {
            Log.e(TAG, "Zeekr scan failed to start", e);
            status("Ошибка запуска BLE");
            if (watchdogEnabled()) {
                scheduleFailureRetry();
            }
        }
    }

    @SuppressLint("MissingPermission")
    private void stopMainScan() {
        if (scanner != null) {
            try {
                scanner.stopScan(scanCallback);
            } catch (IllegalStateException | SecurityException e) {
                Log.w(TAG, "Unable to stop Zeekr scan", e);
            }
        }
        mainScanStarted = false;
    }

    @SuppressLint("MissingPermission")
    private void stopDiagnostic() {
        handler.removeCallbacks(diagnosticEnd);
        if (diagnosticActive && scanner != null) {
            try {
                scanner.stopScan(diagnosticCallback);
            } catch (IllegalStateException | SecurityException e) {
                Log.w(TAG, "Unable to stop BLE diagnostic scan", e);
            }
        }
        diagnosticActive = false;
    }

    private void status(String text) {
        getSharedPreferences("zeekr", MODE_PRIVATE).edit().putString("status", text).apply();
    }

    @Override
    public void onDestroy() {
        destroyed = true;
        cancelWatchdog();
        stopMainScan();
        if (receiverRegistered) {
            unregisterReceiver(bluetoothReceiver);
        }
        if (screenReceiverRegistered) {
            unregisterReceiver(screenReceiver);
        }
        status("Сканирование остановлено");
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
