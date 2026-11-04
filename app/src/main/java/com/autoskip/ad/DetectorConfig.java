package com.autoskip.ad;

/**
 * 检测器需要的配置项。与 SharedPreferences 解耦，方便单元测试。
 */
public class DetectorConfig {

    public static final int MODE_SMART = 0;
    public static final int MODE_COUNTDOWN_ONLY = 1;
    public static final int MODE_CLICK_ONLY = 2;
    public static final int MODE_LOG_ONLY = 3;

    /** Prefs.MODE_* */
    public int mode = MODE_SMART;
    /** 倒计时结束后额外延迟（仅用于冷却计算，实际等待由服务侧二次扫描完成） */
    public int delayMs = 200;
    /** 两次动作之间最小间隔 */
    public int minCooldownMs = 1200;

    /** 倒计时数字消失多久算“结束”。心跳扫描 200ms 一次，这里留一点余量 */
    public int goneMs = 340;

    /** 一次广告场景的“记忆时长”：期间出现跳过按钮可以直接点，不必再等读秒 */
    public int adSessionMs = 45000;

    /** 跳过按钮至少连续出现多久才点，避免一闪而过的浮层 */
    public int stableMs = 260;

    /** 是否允许点击“没有文字、只在右上角的小方块”（自绘跳过按钮） */
    public boolean tapBlankSkipArea = true;

    /** 倒计时结束后如果没有任何按钮，是否直接上滑（部分 App 会因此跳过一整集剧情） */
    public boolean swipeOnAdEnd = false;

    /** 严格模式：必须屏幕上出现过“广告/赞助”字样才允许动作 */
    public boolean requireAdWord = false;

    public DetectorConfig() {
    }

    public DetectorConfig(int mode, int delayMs) {
        this.mode = mode;
        this.delayMs = delayMs;
    }
}
