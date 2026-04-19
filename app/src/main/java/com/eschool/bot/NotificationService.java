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
    private static final String WHATSAPP_PACKAGE = "com.whatsapp.w4b";
    private static final String TELEGRAM_PACKAGE = "org.telegram.messenger";
    private static final String BOT_TOKEN = "8717542008:AAGwVep7MqfHnqzYo8DwgLDFnrGw2kSPE6M";
    private static final int REQUIRED_AMOUNT = 200;
    private static final String APP_URL = "https://slip-retainer-phonics.ngrok-free.dev";
    private static final String WA_SERVER = "http://localhost:3000/send";

    private ExecutorService executor = Executors.newSingleThreadExecutor();

    @Override
    public void onNotificationPosted(StatusBarNotification sbn) {
        String pkg = sbn.getPackageName();
        if (WHATSAPP_PACKAGE.equals(pkg)) {
            handleWhatsApp(sbn);
        } else if (BANKILY_PACKAGE.equals(pkg)) {
            handleBankily(sbn);
        } else if (TELEGRAM_PACKAGE.equals(pkg)) {
            handleTelegram(sbn);
        }
    }

    // ========== واتساب بيزنس ==========
    private void handleWhatsApp(StatusBarNotification sbn) {
        Bundle extras = sbn.getNotification().extras;
        String title = extras.getString(Notification.EXTRA_TITLE, "");
        String text  = extras.getString(Notification.EXTRA_TEXT, "");
        String full  = title + " " + text;

        if (!full.contains("الاسم:") || !full.contains("المعاملة:")) return;

        String name = extractBetween(full, "الاسم:", "\n");
        if (name == null) name = extractBetween(full, "الاسم:", null);
        String txId = extractTransactionId(full);
        int amount  = extractAmount(full);

        // احفظ رقم واتساب الطالب (من عنوان الإشعار)
        String waPhone = title.replaceAll("[^0-9+]", "");

        if (name == null || txId == null) return;
        name = name.trim();

        SharedPreferences prefs = getSharedPreferences("pending_students", MODE_PRIVATE);
        prefs.edit()
            .putString(txId, name)
            .putString(txId + "_phone", waPhone)
            .putLong(txId + "_time", System.currentTimeMillis())
            .apply();

        Intent intent = new Intent("com.eschool.STUDENT_PENDING");
        intent.putExtra("name", name);
        intent.putExtra("txId", txId);
        intent.putExtra("amount", amount);
        sendBroadcast(intent);

        Log.d(TAG, "Student saved: " + name + " | " + txId + " | phone: " + waPhone);

        // جدولة تحقق بعد 5 دقائق — إذا لم يتم التسجيل أرسل رسالة للطالب
        final String finalName2 = name;
        final String finalTxId2 = txId;
        final String finalPhone2 = waPhone;
        new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(() -> {
            SharedPreferences checkPrefs = getSharedPreferences("pending_students", MODE_PRIVATE);
            // إذا الطالب لا يزال منتظراً (لم يُسجَّل بعد)
            if (checkPrefs.contains(finalTxId2)) {
                Log.d(TAG, "Timeout: student still pending after 5min: " + finalName2);
                executor.execute(() -> sendTimeoutMessage(finalName2, finalPhone2));
            }
        }, 5 * 60 * 1000); // 5 دقائق
    }

    // ========== Bankily ==========
    private void handleBankily(StatusBarNotification sbn) {
        Bundle extras = sbn.getNotification().extras;
        String title = extras.getString(Notification.EXTRA_TITLE, "");
        String text  = extras.getString(Notification.EXTRA_TEXT, "");
        String full  = (title + " " + text).toLowerCase();

        boolean isSuccess = full.contains("transfert") || full.contains("نقل")
                         || full.contains("تحويل") || full.contains("reussi")
                         || full.contains("ناجح");
        if (!isSuccess) return;

        String txId = extractTransactionId(title + " " + text);
        int amount  = extractAmount(title + " " + text);
        if (txId == null) return;

        SharedPreferences prefs = getSharedPreferences("pending_students", MODE_PRIVATE);
        String studentName = prefs.getString(txId, null);
        if (studentName == null) studentName = findStudentByAmount(prefs, amount, txId);
        if (studentName == null) {
            getSharedPreferences("pending_bankily", MODE_PRIVATE)
                .edit().putString(txId, String.valueOf(amount)).apply();
            return;
        }

        if (amount < REQUIRED_AMOUNT) {
            sendBroadcast(new Intent("com.eschool.AMOUNT_LOW")
                .putExtra("name", studentName)
                .putExtra("amount", amount)
                .putExtra("txId", txId));
            return;
        }

        final String finalName = studentName;
        final String finalTxId = txId;
        final String finalPhone = prefs.getString(txId + "_phone", "");
        final SharedPreferences finalPrefs = prefs;
        executor.execute(() -> registerStudent(finalName, finalTxId, finalPhone, finalPrefs));
    }

    // ========== تلغرام — يلتقط رد البوت ==========
    private void handleTelegram(StatusBarNotification sbn) {
        Bundle extras = sbn.getNotification().extras;
        String title = extras.getString(Notification.EXTRA_TITLE, "");
        String text  = extras.getString(Notification.EXTRA_TEXT, "");
        String full  = title + " " + text;

        Log.d(TAG, "Telegram notification: " + full);

        // ابحث عن رد البوت
        if (!full.contains("تم تسجيل")) return;

        // استخرج المعرف — قد يكون بدون صفر في البداية
        String txId = extractTransactionId(full);
        if (txId == null) return;

        SharedPreferences prefs = getSharedPreferences("pending_students", MODE_PRIVATE);

        // ابحث بالمعرف مباشرة
        String name  = prefs.getString(txId, null);
        String phone = prefs.getString(txId + "_phone", "");

        // إذا لم يجد — جرب مع صفر في البداية (0 + txId)
        if (name == null) {
            String txIdWithZero = "0" + txId;
            name  = prefs.getString(txIdWithZero, null);
            phone = prefs.getString(txIdWithZero + "_phone", "");
            if (name != null) txId = txIdWithZero;
        }

        // إذا لم يجد — ابحث في كل المفاتيح عن أقرب تطابق
        if (name == null) {
            for (java.util.Map.Entry<String, ?> entry : prefs.getAll().entrySet()) {
                String key = entry.getKey();
                if (key.endsWith("_phone")) continue;
                if (key.contains(txId) || txId.contains(key)) {
                    name  = (String) entry.getValue();
                    phone = prefs.getString(key + "_phone", "");
                    txId  = key;
                    break;
                }
            }
        }

        if (name == null || phone.isEmpty()) {
            Log.d(TAG, "Bot confirmed but student not found for TX: " + txId);
            return;
        }

        Log.d(TAG, "Bot confirmed: " + name + " | sending WA to " + phone);

        final String finalName  = name;
        final String finalTxId  = txId;
        final String finalPhone = phone;
        executor.execute(() -> sendWhatsAppConfirmation(finalName, finalTxId, finalPhone));
    }

    // ========== إرسال رسالة واتساب للطالب ==========
    private void sendWhatsAppConfirmation(String name, String txId, String phone) {
        try {
            String message = "مرحباً " + name + "! 🎉\n\n"
                + "✅ تم تسجيلك بنجاح في الدورة\n\n"
                + "🔑 رمز دخولك: " + txId + "\n\n"
                + "🔗 رابط التطبيق:\n" + APP_URL + "\n\n"
                + "أدخل رمز دخولك عند تسجيل الدخول.";

            JSONObject body = new JSONObject();
            body.put("phone", phone);
            body.put("message", message);

            URL url = new URL(WA_SERVER);
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

            Log.d(TAG, "WA sent: " + sb.toString());

            Intent intent = new Intent("com.eschool.WA_SENT");
            intent.putExtra("name", name);
            intent.putExtra("phone", phone);
            sendBroadcast(intent);

        } catch (Exception e) {
            Log.e(TAG, "WA send error: " + e.getMessage());
        }
    }

    // ========== تسجيل الطالب عبر تلغرام ==========
    private void registerStudent(String name, String txId, String phone, SharedPreferences prefs) {
        try {
            String botMessage = "طالب جديد\nالاسم: " + name + "\nالمعرف: " + txId;
            String botResponse = sendTelegramMessage(botMessage);
            Log.d(TAG, "Bot response: " + botResponse);

            Intent intent = new Intent("com.eschool.STUDENT_REGISTERED");
            intent.putExtra("name", name);
            intent.putExtra("txId", txId);
            sendBroadcast(intent);

            // انتظر رد البوت عبر إشعار تلغرام — handleTelegram سيرسل للطالب

            prefs.edit().remove(txId).remove(txId + "_phone").apply();

        } catch (Exception e) {
            Log.e(TAG, "Register error: " + e.getMessage());
        }
    }

    // ========== تلغرام ==========
    private String sendTelegramMessage(String text) throws Exception {
        String chatId = "6681793612";
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
        return new JSONObject(sb.toString()).optBoolean("ok") ? "sent" : "error";
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
                        .getJSONObject("message").getJSONObject("chat").getString("id");
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "fetchAdminChatId error: " + e.getMessage());
        }
        return "";
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

    private String findStudentByAmount(SharedPreferences prefs, int amount, String txId) {
        if (amount >= REQUIRED_AMOUNT) {
            for (java.util.Map.Entry<String, ?> entry : prefs.getAll().entrySet()) {
                if (entry.getKey().endsWith("_phone")) continue;
                String name = (String) entry.getValue();
                String oldPhone = prefs.getString(entry.getKey() + "_phone", "");
                prefs.edit().remove(entry.getKey()).remove(entry.getKey() + "_phone")
                    .putString(txId, name).putString(txId + "_phone", oldPhone).apply();
                return name;
            }
        }
        return null;
    }

    // ========== رسالة انتهاء المهلة ==========
    private void sendTimeoutMessage(String name, String phone) {
        try {
            String message = "\u0645\u0631\u062d\u0628\u0627\u064b " + name + "!\n\n" + "\u062a\u0623\u062e\u0631 \u0648\u0635\u0648\u0644 \u062a\u0623\u0643\u064a\u062f \u0627\u0644\u062f\u0641\u0639 \u0645\u0646 Bankily.\n\n" + "\u0625\u0630\u0627 \u0643\u0646\u062a \u0642\u062f \u0623\u0631\u0633\u0644\u062a \u0627\u0644\u0645\u0628\u0644\u063a\u060c \u064a\u0631\u062c\u0649 \u0627\u0644\u062a\u0648\u0627\u0635\u0644 \u0645\u0639\u0646\u0627 \u0645\u0628\u0627\u0634\u0631\u0629:\n\n" + "48 58 57 61\n\n" + "\u0633\u0646\u062a\u062d\u0642\u0642 \u064a\u062f\u0648\u064a\u0627\u064b \u0648\u062a\u0641\u0639\u064a\u0644 \u062d\u0633\u0627\u0628\u0643 \u0641\u064a \u0623\u0642\u0631\u0628 \u0648\u0642\u062a.";

            JSONObject body = new JSONObject();
            body.put("phone", phone);
            body.put("message", message);

            URL url = new URL(WA_SERVER);
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
            Log.d(TAG, "Timeout WA sent: " + sb.toString());

        } catch (Exception e) {
            Log.e(TAG, "Timeout WA error: " + e.getMessage());
        }
    }

    private String extractTransactionId(String text) {
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("\\b(\\d{10,})\\b").matcher(text);
        if (m.find()) return m.group(1);
        return null;
    }

    private int extractAmount(String text) {
        java.util.regex.Matcher m = java.util.regex.Pattern.compile(
            "(\\d+)\\s*(?:MRU|mru|أوقية|ouguiya)", java.util.regex.Pattern.CASE_INSENSITIVE).matcher(text);
        if (m.find()) return Integer.parseInt(m.group(1));
        m = java.util.regex.Pattern.compile("\\b(\\d{1,5})\\b").matcher(text);
        int last = 0;
        while (m.find()) { int v = Integer.parseInt(m.group(1)); if (v >= 50 && v <= 99999) last = v; }
        return last;
    }
}