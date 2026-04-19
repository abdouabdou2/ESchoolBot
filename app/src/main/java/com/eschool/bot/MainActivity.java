package com.eschool.bot;

import android.app.*;
import android.content.*;
import android.content.pm.PackageManager;
import android.os.*;
import android.provider.Settings;
import android.text.TextUtils;
import android.view.*;
import android.widget.*;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.NotificationCompat;
import java.util.*;

public class MainActivity extends AppCompatActivity {

    private static final String CHANNEL_ID = "eschool_channel";
    private TextView statusView, logView;
    private Switch serviceSwitch;
    private SharedPreferences prefs;
    private BroadcastReceiver receiver;
    private StringBuilder logBuilder = new StringBuilder();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        prefs = getSharedPreferences("pending_students", MODE_PRIVATE);
        createNotificationChannel();

        statusView = findViewById(R.id.statusView);
        logView    = findViewById(R.id.logView);
        serviceSwitch = findViewById(R.id.serviceSwitch);

        updateStatus();

        serviceSwitch.setOnCheckedChangeListener((btn, checked) -> {
            if (checked && !isNotificationListenerEnabled()) {
                serviceSwitch.setChecked(false);
                startActivity(new Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS));
                toast("فعّل الإذن ثم ارجع للتطبيق");
            }
            updateStatus();
        });

        // استقبال الأحداث من NotificationService
        receiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context ctx, Intent intent) {
                String action = intent.getAction();
                if ("com.eschool.STUDENT_REGISTERED".equals(action)) {
                    String name  = intent.getStringExtra("name");
                    String txId  = intent.getStringExtra("txId");
                    addLog("✅ تم تسجيل: " + name + " | " + txId);
                    showNotification("تم التسجيل", name + " سُجّل بنجاح");
                } else if ("com.eschool.AMOUNT_LOW".equals(action)) {
                    String name   = intent.getStringExtra("name");
                    int amount    = intent.getIntExtra("amount", 0);
                    addLog("⚠️ مبلغ غير كافٍ: " + name + " أرسل " + amount + " MRU");
                    showNotification("مبلغ غير كافٍ", name + " أرسل " + amount + " MRU فقط");
                }
            }
        };

        IntentFilter filter = new IntentFilter();
        filter.addAction("com.eschool.STUDENT_REGISTERED");
        filter.addAction("com.eschool.AMOUNT_LOW");
        registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED);

        // عرض الطلاب المنتظرين
        refreshPendingList();
    }

    private void refreshPendingList() {
        Map<String, ?> all = prefs.getAll();
        if (all.isEmpty()) {
            addLog("— لا يوجد طلاب منتظرون");
        } else {
            addLog("📋 طلاب منتظرون: " + all.size());
            for (Map.Entry<String, ?> e : all.entrySet()) {
                addLog("  • " + e.getValue() + " | " + e.getKey());
            }
        }
    }

    private void updateStatus() {
        boolean enabled = isNotificationListenerEnabled();
        statusView.setText(enabled ? "🟢 الخدمة تعمل" : "🔴 الخدمة متوقفة");
        statusView.setTextColor(enabled ? 0xFF0F6E56 : 0xFFA32D2D);
        serviceSwitch.setChecked(enabled);
    }

    private boolean isNotificationListenerEnabled() {
        String flat = Settings.Secure.getString(getContentResolver(),
                "enabled_notification_listeners");
        return flat != null && flat.contains(getPackageName());
    }

    private void addLog(String msg) {
        runOnUiThread(() -> {
            logBuilder.insert(0, msg + "\n");
            if (logBuilder.length() > 3000) logBuilder.setLength(3000);
            logView.setText(logBuilder.toString());
        });
    }

    private void toast(String msg) {
        Toast.makeText(this, msg, Toast.LENGTH_LONG).show();
    }

    private void showNotification(String title, String text) {
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        Notification n = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentTitle(title)
                .setContentText(text)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setAutoCancel(true)
                .build();
        nm.notify((int) System.currentTimeMillis(), n);
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel ch = new NotificationChannel(
                    CHANNEL_ID, "ESchool Bot", NotificationManager.IMPORTANCE_HIGH);
            ((NotificationManager) getSystemService(NOTIFICATION_SERVICE)).createNotificationChannel(ch);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        updateStatus();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (receiver != null) unregisterReceiver(receiver);
    }
}
