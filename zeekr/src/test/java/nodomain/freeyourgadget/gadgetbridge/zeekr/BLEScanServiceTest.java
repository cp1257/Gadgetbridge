package nodomain.freeyourgadget.gadgetbridge.zeekr;

import static org.junit.Assert.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import android.Manifest;
import android.app.Application;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanFilter;
import android.bluetooth.le.ScanRecord;
import android.bluetooth.le.ScanResult;
import android.bluetooth.le.ScanSettings;
import android.content.BroadcastReceiver;
import android.content.Intent;
import android.os.Looper;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.LooperMode;
import org.robolectric.util.ReflectionHelpers;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, application = Application.class)
@LooperMode(LooperMode.Mode.PAUSED)
public class BLEScanServiceTest {
    private BLEScanService service;
    private BluetoothLeScanner scanner;
    private ScanCallback callback;

    @Before
    public void setUp() {
        Shadows.shadowOf(RuntimeEnvironment.getApplication()).grantPermissions(MainActivity.scanPermissions());
        Shadows.shadowOf(BluetoothAdapter.getDefaultAdapter()).setEnabled(true);
        service = Robolectric.buildService(BLEScanService.class).create().get();
        scanner = mock(BluetoothLeScanner.class);
        ReflectionHelpers.setField(service, "scanner", scanner);
        callback = ReflectionHelpers.getField(service, "scanCallback");
    }

    @After
    public void tearDown() {
        if (service != null) {
            service.onDestroy();
        }
    }

    private void start() {
        assertEquals(BLEScanService.START_STICKY, service.onStartCommand(new Intent(), 0, 1));
    }

    private ScanResult advertisement(String name, String mac, int rssi) {
        byte[] bytes = name.getBytes(StandardCharsets.UTF_8);
        byte[] data = new byte[bytes.length + 3];
        data[0] = (byte) (bytes.length + 1);
        data[1] = 9; // Complete Local Name, not the cached BluetoothDevice name.
        System.arraycopy(bytes, 0, data, 2, bytes.length);
        ScanRecord record = ReflectionHelpers.callStaticMethod(ScanRecord.class, "parseFromBytes",
                ReflectionHelpers.ClassParameter.from(byte[].class, data));
        return new ScanResult(BluetoothAdapter.getDefaultAdapter().getRemoteDevice(mac), record, rssi, 0);
    }

    private List<Intent> events() {
        List<Intent> all = Shadows.shadowOf(RuntimeEnvironment.getApplication()).getBroadcastIntents();
        all.removeIf(intent -> !BLEScanService.ACTION_ZEEKR_NEAR.equals(intent.getAction()));
        return all;
    }

    @Test
    public void everyAdvertisementBroadcastsEvenWithSameOrRotatingMac() {
        callback.onScanResult(1, advertisement("Zeekr862", "01:02:03:04:05:06", -65));
        callback.onScanResult(1, advertisement("Zeekr862", "01:02:03:04:05:06", -64));
        callback.onScanResult(1, advertisement("Zeekr862", "11:12:13:14:15:16", -70));
        assertEquals(3, events().size());
        Intent last = events().get(2);
        assertEquals("Zeekr862", last.getStringExtra("EXTRA_DEVICE_NAME"));
        assertEquals("11:12:13:14:15:16", last.getStringExtra("EXTRA_DEVICE_ADDRESS"));
        assertEquals(-70, last.getIntExtra("EXTRA_RSSI", 0));
        assertNull(last.getPackage()); // Public broadcast for MacroDroid.
    }

    @Test
    public void batchesAreImmediateAndOtherNamesAreIgnored() {
        callback.onScanResult(1, advertisement("zeekr862", "01:02:03:04:05:06", -65));
        callback.onScanResult(1, advertisement("Other", "01:02:03:04:05:06", -65));
        callback.onBatchScanResults(Arrays.asList(
                advertisement("Zeekr862", "01:02:03:04:05:06", -65),
                advertisement("Zeekr862", "11:12:13:14:15:16", -70)));
        assertEquals(2, events().size());
    }

    @Test
    public void foregroundScanUsesNameOnlyAndImmediateLowLatencyResults() {
        start();
        ArgumentCaptor<List<ScanFilter>> filters = ArgumentCaptor.forClass(List.class);
        ArgumentCaptor<ScanSettings> settings = ArgumentCaptor.forClass(ScanSettings.class);
        verify(scanner).startScan(filters.capture(), settings.capture(), same(callback));
        assertEquals(1, filters.getValue().size());
        assertEquals("Zeekr862", filters.getValue().get(0).getDeviceName());
        assertNull(filters.getValue().get(0).getDeviceAddress());
        assertEquals(ScanSettings.SCAN_MODE_LOW_LATENCY, settings.getValue().getScanMode());
        assertEquals(0, settings.getValue().getReportDelayMillis());
        assertNotNull(Shadows.shadowOf(service).getLastForegroundNotification());
    }

    @Test
    public void twoHoursOfRestartsKeepOneCallbackAndOneTimer() {
        start();
        start(); // Reopening UI must replace the timer, not create another scan loop.
        clearInvocations(scanner);
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(30));
        InOrder order = inOrder(scanner);
        order.verify(scanner).stopScan(same(callback));
        order.verify(scanner).startScan(anyList(), any(ScanSettings.class), same(callback));
        verifyNoMoreInteractions(scanner);
        clearInvocations(scanner);
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofHours(2));
        verify(scanner, times(240)).stopScan(same(callback));
        verify(scanner, times(240)).startScan(anyList(), any(ScanSettings.class), same(callback));
        verifyNoMoreInteractions(scanner);
    }

    @Test
    public void failuresRetryInFiveSecondsAndDestroyCancelsAllCallbacks() {
        start();
        clearInvocations(scanner);
        callback.onScanFailed(ScanCallback.SCAN_FAILED_INTERNAL_ERROR);
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(4));
        verifyNoInteractions(scanner);
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(1));
        verify(scanner).stopScan(same(callback));
        verify(scanner).startScan(anyList(), any(ScanSettings.class), same(callback));
        service.onDestroy();
        service = null;
        clearInvocations(scanner);
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofHours(1));
        callback.onScanFailed(ScanCallback.SCAN_FAILED_INTERNAL_ERROR);
        callback.onScanResult(1, advertisement("Zeekr862", "01:02:03:04:05:06", -65));
        verifyNoInteractions(scanner);
        assertTrue(events().isEmpty());
    }

    @Test
    public void bluetoothOffOnResumesAndScreenOffDoesNotStopScan() {
        start();
        BroadcastReceiver receiver = ReflectionHelpers.getField(service, "bluetoothReceiver");
        clearInvocations(scanner);
        receiver.onReceive(service, new Intent(BluetoothAdapter.ACTION_STATE_CHANGED)
                .putExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.STATE_OFF));
        verify(scanner).stopScan(same(callback));
        ReflectionHelpers.setField(service, "scanner", scanner);
        receiver.onReceive(service, new Intent(BluetoothAdapter.ACTION_STATE_CHANGED)
                .putExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.STATE_ON));
        verify(scanner, times(2)).stopScan(same(callback));
        verify(scanner).startScan(anyList(), any(ScanSettings.class), same(callback));
        clearInvocations(scanner);
        service.sendBroadcast(new Intent(Intent.ACTION_SCREEN_OFF));
        service.onTaskRemoved(new Intent());
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(30));
        verify(scanner).stopScan(same(callback));
        verify(scanner).startScan(anyList(), any(ScanSettings.class), same(callback));
    }

    @Test
    public void runtimePermissionRequestContainsOnlyNearbyDevices() {
        assertArrayEquals(new String[]{Manifest.permission.BLUETOOTH_SCAN,
                Manifest.permission.BLUETOOTH_CONNECT}, MainActivity.scanPermissions());
    }
}
