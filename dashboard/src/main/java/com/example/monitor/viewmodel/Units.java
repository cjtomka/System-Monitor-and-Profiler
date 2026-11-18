package com.example.monitor.viewmodel;

import java.util.Locale;

/** Binary-unit formatting for byte counts and rates. Pure. */
public final class Units {
    private static final String[] NAMES = {"B", "KiB", "MiB", "GiB", "TiB"};

    private Units() {}

    /** Index of the largest binary unit that is <= |v| (0 = bytes). */
    public static int unitIndex(double v) {
        double a = Math.abs(v);
        int i = 0;
        double next = 1024.0;
        while (i < NAMES.length - 1 && a >= next) {
            i++;
            next *= 1024.0;
        }
        return i;
    }

    public static double base(int unitIndex) {
        return Math.pow(1024.0, unitIndex);
    }

    /** "412 MiB", "15.9 GiB", "512 B"; "n/a" for non-finite input. */
    public static String bytes(double v) {
        if (!Double.isFinite(v)) return "n/a";
        double a = Math.max(0.0, v);
        int idx = unitIndex(a);
        if (idx == 0) return String.format(Locale.ROOT, "%d B", Math.round(a));
        double val = a / base(idx);
        return String.format(Locale.ROOT, val >= 100 ? "%.0f %s" : "%.1f %s", val, NAMES[idx]);
    }

    public static String rate(double v) {
        String s = bytes(v);
        return s.equals("n/a") ? s : s + "/s";
    }

    /** An axis label in a fixed unit, so every tick on one axis reads the same: "0.25 MiB/s". */
    public static String scaled(double v, int unitIndex, boolean perSecond) {
        int idx = Math.max(0, Math.min(NAMES.length - 1, unitIndex));
        String num = trim(String.format(Locale.ROOT, "%.2f", v / base(idx)));
        return num + " " + NAMES[idx] + (perSecond ? "/s" : "");
    }

    public static String percent(double v) {
        return Double.isFinite(v) ? String.format(Locale.ROOT, "%.1f%%", v) : "n/a";
    }

    /** Drops trailing zeros after a decimal point: "2.50" -> "2.5", "3.00" -> "3", "100" stays. */
    static String trim(String s) {
        if (s.indexOf('.') < 0) return s;
        int end = s.length();
        while (end > 0 && s.charAt(end - 1) == '0') end--;
        if (end > 0 && s.charAt(end - 1) == '.') end--;
        return s.substring(0, end);
    }
}
