package com.autoskip.ad;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * 全局配置。用 SharedPreferences 持久化，无障碍服务与界面共享同一份实例。
 */
public final class Prefs {

    /** 智能模式：倒计时结束优先点“跳过”，没有跳过按钮则上滑 */
    public static final int MODE_SMART = DetectorConfig.MODE_SMART;
    /** 只做上滑，不点按钮 */
    public static final int MODE_COUNTDOWN_ONLY = DetectorConfig.MODE_COUNTDOWN_ONLY;
    /** 只点“跳过/关闭广告”按钮，不上滑 */
    public static final int MODE_CLICK_ONLY = DetectorConfig.MODE_CLICK_ONLY;
    /** 演练模式：只写日志，不执行任何操作 */
    public static final int MODE_LOG_ONLY = DetectorConfig.MODE_LOG_ONLY;

    private static final String SP_NAME = "autoskip_prefs";

    private static final String K_ENABLED = "enabled";
    private static final String K_MODE = "mode";
    private static final String K_DELAY = "delay_ms";
    private static final String K_SWIPE_COUNT = "swipe_count";
    private static final String K_VIBRATE = "vibrate";
    private static final String K_AD_WORD = "ad_word_helper";
    private static final String K_PACKAGES = "packages";
    private static final String K_LOG_VERBOSE = "log_verbose";
    private static final String K_SWIPE_ON_END = "swipe_on_end";

    /** 默认生效的包名，可以在“生效应用”里自由增减 */
    private static final String[] DEFAULT_PKGS = new String[]{
            "com.phoenix.read",            // 红果免费短剧
            "com.ss.android.ugc.aweme",    // 抖音
            "com.ss.android.ugc.aweme.lite", // 抖音极速版
            "com.smile.gifmaker",          // 快手
            "com.kuaishou.nebula",         // 快手极速版
            "com.dragon.read",             // 番茄免费小说
            "com.xs.fm"                    // 番茄畅听
    };

    private static Prefs sInstance;

    private final SharedPreferences sp;

    private Prefs(Context ctx) {
        sp = ctx.getSharedPreferences(SP_NAME, Context.MODE_PRIVATE);
        if (!sp.contains(K_PACKAGES)) {
            sp.edit().putStringSet(K_PACKAGES, new LinkedHashSet<>(Arrays.asList(DEFAULT_PKGS))).apply();
        }
    }

    public static synchronized Prefs get(Context ctx) {
        if (sInstance == null) {
            sInstance = new Prefs(ctx.getApplicationContext());
        }
        return sInstance;
    }

    public boolean isMasterEnabled() {
        return sp.getBoolean(K_ENABLED, true);
    }

    public void setMasterEnabled(boolean v) {
        sp.edit().putBoolean(K_ENABLED, v).apply();
    }

    public int getMode() {
        return sp.getInt(K_MODE, MODE_SMART);
    }

    public void setMode(int v) {
        sp.edit().putInt(K_MODE, v).apply();
    }

    /** 倒计时结束后延迟多久再动作，避免抢在动画中间 */
    public int getDelayMs() {
        return sp.getInt(K_DELAY, 200);
    }

    public void setDelayMs(int v) {
        sp.edit().putInt(K_DELAY, Math.max(0, Math.min(2000, v))).apply();
    }

    public int getSwipeCount() {
        return sp.getInt(K_SWIPE_COUNT, 1);
    }

    public void setSwipeCount(int v) {
        sp.edit().putInt(K_SWIPE_COUNT, Math.max(1, Math.min(3, v))).apply();
    }

    public boolean isVibrate() {
        return sp.getBoolean(K_VIBRATE, true);
    }

    public void setVibrate(boolean v) {
        sp.edit().putBoolean(K_VIBRATE, v).apply();
    }

    /** 严格模式：必须屏幕上出现过“广告/赞助”字样才允许动作（更保守，默认关闭） */
    public boolean isRequireAdWord() {
        return sp.getBoolean(K_AD_WORD, false);
    }

    public void setRequireAdWord(boolean v) {
        sp.edit().putBoolean(K_AD_WORD, v).apply();
    }

    /** 倒计时结束后没有任何按钮时，是否直接上滑（部分 App 会滑掉一整集剧情，默认关闭） */
    public boolean isSwipeOnAdEnd() {
        return sp.getBoolean(K_SWIPE_ON_END, false);
    }

    public void setSwipeOnAdEnd(boolean v) {
        sp.edit().putBoolean(K_SWIPE_ON_END, v).apply();
    }

    public boolean isVerboseLog() {
        return sp.getBoolean(K_LOG_VERBOSE, false);
    }

    public void setVerboseLog(boolean v) {
        sp.edit().putBoolean(K_LOG_VERBOSE, v).apply();
    }

    public Set<String> getPackages() {
        Set<String> s = sp.getStringSet(K_PACKAGES, null);
        if (s == null) {
            return new HashSet<>(Arrays.asList(DEFAULT_PKGS));
        }
        return new LinkedHashSet<>(s);
    }

    public void setPackages(Set<String> pkgs) {
        sp.edit().putStringSet(K_PACKAGES, new LinkedHashSet<>(pkgs)).apply();
    }

    public boolean isPackageEnabled(String pkg) {
        if (pkg == null) {
            return false;
        }
        return getPackages().contains(pkg);
    }

    public void setPackageEnabled(String pkg, boolean on) {
        Set<String> s = getPackages();
        if (on) {
            s.add(pkg);
        } else {
            s.remove(pkg);
        }
        setPackages(s);
    }
}
