package com.dianping.logan;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * Thread-safe local KV store for log creation timestamps.
 */
final class LoganLogCreateTimeStore {

    private final File storeFile;
    private final ReentrantReadWriteLock rwLock = new ReentrantReadWriteLock();
    private final Map<String, Long> values = new HashMap<>();

    LoganLogCreateTimeStore(File storeFile) {
        this.storeFile = storeFile;
        loadFromDisk();
    }

    long get(String key) {
        if (key == null || key.length() == 0) {
            return -1L;
        }
        rwLock.readLock().lock();
        try {
            Long value = values.get(key);
            return value != null ? value : -1L;
        } finally {
            rwLock.readLock().unlock();
        }
    }

    Map<String, Long> snapshot() {
        rwLock.readLock().lock();
        try {
            return new HashMap<>(values);
        } finally {
            rwLock.readLock().unlock();
        }
    }

    void putIfAbsent(String key, long createTimeMs) {
        if (key == null || key.length() == 0 || createTimeMs <= 0L) {
            return;
        }
        rwLock.writeLock().lock();
        try {
            if (!values.containsKey(key)) {
                values.put(key, createTimeMs);
                persistLocked();
            }
        } finally {
            rwLock.writeLock().unlock();
        }
    }

    void remove(String key) {
        if (key == null || key.length() == 0) {
            return;
        }
        rwLock.writeLock().lock();
        try {
            if (values.remove(key) != null) {
                persistLocked();
            }
        } finally {
            rwLock.writeLock().unlock();
        }
    }

    void purgeExpiredOrInvalid(long cutoffCreateTimeMs, File logRoot) {
        rwLock.writeLock().lock();
        try {
            boolean changed = false;
            String tempPrefix = null;
            if (logRoot != null) {
                tempPrefix = new File(logRoot, LoganPaths.TEMP).getAbsolutePath()
                        + File.separator;
            }
            Iterator<Map.Entry<String, Long>> iterator = values.entrySet().iterator();
            while (iterator.hasNext()) {
                Map.Entry<String, Long> entry = iterator.next();
                String key = entry.getKey();
                Long value = entry.getValue();
                if (key == null || key.length() == 0 || value == null || value <= 0L) {
                    iterator.remove();
                    changed = true;
                    continue;
                }
                String absPath = new File(key).getAbsolutePath();
                boolean isTemp = tempPrefix != null
                        && (absPath.equals(new File(logRoot, LoganPaths.TEMP).getAbsolutePath())
                        || absPath.startsWith(tempPrefix));
                if (isTemp || value < cutoffCreateTimeMs || !new File(absPath).isFile()) {
                    iterator.remove();
                    changed = true;
                }
            }
            if (changed) {
                persistLocked();
            }
        } finally {
            rwLock.writeLock().unlock();
        }
    }

    private void loadFromDisk() {
        rwLock.writeLock().lock();
        try {
            values.clear();
            if (!storeFile.isFile()) {
                return;
            }
            Properties properties = new Properties();
            FileInputStream inputStream = null;
            try {
                inputStream = new FileInputStream(storeFile);
                properties.load(inputStream);
                for (String key : properties.stringPropertyNames()) {
                    String value = properties.getProperty(key);
                    if (value == null || value.length() == 0) {
                        continue;
                    }
                    try {
                        long ts = Long.parseLong(value);
                        if (ts > 0L) {
                            values.put(key, ts);
                        }
                    } catch (NumberFormatException ignored) {
                    }
                }
            } catch (IOException ignored) {
            } finally {
                if (inputStream != null) {
                    try {
                        inputStream.close();
                    } catch (IOException ignored) {
                    }
                }
            }
        } finally {
            rwLock.writeLock().unlock();
        }
    }

    private void persistLocked() {
        File parent = storeFile.getParentFile();
        if (parent != null && !parent.exists() && !parent.mkdirs()) {
            return;
        }
        Properties properties = new Properties();
        for (Map.Entry<String, Long> entry : values.entrySet()) {
            properties.setProperty(entry.getKey(), String.valueOf(entry.getValue()));
        }

        File tmp = new File(storeFile.getAbsolutePath() + ".tmp");
        FileOutputStream outputStream = null;
        try {
            outputStream = new FileOutputStream(tmp);
            properties.store(outputStream, "logan log create time");
            outputStream.flush();
        } catch (IOException ignored) {
            return;
        } finally {
            if (outputStream != null) {
                try {
                    outputStream.close();
                } catch (IOException ignored) {
                }
            }
        }

        if (!tmp.renameTo(storeFile)) {
            LoganIoUtils.deleteFileQuietly(storeFile);
            //noinspection ResultOfMethodCallIgnored
            tmp.renameTo(storeFile);
        }
    }
}
