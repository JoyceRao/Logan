package com.dianping.logan;

import android.os.Handler;
import android.os.Looper;

import androidx.annotation.Nullable;

import org.json.JSONObject;

import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
/**
 * {@code syncCloudUploadUrl} (Spec §9.4.1).
 */
final class LoganSyncCloudClient {

    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    private LoganSyncCloudClient() {
    }

    interface Completion {
        void onDone(boolean success);
    }

    static void postMain(boolean ok, @Nullable Completion completion) {
        if (completion == null) {
            return;
        }
        Runnable r = () -> completion.onDone(ok);
        if (Looper.myLooper() == Looper.getMainLooper()) {
            r.run();
        } else {
            MAIN.post(r);
        }
    }

    static void execute(@Nullable String urlString,
            @Nullable String logFileUrl,
            @Nullable String fileDate,
            @Nullable String logFileType,
            @Nullable String retrievalId,
            @Nullable String appId,
            @Nullable String unionId,
            @Nullable String deviceId,
            @Nullable String bundleVersion,
            @Nullable String appVersion,
            @Nullable Completion completion) {
        if (urlString == null || urlString.isEmpty()
                || logFileUrl == null || logFileUrl.isEmpty()
                || fileDate == null || fileDate.isEmpty()) {
            postMain(false, completion);
            return;
        }
        if (!LoganDateUtils.isValidYyyyMmDd(fileDate)) {
            postMain(false, completion);
            return;
        }
        long dateInt;
        try {
            dateInt = LoganDateUtils.dayStartMillisDefaultTz(fileDate);
        } catch (Exception e) {
            postMain(false, completion);
            return;
        }
        long logEndTimeInt = dateInt + 86400000L - 1000L;
        long curTime = System.currentTimeMillis();
        long logEndTime = Math.min(logEndTimeInt, curTime);

        JSONObject body = new JSONObject();
        try {
            body.put("logFileUrl", logFileUrl);
            body.put("logFileType", logFileType != null ? logFileType : "");
            body.put("logStartTime", dateInt);
            body.put("logEndTime", logEndTime);
            if (retrievalId != null && retrievalId.length() > 0) {
                body.put("retrievalId", retrievalId);
            }
        } catch (org.json.JSONException e) {
            postMain(false, completion);
            return;
        }

        byte[] raw;
        try {
            raw = body.toString().getBytes(StandardCharsets.UTF_8);
        } catch (Exception e) {
            postMain(false, completion);
            return;
        }

        URL url;
        try {
            url = new URL(urlString);
        } catch (Exception e) {
            postMain(false, completion);
            return;
        }

        new Thread(() -> {
            boolean ok = doPost(url, raw, fileDate, appId, unionId, deviceId, bundleVersion, appVersion);
            postMain(ok, completion);
        }, "gzlogan-sync-cloud").start();
    }

    private static boolean doPost(URL url, byte[] body, String fileDate,
            @Nullable String appId, @Nullable String unionId, @Nullable String deviceId,
            @Nullable String bundleVersion, @Nullable String appVersion) {
        HttpURLConnection c = null;
        OutputStream os = null;
        InputStream in = null;
        try {
            c = (HttpURLConnection) url.openConnection();
            c.setRequestMethod("POST");
            c.setConnectTimeout(30000);
            c.setReadTimeout(30000);
            c.setUseCaches(false);
            c.setRequestProperty("Content-Type", "application/json");
            c.setRequestProperty("fileDate", fileDate);
            putIfNonEmpty(c, "appId", appId);
            putIfNonEmpty(c, "unionId", unionId);
            putIfNonEmpty(c, "deviceId", deviceId);
            putIfNonEmpty(c, "bundleVersion", bundleVersion);
            putIfNonEmpty(c, "appVersion", appVersion);
            c.setDoOutput(true);
            c.setDoInput(true);
            os = new BufferedOutputStream(c.getOutputStream());
            os.write(body);
            os.flush();
            int code = c.getResponseCode();
            if (code < 200 || code >= 300) {
                return false;
            }
            in = c.getInputStream();
            ByteArrayOutputStream bout = new ByteArrayOutputStream();
            byte[] buf = new byte[2048];
            int n;
            while ((n = in.read(buf)) != -1) {
                bout.write(buf, 0, n);
            }
            byte[] data = bout.toByteArray();
            if (data.length == 0) {
                return false;
            }
            JSONObject jo = new JSONObject(new String(data, StandardCharsets.UTF_8));
            return jo.optInt("code", -1) == 200;
        } catch (Exception e) {
            return false;
        } finally {
            try {
                if (os != null) {
                    os.close();
                }
            } catch (IOException ignored) {
            }
            try {
                if (in != null) {
                    in.close();
                }
            } catch (IOException ignored) {
            }
            if (c != null) {
                c.disconnect();
            }
        }
    }

    private static void putIfNonEmpty(HttpURLConnection c, String key, @Nullable String val) {
        if (val != null && val.length() > 0) {
            c.setRequestProperty(key, val);
        }
    }
}
