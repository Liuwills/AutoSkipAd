package com.autoskip.ad;

import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Date;
import java.util.Deque;
import java.util.List;
import java.util.Locale;

/**
 * 极简内存日志，用于界面上排查“为什么没有触发”。
 */
public final class LogStore {

    private static final int MAX_LINES = 120;
    private static final Deque<String> LINES = new ArrayDeque<>();
    private static final List<Runnable> LISTENERS = new ArrayList<>();
    private static final SimpleDateFormat FMT = new SimpleDateFormat("HH:mm:ss", Locale.CHINA);

    private LogStore() {
    }

    public static void add(String msg) {
        String line = FMT.format(new Date()) + "  " + msg;
        List<Runnable> copy;
        synchronized (LogStore.class) {
            LINES.addLast(line);
            while (LINES.size() > MAX_LINES) {
                LINES.removeFirst();
            }
            copy = new ArrayList<>(LISTENERS);
        }
        for (Runnable r : copy) {
            try {
                r.run();
            } catch (Throwable ignored) {
            }
        }
    }

    public static void clear() {
        synchronized (LogStore.class) {
            LINES.clear();
        }
    }

    public static String dump() {
        synchronized (LogStore.class) {
            if (LINES.isEmpty()) {
                return "";
            }
            StringBuilder sb = new StringBuilder();
            for (String l : LINES) {
                sb.append(l).append('\n');
            }
            return sb.toString();
        }
    }

    public static void addListener(Runnable r) {
        synchronized (LogStore.class) {
            if (!LISTENERS.contains(r)) {
                LISTENERS.add(r);
            }
        }
    }

    public static void removeListener(Runnable r) {
        synchronized (LogStore.class) {
            LISTENERS.remove(r);
        }
    }
}
