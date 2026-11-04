package com.autoskip.ad;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 广告识别核心（纯 Java，可脱离 Android 跑单元测试）。
 *
 * <p>上一版在红果短剧上不生效，主要不是“App 禁了无障碍”，而是这里的判定链太苛刻 + 只认文字：
 * <ol>
 *   <li>“结束”要求 {@code 最后一帧读秒 - 第一帧读秒 >= 900ms}，事件稀疏时永远不成立；</li>
 *   <li>只认 text/contentDescription，广告 SDK 自绘的跳过按钮（无文字 ImageView）完全看不见；</li>
 *   <li>采集了 viewId 却从不使用，而 {@code tt_splash_skip_btn} 这类 id 是最强信号；</li>
 *   <li>“跳过片头/片尾”会被当成跳过按钮，扣 15 分后仍然过阈值。</li>
 * </ol>
 *
 * <p>现在的判定分层（从强到弱）：
 * <ol>
 *   <li><b>viewId 命中</b>（skip / ad_close / splash_skip …）——几乎不会误判；</li>
 *   <li><b>按钮自带倒计时</b>：“跳过 5”“5s | 跳过”“跳过广告 5s”；</li>
 *   <li><b>独立读秒</b>：“5秒”“12s”，必须观察到数值递减；</li>
 *   <li><b>无文字的自绘按钮</b>：只在“读秒刚结束 3 秒内”+ 右上角 + 按钮尺寸时点击；</li>
 *   <li><b>“上滑继续 / 继续观看”</b>：广告播完后常见的收尾提示，单独上滑。</li>
 * </ol>
 */
public class AdDetector {

    public static final int ACT_NONE = 0;
    public static final int ACT_SWIPE = 1;
    public static final int ACT_CLICK = 2;
    public static final int ACT_TAP = 3;

    public static class Decision {
        public int action = ACT_NONE;
        public String reason = "";
        public Object target;
        public int tapX;
        public int tapY;
        /** 本帧是否在读秒，供日志展示 */
        public boolean counting;
        public int countdown = -1;
        /** 点击目标是“无文字的自绘按钮”，服务侧应直接坐标点击 */
        public boolean blankTarget;
    }

    // ---------------------------------------------------------------- 文本规则

    /** “5秒 / 5s / 12S” */
    private static final Pattern P_SEC = Pattern.compile("(\\d{1,3})\\s*(?:秒|s(?![a-zA-Z])|S(?![a-zA-Z]))");
    /** 取文本里第一组 1~3 位数字 */
    private static final Pattern P_DIGIT = Pattern.compile("(\\d{1,3})");
    /** 纯数字 */
    private static final Pattern P_BARE = Pattern.compile("^\\d{1,3}$");

    /** 跳过类 */
    private static final Pattern P_SKIP = Pattern.compile("跳过|跳過|skip", Pattern.CASE_INSENSITIVE);
    /** 关闭广告类 */
    private static final Pattern P_CLOSE_AD = Pattern.compile(
            "关闭(?:此|该)?广告|广告关闭|关闭推广|close\\s*ad", Pattern.CASE_INSENSITIVE);
    /** 只有叉号/“关闭”两个字的弱信号，必须配合广告场景才点 */
    private static final Pattern P_WEAK_CLOSE = Pattern.compile("关闭|×|✕|╳|✗|✘|x|X");

    /** 出现这些词的节点一定不是跳过按钮（短剧 App 的“跳过片头/片尾”就是典型误判来源） */
    private static final Pattern P_NOT_SKIP = Pattern.compile(
            "片头|片尾|下一集|下集|上滑|继续|举报|反馈|不感兴趣|取消|我知道了|开通|会员|vip|"
                    + "立即下载|查看详情|了解详情|下载|安装|广告合作|隐私|设置");

    /** 广告场景标识（只作为辅助证据，不单独触发动作） */
    private static final Pattern P_AD_WORD = Pattern.compile(
            "广告|廣告|赞助|贊助|推广|advertisement|sponsored", Pattern.CASE_INSENSITIVE);

    /** 广告播完后的收尾提示 */
    private static final Pattern P_CONTINUE = Pattern.compile(
            "上滑继续|滑动继续|继续观看|上滑观看|上滑解锁|继续播放|上滑看下一集");

    /** 广告 SDK 常用的 viewId 片段 */
    private static final Pattern P_VIEW_SKIP = Pattern.compile(
            "skip|splash_skip|ad_close|close_ad|ad_skip|btn_close|close_btn|ad_skip_btn",
            Pattern.CASE_INSENSITIVE);

    /** 点击阈值：读完秒后 */
    private static final int MIN_SCORE_AFTER_END = 85;
    /** 没有读秒、直接出现按钮时 */
    private static final int MIN_SCORE_DIRECT = 110;

    // ---------------------------------------------------------------- 状态

    private long lastActionAt = 0;

    /** 最近一次“这是广告场景”的证据时间 */
    private long lastAdEvidenceAt = 0;

    /** 读秒状态 */
    private long countStartAt = 0;
    private long lastCountAt = 0;
    private int lastCd = -1;
    private int samples = 0;
    private boolean decreased = false;
    private boolean lastCdFromButton = false;

    /** 独立文本读秒的递减确认 */
    private int textPrevRaw = -1;
    private long textPrevAt = 0;
    private boolean textDecreasing = false;

    /** 同一个跳过按钮的稳定性与点击退避 */
    private String lastSkipKey = "";
    private long skipKeySince = 0;
    private String lastClickedKey = "";
    private int clickKeyCount = 0;
    private long clickKeyAt = 0;

    /** 上滑继续的冷却 */
    private long lastContinueAt = 0;

    /** 纯数字倒计时（没有“秒/s”也没有“跳过”字样，广告 SDK 常见） */
    private long lastBare = -1;
    private long bareSeenAt = 0;
    private boolean bareDecreased = false;

    public void reset() {
        lastActionAt = 0;
        lastAdEvidenceAt = 0;
        countStartAt = 0;
        lastCountAt = 0;
        lastCd = -1;
        samples = 0;
        decreased = false;
        lastCdFromButton = false;
        textPrevRaw = -1;
        textPrevAt = 0;
        textDecreasing = false;
        lastSkipKey = "";
        skipKeySince = 0;
        lastClickedKey = "";
        clickKeyCount = 0;
        clickKeyAt = 0;
        lastContinueAt = 0;
        lastBare = -1;
        bareSeenAt = 0;
        bareDecreased = false;
    }

    // ---------------------------------------------------------------- 主流程

    public Decision analyze(ScreenSnapshot snap, long now, DetectorConfig cfg) {
        Decision d = new Decision();
        if (snap == null || snap.width <= 0 || snap.height <= 0) {
            return d;
        }
        final int W = snap.width;
        final int H = snap.height;
        final long screenArea = (long) W * H;

        boolean adWord = false;
        int btnCountdown = -1;
        int textCountdownRaw = -1;
        int bareRaw = -1;
        boolean continuePrompt = false;

        Candidate best = null;    // 有文字的跳过/关闭候选
        Candidate blank = null;   // 无文字的自绘按钮候选

        for (ScreenSnapshot.Item it : snap.items) {
            String t = it.plain();
            boolean hasText = !t.isEmpty();
            String vid = it.viewId == null ? "" : it.viewId;
            boolean vidHit = !vid.isEmpty() && P_VIEW_SKIP.matcher(vid).find();

            if (hasText && t.length() <= 24) {
                if (!adWord && P_AD_WORD.matcher(t).find()) {
                    adWord = true;
                }
                if (!continuePrompt && P_CONTINUE.matcher(t).find()
                        && it.area() < screenArea * 0.5) {
                    continuePrompt = true;
                }
            }

            boolean skipWord = hasText && P_SKIP.matcher(t).find();
            boolean closeAd = hasText && !skipWord && P_CLOSE_AD.matcher(t).find();
            boolean weakClose = hasText && !skipWord && !closeAd
                    && t.length() <= 4 && P_WEAK_CLOSE.matcher(t).find();

            boolean excluded = hasText && P_NOT_SKIP.matcher(t).find() && !vidHit;

            // ---- 倒计时数字 ----
            int num = -1;
            if (!excluded && (skipWord || closeAd || vidHit)) {
                // 按钮上的数字：“跳过 5” / “5 | 跳过” / “跳过广告 5s”
                num = firstNumber(t);
            } else if (hasText && !excluded) {
                Matcher m = P_SEC.matcher(t);
                if (m.find()) {
                    num = parseIntSafe(m.group(1));
                }
            }
            if (num > 120) {
                num = -1;
            }

            if (num > 0) {
                if (!excluded && (skipWord || closeAd || vidHit)) {
                    if (num > btnCountdown) {
                        btnCountdown = num;
                    }
                } else if (it.area() < screenArea * 0.15) {
                    if (num > textCountdownRaw) {
                        textCountdownRaw = num;
                    }
                }
            }

            // ---- 纯数字（只有“8”“7”…）：位置固定、逐帧 -1 才算倒计时 ----
            if (num < 0 && hasText && it.area() < screenArea * 0.12 && it.cy() < H * 0.45f) {
                String pt = t.length() <= 4 ? t : "";
                if (!pt.isEmpty() && P_BARE.matcher(pt).matches()) {
                    int v = parseIntSafe(pt);
                    if (v > 0 && v > bareRaw) {
                        bareRaw = v;
                    }
                }
            }

            // ---- 跳过按钮候选 ----
            if (!excluded && (skipWord || closeAd || vidHit || weakClose)) {
                int score = 0;
                if (vidHit) {
                    score += 110;
                }
                if (skipWord) {
                    score += 100;
                }
                if (closeAd) {
                    score += 80;
                }
                if (weakClose) {
                    score += 45;
                }
                if (it.clickable) {
                    score += 25;
                }
                if (it.enabled) {
                    score += 8;
                }
                if (it.cx() > W * 0.55f) {
                    score += 12;
                }
                if (it.cy() < H * 0.35f) {
                    score += 10;
                }
                if (it.area() < screenArea * 0.12) {
                    score += 8;
                }
                // 整屏可点击的广告根容器（点它会打开落地页，不是跳过）
                if (it.area() > screenArea * 0.5) {
                    score -= 80;
                }
                Candidate c = new Candidate(it, score, skipWord, closeAd, vidHit,
                        keyOf(it, W, H));
                if (best == null || score > best.score) {
                    best = c;
                }
            }

            // ---- 无文字的自绘按钮（右上角、按钮尺寸、可点击） ----
            if (cfg.tapBlankSkipArea && !hasText && it.clickable && it.enabled) {
                int w = it.width();
                int h = it.height();
                if (w >= W * 0.04 && w <= W * 0.45
                        && h >= H * 0.015 && h <= H * 0.10
                        && it.cx() > W * 0.5f && it.cy() < H * 0.42f
                        && it.area() < screenArea * 0.06) {
                    int score = 40;
                    if (it.cx() > W * 0.68f) {
                        score += 14;
                    }
                    if (it.cy() < H * 0.22f) {
                        score += 12;
                    }
                    if (w < W * 0.30f) {
                        score += 8;
                    }
                    Candidate c = new Candidate(it, score, false, false, false,
                            "-blank-@" + it.cx() + "_" + it.cy());
                    if (blank == null || score > blank.score) {
                        blank = c;
                    }
                }
            }
        }

        // ---------------------------------------------------------------- 读秒状态机

        // 独立读秒必须先看到“数值递减”才可信，避免把“30秒”这种静态时长当倒计时
        int textCountdown = -1;
        if (textCountdownRaw > 0) {
            if (textPrevRaw > textCountdownRaw && now - textPrevAt < 2500) {
                textDecreasing = true;
            }
            if (textDecreasing) {
                textCountdown = textCountdownRaw;
            }
            textPrevRaw = textCountdownRaw;
            textPrevAt = now;
        } else if (now - textPrevAt > 1500) {
            textDecreasing = false;
            textPrevRaw = -1;
        }

        // 纯数字：先确认它在 -1 递减（过滤点赞数、集数这类静态数字）
        boolean bareVisible = false;
        if (bareRaw > 0) {
            if (lastBare > 0 && bareRaw < lastBare && now - bareSeenAt < 2600) {
                bareDecreased = true;
            }
            lastBare = bareRaw;
            bareSeenAt = now;
            bareVisible = true;
        } else if (now - bareSeenAt > 1500) {
            lastBare = -1;
            bareDecreased = false;
        }
        if (textCountdown < 0 && bareDecreased && bareVisible) {
            textCountdown = (int) lastBare;
        }

        // 按钮上的数字本身就是“可点击的跳过按钮”，直接采信
        int cd = btnCountdown > 0 ? btnCountdown : textCountdown;
        boolean fromButton = btnCountdown > 0;
        boolean counting = cd > 0;

        if (counting) {
            if (lastCountAt == 0) {
                countStartAt = now;
            }
            if (lastCd > 0 && cd < lastCd) {
                decreased = true;
            }
            samples++;
            lastCountAt = now;
            lastCd = cd;
            lastCdFromButton = fromButton;
        }
        if (counting || adWord || (best != null && (best.vidHit || best.skipWord || best.closeAd))) {
            lastAdEvidenceAt = now;
        }

        long gone = lastCountAt == 0 ? Long.MAX_VALUE : now - lastCountAt;
        boolean durationOk = (lastCountAt - countStartAt) >= 300 || samples >= 2 || lastCd <= 2;
        boolean trusted = decreased || lastCdFromButton;
        boolean ended = lastCountAt > 0
                && gone >= cfg.goneMs
                && gone <= 8000
                && durationOk
                && trusted;

        d.counting = counting;
        d.countdown = cd;

        // ---------------------------------------------------------------- 模式

        int mode = cfg.mode;
        boolean clickAllowed = mode == DetectorConfig.MODE_SMART || mode == DetectorConfig.MODE_CLICK_ONLY;
        boolean swipeAllowed = mode == DetectorConfig.MODE_SMART || mode == DetectorConfig.MODE_COUNTDOWN_ONLY;

        if (mode == DetectorConfig.MODE_LOG_ONLY) {
            if (ended) {
                d.reason = "演练：倒计时结束"
                        + (best != null ? "，按钮“" + trim(best.item.plain()) + "”" : "，无跳过按钮");
            } else if (counting) {
                d.reason = "演练：读秒中 " + cd + "s" + (fromButton ? "（按钮自带）" : "");
            } else if (continuePrompt) {
                d.reason = "演练：出现“上滑继续”";
            } else if (best != null && best.score >= MIN_SCORE_DIRECT) {
                d.reason = "演练：发现跳过按钮“" + trim(best.item.plain()) + "”";
            }
            return d;
        }

        long cooldown = Math.max(cfg.minCooldownMs, cfg.delayMs + 900);
        if (lastActionAt > 0 && now - lastActionAt < cooldown) {
            return d;
        }

        boolean adContext = lastAdEvidenceAt > 0 && now - lastAdEvidenceAt < cfg.adSessionMs;
        if (cfg.requireAdWord && !adContext) {
            return d;
        }

        // 同一按钮反复点击失败时退避，避免“疯狂点击”被判定为脚本
        if (best != null) {
            if (!best.key.equals(lastSkipKey)) {
                lastSkipKey = best.key;
                skipKeySince = now;
            }
            if (best.key.equals(lastClickedKey) && now - clickKeyAt < 6000 && clickKeyCount >= 2) {
                best = null;
            }
        }

        // ---- 规则 1：倒计时刚结束 ----
        if (ended) {
            if (clickAllowed && best != null && best.score >= MIN_SCORE_AFTER_END) {
                return click(d, best, now, "倒计时结束，点击“" + trim(best.item.plain()) + "”");
            }
            if (clickAllowed && blank != null && now - lastCountAt <= 3000) {
                d.action = ACT_CLICK;
                d.blankTarget = true;
                d.target = blank.item.node;
                d.tapX = blank.item.cx();
                d.tapY = blank.item.cy();
                d.reason = "倒计时结束，点击右上角无文字跳过按钮";
                markClicked(blank.key, now);
                return d;
            }
            if (swipeAllowed && continuePrompt
                    && (lastContinueAt == 0 || now - lastContinueAt > 2500)) {
                lastContinueAt = now;
                d.action = ACT_SWIPE;
                d.reason = "广告结束，出现“上滑继续”，自动上滑";
                markClicked("", now);
                return d;
            }
            if (swipeAllowed && cfg.swipeOnAdEnd) {
                d.action = ACT_SWIPE;
                d.reason = "倒计时结束（" + lastCd + "s），自动上滑";
                markClicked("", now);
                return d;
            }
            d.reason = "倒计时结束（" + lastCd + "s），没找到跳过按钮，广告应该已自然结束";
            // 结束状态只报告一次
            countStartAt = 0;
            lastCountAt = 0;
            lastCd = -1;
            samples = 0;
            decreased = false;
            lastCdFromButton = false;
            return d;
        }

        // ---- 规则 2：没有读秒，但存在稳定的跳过按钮（开屏广告常见） ----
        if (clickAllowed && !counting && best != null && best.score >= MIN_SCORE_DIRECT
                && now - skipKeySince >= cfg.stableMs) {
            return click(d, best, now, "发现跳过按钮“" + trim(best.item.plain()) + "”，点击");
        }

        // ---- 规则 3：“上滑继续” ----
        if (swipeAllowed && continuePrompt && !counting
                && (lastContinueAt == 0 || now - lastContinueAt > 2500)) {
            lastContinueAt = now;
            d.action = ACT_SWIPE;
            d.reason = "出现“上滑继续”，自动上滑";
            markClicked("", now);
            return d;
        }

        return d;
    }

    // ---------------------------------------------------------------- 内部工具

    private Decision click(Decision d, Candidate c, long now, String reason) {
        d.action = ACT_CLICK;
        d.target = c.item.node;
        d.tapX = c.item.cx();
        d.tapY = c.item.cy();
        d.reason = reason;
        markClicked(c.key, now);
        return d;
    }

    /** 动作后重置读秒状态，等下一轮广告 */
    private void markClicked(String key, long now) {
        lastActionAt = now;
        if (key != null && !key.isEmpty()) {
            if (key.equals(lastClickedKey) && now - clickKeyAt < 8000) {
                clickKeyCount++;
            } else {
                clickKeyCount = 1;
            }
            lastClickedKey = key;
            clickKeyAt = now;
        }
        countStartAt = 0;
        lastCountAt = 0;
        lastCd = -1;
        samples = 0;
        decreased = false;
        lastCdFromButton = false;
    }

    private static String keyOf(ScreenSnapshot.Item it, int w, int h) {
        int gx = (int) (it.cx() / Math.max(1f, w / 12f));
        int gy = (int) (it.cy() / Math.max(1f, h / 24f));
        return it.plain() + "@" + gx + "_" + gy;
    }

    private static int firstNumber(String s) {
        if (s == null || s.isEmpty()) {
            return -1;
        }
        Matcher m = P_DIGIT.matcher(s);
        if (m.find()) {
            return parseIntSafe(m.group(1));
        }
        return -1;
    }

    private static int parseIntSafe(String s) {
        try {
            return Integer.parseInt(s.trim());
        } catch (Exception e) {
            return -1;
        }
    }

    private static String trim(String s) {
        if (s == null) {
            return "";
        }
        return s.length() > 12 ? s.substring(0, 12) : s;
    }

    private static class Candidate {
        final ScreenSnapshot.Item item;
        final int score;
        final boolean skipWord;
        final boolean closeAd;
        final boolean vidHit;
        final String key;

        Candidate(ScreenSnapshot.Item item, int score, boolean skipWord, boolean closeAd,
                  boolean vidHit, String key) {
            this.item = item;
            this.score = score;
            this.skipWord = skipWord;
            this.closeAd = closeAd;
            this.vidHit = vidHit;
            this.key = key;
        }
    }
}
