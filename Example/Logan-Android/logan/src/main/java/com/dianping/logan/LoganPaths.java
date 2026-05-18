package com.dianping.logan;

import java.io.File;

/**
 * Directory layout per {@code LoganSpec.md} §1.
 */
public final class LoganPaths {

    public static final String TECH = "Tech";
    public static final String BIZ = "Biz";
    public static final String TEMP = "Temp";

    private LoganPaths() {
    }

    public static File techDir(File logRoot) {
        return new File(logRoot, TECH);
    }

    public static File bizDir(File logRoot) {
        return new File(logRoot, BIZ);
    }

    public static File tempDir(File logRoot) {
        return new File(logRoot, TEMP);
    }

    public static File tempTechDir(File logRoot) {
        return new File(tempDir(logRoot), TECH);
    }

    public static File tempBizDir(File logRoot) {
        return new File(tempDir(logRoot), BIZ);
    }

    public static File mainLogFile(File logRoot, String yyyyMmDd) {
        return new File(logRoot, yyyyMmDd);
    }

    public static File techLogFile(File logRoot, String yyyyMmDd) {
        return new File(techDir(logRoot), yyyyMmDd);
    }

    public static File bizLogFile(File logRoot, String yyyyMmDd) {
        return new File(bizDir(logRoot), yyyyMmDd);
    }

    public static File tempMainFile(File logRoot, String yyyyMmDd) {
        return new File(tempDir(logRoot), yyyyMmDd);
    }

    public static File tempTechFile(File logRoot, String yyyyMmDd) {
        return new File(tempTechDir(logRoot), yyyyMmDd);
    }

    public static File tempBizFile(File logRoot, String yyyyMmDd) {
        return new File(tempBizDir(logRoot), yyyyMmDd);
    }

    /** clogan_open pathname for main channel. */
    public static String mainPathname(String yyyyMmDd) {
        return yyyyMmDd;
    }

    public static String techPathname(String yyyyMmDd) {
        return TECH + "/" + yyyyMmDd;
    }

    public static String bizPathname(String yyyyMmDd) {
        return BIZ + "/" + yyyyMmDd;
    }
}
