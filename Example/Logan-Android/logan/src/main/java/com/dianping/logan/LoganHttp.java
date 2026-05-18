package com.dianping.logan;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Map;
import java.util.Set;

import javax.net.ssl.HostnameVerifier;
import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLSession;

/**
 * Built-in {@code POST} with {@code binary/octet-stream} (Spec §3.3).
 */
final class LoganHttp {

    private LoganHttp() {
    }

    static boolean uploadBinary(String urlString, File bodyFile, Map<String, String> headers) {
        InputStream fileIn = null;
        HttpURLConnection c = null;
        OutputStream os = null;
        try {
            URL u = new URL(urlString);
            c = (HttpURLConnection) u.openConnection();
            if (c instanceof HttpsURLConnection) {
                ((HttpsURLConnection) c).setHostnameVerifier(new HostnameVerifier() {
                    @Override
                    public boolean verify(String hostname, SSLSession session) {
                        return true;
                    }
                });
            }
            c.setConnectTimeout(15000);
            c.setReadTimeout(15000);
            c.setDoOutput(true);
            c.setDoInput(true);
            c.setRequestMethod("POST");
            c.setRequestProperty("Content-Type", "binary/octet-stream");
            if (headers != null) {
                Set<Map.Entry<String, String>> es = headers.entrySet();
                for (Map.Entry<String, String> e : es) {
                    if (e.getKey() != null && e.getValue() != null) {
                        c.setRequestProperty(e.getKey(), e.getValue());
                    }
                }
            }
            os = c.getOutputStream();
            fileIn = new FileInputStream(bodyFile);
            byte[] buf = new byte[8192];
            int n;
            while ((n = fileIn.read(buf)) >= 0) {
                os.write(buf, 0, n);
            }
            os.flush();
            int code = c.getResponseCode();
            return code >= 200 && code < 300;
        } catch (IOException e) {
            return false;
        } finally {
            try {
                if (fileIn != null) {
                    fileIn.close();
                }
            } catch (IOException ignored) {
            }
            try {
                if (os != null) {
                    os.close();
                }
            } catch (IOException ignored) {
            }
            if (c != null) {
                c.disconnect();
            }
        }
    }
}
