package com.eschool.bot;

import android.app.Notification;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;
import android.util.Log;
import org.json.JSONObject;
import java.io.*;
import java.net.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class NotificationService extends NotificationListenerService {

    private static final String TAG = "ESchoolBot";
    private static final String BANKILY_PACKAGE = "mr.bpm.digitalbanking.consumer";
    private static final String WHATSAPP_PACKAGE = "com.whatsapp.w4b";
    private static final int REQUIRED_AMOUNT = 200;
    private static final String ASSIGN_URL = "http://localhost:3000/assign-code";
    private static final String FIND_URL = "http://localhost:3000/find-and-send";
    private static final long WAIT_TIMEOUT = 3 * 60 * 1000;

    private ExecutorService executor = Executors.newSingleThreadExecutor();
    private Handler handler = new Handler(Looper.getMainLooper());

    @Override
    public void onNotificationPosted(StatusBarNotification sbn) {
        String pkg = sbn.getPackageName();
        if (WHATSAPP_PACKAGE.equals(pkg)) handleWhatsApp(sbn);
        else if (BANKILY_PACKAGE.equals(pkg)) handleBankily(sbn);
    }

    // ========== واتساب ==========
    private void handleWhatsApp(StatusBarNotification sbn) {
        Bundle extras = sbn.getNotification().extras;
        String title = extras.getString(Notification.EXTRA_TITLE, "");
        String text  = extras.getString(Notification.EXTRA_TEXT, "");
        String full  = title + " " + text;

        if (!full.contains("\u062a\u0633\u062c\u064a\u0644 \u062c\u062f\u064a\u062f") 
            || !full.contains("\u0627\u0644\u0645\u0639\u0627\u0645\u0644\u0629:")) return;

        String name = extractBetween(full, "\u0627\u0644\u0627\u0633\u0645:", "\n");
        if (name == null) name = extractBetween(full, "\u0627\u0644\u0627\u0633\u0645:", null);
        String txId = extractTransactionId(full);
        int amount  = extractAmount(full);

        if (name == null || txId == null) return;
        name = name.trim();

        Log.d(TAG, "WA: " + name + " | TX: " + txId + " | amount: " + amount);

        SharedPreferences waPrefs = getSharedPreferences("pending_wa", MODE_PRIVATE);
        waPrefs.edit()
            .putString(txId, name)
            .putInt(txId + "_amount", amount)
            .putLong(txId + "_time", System.currentTimeMillis())
            .apply();

        sendBroadcast(new Intent("com.eschool.STUDENT_PENDING")
            .putExtra("name", name).putExtra("txId", txId).putExtra("amount", amount));

        // تحقق إذا كان Bankily وصل مسبقاً
        SharedPreferences bankilyPrefs = getSharedPreferences("pending_bankily", MODE_PRIVATE);
        String savedAmount = bankilyPrefs.getString(txId, null);
        if (savedAmount == null) {
            String alt = txId.startsWith("0") ? txId.substring(1) : "0" + txId;
            savedAmount = bankilyPrefs.getString(alt, null);
        }

        if (savedAmount != null && Integer.parseInt(savedAmount) >= REQUIRED_AMOUNT) {
            Log.d(TAG, "Bankily already received! Assigning code: " + name);
            final String fn = name; final String ft = txId;
            executor.execute(() -> assignCode(fn, ft));
            return;
        }

        // ابدأ عداد 3 دقائق
        final String finalName = name;
        final String finalTxId = txId;
        handler.postDelayed(() -> {
            SharedPreferences check = getSharedPreferences("pending_wa", MODE_PRIVATE);
            if (check.contains(finalTxId)) {
                Log.d(TAG, "Timeout: no Bankily for " + finalName);
                executor.execute(() -> sendTimeoutMessage(finalTxId));
                check.edit().remove(finalTxId).remove(finalTxId + "_amount")
                    .remove(finalTxId + "_time").apply();
            }
        }, WAIT_TIMEOUT);
    }

    // ========== Bankily ==========
    private void handleBankily(StatusBarNotification sbn) {
        Bundle extras = sbn.getNotification().extras;
        String title = extras.getString(Notification.EXTRA_TITLE, "");
        String text  = extras.getString(Notification.EXTRA_TEXT, "");
        String full  = (title + " " + text).toLowerCase();

        boolean isSuccess = full.contains("transfert") || full.contains("\u0646\u0642\u0644")
                         || full.contains("\u062a\u062d\u0648\u064a\u0644") 
                         || full.contains("reussi") || full.contains("\u0646\u0627\u062c\u062d");
        if (!isSuccess) return;

        String txId = extractTransactionId(title + " " + text);
        int amount  = extractAmount(title + " " + text);
        if (txId == null) return;

        Log.d(TAG, "Bankily TX: " + txId + " | amount: " + amount);

        // احفظ إشعار Bankily دائماً
        SharedPreferences bankilyPrefs = getSharedPreferences("pending_bankily", MODE_PRIVATE);
        bankilyPrefs.edit().putString(txId, String.valueOf(amount)).apply();

        // تحقق إذا كانت رسالة واتساب وصلت مسبقاً
        SharedPreferences waPrefs = getSharedPreferences("pending_wa", MODE_PRIVATE);
        String studentName = waPrefs.getString(txId, null);
        if (studentName == null) {
            String alt = txId.startsWith("0") ? txId.substring(1) : "0" + txId;
            studentName = waPrefs.getString(alt, null);
            if (studentName != null) txId = alt;
        }

        if (studentName == null) {
            Log.d(TAG, "No WA yet for TX: " + txId + " — waiting");
            return;
        }

        if (amount < REQUIRED_AMOUNT) {
            sendBroadcast(new Intent("com.eschool.AMOUNT_LOW")
                .putExtra("name", studentName).putExtra("amount", amount).putExtra("txId", txId));
            return;
        }

        final String fn = studentName; final String ft = txId;
        executor.execute(() -> assignCode(fn, ft));
    }

    // ========== تخصيص رمز وإرساله للطالب ==========
    private void assignCode(String name, String txId) {
        try {
            JSONObject body = new JSONObject();
            body.put("txId", txId);
            body.put("name", name);

            URL url = new URL(ASSIGN_URL);
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("POST");
            conn.setRequestProperty("Content-Type", "application/json");
            conn.setDoOutput(true);
            conn.setConnectTimeout(5000);

            try (OutputStream os = conn.getOutputStream()) {
                os.write(body.toString().getBytes("UTF-8"));
            }

            BufferedReader br = new BufferedReader(new InputStreamReader(conn.getInputStream()));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = br.readLine()) != null) sb.append(line);

            JSONObject resp = new JSONObject(sb.toString());
            Log.d(TAG, "assign-code response: " + sb.toString());

            if (resp.optBoolean("ok")) {
                String code = resp.optString("code");
                sendBroadcast(new Intent("com.eschool.STUDENT_REGISTERED")
                    .putExtra("name", name).putExtra("code", code));

                getSharedPreferences("pending_wa", MODE_PRIVATE).edit()
                    .remove(txId).remove(txId + "_amount").remove(txId + "_time").apply();
                getSharedPreferences("pending_bankily", MODE_PRIVATE).edit().remove(txId).apply();
            }

        } catch (Exception e) {
            Log.e(TAG, "assignCode error: " + e.getMessage());
        }
    }

    // ========== رسالة انتهاء المهلة ==========
    private void sendTimeoutMessage(String txId) {
        try {
            String message = "\u062a\u0623\u062e\u0631 \u0648\u0635\u0648\u0644 \u062a\u0623\u0643\u064a\u062f \u0627\u0644\u062f\u0641\u0639 \u0645\u0646 Bankily.\n\n"
                + "\u0625\u0630\u0627 \u0643\u0646\u062a \u0642\u062f \u0623\u0631\u0633\u0644\u062a \u0627\u0644\u0645\u0628\u0644\u063a\u060c "
                + "\u064a\u0631\u062c\u0649 \u0627\u0644\u062a\u0648\u0627\u0635\u0644 \u0645\u0639\u0646\u0627 \u0645\u0628\u0627\u0634\u0631\u0629:\n\n"
                + "48 58 57 61\n\n"
                + "\u0633\u0646\u062a\u062d\u0642\u0642 \u064a\u062f\u0648\u064a\u0627\u064b \u0648\u062a\u0641\u0639\u064a\u0644 \u062d\u0633\u0627\u0628\u0643.";

            JSONObject body = new JSONObject();
            body.put("txId", txId);
            body.put("message", message);

            URL url = new URL(FIND_URL);
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("POST");
            conn.setRequestProperty("Content-Type", "application/json");
            conn.setDoOutput(true);
            conn.setConnectTimeout(5000);

            try (OutputStream os = conn.getOutputStream()) {
                os.write(body.toString().getBytes("UTF-8"));
            }

            BufferedReader br = new BufferedReader(new InputStreamReader(conn.getInputStream()));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = br.readLine()) != null) sb.append(line);
            Log.d(TAG, "Timeout sent: " + sb.toString());

        } catch (Exception e) {
            Log.e(TAG, "Timeout error: " + e.getMessage());
        }
    }

    // ========== أدوات ==========
    private String extractBetween(String text, String start, String end) {
        int s = text.indexOf(start);
        if (s < 0) return null;
        s += start.length();
        if (end == null) return text.substring(s).trim();
        int e = text.indexOf(end, s);
        if (e < 0) return text.substring(s).trim();
        return text.substring(s, e).trim();
    }

    private String extractTransactionId(String text) {
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("\\b(\\d{10,})\\b").matcher(text);
        if (m.find()) return m.group(1);
        return null;
    }

    private int extractAmount(String text) {
        java.util.regex.Matcher m = java.util.regex.Pattern.compile(
            "(\\d+)\\s*(?:MRU|mru|\u0623\u0648\u0642\u064a\u0629|ouguiya)",
            java.util.regex.Pattern.CASE_INSENSITIVE).matcher(text);
        if (m.find()) return Integer.parseInt(m.group(1));
        m = java.util.regex.Pattern.compile("\\b(\\d{1,5})\\b").matcher(text);
        int last = 0;
        while (m.find()) { int v = Integer.parseInt(m.group(1)); if (v >= 50 && v <= 99999) last = v; }
        return last;
    }
}