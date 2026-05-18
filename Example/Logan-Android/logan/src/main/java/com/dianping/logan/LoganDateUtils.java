package com.dianping.logan;

import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;
import java.util.regex.Pattern;

final class LoganDateUtils {

    private static final String PATTERN = "yyyy-MM-dd";
    private static final Pattern YMD = Pattern.compile("^\\d{4}-\\d{2}-\\d{2}$");

    private LoganDateUtils() {
    }

    static boolean isValidYyyyMmDd(String date) {
        if (date == null || !YMD.matcher(date).matches()) {
            return false;
        }
        SimpleDateFormat sdf = new SimpleDateFormat(PATTERN, Locale.US);
        sdf.setLenient(false);
        try {
            sdf.parse(date);
            return true;
        } catch (ParseException e) {
            return false;
        }
    }

    static String today() {
        SimpleDateFormat sdf = new SimpleDateFormat(PATTERN, Locale.getDefault());
        return sdf.format(new Date());
    }

    /**
     * Start of calendar day for {@code yyyyMmDd} in the default timezone, in milliseconds since epoch.
     */
    static long dayStartMillisDefaultTz(String yyyyMmDd) throws ParseException {
        SimpleDateFormat sdf = new SimpleDateFormat(PATTERN, Locale.getDefault());
        sdf.setLenient(false);
        sdf.setTimeZone(TimeZone.getDefault());
        return sdf.parse(yyyyMmDd).getTime();
    }

    /**
     * Oldest calendar day (00:00 local) that should be kept when retaining {@code maxDays} days including today.
     */
    static long cutoffDayStartMillis(int maxDays) {
        if (maxDays < 1) {
            maxDays = 7;
        }
        Calendar cal = Calendar.getInstance(TimeZone.getDefault(), Locale.getDefault());
        cal.set(Calendar.HOUR_OF_DAY, 0);
        cal.set(Calendar.MINUTE, 0);
        cal.set(Calendar.SECOND, 0);
        cal.set(Calendar.MILLISECOND, 0);
        cal.add(Calendar.DAY_OF_MONTH, -(maxDays - 1));
        return cal.getTimeInMillis();
    }
}
