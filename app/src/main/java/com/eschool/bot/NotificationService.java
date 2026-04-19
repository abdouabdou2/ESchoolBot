package com.eschool.bot;

import android.app.Notification;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
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
    private static final String BOT_TOKEN = "8717542008:AAGwVep7MqfHnqzYo8DwgLDFnrGw2kSPE6M";
    private static final String APP_URL = "https://slip-retainer-phonics.ngrok-free.dev/";
    private static final int REQUIRED_AMOUNT = 200;

    private ExecutorService executor = Executors.newSingleThreadExecutor();

    @Override
    public void onNotificationPosted(StatusBarNotification sbn) {
        if (!BANKILY_PACKAGE.equals(sbn.getPackageName())) return;

        Bundle extras = sbn.getNotification().extras;
        String title = extras.getString(Notification.EXTRA_TITLE, "");
        String text  = extras.getString(Notification.EXTRA_TEXT, "");
        String full  = (title + " " + text).toLowerCase();

        Log.d(TAG, "Bankily notification: " + full);

        // تحقق أن العملية ناجحة
        boolean isSuccess = full.contains("transfert") || full.contains("نقل") 
                         || full.contains("تحويل") || full.contains("reussi")
                         || full.contains("ناجح");
        if (!isSuccess) return;

        // استخرج رقم المعاملة
        String txId = extractTransactionId(title + " " + text);
        // استخرج المبلغ
        int amount = extractAmount(title + " " + text);

        if (txId == null || txId.isEmpty()) {
            Log.d(TAG, "No transaction ID found");
            return;
        }

        Log.d(TAG, "TX: " + txId + " | Amount: " + amount);

        // ابحث عن طالب منتظر بنفس رقم المعاملة أو تطابق المبلغ
        SharedPreferences prefs = getSharedPreferences("pending_students", MODE_PRIVATE);
        String studentName = prefs.getString(txId, null);

        // إذا لم يجد بالمعرف الكامل، ابحث عن أقرب طالب بالمبلغ الصحيح
        if (studentName == null) {
            studentName = findStudentByAmount(prefs, amount, txId);
        }

        if (studentName == null) {
            Log.d(TAG, "No matching student for TX: " + txId);
            return;
        }

        if (amount < REQUIRED_AMOUNT) {
            Log.d(TAG, "Amount too low: " + amount);
            // أرسل إشعاراً للمدير
            sendBroadcast(new Intent("com.eschool.AMOUNT_LOW")
                .putExtra("name", studentName)
                .putExtra("amount", amount)
                .putExtra("txId", txId));
            return;
        }

        // ✅ كل شيء صحيح — سجّل الطالب
        final String finalName = studentName;
        final String finalTxId = txId;
        final SharedPreferences finalPrefs = prefs;

        executor.execute(() -> registerStudent(finalName, finalTxId, finalPrefs));
    }

    private void registerStudent(String name, String txId, SharedPreferences prefs) {
        try {
            // 1. أرسل للبوت
            String botMessage = "طالب جديد\nالاسم: " + name + "\nالمعرف: " + txId;
            String botResponse = sendTelegramMessage(botMessage);
            Log.d(TAG, "Bot response: " + botResponse);

            // 2. أرسل إشعار للتطبيق الرئيسي
            Intent intent = new Intent("com.eschool.STUDENT_REGISTERED");
            intent.putExtra("name", name);
            intent.putExtra("txId", txId);
            intent.putExtra("botResponse", botResponse);
            sendBroadcast(intent);

            // 3. احذف الطالب من قائمة الانتظار
            prefs.edit().remove(txId).apply();

        } catch (Exception e) {
            Log.e(TAG, "Error registering student: " + e.getMessage());
        }
    }

    private String sendTelegramMessage(String text) throws Exception {
        // نحتاج chat_id — نحصل عليه من getUpdates أولاً
        SharedPreferences prefs = getSharedPreferences("bot_config", MODE_PRIVATE);
        String chatId = prefs.getString("admin_chat_id", "");

        if (chatId.isEmpty()) {
            chatId = fetchAdminChatId();
            if (!chatId.isEmpty()) {
                prefs.edit().putString("admin_chat_id", chatId).apply();
            }
        }

        if (chatId.isEmpty()) return "no_chat_id";

        URL url = new URL("https://api.telegram.org/bot" + BOT_TOKEN + "/sendMessage");
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod("POST");
        conn.setRequestProperty("Content-Type", "application/json");
        conn.setDoOutput(true);

        JSONObject body = new JSONObject();
        body.put("chat_id", chatId);
        body.put("text", text);

        try (OutputStream os = conn.getOutputStream()) {
            os.write(body.toString().getBytes("UTF-8"));
        }

        BufferedReader br = new BufferedReader(new InputStreamReader(conn.getInputStream()));
        StringBuilder sb = new StringBuilder();
        String line;
        while ((line = br.readLine()) != null) sb.append(line);

        JSONObject resp = new JSONObject(sb.toString());
        return resp.optBoolean("ok") ? "sent" : "error";
    }

    private String fetchAdminChatId() {
        try {
            URL url = new URL("https://api.telegram.org/bot" + BOT_TOKEN + "/getUpdates");
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("GET");
            BufferedReader br = new BufferedReader(new InputStreamReader(conn.getInputStream()));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = br.readLine()) != null) sb.append(line);
            JSONObject resp = new JSONObject(sb.toString());
            if (resp.optBoolean("ok")) {
                org.json.JSONArray results = resp.getJSONArray("result");
                if (results.length() > 0) {
                    return results.getJSONObject(results.length() - 1)
                        .getJSONObject("message")
                        .getJSONObject("chat")
                        .getString("id");
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "fetchAdminChatId error: " + e.getMessage());
        }
        return "";
    }

    private String findStudentByAmount(SharedPreferences prefs, int amount, String txId) {
        // ابحث عن طالب انتظر بأي مفتاح إذا كان المبلغ صحيح
        if (amount >= REQUIRED_AMOUNT) {
            java.util.Map<String, ?> all = prefs.getAll();
            for (java.util.Map.Entry<String, ?> entry : all.entrySet()) {
                if (entry.getKey().equals("__dummy")) continue;
                // أعد تسمية المفتاح برقم المعاملة الصحيح
                String name = (String) entry.getValue();
                prefs.edit().remove(entry.getKey()).putString(txId, name).apply();
                return name;
            }
        }
        return null;
    }

    private String extractTransactionId(String text) {
        // ابحث عن أرقام طويلة (10+ خانة) = رقم المعاملة
        java.util.regex.Pattern p = java.util.regex.Pattern.compile("\\b(\\d{10,})\\b");
        java.util.regex.Matcher m = p.matcher(text);
        if (m.find()) return m.group(1);
        return null;
    }

    private int extractAmount(String text) {
        // ابحث عن رقم قبل MRU أو أوقية
        java.util.regex.Pattern p = java.util.regex.Pattern.compile("(\\d+)\\s*(?:MRU|mru|أوقية|ouguiya)", 
            java.util.regex.Pattern.CASE_INSENSITIVE);
        java.util.regex.Matcher m = p.matcher(text);
        if (m.find()) return Integer.parseInt(m.group(1));
        // بديل: ابحث عن أي رقم صغير (المبلغ عادة 3 خانات أو أقل)
        p = java.util.regex.Pattern.compile("\\b(\\d{1,5})\\b");
        m = p.matcher(text);
        int last = 0;
        while (m.find()) {
            int v = Integer.parseInt(m.group(1));
            if (v >= 50 && v <= 99999) last = v;
        }
        return last;
    }
}
