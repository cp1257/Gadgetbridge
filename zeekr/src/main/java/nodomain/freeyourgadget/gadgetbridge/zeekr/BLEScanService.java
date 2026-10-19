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
import android.util.Log;

import java.util.Collections;
import java.util.List;

/** Standalone scan-only version of Gadgetbridge's BLEScanService. No device connection path. */
public class BLEScanService extends Service {
    public static final String ACTION_ZEEKR_NEAR = "nodomain.freeyourgadget.gadgetbridge.ZEEKR_NEAR";
    public static final String EXTRA_DEVICE_NAME = "EXTRA_DEVICE_NAME";
    public static final String EXTRA_DEVICE_ADDRESS = "EXTRA_DEVICE_ADDRESS";
    public static final String EXTRA_RSSI = "EXTRA_RSSI";
    static final String DEVICE_NAME = "Zeekr862";
    private static final String TAG = "ZeekrBLE";
    private static final String CHANNEL = "zeekr_scan";
    private static final int NOTIFICATION_ID = 1;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable restart = this::restartScan;
    private BluetoothLeScanner scanner;
    private boolean destroyed;
    private boolean startedBefore;
    private boolean receiverRegistered;

    private final ScanCallback scanCallback = new ScanCallback() {
        @Override
        public void onScanResult(int type, ScanResult result) {
            if (destroyed || result.getScanRecord() == null
                    || !DEVICE_NAME.equals(result.getScanRecord().getDeviceName())) {
                return;
            }
            String address = result.getDevice().getAddress();
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
            if (!destroyed) {
                Log.e(TAG, "Zeekr scan failed: " + code);
                status("Ошибка BLE " + code + ". Повтор через 5 секунд");
                schedule(5000);
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
                restartScan();
            } else if (state == BluetoothAdapter.STATE_OFF || state == BluetoothAdapter.STATE_TURNING_OFF) {
                stopScan();
                scanner = null;
                status("Ожидание включения Bluetooth");
                schedule(30000);
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
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (!MainActivity.hasScanPermissions(this)
                || !getSharedPreferences("zeekr", MODE_PRIVATE).getBoolean("enabled", true)) {
            stopSelf();
            return START_NOT_STICKY;
        }
        restartScan();
        return START_STICKY;
    }

    private void schedule(long delay) {
        handler.removeCallbacks(restart);
        if (!destroyed) {
            handler.postDelayed(restart, delay);
        }
    }

    @SuppressLint("MissingPermission")
    private void restartScan() {
        if (destroyed) {
            return;
        }
        schedule(30000);
        stopScan();
        if (!MainActivity.hasScanPermissions(this)) {
            status("Нет разрешения на BLE-сканирование");
            stopSelf();
            return;
        }
        try {
            BluetoothManager manager = getSystemService(BluetoothManager.class);
            BluetoothAdapter adapter = manager == null ? null : manager.getAdapter();
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
                schedule(5000);
                return;
            }
            scanner.startScan(Collections.singletonList(new ScanFilter.Builder()
                            .setDeviceName(DEVICE_NAME).build()),
                    new ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
                            .setCallbackType(ScanSettings.CALLBACK_TYPE_ALL_MATCHES)
                            .setMatchMode(ScanSettings.MATCH_MODE_AGGRESSIVE)
                            .setReportDelay(0).setLegacy(false).build(), scanCallback);
            Log.i(TAG, startedBefore ? "Zeekr scan restarted" : "Zeekr scan started");
            startedBefore = true;
            status("Сканирование Zeekr862 активно");
        } catch (RuntimeException e) {
            Log.e(TAG, "Zeekr scan failed to start", e);
            status("Ошибка запуска BLE. Повтор через 5 секунд");
            schedule(5000);
        }
    }

    @SuppressLint("MissingPermission")
    private void stopScan() {
        if (scanner != null) {
            try {
                scanner.stopScan(scanCallback);
            } catch (IllegalStateException | SecurityException e) {
                Log.w(TAG, "Unable to stop Zeekr scan", e);
            }
        }
    }

    private void status(String text) {
        getSharedPreferences("zeekr", MODE_PRIVATE).edit().putString("status", text).apply();
    }

    @Override
    public void onDestroy() {
        destroyed = true;
        handler.removeCallbacks(restart);
        stopScan();
        if (receiverRegistered) {
            unregisterReceiver(bluetoothReceiver);
        }
        status("Сканирование остановлено");
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
