package nodomain.freeyourgadget.gadgetbridge.zeekr;

import static org.junit.Assert.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import android.Manifest;
import android.app.Application;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothManager;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanFilter;
import android.bluetooth.le.ScanRecord;
import android.bluetooth.le.ScanResult;
import android.bluetooth.le.ScanSettings;
import android.content.BroadcastReceiver;
import android.content.Intent;
import android.os.Looper;
import android.os.PowerManager;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
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
import java.util.ArrayList;
import java.util.List;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, application = Application.class)
@LooperMode(LooperMode.Mode.PAUSED)
public class BLEScanServiceTest {
    private BLEScanService service;
    private BluetoothLeScanner scanner;
    private ScanCallback callback;
    private ScanCallback diagnostic;
    private BluetoothAdapter adapter;

    @Before
    public void setUp() {
        Shadows.shadowOf(RuntimeEnvironment.getApplication()).grantPermissions(MainActivity.scanPermissions());
        Shadows.shadowOf(BluetoothAdapter.getDefaultAdapter()).setEnabled(true);
        Shadows.shadowOf(RuntimeEnvironment.getApplication().getSystemService(PowerManager.class))
                .setIsInteractive(true);
        service = Robolectric.buildService(BLEScanService.class).create().get();
        scanner = mock(BluetoothLeScanner.class);
        adapter = mock(BluetoothAdapter.class);
        BluetoothManager manager = mock(BluetoothManager.class);
        when(manager.getAdapter()).thenReturn(adapter);
        when(adapter.isEnabled()).thenReturn(true);
        when(adapter.getBluetoothLeScanner()).thenReturn(scanner);
        ReflectionHelpers.setField(service, "bluetoothManager", manager);
        callback = ReflectionHelpers.getField(service, "scanCallback");
        diagnostic = ReflectionHelpers.getField(service, "diagnosticCallback");
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
        return events(BLEScanService.ACTION_ZEEKR_NEAR);
    }

    private List<Intent> events(String action) {
        List<Intent> all = Shadows.shadowOf(RuntimeEnvironment.getApplication()).getBroadcastIntents();
        List<Intent> matching = new ArrayList<>();
        for (Intent intent : all) {
            if (action.equals(intent.getAction())) matching.add(intent);
        }
        return matching;
    }

    private void screen(boolean off) {
        BroadcastReceiver receiver = ReflectionHelpers.getField(service, "screenReceiver");
        receiver.onReceive(service, new Intent(off ? Intent.ACTION_SCREEN_OFF : Intent.ACTION_SCREEN_ON));
    }

    private void advance(long seconds) {
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(seconds));
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
    public void screenOnNeverRunsDiagnosticsTimeoutsOrFailureRestarts() {
        start();
        start();
        verify(scanner, times(1)).startScan(anyList(), any(ScanSettings.class), same(callback));
        clearInvocations(scanner);
        advance(7200);
        callback.onScanFailed(ScanCallback.SCAN_FAILED_INTERNAL_ERROR);
        advance(7200);
        verifyNoInteractions(scanner);
        assertTrue(events(BLEScanService.ACTION_SCANNER_RESTART).isEmpty());
    }

    @Test
    public void failureRetriesOnlyScreenOffWithErrorExtrasAndCooldown() {
        start();
        screen(true);
        clearInvocations(scanner);
        callback.onScanFailed(ScanCallback.SCAN_FAILED_INTERNAL_ERROR);
        advance(4);
        verifyNoInteractions(scanner);
        advance(1);
        verify(scanner).stopScan(same(callback));
        verify(scanner).startScan(anyList(), any(ScanSettings.class), same(callback));
        Intent restart = events(BLEScanService.ACTION_SCANNER_RESTART).get(0);
        assertEquals("scan_failed", restart.getStringExtra(BLEScanService.EXTRA_RESTART_REASON));
        assertEquals(ScanCallback.SCAN_FAILED_INTERNAL_ERROR,
                restart.getIntExtra(BLEScanService.EXTRA_ERROR_CODE, -1));
        assertTrue(restart.hasExtra(BLEScanService.EXTRA_TIMESTAMP));
        callback.onScanFailed(ScanCallback.SCAN_FAILED_INTERNAL_ERROR);
        advance(119);
        assertEquals(1, events(BLEScanService.ACTION_SCANNER_RESTART).size());
        advance(1);
        assertEquals(2, events(BLEScanService.ACTION_SCANNER_RESTART).size());
    }

    @Test
    public void destroyStopsBothScansCancelsTimersAndUnregistersBothReceivers() {
        start();
        screen(true);
        advance(60);
        int before = Shadows.shadowOf(RuntimeEnvironment.getApplication()).getRegisteredReceivers().size();
        assertTrue(before >= 2);
        clearInvocations(scanner);
        service.onDestroy();
        service = null;
        verify(scanner).stopScan(same(callback));
        verify(scanner).stopScan(same(diagnostic));
        assertEquals(before - 2,
                Shadows.shadowOf(RuntimeEnvironment.getApplication()).getRegisteredReceivers().size());
        clearInvocations(scanner);
        advance(3600);
        callback.onScanFailed(ScanCallback.SCAN_FAILED_INTERNAL_ERROR);
        callback.onScanResult(1, advertisement("Zeekr862", "01:02:03:04:05:06", -65));
        diagnostic.onScanResult(1, advertisement("Other", "01:02:03:04:05:06", -65));
        verifyNoInteractions(scanner);
        assertTrue(events().isEmpty());
    }

    @Test
    public void bluetoothOffIsNotATimeoutAndOnRestartsWithFreshScreenOffPeriod() {
        start();
        screen(true);
        BroadcastReceiver receiver = ReflectionHelpers.getField(service, "bluetoothReceiver");
        clearInvocations(scanner);
        when(adapter.isEnabled()).thenReturn(false);
        receiver.onReceive(service, new Intent(BluetoothAdapter.ACTION_STATE_CHANGED)
                .putExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.STATE_OFF));
        verify(scanner).stopScan(same(callback));
        clearInvocations(scanner);
        advance(3600);
        verifyNoInteractions(scanner);
        when(adapter.isEnabled()).thenReturn(true);
        receiver.onReceive(service, new Intent(BluetoothAdapter.ACTION_STATE_CHANGED)
                .putExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.STATE_ON));
        verify(scanner).startScan(anyList(), any(ScanSettings.class), same(callback));
        advance(65);
        assertTrue(events(BLEScanService.ACTION_SCANNER_RESTART).isEmpty());
    }

    @Test
    public void diagnosticHeartbeatDoesNotStopMainScanOrStoreForeignDevices() {
        start();
        screen(true);
        for (int i = 0; i < 8; i++) {
            advance(i == 0 ? 60 : 55);
            diagnostic.onScanResult(1, advertisement("Other", "11:12:13:14:15:16", -70));
            advance(5);
        }
        verify(scanner, times(8)).startScan(isNull(), any(ScanSettings.class), same(diagnostic));
        verify(scanner, times(8)).stopScan(same(diagnostic));
        verify(scanner, never()).stopScan(same(callback));
        assertTrue(events(BLEScanService.ACTION_SCANNER_RESTART).isEmpty());
        assertTrue(events().isEmpty());
        assertEquals(8, events(BLEScanService.ACTION_SCANNER_ALIVE).size());
        Intent alive = events(BLEScanService.ACTION_SCANNER_ALIVE).get(0);
        assertEquals("Other", alive.getStringExtra(BLEScanService.EXTRA_DEVICE_NAME));
        assertEquals(-70, alive.getIntExtra(BLEScanService.EXTRA_RSSI, 0));
        assertFalse(service.getSharedPreferences("zeekr", 0).getAll().toString().contains("11:12:13:14:15:16"));
    }

    @Test
    public void fiveMinutesSilenceCausesOneRestartThenAtLeastTwoMinuteCooldown() {
        start();
        screen(true);
        advance(304);
        assertTrue(events(BLEScanService.ACTION_SCANNER_RESTART).isEmpty());
        advance(1);
        assertEquals(1, events(BLEScanService.ACTION_SCANNER_RESTART).size());
        assertEquals("no_ble_results", events(BLEScanService.ACTION_SCANNER_RESTART).get(0)
                .getStringExtra(BLEScanService.EXTRA_RESTART_REASON));
        advance(124);
        assertEquals(1, events(BLEScanService.ACTION_SCANNER_RESTART).size());
        advance(1);
        assertEquals(2, events(BLEScanService.ACTION_SCANNER_RESTART).size());
        verify(adapter, atLeast(3)).getBluetoothLeScanner();
    }

    @Test
    public void screenOnCancelsDiagnosticAndFailureRetryAndNextOffHasFreshDeadline() {
        start();
        screen(true);
        advance(60);
        callback.onScanFailed(ScanCallback.SCAN_FAILED_INTERNAL_ERROR);
        screen(false);
        verify(scanner).stopScan(same(diagnostic));
        clearInvocations(scanner);
        diagnostic.onScanResult(1, advertisement("Other", "11:12:13:14:15:16", -70));
        advance(7200);
        verifyNoInteractions(scanner);
        assertTrue(events(BLEScanService.ACTION_SCANNER_ALIVE).isEmpty());
        assertTrue(events(BLEScanService.ACTION_SCANNER_RESTART).isEmpty());
        // A healthy main scan, but no advertisements, must get a new full grace period.
        ReflectionHelpers.setField(service, "mainScanStarted", true);
        ReflectionHelpers.setField(service, "lastErrorCode", null);
        screen(true);
        advance(65);
        assertTrue(events(BLEScanService.ACTION_SCANNER_RESTART).isEmpty());
    }

    @Test
    public void repeatedScreenTransitionsAndTwoHoursDoNotAccumulateDiagnosticCallbacks() {
        start();
        for (int i = 0; i < 120; i++) {
            screen(false);
            screen(true);
            screen(true);
            advance(60);
            diagnostic.onScanResult(1, advertisement("Other", "11:12:13:14:15:16", -70));
            advance(5);
        }
        verify(scanner, times(120)).startScan(isNull(), any(ScanSettings.class), same(diagnostic));
        verify(scanner, times(120)).stopScan(same(diagnostic));
        verify(scanner, times(1)).startScan(anyList(), any(ScanSettings.class), same(callback));
        assertTrue(events(BLEScanService.ACTION_SCANNER_RESTART).isEmpty());
    }

    @Test
    public void zeekrDuringDiagnosticStillBroadcastsAndUpdatesBothTimestamps() {
        start();
        screen(true);
        advance(60);
        diagnostic.onScanResult(1, advertisement("Zeekr862", "11:12:13:14:15:16", -70));
        assertEquals(1, events().size());
        assertTrue((long) ReflectionHelpers.getField(service, "lastZeekrSeen") >= 60000);
        long zeekrSeen = ReflectionHelpers.getField(service, "lastZeekrSeen");
        long anySeen = ReflectionHelpers.getField(service, "lastAnyBleSeen");
        assertEquals(zeekrSeen, anySeen);
    }

    @Test
    public void unnamedAdvertisementsAlsoProveScannerAlive() {
        start();
        screen(true);
        advance(60);
        diagnostic.onScanResult(1, new ScanResult(BluetoothAdapter.getDefaultAdapter()
                .getRemoteDevice("11:12:13:14:15:16"), null, -72, 0));
        Intent alive = events(BLEScanService.ACTION_SCANNER_ALIVE).get(0);
        assertFalse(alive.hasExtra(BLEScanService.EXTRA_DEVICE_NAME));
        assertEquals(-72, alive.getIntExtra(BLEScanService.EXTRA_RSSI, 0));
        assertTrue(events().isEmpty());
    }

    @Test
    public void diagnosticFailureDoesNotStopMainScanAndScreenOnCancelsRecovery() {
        start();
        screen(true);
        advance(60);
        diagnostic.onScanFailed(ScanCallback.SCAN_FAILED_INTERNAL_ERROR);
        verify(scanner, never()).stopScan(same(callback));
        screen(false);
        clearInvocations(scanner);
        advance(600);
        verifyNoInteractions(scanner);
        assertTrue(events(BLEScanService.ACTION_SCANNER_RESTART).isEmpty());
    }

    @Test
    public void screenOnJustBeforeTimeoutCancelsItAndNewOffPeriodStartsAtZero() {
        start();
        screen(true);
        advance(304);
        screen(false);
        clearInvocations(scanner);
        advance(3600);
        verifyNoInteractions(scanner);
        screen(true);
        advance(304);
        assertTrue(events(BLEScanService.ACTION_SCANNER_RESTART).isEmpty());
        advance(1);
        assertEquals(1, events(BLEScanService.ACTION_SCANNER_RESTART).size());
    }

    @Test
    public void stickyServiceRestoredWithScreenOffReadsInitialPowerState() {
        BluetoothManager manager = ReflectionHelpers.getField(service, "bluetoothManager");
        service.onDestroy();
        Shadows.shadowOf(RuntimeEnvironment.getApplication().getSystemService(PowerManager.class))
                .setIsInteractive(false);
        service = Robolectric.buildService(BLEScanService.class).create().get();
        ReflectionHelpers.setField(service, "bluetoothManager", manager);
        diagnostic = ReflectionHelpers.getField(service, "diagnosticCallback");
        assertTrue((boolean) ReflectionHelpers.getField(service, "screenOff"));
        start();
        clearInvocations(scanner);
        advance(59);
        verifyNoInteractions(scanner);
        advance(1);
        verify(scanner).startScan(isNull(), any(ScanSettings.class), same(diagnostic));
    }

    @Test
    public void runtimePermissionRequestContainsOnlyNearbyDevices() {
        assertArrayEquals(new String[]{Manifest.permission.BLUETOOTH_SCAN,
                Manifest.permission.BLUETOOTH_CONNECT}, MainActivity.scanPermissions());
    }
}
