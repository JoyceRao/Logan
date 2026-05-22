package com.dianping.logan;

import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.StatFs;

import androidx.annotation.Nullable;

import java.io.File;
import java.text.ParseException;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Serial {@code loganQueue} equivalent (Spec §8).
 */
public final class LoganEngine {

    private final HandlerThread queueThread;
    private final Handler loganHandler;
    private final Handler mainHandler;
    private final ExecutorService networkExecutor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "gzlogan-net");
        t.setPriority(Thread.NORM_PRIORITY);
        return t;
    });

    private final AtomicBoolean uploadChainActive = new AtomicBoolean(false);

    private File logRoot;
    private File cacheRoot;
    private long minFreeDiskBytes;
    private long maxFileBytes;
    private volatile int maxReversedDays;
    private String openTechDay;
    private volatile boolean nativeReady;
    private volatile boolean released;
    private long lastExpireSweepMs;

    LoganEngine() {
        queueThread = new HandlerThread("gzlogan-queue");
        queueThread.start();
        loganHandler = new Handler(queueThread.getLooper());
        mainHandler = new Handler(Looper.getMainLooper());
    }

    void initAndAwait(LoganInitConfig config) throws InterruptedException {
        CountDownLatch latch = new CountDownLatch(1);
        loganHandler.post(() -> {
            try {
                initOnQueue(config);
            } finally {
                latch.countDown();
            }
        });
        latch.await(30, TimeUnit.SECONDS);
    }

    private void initOnQueue(LoganInitConfig c) {
        logRoot = c.loganLogDirectory;
        cacheRoot = c.cacheDirectory;
        minFreeDiskBytes = c.minFreeDiskBytes;
        maxFileBytes = c.maxFileBytes;
        maxReversedDays = c.maxReversedDays;
        nativeReady = false;
        if (!CloganNative.isAvailable()) {
            return;
        }
        ensureDirectoryTree();
        clearStartupTemp();
        String cachePath = withTrailingSlash(cacheRoot.getAbsolutePath());
        String logPath = withTrailingSlash(logRoot.getAbsolutePath());
        int maxFileInt = (int) Math.min(maxFileBytes, (long) Integer.MAX_VALUE);
        String key = new String(c.aesKey16);
        String iv = new String(c.aesIv16);
        CloganNative.clogan_init(cachePath, logPath, maxFileInt, key, iv);
        CloganNative.clogan_debug(Logan.isDebugEnabled());
        sweepExpiredLogs();
        String today = LoganDateUtils.today();
        CloganNative.clogan_open(LoganPaths.techPathname(today));
        openTechDay = today;
        nativeReady = true;
    }

    private static String withTrailingSlash(String p) {
        if (p.endsWith(File.separator)) {
            return p;
        }
        return p + File.separator;
    }

    boolean isNativeReady() {
        return nativeReady;
    }

    void setDebugNative(boolean debug) {
        runOnLoganThread(() -> CloganNative.clogan_debug(debug));
    }

    void setMaxReversedDays(int days) {
        runOnLoganThread(() -> {
            maxReversedDays = days > 0 ? days : 7;
            sweepExpiredLogs();
        });
    }

    void write(int type, String log, LoganChannel channel) {
        if (log == null || log.length() == 0) {
            return;
        }
        runOnLoganThread(() -> {
            if (!nativeReady) {
                return;
            }
            if (!hasMinDisk()) {
                return;
            }
            maybeSweepExpire();
            ensureTechDayOpen();
            switch (channel) {
                case TECH:
                    writeCurrentOpen(type, log);
                    break;
                case BIZ:
                    CloganNative.clogan_open(LoganPaths.bizPathname(openTechDay));
                    writeCurrentOpen(type, log);
                    CloganNative.clogan_flush();
                    CloganNative.clogan_open(LoganPaths.techPathname(openTechDay));
                    break;
                default:
                    break;
            }
        });
    }

    private void writeCurrentOpen(int type, String log) {
        long tid = Thread.currentThread().getId();
        String tname = Thread.currentThread().getName();
        int isMain = Looper.getMainLooper() == Looper.myLooper() ? 1 : 0;
        CloganNative.clogan_write(type, log, System.currentTimeMillis(), tname, tid, isMain);
    }

    void flush() {
        runOnLoganThread(() -> {
            if (nativeReady) {
                CloganNative.clogan_flush();
            }
        });
    }

    void flushNative() {
        if (nativeReady) {
            CloganNative.clogan_flush();
        }
    }

    void clearLogOfDate(String date) {
        if (!LoganDateUtils.isValidYyyyMmDd(date)) {
            return;
        }
        runOnLoganThread(() -> deleteSix(date));
    }

    void clearAllLogs() {
        runOnLoganThread(() -> {
            Set<String> dates = new HashSet<>();
            collectValidDateFiles(LoganPaths.techDir(logRoot), dates);
            collectValidDateFiles(LoganPaths.bizDir(logRoot), dates);
            for (String d : dates) {
                deleteSix(d);
            }
            LoganIoUtils.deleteChildFiles(LoganPaths.tempTechDir(logRoot));
            LoganIoUtils.deleteChildFiles(LoganPaths.tempBizDir(logRoot));
            ensureDirectoryTree();
        });
    }

    File getLogRoot() {
        return logRoot;
    }

    ExecutorService getNetworkExecutor() {
        return networkExecutor;
    }

    void runOnLoganThread(Runnable r) {
        if (released) {
            return;
        }
        loganHandler.post(r);
    }

    boolean tryAcquireUploadChain() {
        return uploadChainActive.compareAndSet(false, true);
    }

    void releaseUploadChain() {
        uploadChainActive.set(false);
    }

    void postUploadCallback(@Nullable LoganUploadResultCallback cb, boolean success,
            @Nullable String fileUrl, @Nullable String filePath) {
        if (cb == null) {
            return;
        }
        Runnable r = () -> cb.onUploadFinished(success, fileUrl, filePath);
        if (Looper.myLooper() == Looper.getMainLooper()) {
            r.run();
        } else {
            mainHandler.post(r);
        }
    }

    void completeUploadChain(@Nullable LoganUploadResultCallback cb, boolean success,
            @Nullable String fileUrl, @Nullable String filePath) {
        releaseUploadChain();
        postUploadCallback(cb, success, fileUrl, filePath);
    }

    Map<String, Map<String, String>> allSubFilesInfo() {
        if (Thread.currentThread() == loganHandler.getLooper().getThread()) {
            return computeSubFilesInfo();
        }
        final CountDownLatch latch = new CountDownLatch(1);
        final Map<String, Map<String, String>>[] holder = new Map[1];
        loganHandler.post(() -> {
            holder[0] = computeSubFilesInfo();
            latch.countDown();
        });
        try {
            latch.await(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Collections.emptyMap();
        }
        return holder[0] != null ? holder[0] : Collections.emptyMap();
    }

    private Map<String, Map<String, String>> computeSubFilesInfo() {
        Map<String, Map<String, String>> out = new LinkedHashMap<>();
        if (logRoot == null) {
            return out;
        }
        File tech = LoganPaths.techDir(logRoot);
        File biz = LoganPaths.bizDir(logRoot);
        Set<String> dates = new HashSet<>();
        collectValidDateFiles(tech, dates);
        collectValidDateFiles(biz, dates);
        for (String d : dates) {
            Map<String, String> inner = new LinkedHashMap<>();
            File tf = new File(tech, d);
            File bf = new File(biz, d);
            if (tf.isFile()) {
                inner.put(LoganPaths.TECH, String.valueOf(tf.length()));
            }
            if (bf.isFile()) {
                inner.put(LoganPaths.BIZ, String.valueOf(bf.length()));
            }
            if (!inner.isEmpty()) {
                out.put(d, inner);
            }
        }
        return out;
    }

    private void collectValidDateFiles(File dir, Set<String> dates) {
        if (dir == null || !dir.isDirectory()) {
            return;
        }
        File[] list = dir.listFiles();
        if (list == null) {
            return;
        }
        for (File f : list) {
            if (f.isFile() && LoganDateUtils.isValidYyyyMmDd(f.getName())) {
                dates.add(f.getName());
            }
        }
    }

    private void ensureTechDayOpen() {
        String today = LoganDateUtils.today();
        if (openTechDay == null || !openTechDay.equals(today)) {
            CloganNative.clogan_open(LoganPaths.techPathname(today));
            openTechDay = today;
        }
    }

    private boolean hasMinDisk() {
        try {
            StatFs stat = new StatFs(logRoot.getAbsolutePath());
            long avail = stat.getAvailableBlocksLong() * stat.getBlockSizeLong();
            return avail > minFreeDiskBytes;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    private void maybeSweepExpire() {
        long now = System.currentTimeMillis();
        if (now - lastExpireSweepMs < 60_000L) {
            return;
        }
        lastExpireSweepMs = now;
        sweepExpiredLogs();
    }

    private void sweepExpiredLogs() {
        if (logRoot == null) {
            return;
        }
        long cutoff = LoganDateUtils.cutoffDayStartMillis(maxReversedDays);
        Set<String> dates = new HashSet<>();
        collectValidDateFiles(LoganPaths.techDir(logRoot), dates);
        collectValidDateFiles(LoganPaths.bizDir(logRoot), dates);
        for (String d : dates) {
            long dayStart;
            try {
                dayStart = LoganDateUtils.dayStartMillisDefaultTz(d);
            } catch (ParseException e) {
                deleteSix(d);
                continue;
            }
            if (dayStart < cutoff) {
                deleteSix(d);
            }
        }
        sweepTempOldFiles(cutoff);
    }

    private void sweepTempOldFiles(long cutoffDayStart) {
        sweepTempDir(LoganPaths.tempTechDir(logRoot), cutoffDayStart);
        sweepTempDir(LoganPaths.tempBizDir(logRoot), cutoffDayStart);
    }

    private void sweepTempDir(File dir, long cutoffDayStart) {
        if (dir == null || !dir.isDirectory()) {
            return;
        }
        File[] list = dir.listFiles();
        if (list == null) {
            return;
        }
        for (File f : list) {
            if (!f.isFile()) {
                continue;
            }
            String n = f.getName();
            if (!LoganDateUtils.isValidYyyyMmDd(n)) {
                LoganIoUtils.deleteFileQuietly(f);
                continue;
            }
            try {
                if (LoganDateUtils.dayStartMillisDefaultTz(n) < cutoffDayStart) {
                    LoganIoUtils.deleteFileQuietly(f);
                }
            } catch (ParseException ignored) {
                LoganIoUtils.deleteFileQuietly(f);
            }
        }
    }

    private void deleteSix(String date) {
        LoganIoUtils.deleteFileQuietly(LoganPaths.techLogFile(logRoot, date));
        LoganIoUtils.deleteFileQuietly(LoganPaths.bizLogFile(logRoot, date));
        LoganIoUtils.deleteFileQuietly(LoganPaths.tempTechFile(logRoot, date));
        LoganIoUtils.deleteFileQuietly(LoganPaths.tempBizFile(logRoot, date));
    }

    private void ensureDirectoryTree() {
        logRoot.mkdirs();
        LoganPaths.techDir(logRoot).mkdirs();
        LoganPaths.bizDir(logRoot).mkdirs();
        LoganPaths.tempDir(logRoot).mkdirs();
        LoganPaths.tempTechDir(logRoot).mkdirs();
        LoganPaths.tempBizDir(logRoot).mkdirs();
    }

    private void clearStartupTemp() {
        LoganIoUtils.deleteChildFiles(LoganPaths.tempTechDir(logRoot));
        LoganIoUtils.deleteChildFiles(LoganPaths.tempBizDir(logRoot));
    }

    void release() {
        released = true;
        networkExecutor.shutdown();
        queueThread.quitSafely();
    }

}
