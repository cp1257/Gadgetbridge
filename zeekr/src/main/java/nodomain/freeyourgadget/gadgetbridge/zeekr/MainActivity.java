package nodomain.freeyourgadget.gadgetbridge.zeekr;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.widget.LinearLayout;
import android.widget.TextView;

/** The only entry point: no Gadgetbridge startup, permission overview or wearable features. */
public class MainActivity extends Activity {
    private static final int REQUEST_SCAN = 1;
    private TextView status;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable refresh = new Runnable() {
        @Override
        public void run() {
            status.setText(getSharedPreferences("zeekr", MODE_PRIVATE)
                    .getString("status", "Обнаружение выполняется автоматически"));
            handler.postDelayed(this, 1000);
        }
    };

    static String[] scanPermissions() {
        return new String[]{Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT};
    }

    static boolean hasScanPermissions(Context context) {
        for (String permission : scanPermissions()) {
            if (context.checkSelfPermission(permission) != PackageManager.PERMISSION_GRANTED) {
                return false;
            }
        }
        return true;
    }

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        int padding = (int) (24 * getResources().getDisplayMetrics().density);
        layout.setPadding(padding, padding, padding, padding);
        TextView title = new TextView(this);
        title.setText("Zeekr BLE · Zeekr862");
        title.setTextSize(24);
        layout.addView(title);
        status = new TextView(this);
        layout.addView(status);
        TextView description = new TextView(this);
        description.setText("Обнаружение выполняется автоматически. "
                + "При обнаружении Zeekr862 отправляется ZEEKR_NEAR для MacroDroid.");
        layout.addView(description);
        setContentView(layout);
        if (!hasScanPermissions(this)) {
            // Only the permissions required by the automatic BLE service.
            requestPermissions(scanPermissions(), REQUEST_SCAN);
        }
    }

    private void ensureScanService() {
        if (hasScanPermissions(this)) {
            startForegroundService(new Intent(this, BLEScanService.class));
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(requestCode, permissions, results);
        if (requestCode == REQUEST_SCAN && hasScanPermissions(this)) {
            ensureScanService();
        } else if (requestCode == REQUEST_SCAN) {
            getSharedPreferences("zeekr", MODE_PRIVATE).edit()
                    .putString("status", "Для BLE-сканирования нужно разрешение на устройства поблизости").apply();
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        ensureScanService();
        handler.removeCallbacks(refresh);
        handler.post(refresh);
    }

    @Override
    protected void onPause() {
        handler.removeCallbacks(refresh);
        super.onPause();
    }
}
