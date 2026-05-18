package com.dianping.logan;

import androidx.annotation.NonNull;

import java.io.File;

/**
 * Initialization parameters aligned with {@code LoganSpec.md} §2.1 / §9.2.
 */
public final class LoganInitConfig {

    final File loganLogDirectory;
    final File cacheDirectory;
    final byte[] aesKey16;
    final byte[] aesIv16;
    final long maxFileBytes;
    final long minFreeDiskBytes;
    final int maxReversedDays;

    private LoganInitConfig(Builder b) {
        this.loganLogDirectory = b.loganLogDirectory;
        this.cacheDirectory = b.cacheDirectory;
        this.aesKey16 = b.aesKey16;
        this.aesIv16 = b.aesIv16;
        this.maxFileBytes = b.maxFileBytes > 0 ? b.maxFileBytes : 10L * 1024 * 1024;
        this.minFreeDiskBytes = b.minFreeDiskBytes > 0 ? b.minFreeDiskBytes : 50L * 1024 * 1024;
        this.maxReversedDays = b.maxReversedDays > 0 ? b.maxReversedDays : 7;
    }

    public static final class Builder {
        private File loganLogDirectory;
        private File cacheDirectory;
        private byte[] aesKey16;
        private byte[] aesIv16;
        private long maxFileBytes;
        private long minFreeDiskBytes;
        private int maxReversedDays;

        @NonNull
        public Builder loganLogDirectory(@NonNull File dir) {
            this.loganLogDirectory = dir;
            return this;
        }

        @NonNull
        public Builder cacheDirectory(@NonNull File dir) {
            this.cacheDirectory = dir;
            return this;
        }

        @NonNull
        public Builder aesKey16(@NonNull byte[] key) {
            this.aesKey16 = key;
            return this;
        }

        @NonNull
        public Builder aesIv16(@NonNull byte[] iv) {
            this.aesIv16 = iv;
            return this;
        }

        @NonNull
        public Builder maxFileBytes(long bytes) {
            this.maxFileBytes = bytes;
            return this;
        }

        @NonNull
        public Builder minFreeDiskBytes(long bytes) {
            this.minFreeDiskBytes = bytes;
            return this;
        }

        @NonNull
        public Builder maxReversedDays(int days) {
            this.maxReversedDays = days;
            return this;
        }

        @NonNull
        public LoganInitConfig build() {
            if (loganLogDirectory == null || cacheDirectory == null
                    || aesKey16 == null || aesIv16 == null
                    || aesKey16.length != 16 || aesIv16.length != 16) {
                throw new IllegalArgumentException("loganLogDirectory, cacheDirectory, aesKey16/iv16 required");
            }
            return new LoganInitConfig(this);
        }
    }
}
