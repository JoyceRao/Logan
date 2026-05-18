package com.dianping.logan;

import androidx.annotation.Nullable;

/**
 * Thin adapter over the existing {@link CLoganProtocol} JNI bridge.
 * All calls must run on {@link LoganEngine}'s serial handler thread.
 */
final class CloganNative {

    private static final CLoganProtocol PROTOCOL = CLoganProtocol.newInstance();

    static boolean isAvailable() {
        return CLoganProtocol.isCloganSuccess();
    }

    private CloganNative() {
    }

    static void clogan_init(String cache_path, String dir_path, int max_file,
            String encrypt_key_16, String encrypt_iv_16) {
        PROTOCOL.logan_init(cache_path, dir_path, max_file, encrypt_key_16, encrypt_iv_16);
    }

    static void clogan_open(String file_name) {
        PROTOCOL.logan_open(file_name);
    }

    static void clogan_write(int flag, String log, long local_time, String thread_name,
            long thread_id, int is_main) {
        PROTOCOL.logan_write(flag, log, local_time, thread_name, thread_id, is_main == 1);
    }

    static void clogan_flush() {
        PROTOCOL.logan_flush();
    }

    static void clogan_debug(boolean is_debug) {
        PROTOCOL.logan_debug(is_debug);
    }

    @Nullable
    static String loadError() {
        return isAvailable() ? null : "native library logan failed to load";
    }
}
