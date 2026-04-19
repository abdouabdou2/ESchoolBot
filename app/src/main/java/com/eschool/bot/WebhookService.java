package com.eschool.bot;

import android.app.Service;
import android.content.*;
import android.os.IBinder;
import android.util.Log;
import org.json.JSONObject;
import java.io.*;
import java.net.*;

/**
 * خادم HTTP محلي صغير يستقبل بيانات الطالب من صفحة الويب
 * يعمل على المنفذ 8080
 * الصفحة ترسل: POST http://localhost:8080/register
 * {"name": "محمد", "txId": "09260410071148", "amount": 200}
 */
public class WebhookService extends Service {

    private static final String TAG = "WebhookService";
    private static final int PORT = 8080;
    private ServerSocket serverSocket;
    private Thread serverThread;
    private SharedPreferences prefs;

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        prefs = getSharedPreferences("pending_students", MODE_PRIVATE);
        serverThread = new Thread(this::runServer);
        serverThread.start();
        Log.d(TAG, "Webhook server started on port " + PORT);
        return START_STICKY;
    }

    private void runServer() {
        try {
            serverSocket = new ServerSocket(PORT);
            while (!serverSocket.isClosed()) {
                try {
                    Socket client = serverSocket.accept();
                    handleClient(client);
                } catch (Exception e) {
                    if (!serverSocket.isClosed()) Log.e(TAG, "Client error: " + e.getMessage());
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Server error: " + e.getMessage());
        }
    }

    private void handleClient(Socket client) throws Exception {
        BufferedReader in = new BufferedReader(new InputStreamReader(client.getInputStream()));
        StringBuilder rawRequest = new StringBuilder();
        String line;
        int contentLength = 0;

        // قراءة الرأس
        while (!(line = in.readLine()).isEmpty()) {
            rawRequest.append(line).append("\n");
            if (line.toLowerCase().startsWith("content-length:")) {
                contentLength = Integer.parseInt(line.split(":")[1].trim());
            }
        }

        // قراءة الجسم
        char[] body = new char[contentLength];
        in.read(body, 0, contentLength);
        String bodyStr = new String(body);

        String response;
        try {
            JSONObject json = new JSONObject(bodyStr);
            String name   = json.getString("name");
            String txId   = json.getString("txId");
            int amount    = json.optInt("amount", 0);

            // احفظ الطالب منتظراً التأكيد من Bankily
            prefs.edit().putString(txId, name).apply();
            Log.d(TAG, "Student saved: " + name + " | " + txId);

            // أرسل إشعار للتطبيق
            Intent broadcast = new Intent("com.eschool.STUDENT_PENDING");
            broadcast.putExtra("name", name);
            broadcast.putExtra("txId", txId);
            broadcast.putExtra("amount", amount);
            sendBroadcast(broadcast);

            response = "{\"status\":\"ok\",\"message\":\"saved\"}";
        } catch (Exception e) {
            response = "{\"status\":\"error\",\"message\":\"" + e.getMessage() + "\"}";
        }

        // إرسال الرد مع CORS
        PrintWriter out = new PrintWriter(client.getOutputStream());
        out.println("HTTP/1.1 200 OK");
        out.println("Content-Type: application/json");
        out.println("Access-Control-Allow-Origin: *");
        out.println("Access-Control-Allow-Methods: POST, OPTIONS");
        out.println("Access-Control-Allow-Headers: Content-Type");
        out.println("Content-Length: " + response.getBytes("UTF-8").length);
        out.println();
        out.print(response);
        out.flush();
        client.close();
    }

    @Override
    public IBinder onBind(Intent intent) { return null; }

    @Override
    public void onDestroy() {
        super.onDestroy();
        try { if (serverSocket != null) serverSocket.close(); } catch (Exception ignored) {}
        if (serverThread != null) serverThread.interrupt();
    }
}
