package com.dianping.logan;

import androidx.annotation.Nullable;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

/**
 * Upload ordering, temp copies, deletion rules (Spec §3–§5).
 */
final class LoganUploadCoordinator {

    private LoganUploadCoordinator() {
    }

    static void uploadOneDate(LoganEngine engine, String url, String date,
            @Nullable String retrievalId,
            @Nullable String appId,
            @Nullable String unionId,
            @Nullable String deviceId,
            @Nullable String bundleVersion,
            @Nullable String appVersion,
            @Nullable String platform,
            @Nullable LoganUploadInterceptor interceptor,
            @Nullable LoganUploadResultCallback cb) {
        engine.runOnLoganThread(() -> {
            if (!engine.tryAcquireUploadChain()) {
                engine.postUploadCallback(cb, false, null, null);
                return;
            }
            List<Job> jobs = buildJobs(engine.getLogRoot(), date);
            if (jobs.isEmpty()) {
                engine.completeUploadChain(cb, false, null, null);
                return;
            }
            if (date.equals(LoganDateUtils.today())) {
                engine.flushNative();
            }
            runJobIndex(engine, jobs, 0, url, retrievalId, appId, unionId, deviceId,
                    bundleVersion, appVersion, platform, interceptor, cb, null,
                    new UploadChainState());
        });
    }

    static void uploadAllDates(LoganEngine engine, String url,
            @Nullable String retrievalId,
            @Nullable String appId,
            @Nullable String unionId,
            @Nullable String deviceId,
            @Nullable String bundleVersion,
            @Nullable String appVersion,
            @Nullable String platform,
            @Nullable LoganUploadInterceptor interceptor,
            @Nullable LoganUploadResultCallback cb) {
        engine.runOnLoganThread(() -> {
            if (!engine.tryAcquireUploadChain()) {
                engine.postUploadCallback(cb, false, null, null);
                return;
            }
            TreeSet<String> dates = collectAllDates(engine.getLogRoot());
            if (dates.isEmpty()) {
                engine.completeUploadChain(cb, false, null, null);
                return;
            }
            Iterator<String> it = dates.iterator();
            uploadNextDate(engine, it, url, retrievalId, appId, unionId, deviceId,
                    bundleVersion, appVersion, platform, interceptor, cb,
                    new UploadChainState());
        });
    }

    private static void uploadNextDate(LoganEngine engine, Iterator<String> it, String url,
            @Nullable String retrievalId,
            @Nullable String appId,
            @Nullable String unionId,
            @Nullable String deviceId,
            @Nullable String bundleVersion,
            @Nullable String appVersion,
            @Nullable String platform,
            @Nullable LoganUploadInterceptor interceptor,
            @Nullable LoganUploadResultCallback cb,
            UploadChainState state) {
        if (!it.hasNext()) {
            engine.completeUploadChain(cb, !state.hasFailure, state.firstFailedFileUrl,
                    state.firstFailedFilePath);
            return;
        }
        String date = it.next();
        List<Job> jobs = buildJobs(engine.getLogRoot(), date);
        if (jobs.isEmpty()) {
            uploadNextDate(engine, it, url, retrievalId, appId, unionId, deviceId,
                    bundleVersion, appVersion, platform, interceptor, cb, state);
            return;
        }
        if (date.equals(LoganDateUtils.today())) {
            engine.flushNative();
        }
        runJobIndex(engine, jobs, 0, url, retrievalId, appId, unionId, deviceId,
                bundleVersion, appVersion, platform, interceptor, cb, it, state);
    }

    private static void runJobIndex(LoganEngine engine, List<Job> jobs, int index, String url,
            @Nullable String retrievalId,
            @Nullable String appId,
            @Nullable String unionId,
            @Nullable String deviceId,
            @Nullable String bundleVersion,
            @Nullable String appVersion,
            @Nullable String platform,
            @Nullable LoganUploadInterceptor interceptor,
            @Nullable LoganUploadResultCallback cb,
            @Nullable Iterator<String> allDatesIt,
            UploadChainState state) {
        if (index >= jobs.size()) {
            if (allDatesIt == null) {
                engine.completeUploadChain(cb, !state.hasFailure, state.firstFailedFileUrl,
                        state.firstFailedFilePath);
            } else {
                uploadNextDate(engine, allDatesIt, url, retrievalId, appId, unionId, deviceId,
                        bundleVersion, appVersion, platform, interceptor, cb, state);
            }
            return;
        }
        Job job = jobs.get(index);
        String date = job.date;
        Map<String, String> headers = buildHeaders(date, retrievalId, appId, unionId, deviceId,
                bundleVersion, appVersion, platform, job.subFolderName);

        LoganUploadFileResult fileResult = (success, fileUrl, filePath) ->
                engine.runOnLoganThread(() -> {
                    if (success) {
                        job.onUploadSuccess(engine);
                    } else {
                        state.recordFailure(fileUrl, filePath);
                    }
                    runJobIndex(engine, jobs, index + 1, url, retrievalId, appId, unionId, deviceId,
                            bundleVersion, appVersion, platform, interceptor, cb, allDatesIt,
                            state);
                });

        boolean intercepted = false;
        if (interceptor != null) {
            long localCreateTime = engine.getLogCreateTime(
                    job.sourceFile.getAbsolutePath());
            intercepted = interceptor.interceptUpload(date, retrievalId, url,
                    job.uploadBodyFile.getAbsolutePath(), job.subFolderName, localCreateTime,
                    fileResult);
        }
        if (intercepted) {
            return;
        }
        engine.getNetworkExecutor().execute(() -> {
            boolean ok = LoganHttp.uploadBinary(url, job.uploadBodyFile, headers);
            if (!ok) {
                fileResult.complete(false, null, job.uploadBodyFile.getAbsolutePath());
            } else {
                fileResult.complete(true, null, job.uploadBodyFile.getAbsolutePath());
            }
        });
    }

    private static final class UploadChainState {
        boolean hasFailure;
        @Nullable String firstFailedFileUrl;
        @Nullable String firstFailedFilePath;

        void recordFailure(@Nullable String fileUrl, @Nullable String filePath) {
            if (hasFailure) {
                return;
            }
            hasFailure = true;
            firstFailedFileUrl = fileUrl;
            firstFailedFilePath = filePath;
        }
    }

    private static Map<String, String> buildHeaders(String date,
            @Nullable String retrievalId,
            @Nullable String appId,
            @Nullable String unionId,
            @Nullable String deviceId,
            @Nullable String bundleVersion,
            @Nullable String appVersion,
            @Nullable String platform,
            @Nullable String subFolderName) {
        Map<String, String> m = new HashMap<>();
        m.put("fileDate", date);
        if (retrievalId != null && retrievalId.length() > 0) {
            m.put("retrievalId", retrievalId);
        }
        if (appId != null && appId.length() > 0) {
            m.put("appId", appId);
        }
        if (unionId != null && unionId.length() > 0) {
            m.put("unionId", unionId);
        }
        if (deviceId != null && deviceId.length() > 0) {
            m.put("deviceId", deviceId);
        }
        if (bundleVersion != null && bundleVersion.length() > 0) {
            m.put("bundleVersion", bundleVersion);
        }
        if (appVersion != null && appVersion.length() > 0) {
            m.put("appVersion", appVersion);
        }
        m.put("platform", platform != null && platform.length() > 0 ? platform : "1");
        if (subFolderName != null) {
            m.put("loganSubFolder", subFolderName);
        }
        return m;
    }

    private static TreeSet<String> collectAllDates(File logRoot) {
        TreeSet<String> out = new TreeSet<>();
        addDatesFromDir(LoganPaths.techDir(logRoot), out);
        addDatesFromDir(LoganPaths.bizDir(logRoot), out);
        return out;
    }

    private static void addDatesFromDir(File dir, TreeSet<String> out) {
        if (!dir.isDirectory()) {
            return;
        }
        File[] list = dir.listFiles();
        if (list == null) {
            return;
        }
        for (File f : list) {
            if (f.isFile() && LoganDateUtils.isValidYyyyMmDd(f.getName())) {
                out.add(f.getName());
            }
        }
    }

    private static List<Job> buildJobs(File root, String date) {
        boolean today = date.equals(LoganDateUtils.today());
        ArrayList<Job> jobs = new ArrayList<>(2);
        addChannel(root, date, today, LoganPaths.techLogFile(root, date),
                LoganPaths.tempTechFile(root, date), LoganPaths.TECH, jobs);
        addChannel(root, date, today, LoganPaths.bizLogFile(root, date),
                LoganPaths.tempBizFile(root, date), LoganPaths.BIZ, jobs);
        return jobs;
    }

    private static void addChannel(File root, String date, boolean today, File src, File tmp,
            @Nullable String subFolder, List<Job> jobs) {
        if (!src.isFile()) {
            return;
        }
        if (today) {
            if (!LoganIoUtils.copyFile(src, tmp)) {
                return;
            }
            jobs.add(new Job(date, src, tmp, tmp, subFolder));
        } else {
            jobs.add(new Job(date, src, src, tmp, subFolder));
        }
    }

    static final class Job {
        final String date;
        final File sourceFile;
        final File uploadBodyFile;
        final File tempFile;
        final @Nullable String subFolderName;

        Job(String date, File sourceFile, File uploadBodyFile, File tempFile,
                @Nullable String subFolderName) {
            this.date = date;
            this.sourceFile = sourceFile;
            this.uploadBodyFile = uploadBodyFile;
            this.tempFile = tempFile;
            this.subFolderName = subFolderName;
        }

        void onUploadSuccess(LoganEngine engine) {
            LoganIoUtils.deleteFileQuietly(sourceFile);
            LoganIoUtils.deleteFileQuietly(tempFile);
            if (engine != null) {
                engine.removeLogCreateTime(sourceFile.getAbsolutePath());
            }
        }
    }
}
