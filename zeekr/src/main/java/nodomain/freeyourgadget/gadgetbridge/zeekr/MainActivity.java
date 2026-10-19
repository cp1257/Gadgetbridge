package nodomain.freeyourgadget.gadgetbridge.zeekr;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.View;
import android.widget.Button;
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
                    .getString("status", "Сканирование остановлено"));
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
        description.setText("Обнаружение только по BLE-имени. При каждом обнаружении отправляется "
                + "ZEEKR_NEAR для MacroDroid. Подключение к автомобилю не выполняется. "
                + "После запуска экран приложения можно закрыть.");
        layout.addView(description);
        button(layout, "Запустить сканирование", v -> startScanning());
        button(layout, "Остановить сканирование", v -> {
            getSharedPreferences("zeekr", MODE_PRIVATE).edit()
                    .putBoolean("enabled", false).putString("status", "Сканирование остановлено").apply();
            stopService(new Intent(this, BLEScanService.class));
        });
        button(layout, "Настройки Bluetooth", v -> startActivity(new Intent(Settings.ACTION_BLUETOOTH_SETTINGS)));
        setContentView(layout);
        if (getSharedPreferences("zeekr", MODE_PRIVATE).getBoolean("enabled", true)) {
            startScanning();
        }
    }

    private void button(LinearLayout layout, String text, View.OnClickListener listener) {
        Button button = new Button(this);
        button.setText(text);
        button.setOnClickListener(listener);
        layout.addView(button);
    }

    private void startScanning() {
        if (!hasScanPermissions(this)) {
            // Only permissions used by this scanner. No "request all permissions" flow.
            requestPermissions(scanPermissions(), REQUEST_SCAN);
            return;
        }
        getSharedPreferences("zeekr", MODE_PRIVATE).edit().putBoolean("enabled", true).apply();
        startForegroundService(new Intent(this, BLEScanService.class));
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(requestCode, permissions, results);
        if (requestCode == REQUEST_SCAN && hasScanPermissions(this)) {
            startScanning();
        } else if (requestCode == REQUEST_SCAN) {
            getSharedPreferences("zeekr", MODE_PRIVATE).edit()
                    .putString("status", "Для BLE-сканирования нужно разрешение на устройства поблизости").apply();
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        handler.post(refresh);
    }

    @Override
    protected void onPause() {
        handler.removeCallbacks(refresh);
        super.onPause();
    }
}
