/*
 * Copyright (c) 2018-present, 美团点评
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 */

package com.dianping.logan;

import android.util.Log;

import java.io.File;
import java.util.Map;

import kotlin.jvm.JvmStatic;

public class Logan {

    private static final Object LOCK = new Object();
    private static final LoganEngine ENGINE = new LoganEngine();
    private static OnLoganProtocolStatus sLoganProtocolStatus;
    private static File sLogRoot;
    private static volatile boolean sInitialized;
    static boolean sDebug = false;

    /**
     * 初始化 Logan 日志系统。
     *
     * @param loganConfig Logan 初始化配置，包含日志目录、加密参数和文件保留策略
     */
    public static void init(LoganConfig loganConfig) {
        if (loganConfig == null) {
//            throw new IllegalArgumentException("Logan init config invalid: config is null");
            Log.e("Logan", "Logan init config invalid: config is null");
            return;
        }
        String invalidReason = loganConfig.invalidReason();
        if (invalidReason != null) {
//            throw new IllegalArgumentException("Logan init config invalid: " + invalidReason);
            Log.e("Logan", "Logan init config invalid: " + invalidReason);
            return;
        }
        synchronized (LOCK) {
            if (sInitialized) {
//                throw new IllegalStateException("Logan.init was already called");
                Log.e("Logan", "Logan.init was already called");
                return;
            }
            File logRoot = new File(loganConfig.mPathPath);
            int days = (int) Math.max(1L, loganConfig.mDay / (24L * 60L * 60L * 1000L));
            LoganInitConfig config = new LoganInitConfig.Builder()
                    .cacheDirectory(new File(loganConfig.mCachePath))
                    .loganLogDirectory(logRoot)
                    .aesKey16(loganConfig.mEncryptKey16)
                    .aesIv16(loganConfig.mEncryptIv16)
                    .maxFileBytes(loganConfig.mMaxFile)
                    .minFreeDiskBytes(loganConfig.mMinSDCard)
                    .maxReversedDays(days)
                    .build();
            try {
                ENGINE.initAndAwait(config);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
//                throw new IllegalStateException("Logan.init interrupted", e);
                Log.e("Logan", "Logan.init interrupted", e);
                return;
            }
            if (!ENGINE.isNativeReady()) {
                String err = CloganNative.loadError();
//                throw new IllegalStateException(err != null ? err : "clogan native init failed");
                Log.e("Logan", err != null ? err : "clogan native init failed");
                return;
            }
            sLogRoot = logRoot;
            sInitialized = true;
        }
    }

    /**
     * @deprecated Old short-form write API. Use {@link #log(int, String)}.
     * @param log  表示日志内容
     * @param type 表示日志类型
     * @brief Logan写入日志
     */
//    @Deprecated
//    public static void w(String log, int type) {
//        log(type, log);
//    }

    /**
     * @deprecated Old short-form flush API. Use {@link #flush()}.
     * @brief 立即写入日志文件
     */
//    @Deprecated
//    public static void f() {
//        flush();
//    }

    /**
     * @deprecated Old custom upload API removed from LoganSpec. Use
     * {@link #uploadWithInterception(String, String, String, String, String, String, LoganUploadInterceptor, LoganUploadResultCallback)}
     * or {@link #uploadAll(String, String, String, String, String, LoganUploadInterceptor, LoganUploadResultCallback)}.
     * @param dates    日期数组，格式：“2018-07-27”
     * @param runnable 发送操作
     * @brief 发送日志
     */
//    @Deprecated
//    public static void s(String[] dates, SendLogRunnable runnable) {
//        requireInit();
//        if (dates == null || runnable == null) {
//            return;
//        }
//        for (String date : dates) {
//            if (date == null || date.length() == 0) {
//                continue;
//            }
//            File file = new File(sLogRoot, date);
//            if (file.isFile()) {
//                runnable.sendLog(file);
//                runnable.finish();
//            }
//        }
//    }

    /**
     * @deprecated Old upload API. Use
     * {@link #upload(String, String, String, String, String, String, LoganUploadResultCallback)}.
     * @param url             接受日志的服务器完整url.
     * @param date            日志日期 格式："2018-11-21".
     * @param appId           当前应用的唯一标识,在多App时区分日志来源App.
     * @param unionId         当前用户的唯一标识,用来区分日志来源用户.
     * @param deviceId        设备id.
     * @param buildVersion    上报源App的build号.
     * @param appVersion      上报源的App版本.
     * @param sendLogCallback 上报结果回调（子线程调用）.
     */
//    @Deprecated
//    public static void s(String url, String date, String appId, String unionId, String deviceId,
//                         String buildVersion, String appVersion, SendLogCallback sendLogCallback) {
//        final Map<String, String> headers = new HashMap<>();
//        headers.put("fileDate", date);
//        headers.put("appId", appId);
//        headers.put("unionId", unionId);
//        headers.put("deviceId", deviceId);
//        headers.put("bundleVersion", buildVersion);
//        headers.put("appVersion", appVersion);
//        headers.put("platform", "1");
//        s(url, date, headers, sendLogCallback);
//    }

    /**
     * @deprecated Old header-map upload API. Use
     * {@link #uploadWithInterception(String, String, String, String, String, String, LoganUploadInterceptor, LoganUploadResultCallback)}.
     * @param url             接受日志的服务器完整url.
     * @param date            日志日期 格式："2018-11-21".
     * @param headers         请求头信息.
     * @param sendLogCallback 上报结果回调（子线程调用）.
     */
//    @Deprecated
//    public static void s(String url, String date, Map<String, String> headers, SendLogCallback sendLogCallback) {
//        requireInit();
//        Map<String, String> safeHeaders = headers == null ? new HashMap<String, String>() : headers;
//        upload(url, date,
//                safeHeaders.get("retrievalId"),
//                safeHeaders.get("appId"),
//                safeHeaders.get("unionId"),
//                safeHeaders.get("deviceId"),
//                safeHeaders.get("bundleVersion"),
//                safeHeaders.get("appVersion"),
//                safeHeaders.get("platform"),
//                (success, fileUrl, filePath) -> {
//                    if (sendLogCallback != null) {
//                        sendLogCallback.onLogSendCompleted(success ? 200 : -1, null);
//                    }
//                });
//    }

    /**
     * 设置 Logan 调试开关。
     *
     * @param debug true 表示开启调试日志，false 表示关闭调试日志
     */
    public static void setDebug(boolean debug) {
        Logan.sDebug = debug;
        if (sInitialized) {
            ENGINE.setDebugNative(debug);
        }
    }

    /**
     * 设置日志文件最大保留天数。
     *
     * @param maxReversedDate 最大保留天数，小于等于 0 的值由底层实现处理
     */
    public static void setMaxReversedDate(int maxReversedDate) {
        requireInit();
        ENGINE.setMaxReversedDays(maxReversedDate);
    }

    /**
     * 控制底层 C 库日志输出。
     *
     * @param enabled true 表示开启底层日志输出，false 表示关闭
     */
    public static void printClibLog(boolean enabled) {
        setDebug(enabled);
    }


    static void onListenerLogWriteStatus(String name, int status) {
        if (sLoganProtocolStatus != null) {
            sLoganProtocolStatus.loganProtocolStatus(name, status);
        }
    }

    /**
     * 设置底层协议状态监听器。
     *
     * @param listener 协议状态回调，传 null 表示清空监听器
     * @deprecated Old native status listener API retained for compatibility.
     */
    @Deprecated
    public static void setOnLoganProtocolStatus(OnLoganProtocolStatus listener) {
        sLoganProtocolStatus = listener;
    }

    /**
     * 写入默认技术通道日志。
     *
     * @param type 日志类型，由业务侧定义
     * @param log  日志内容
     */
    @JvmStatic
    public static void log(int type, String log) {
        requireInit();
        ENGINE.write(type, log, LoganChannel.TECH);
    }

    /**
     * 写入技术通道日志。
     *
     * @param type 日志类型，由业务侧定义
     * @param log  日志内容
     */
    public static void logTech(int type, String log) {
        requireInit();
        ENGINE.write(type, log, LoganChannel.TECH);
    }

    /**
     * 写入业务通道日志。
     *
     * @param type 日志类型，由业务侧定义
     * @param log  日志内容
     */
    public static void logBiz(int type, String log) {
        requireInit();
        ENGINE.write(type, log, LoganChannel.BIZ);
    }

    /**
     * 生成统一格式的标准日志内容。
     *
     * @param level        日志级别
     * @param currentName  当前线程或调用方名称
     * @param threadCount  线程标识或线程计数
     * @param userId       用户标识
     * @param category     日志分类
     * @param bizModule    业务模块
     * @param flowId       流程 ID
     * @param stage        当前阶段
     * @param functionName 函数名或功能名
     * @param location     代码位置
     * @param message      日志消息
     * @return 拼接后的标准日志字符串
     */
    public static String createStandardLog(String level,
            String tag,
            String currentName,
            Long threadCount,
            String userId,
            Integer category,
            String bizModule,
            String flowId,
            String stage,
            String functionName,
            String location,
            String message) {
        String cn = validLogStr(currentName);
        String lv = validLogStr(level);
        String t = validLogStr(tag);
        String uid = validLogStr(userId);
        String bm = validLogStr(bizModule);
        String fid = validLogStr(flowId);
        String st = validLogStr(stage);
        String fn = validLogStr(functionName);
        String loc = validLogStr(location);
        String msg = validLogStr(message);
        String part1 = cn + ":" + validLogStr(threadCount);
        String part5 = "[" + bm + "," + fid + "," + st + "," + fn + "," + loc + "," + t + "]";
        return part1 + "|" + lv + "|" + uid + "|" + validLogStr(category) + "|" + part5 + "|" + msg;
    }

    /**
     * 立即将内存中的日志刷入文件。
     */
    @JvmStatic
    public static void flush() {
        requireInit();
        ENGINE.flush();
    }

    /**
     * 上传指定日期的日志，并允许传入完整元信息和上传拦截器。
     *
     * @param url            上传接口地址
     * @param date           日志日期，格式为 yyyy-MM-dd
     * @param retrievalId    检索 ID
     * @param appId          应用 ID
     * @param unionId        用户唯一标识
     * @param deviceId       设备 ID
     * @param bundleVersion  构建版本号
     * @param appVersion     应用版本号
     * @param interceptor    上传拦截器，可为 null
     * @param resultCallback 上传结果回调，可为 null
     */
    public static void uploadWithInterception(String url,
            String date,
            String retrievalId,
            String appId,
            String unionId,
            String deviceId,
            String bundleVersion,
            String appVersion,
            LoganUploadInterceptor interceptor,
            LoganUploadResultCallback resultCallback) {
        requireInit();
        if (url == null || url.length() == 0 || date == null || date.length() == 0) {
            ENGINE.postUploadCallback(resultCallback, false, null, null);
            return;
        }
        if (!LoganDateUtils.isValidYyyyMmDd(date)) {
            ENGINE.postUploadCallback(resultCallback, false, null, null);
            return;
        }
        LoganUploadCoordinator.uploadOneDate(ENGINE, url, date, retrievalId, appId, unionId,
                deviceId, bundleVersion, appVersion, "1", interceptor, resultCallback);
    }

    /**
     * 上传指定日期的日志，并传入完整元信息。
     *
     * @param url            上传接口地址
     * @param date           日志日期，格式为 yyyy-MM-dd
     * @param retrievalId    检索 ID
     * @param appId          应用 ID
     * @param unionId        用户唯一标识
     * @param deviceId       设备 ID
     * @param bundleVersion  构建版本号
     * @param appVersion     应用版本号
     * @param platform       平台标识
     * @param resultCallback 上传结果回调，可为 null
     */
    public static void upload(String url,
            String date,
            String retrievalId,
            String appId,
            String unionId,
            String deviceId,
            String bundleVersion,
            String appVersion,
            String platform,
            LoganUploadResultCallback resultCallback) {
        uploadWithInterception(url, date, retrievalId, appId, unionId, deviceId, bundleVersion,
                appVersion, null, resultCallback);
    }

    /**
     * 上传本地保存的全部日期日志，并允许调用方拦截或改写上传过程。
     *
     * @param url            上传接口地址
     * @param retrievalId    检索 ID
     * @param appId          应用 ID
     * @param unionId        用户唯一标识
     * @param deviceId       设备 ID
     * @param interceptor    上传拦截器，可为 null
     * @param resultCallback 上传结果回调，可为 null
     */
    public static void uploadAll(String url,
            String retrievalId,
            String appId,
            String unionId,
            String deviceId,
            LoganUploadInterceptor interceptor,
            LoganUploadResultCallback resultCallback) {
        uploadAll(url, retrievalId, appId, unionId, deviceId, null, null, "1", interceptor,
                resultCallback);
    }

    /**
     * 上传本地保存的全部日期日志，并传入完整元信息和上传拦截器。
     *
     * @param url            上传接口地址
     * @param retrievalId    检索 ID
     * @param appId          应用 ID
     * @param unionId        用户唯一标识
     * @param deviceId       设备 ID
     * @param bundleVersion  构建版本号
     * @param appVersion     应用版本号
     * @param platform       平台标识
     * @param interceptor    上传拦截器，可为 null
     * @param resultCallback 上传结果回调，可为 null
     */
    public static void uploadAll(String url,
            String retrievalId,
            String appId,
            String unionId,
            String deviceId,
            String bundleVersion,
            String appVersion,
            String platform,
            LoganUploadInterceptor interceptor,
            LoganUploadResultCallback resultCallback) {
        requireInit();
        if (url == null || url.length() == 0) {
            ENGINE.postUploadCallback(resultCallback, false, null, null);
            return;
        }
        LoganUploadCoordinator.uploadAllDates(ENGINE, url, retrievalId, appId, unionId,
                deviceId, bundleVersion, appVersion, platform, interceptor, resultCallback);
    }

    /**
     * 获取本地各通道日志文件信息。
     *
     * @return 第一层 key 为日期，第二层 key 为通道，value 为文件大小等信息
     */
    public static Map<String, Map<String, String>> allSubFilesInfo() {
        requireInit();
        return ENGINE.allSubFilesInfo();
    }

    /**
     * 获取当天日期字符串。
     *
     * @return 当前日期，格式为 yyyy-MM-dd
     */
    public static String todaysDate() {
        return LoganDateUtils.today();
    }

    /**
     * 清理指定日期的本地日志。
     *
     * @param date 日志日期，格式为 yyyy-MM-dd
     */
    public static void clearLogOfDate(String date) {
        requireInit();
        ENGINE.clearLogOfDate(date);
    }

    /**
     * 清理全部本地日志。
     */
    public static void clearAllLogs() {
        requireInit();
        ENGINE.clearAllLogs();
    }

    private static void requireInit() {
        if (!sInitialized || sLogRoot == null) {
            throw new IllegalStateException("Logan is not initialized. Call Logan.init(...) first.");
        }
    }

    static boolean isDebugEnabled() {
        return sDebug;
    }

    private static String validLogStr(String s) {
        return (s != null && s.length() > 0) ? s : "-";
    }

    private static String validLogStr(Object o) {
        return o != null ? String.valueOf(o) : "-";
    }
}
