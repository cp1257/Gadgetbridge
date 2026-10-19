package nodomain.freeyourgadget.gadgetbridge.service.btle;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.*;

import android.Manifest;
import android.app.Application;
import android.app.NotificationManager;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothManager;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanFilter;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanRecord;
import android.bluetooth.le.ScanResult;
import android.bluetooth.le.ScanSettings;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Looper;

import androidx.localbroadcastmanager.content.LocalBroadcastManager;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.Robolectric;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.LooperMode;
import org.robolectric.util.ReflectionHelpers;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.time.Duration;

import nodomain.freeyourgadget.gadgetbridge.GBApplication;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, application = Application.class, manifest = Config.NONE)
@LooperMode(LooperMode.Mode.PAUSED)
public class BLEScanServiceTest {
    private final List<Intent> broadcasts = new ArrayList<>();
    private ScanCallback callback;
    private BLEScanService lifecycleService;
    private BluetoothLeScanner scanner;
    private BluetoothAdapter adapter;
    private GBApplication previousApp;

    @Before
    public void setUp() throws Exception {
        BLEScanService service = new BLEScanService() {
            @Override
            public void sendBroadcast(Intent intent) {
                broadcasts.add(intent);
            }
        };
        // Exercise callbacks without starting real Bluetooth or the foreground service.
        Field field = BLEScanService.class.getDeclaredField("scanCallback");
        field.setAccessible(true);
        callback = (ScanCallback) field.get(service);
    }

    private void prepareLifecycle() {
        lifecycleService = Robolectric.buildService(BLEScanService.class).get();
        scanner = mock(BluetoothLeScanner.class);
        adapter = mock(BluetoothAdapter.class);
        BluetoothManager manager = mock(BluetoothManager.class);
        when(manager.getAdapter()).thenReturn(adapter);
        when(adapter.getState()).thenReturn(BluetoothAdapter.STATE_ON);
        when(adapter.getBluetoothLeScanner()).thenReturn(scanner);
        ReflectionHelpers.setField(lifecycleService, "bluetoothManager", manager);
        ReflectionHelpers.setField(lifecycleService, "notificationManager", mock(NotificationManager.class));
        ReflectionHelpers.setField(lifecycleService, "localBroadcastManager",
                LocalBroadcastManager.getInstance(lifecycleService));
        lifecycleService.registerReceiver(lifecycleService.bluetoothStateChangedReceiver,
                new IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED));
        Shadows.shadowOf(RuntimeEnvironment.getApplication())
                .grantPermissions(Manifest.permission.ACCESS_COARSE_LOCATION);
        previousApp = GBApplication.app();
        GBApplication app = mock(GBApplication.class, RETURNS_DEEP_STUBS);
        when(app.getDeviceManager().getDevices()).thenReturn(new ArrayList<>());
        ReflectionHelpers.setStaticField(GBApplication.class, "app", app);
    }

    private void restart() {
        ReflectionHelpers.callInstanceMethod(lifecycleService, "restartScan",
                ReflectionHelpers.ClassParameter.from(boolean.class, true));
    }

    @After
    public void cleanUpLifecycle() {
        if (lifecycleService != null) {
            lifecycleService.onDestroy();
            ReflectionHelpers.setStaticField(GBApplication.class, "app", previousApp);
        }
    }

    @Test
    public void periodicRestartStopsSameCallbackAndKeepsSingleTimerAndNameFilter() {
        prepareLifecycle();
        restart();
        ArgumentCaptor<List<ScanFilter>> filters = ArgumentCaptor.forClass(List.class);
        ArgumentCaptor<ScanSettings> settings = ArgumentCaptor.forClass(ScanSettings.class);
        ArgumentCaptor<ScanCallback> callbacks = ArgumentCaptor.forClass(ScanCallback.class);
        verify(scanner).startScan(filters.capture(), settings.capture(), callbacks.capture());
        assertEquals(1, filters.getValue().size());
        assertEquals("Zeekr862", filters.getValue().get(0).getDeviceName());
        assertEquals(null, filters.getValue().get(0).getDeviceAddress());
        assertEquals(ScanSettings.SCAN_MODE_LOW_LATENCY, settings.getValue().getScanMode());
        assertEquals(0, settings.getValue().getReportDelayMillis());
        restart(); // A device state change must replace the existing timer.
        clearInvocations(scanner);
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(30));
        InOrder order = inOrder(scanner);
        order.verify(scanner).stopScan(same(callbacks.getValue()));
        order.verify(scanner).startScan(anyList(), any(ScanSettings.class), same(callbacks.getValue()));
        verifyNoMoreInteractions(scanner);
    }

    @Test
    public void failureRetriesAfterFiveSecondsAndDestructionCancelsRestarts() {
        prepareLifecycle();
        restart();
        ScanCallback active = ReflectionHelpers.getField(lifecycleService, "scanCallback");
        clearInvocations(scanner);
        active.onScanFailed(ScanCallback.SCAN_FAILED_INTERNAL_ERROR);
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(4));
        verifyNoInteractions(scanner);
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(1));
        verify(scanner).stopScan(same(active));
        verify(scanner).startScan(anyList(), any(ScanSettings.class), same(active));
        lifecycleService.onDestroy();
        lifecycleService = null;
        ReflectionHelpers.setStaticField(GBApplication.class, "app", previousApp);
        clearInvocations(scanner);
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMinutes(10));
        verifyNoInteractions(scanner);
    }

    @Test
    public void bluetoothOffOnRecoversAndScreenOffDoesNotStopFilteredScan() {
        prepareLifecycle();
        restart();
        ScanCallback active = ReflectionHelpers.getField(lifecycleService, "scanCallback");
        clearInvocations(scanner);
        when(adapter.getState()).thenReturn(BluetoothAdapter.STATE_OFF);
        lifecycleService.bluetoothStateChangedReceiver.onReceive(lifecycleService,
                new Intent(BluetoothAdapter.ACTION_STATE_CHANGED)
                        .putExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.STATE_OFF));
        verify(scanner).stopScan(same(active));
        when(adapter.getState()).thenReturn(BluetoothAdapter.STATE_ON);
        lifecycleService.bluetoothStateChangedReceiver.onReceive(lifecycleService,
                new Intent(BluetoothAdapter.ACTION_STATE_CHANGED)
                        .putExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.STATE_ON));
        verify(scanner).startScan(anyList(), any(ScanSettings.class), same(active));
        clearInvocations(scanner);
        lifecycleService.sendBroadcast(new Intent(Intent.ACTION_SCREEN_OFF));
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(30));
        verify(scanner).stopScan(same(active));
        verify(scanner).startScan(anyList(), any(ScanSettings.class), same(active));
    }

    @Test
    public void restartsContinueForTwoHoursWithoutAccumulatingTimers() {
        prepareLifecycle();
        restart();
        clearInvocations(scanner);
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofHours(2));
        verify(scanner, times(240)).stopScan(any(ScanCallback.class));
        verify(scanner, times(240)).startScan(anyList(), any(ScanSettings.class), any(ScanCallback.class));
        verifyNoMoreInteractions(scanner);
    }

    private ScanResult car(String address, int rssi) {
        // Complete Local Name advertising field: Zeekr862.
        byte[] advertisement = {9, 9, 'Z', 'e', 'e', 'k', 'r', '8', '6', '2', 0};
        ScanRecord record = ReflectionHelpers.callStaticMethod(ScanRecord.class,
                "parseFromBytes", ReflectionHelpers.ClassParameter.from(byte[].class, advertisement));
        assertEquals("Zeekr862", record.getDeviceName());
        return new ScanResult(BluetoothAdapter.getDefaultAdapter().getRemoteDevice(address),
                record, rssi, 0);
    }

    private void seen(String address) {
        callback.onScanResult(ScanSettings.CALLBACK_TYPE_ALL_MATCHES, car(address, -65));
    }

    @Test
    public void broadcastsNameCurrentAddressAndIntegerRssi() {
        seen("01:02:03:04:05:06");
        assertEquals(1, broadcasts.size());
        Intent intent = broadcasts.get(0);
        assertEquals("nodomain.freeyourgadget.gadgetbridge.ZEEKR_NEAR", intent.getAction());
        assertEquals("Zeekr862", intent.getStringExtra("EXTRA_DEVICE_NAME"));
        assertEquals("01:02:03:04:05:06", intent.getStringExtra("EXTRA_DEVICE_ADDRESS"));
        assertTrue(intent.hasExtra("EXTRA_RSSI"));
        assertEquals(-65, intent.getIntExtra("EXTRA_RSSI", 0));
        // A local DEVICE_FOUND dispatch would fail here: no connection receiver was set up.
    }

    @Test
    public void everyDetectionBroadcastsImmediatelyIncludingSameAndRotatingMac() {
        seen("01:02:03:04:05:06");
        seen("01:02:03:04:05:06");
        seen("11:12:13:14:15:16");
        seen("21:22:23:24:25:26");
        seen("31:32:33:34:35:36");
        assertEquals(5, broadcasts.size());
        assertEquals("31:32:33:34:35:36",
                broadcasts.get(4).getStringExtra("EXTRA_DEVICE_ADDRESS"));
    }

    @Test
    public void everyBatchedResultBroadcasts() {
        callback.onBatchScanResults(Arrays.asList(
                car("01:02:03:04:05:06", -65), car("11:12:13:14:15:16", -70)));
        assertEquals(2, broadcasts.size());
        assertEquals(-70, broadcasts.get(1).getIntExtra("EXTRA_RSSI", 0));
    }
}
