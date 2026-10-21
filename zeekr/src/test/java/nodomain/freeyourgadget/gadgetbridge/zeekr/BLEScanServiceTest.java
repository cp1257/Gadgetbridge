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
        Shadows.shadowOf(RuntimeEnvironment.getApplication().getSystemService(PowerManager.class))
                .setIsInteractive(!off);
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
    public void screenOnRunsDiagnosticsButSuppressesTimeoutRestart() {
        start();
        start();
        advance(95);
        verify(scanner, times(3)).startScan(isNull(), any(ScanSettings.class), same(diagnostic));
        verify(scanner, never()).stopScan(same(callback));
        assertTrue(events(BLEScanService.ACTION_SCANNER_RESTART).isEmpty());
        assertTrue(org.robolectric.shadows.ShadowLog.getLogsForTag("ZeekrBLE").stream()
                .anyMatch(log -> log.msg.contains("timeout detected while screen on")));
    }

    @Test
    public void failureRetriesOnEitherScreenStateWithErrorExtrasAndCooldown() {
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
        advance(59);
        assertEquals(1, events(BLEScanService.ACTION_SCANNER_RESTART).size());
        advance(1);
        assertEquals(2, events(BLEScanService.ACTION_SCANNER_RESTART).size());
    }

    @Test
    public void destroyStopsBothScansCancelsTimersAndUnregistersBothReceivers() {
        start();
        screen(true);
        advance(30);
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
    public void bluetoothOffSuppressesTimeoutAndOnRestoresScanAndDiagnostics() {
        start();
        screen(true);
        BroadcastReceiver receiver = ReflectionHelpers.getField(service, "bluetoothReceiver");
        when(adapter.isEnabled()).thenReturn(false);
        receiver.onReceive(service, new Intent(BluetoothAdapter.ACTION_STATE_CHANGED)
                .putExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.STATE_OFF));
        clearInvocations(scanner);
        advance(3600);
        verifyNoInteractions(scanner);
        assertTrue(events(BLEScanService.ACTION_SCANNER_RESTART).isEmpty());
        when(adapter.isEnabled()).thenReturn(true);
        receiver.onReceive(service, new Intent(BluetoothAdapter.ACTION_STATE_CHANGED)
                .putExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.STATE_ON));
        verify(scanner).startScan(anyList(), any(ScanSettings.class), same(callback));
        assertEquals("bluetooth_reenabled", events(BLEScanService.ACTION_SCANNER_RESTART).get(0)
                .getStringExtra(BLEScanService.EXTRA_RESTART_REASON));
        advance(30);
        diagnostic.onScanResult(1, advertisement("Other", "11:12:13:14:15:16", -70));
        advance(5);
        assertEquals(1, events(BLEScanService.ACTION_SCANNER_RESTART).size());
    }

    @Test
    public void diagnosticHeartbeatDoesNotStopMainScanOrStoreForeignDevices() {
        start();
        screen(true);
        for (int i = 0; i < 8; i++) {
            advance(i == 0 ? 30 : 25);
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
    public void silenceRestartsAfterSixtySecondsAndObservesSixtySecondCooldown() {
        start();
        screen(true);
        advance(64);
        assertTrue(events(BLEScanService.ACTION_SCANNER_RESTART).isEmpty());
        advance(1);
        assertEquals(1, events(BLEScanService.ACTION_SCANNER_RESTART).size());
        assertEquals("watchdog_timeout", events(BLEScanService.ACTION_SCANNER_RESTART).get(0)
                .getStringExtra(BLEScanService.EXTRA_RESTART_REASON));
        advance(59);
        assertEquals(1, events(BLEScanService.ACTION_SCANNER_RESTART).size());
        advance(6);
        assertEquals(2, events(BLEScanService.ACTION_SCANNER_RESTART).size());
        verify(adapter, atLeast(3)).getBluetoothLeScanner();
    }

    @Test
    public void screenOnKeepsDiagnosticAndFailureRecoveryRunning() {
        start();
        screen(true);
        advance(30);
        screen(false);
        verify(scanner, never()).stopScan(same(diagnostic));
        diagnostic.onScanResult(1, advertisement("Other", "11:12:13:14:15:16", -70));
        assertEquals(1, events(BLEScanService.ACTION_SCANNER_ALIVE).size());
        advance(5);
        verify(scanner).stopScan(same(diagnostic));
        callback.onScanFailed(ScanCallback.SCAN_FAILED_INTERNAL_ERROR);
        advance(5);
        assertEquals(1, events(BLEScanService.ACTION_SCANNER_RESTART).size());
    }

    @Test
    public void repeatedTransitionsAndTwoHoursKeepOneDiagnosticAndContinuousMainScan() {
        start();
        for (int i = 0; i < 240; i++) {
            screen(false);
            screen(true);
            screen(true);
            advance(i == 0 ? 30 : 25);
            diagnostic.onScanResult(1, advertisement("Other", "11:12:13:14:15:16", -70));
            advance(5);
        }
        verify(scanner, times(240)).startScan(isNull(), any(ScanSettings.class), same(diagnostic));
        verify(scanner, times(240)).stopScan(same(diagnostic));
        verify(scanner, times(1)).startScan(anyList(), any(ScanSettings.class), same(callback));
        assertTrue(events(BLEScanService.ACTION_SCANNER_RESTART).isEmpty());
    }

    @Test
    public void zeekrDuringDiagnosticStillBroadcastsAndUpdatesBothTimestamps() {
        start();
        screen(true);
        advance(30);
        diagnostic.onScanResult(1, advertisement("Zeekr862", "11:12:13:14:15:16", -70));
        assertEquals(1, events().size());
        assertTrue((long) ReflectionHelpers.getField(service, "lastZeekrSeen") >= 30000);
        long zeekrSeen = ReflectionHelpers.getField(service, "lastZeekrSeen");
        long anySeen = ReflectionHelpers.getField(service, "lastAnyBleSeen");
        assertEquals(zeekrSeen, anySeen);
    }

    @Test
    public void unnamedAdvertisementsAlsoProveScannerAlive() {
        start();
        screen(true);
        advance(30);
        diagnostic.onScanResult(1, new ScanResult(BluetoothAdapter.getDefaultAdapter()
                .getRemoteDevice("11:12:13:14:15:16"), null, -72, 0));
        Intent alive = events(BLEScanService.ACTION_SCANNER_ALIVE).get(0);
        assertFalse(alive.hasExtra(BLEScanService.EXTRA_DEVICE_NAME));
        assertEquals(-72, alive.getIntExtra(BLEScanService.EXTRA_RSSI, 0));
        assertTrue(events().isEmpty());
    }

    @Test
    public void diagnosticFailureWaitsForNextCheckWithoutRestartingMain() {
        start();
        advance(30);
        diagnostic.onScanFailed(ScanCallback.SCAN_FAILED_INTERNAL_ERROR);
        advance(5);
        verify(scanner, never()).stopScan(same(callback));
        assertTrue(events(BLEScanService.ACTION_SCANNER_RESTART).isEmpty());
        advance(25);
        verify(scanner, times(2)).startScan(isNull(), any(ScanSettings.class), same(diagnostic));
    }

    @Test
    public void screenOffAfterScreenOnTimeoutRestartsImmediatelyWithoutFreshGracePeriod() {
        start();
        advance(95);
        assertTrue(events(BLEScanService.ACTION_SCANNER_RESTART).isEmpty());
        screen(true);
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
        advance(29);
        verifyNoInteractions(scanner);
        advance(1);
        verify(scanner).startScan(isNull(), any(ScanSettings.class), same(diagnostic));
    }

    @Test
    public void actualInteractiveStateSuppressesTimeoutBeforeScreenOnBroadcastArrives() {
        start();
        screen(true);
        advance(64);
        Shadows.shadowOf(RuntimeEnvironment.getApplication().getSystemService(PowerManager.class))
                .setIsInteractive(true);
        advance(1);
        assertTrue(events(BLEScanService.ACTION_SCANNER_RESTART).isEmpty());
        verify(scanner, times(2)).startScan(isNull(), any(ScanSettings.class), same(diagnostic));
    }

    @Test
    public void mainResultResetsHealthTimeoutWithoutDiagnosticResult() {
        start();
        screen(true);
        advance(59);
        callback.onScanResult(1, advertisement("Zeekr862", "11:12:13:14:15:16", -70));
        advance(36);
        assertTrue(events(BLEScanService.ACTION_SCANNER_RESTART).isEmpty());
        assertEquals(1, events().size());
    }

    @Test
    public void mainFailureRecoversWhileScreenOn() {
        start();
        callback.onScanFailed(ScanCallback.SCAN_FAILED_INTERNAL_ERROR);
        advance(5);
        assertEquals(1, events(BLEScanService.ACTION_SCANNER_RESTART).size());
        verify(scanner, times(2)).startScan(anyList(), any(ScanSettings.class), same(callback));
    }

    @Test
    public void runtimePermissionRequestContainsOnlyNearbyDevices() {
        assertArrayEquals(new String[]{Manifest.permission.BLUETOOTH_SCAN,
                Manifest.permission.BLUETOOTH_CONNECT}, MainActivity.scanPermissions());
    }

    private void bluetoothState(int state) {
        BroadcastReceiver receiver = ReflectionHelpers.getField(service, "bluetoothReceiver");
        receiver.onReceive(service, new Intent(BluetoothAdapter.ACTION_STATE_CHANGED)
                .putExtra(BluetoothAdapter.EXTRA_STATE, state));
    }

    private void assertNoWatchdogTimers() {
        android.os.Handler handler = ReflectionHelpers.getField(service, "handler");
        for (String field : new String[]{"watchdogCheck", "diagnosticEnd", "failureRetry"}) {
            Runnable task = ReflectionHelpers.getField(service, field);
            assertFalse(field, handler.hasCallbacks(task));
        }
    }

    @Test
    public void initialBluetoothOffWaitsWithoutAnyTimersAndOnRestoresWithoutActivity() {
        BluetoothManager manager = ReflectionHelpers.getField(service, "bluetoothManager");
        service.onDestroy();
        Shadows.shadowOf(BluetoothAdapter.getDefaultAdapter()).setEnabled(false);
        service = Robolectric.buildService(BLEScanService.class).create().get();
        ReflectionHelpers.setField(service, "bluetoothManager", manager);
        callback = ReflectionHelpers.getField(service, "scanCallback");
        diagnostic = ReflectionHelpers.getField(service, "diagnosticCallback");
        when(adapter.isEnabled()).thenReturn(false);
        clearInvocations(scanner);
        assertEquals(BLEScanService.START_STICKY, service.onStartCommand(null, 0, 1));
        assertNoWatchdogTimers();
        advance(3600);
        verifyNoInteractions(scanner);
        assertEquals("Ожидание включения Bluetooth",
                service.getSharedPreferences("zeekr", 0).getString("status", ""));
        assertNotNull(Shadows.shadowOf(service).getLastForegroundNotification());
        when(adapter.isEnabled()).thenReturn(true);
        bluetoothState(BluetoothAdapter.STATE_ON);
        verify(scanner).startScan(anyList(), any(ScanSettings.class), same(callback));
        assertEquals("Сканирование Zeekr862 активно",
                service.getSharedPreferences("zeekr", 0).getString("status", ""));
        advance(30);
        verify(scanner).startScan(isNull(), any(ScanSettings.class), same(diagnostic));
    }

    @Test
    public void bluetoothOffCancelsActiveDiagnosticAndPendingFailureRecovery() {
        start();
        advance(30);
        callback.onScanFailed(ScanCallback.SCAN_FAILED_INTERNAL_ERROR);
        when(adapter.isEnabled()).thenReturn(false);
        bluetoothState(BluetoothAdapter.STATE_TURNING_OFF);
        verify(scanner).stopScan(same(callback));
        verify(scanner).stopScan(same(diagnostic));
        assertNoWatchdogTimers();
        clearInvocations(scanner);
        callback.onScanFailed(ScanCallback.SCAN_FAILED_INTERNAL_ERROR);
        advance(3600);
        assertNoWatchdogTimers();
        verifyNoInteractions(scanner);
        assertTrue(events(BLEScanService.ACTION_SCANNER_RESTART).isEmpty());
    }

    @Test
    public void stickyRecoveryIgnoresObsoleteManualStopSetting() {
        service.getSharedPreferences("zeekr", 0).edit().putBoolean("enabled", false).apply();
        assertEquals(BLEScanService.START_STICKY, service.onStartCommand(null, 0, 1));
        verify(scanner).startScan(anyList(), any(ScanSettings.class), same(callback));
        assertEquals("service_recovery", events(BLEScanService.ACTION_SCANNER_RESTART).get(0)
                .getStringExtra(BLEScanService.EXTRA_RESTART_REASON));
        advance(30);
        verify(scanner).startScan(isNull(), any(ScanSettings.class), same(diagnostic));
    }

    @Test
    public void repeatedBluetoothOnAndServiceStartDoNotDuplicateMainOrDiagnosticScans() {
        start();
        bluetoothState(BluetoothAdapter.STATE_ON);
        bluetoothState(BluetoothAdapter.STATE_ON);
        start();
        advance(30);
        start();
        bluetoothState(BluetoothAdapter.STATE_ON);
        verify(scanner, times(1)).startScan(anyList(), any(ScanSettings.class), same(callback));
        verify(scanner, times(1)).startScan(isNull(), any(ScanSettings.class), same(diagnostic));
        verify(scanner, never()).stopScan(same(callback));
    }

    private boolean containsButton(android.view.View view) {
        if (view instanceof android.widget.Button) return true;
        if (view instanceof android.view.ViewGroup) {
            android.view.ViewGroup group = (android.view.ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                if (containsButton(group.getChildAt(i))) return true;
            }
        }
        return false;
    }

    @Test
    public void informationalActivityStartsServiceAutomaticallyAndClosingItDoesNotStopScan() {
        start();
        org.robolectric.android.controller.ActivityController<MainActivity> controller =
                Robolectric.buildActivity(MainActivity.class).create().start().resume().visible();
        MainActivity activity = controller.get();
        assertFalse(containsButton(activity.getWindow().getDecorView()));
        Intent started = Shadows.shadowOf(activity).getNextStartedService();
        assertNotNull(started);
        assertEquals(BLEScanService.class.getName(), started.getComponent().getClassName());
        clearInvocations(scanner);
        controller.pause().stop().destroy();
        assertNull(Shadows.shadowOf(RuntimeEnvironment.getApplication()).getNextStoppedService());
        verifyNoInteractions(scanner);
        advance(30);
        diagnostic.onScanResult(1, advertisement("Zeekr862", "11:12:13:14:15:16", -70));
        assertEquals(1, events().size());
        verify(scanner, never()).stopScan(same(callback));
    }

    @Test
    public void permissionGrantStartsServiceWithoutButtonAndDenialDoesNotStartIt() {
        Shadows.shadowOf(RuntimeEnvironment.getApplication()).denyPermissions(MainActivity.scanPermissions());
        org.robolectric.android.controller.ActivityController<MainActivity> controller =
                Robolectric.buildActivity(MainActivity.class).create().start().resume();
        MainActivity activity = controller.get();
        assertArrayEquals(MainActivity.scanPermissions(),
                Shadows.shadowOf(activity).getLastRequestedPermission().requestedPermissions);
        assertNull(Shadows.shadowOf(activity).getNextStartedService());
        activity.onRequestPermissionsResult(1, MainActivity.scanPermissions(), new int[]{-1, -1});
        assertNull(Shadows.shadowOf(activity).getNextStartedService());
        Shadows.shadowOf(RuntimeEnvironment.getApplication()).grantPermissions(MainActivity.scanPermissions());
        activity.onRequestPermissionsResult(1, MainActivity.scanPermissions(), new int[]{0, 0});
        assertNotNull(Shadows.shadowOf(activity).getNextStartedService());
        controller.pause().stop().destroy();
    }

    @Test
    public void returningAfterGrantInAndroidSettingsAutomaticallyStartsService() {
        Shadows.shadowOf(RuntimeEnvironment.getApplication()).denyPermissions(MainActivity.scanPermissions());
        org.robolectric.android.controller.ActivityController<MainActivity> controller =
                Robolectric.buildActivity(MainActivity.class).create().start().resume().pause();
        MainActivity activity = controller.get();
        Shadows.shadowOf(RuntimeEnvironment.getApplication()).grantPermissions(MainActivity.scanPermissions());
        controller.resume();
        assertNotNull(Shadows.shadowOf(activity).getNextStartedService());
        controller.pause().stop().destroy();
    }
}
