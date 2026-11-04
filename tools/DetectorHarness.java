import com.autoskip.ad.AdDetector;
import com.autoskip.ad.DetectorConfig;
import com.autoskip.ad.ScreenSnapshot;

/**
 * 纯 JVM 逻辑测试：验证 AdDetector 的状态机在各种界面序列下的判定。
 * 不依赖 Android，直接用 javac/java 跑。
 */
public class DetectorHarness {

    static int passed = 0;
    static int failed = 0;

    static final int W = 1080;
    static final int H = 2400;

    public static void main(String[] args) {
        testSkipButtonCountdown();
        testPlainCountdownThenGone();
        testSwipeOnAdEndOptIn();
        testStaticDurationMustNotTrigger();
        testBareNumberCountdown();
        testCooldownPreventsDouble();
        testLogOnlyMode();
        testClickOnlyMode();
        testRandomUiNoAction();
        testViewIdSkipButton();
        testSkipIntroNotClicked();
        testBlankSkipArea();
        testContinuePrompt();
        testForcedAdNoButton();
        testLiveShoppingAd();
        testMallAdRejectButton();
        testMallAdNoReject();
        testNormalPlaybackNotSwiped();
        testBlindSwipe();
        testBlindSwipeOff();

        System.out.println();
        System.out.println("==== 结果: " + passed + " 通过 / " + failed + " 失败 ====");
        if (failed > 0) {
            System.exit(1);
        }
    }

    // ---------------------------------------------------------------- 用例

    /** 场景1：“跳过 5” → “跳过 1” → “跳过”：倒计时结束后点击跳过按钮 */
    static void testSkipButtonCountdown() {
        AdDetector d = new AdDetector();
        DetectorConfig cfg = new DetectorConfig(0, 200);
        long t = 0;
        for (int n = 5; n >= 1; n--) {
            ScreenSnapshot s = base();
            addAdLabel(s, "广告");
            addSkip(s, "跳过 " + n, true);
            d.analyze(s, t, cfg);
            t += 500;
        }
        int action = 0;
        for (int i = 0; i < 6; i++) {
            ScreenSnapshot s = base();
            addAdLabel(s, "广告");
            addSkip(s, "跳过", true);
            action = d.analyze(s, t, cfg).action;
            if (action != 0) {
                break;
            }
            t += 200;
        }
        check("跳过按钮带倒计时 → 结束时点击", action == AdDetector.ACT_CLICK, action);
    }

    /** 场景2：只有 “5s” 独立读秒，结束后消失；默认不自动上滑 */
    static void testPlainCountdownThenGone() {
        AdDetector d = new AdDetector();
        DetectorConfig cfg = new DetectorConfig(0, 200);
        long t = 0;
        for (int n = 5; n >= 1; n--) {
            ScreenSnapshot s = base();
            addAdLabel(s, "广告");
            addText(s, n + "s", 880, 120, 1020, 200);
            d.analyze(s, t, cfg);
            t += 500;
        }
        int action = 0;
        for (int i = 0; i < 8; i++) {
            ScreenSnapshot s = base();
            addAdLabel(s, "广告");
            action = d.analyze(s, t, cfg).action;
            if (action != 0) {
                break;
            }
            t += 150;
        }
        check("独立读秒结束（默认）→ 不动作", action == AdDetector.ACT_NONE, action);
    }

    /** 场景2b：打开“没按钮时自动上滑”才滑 */
    static void testSwipeOnAdEndOptIn() {
        AdDetector d = new AdDetector();
        DetectorConfig cfg = new DetectorConfig(0, 200);
        cfg.swipeOnAdEnd = true;
        long t = 0;
        for (int n = 5; n >= 1; n--) {
            ScreenSnapshot s = base();
            addAdLabel(s, "广告");
            addText(s, n + "s", 880, 120, 1020, 200);
            d.analyze(s, t, cfg);
            t += 500;
        }
        int action = 0;
        for (int i = 0; i < 8; i++) {
            ScreenSnapshot s = base();
            addAdLabel(s, "广告");
            action = d.analyze(s, t, cfg).action;
            if (action != 0) {
                break;
            }
            t += 150;
        }
        check("独立读秒结束（开启自动上滑）→ 上滑", action == AdDetector.ACT_SWIPE, action);
    }

    /** 场景3：静态的 “30秒” 时长标签一直存在，随后消失：不能触发 */
    static void testStaticDurationMustNotTrigger() {
        AdDetector d = new AdDetector();
        DetectorConfig cfg = new DetectorConfig(0, 200);
        cfg.swipeOnAdEnd = true;
        long t = 0;
        int action = 0;
        for (int i = 0; i < 12; i++) {
            ScreenSnapshot s = base();
            addText(s, "30秒", 60, 2200, 220, 2280);
            action = d.analyze(s, t, cfg).action;
            t += 400;
        }
        for (int i = 0; i < 12; i++) {
            ScreenSnapshot s = base();
            action = d.analyze(s, t, cfg).action;
            if (action != 0) {
                break;
            }
            t += 200;
        }
        check("静态时长标签消失 → 不触发", action == AdDetector.ACT_NONE, action);
    }

    /** 场景4：只有一个逐帧递减的裸数字（没有“广告/跳过”字样） */
    static void testBareNumberCountdown() {
        AdDetector d = new AdDetector();
        DetectorConfig cfg = new DetectorConfig(0, 200);
        cfg.swipeOnAdEnd = true;
        long t = 0;
        for (int n = 8; n >= 2; n--) {
            ScreenSnapshot s = base();
            addText(s, String.valueOf(n), 940, 140, 1010, 210);
            d.analyze(s, t, cfg);
            t += 500;
        }
        int action = 0;
        for (int i = 0; i < 8; i++) {
            ScreenSnapshot s = base();
            action = d.analyze(s, t, cfg).action;
            if (action != 0) {
                break;
            }
            t += 150;
        }
        check("裸数字递减结束后 → 上滑", action == AdDetector.ACT_SWIPE, action);
    }

    /** 场景5：触发一次之后，冷却期内不能连续动作 */
    static void testCooldownPreventsDouble() {
        AdDetector d = new AdDetector();
        DetectorConfig cfg = new DetectorConfig(0, 200);
        cfg.swipeOnAdEnd = true;
        long t = 0;
        for (int n = 5; n >= 1; n--) {
            ScreenSnapshot s = base();
            addAdLabel(s, "广告");
            addText(s, n + "s", 880, 120, 1020, 200);
            d.analyze(s, t, cfg);
            t += 500;
        }
        int first = 0;
        for (int i = 0; i < 6; i++) {
            ScreenSnapshot s = base();
            addAdLabel(s, "广告");
            first = d.analyze(s, t, cfg).action;
            t += 150;
            if (first != 0) {
                break;
            }
        }
        int second = 0;
        for (int i = 0; i < 6; i++) {
            ScreenSnapshot s = base();
            addAdLabel(s, "广告");
            int a = d.analyze(s, t, cfg).action;
            if (a != 0) {
                second = a;
            }
            t += 150;
        }
        check("首次触发成功", first == AdDetector.ACT_SWIPE, first);
        check("冷却期内不重复触发", second == AdDetector.ACT_NONE, second);
    }

    /** 场景6：演练模式绝不动作 */
    static void testLogOnlyMode() {
        AdDetector d = new AdDetector();
        DetectorConfig cfg = new DetectorConfig(3, 200);
        long t = 0;
        int any = 0;
        for (int n = 5; n >= 1; n--) {
            ScreenSnapshot s = base();
            addSkip(s, "跳过 " + n, true);
            any |= d.analyze(s, t, cfg).action;
            t += 500;
        }
        for (int i = 0; i < 4; i++) {
            ScreenSnapshot s = base();
            addSkip(s, "跳过", true);
            any |= d.analyze(s, t, cfg).action;
            t += 200;
        }
        check("演练模式无动作", any == AdDetector.ACT_NONE, any);
    }

    /** 场景7：只点跳过模式，没有跳过按钮时不应当上滑 */
    static void testClickOnlyMode() {
        AdDetector d = new AdDetector();
        DetectorConfig cfg = new DetectorConfig(2, 200);
        cfg.swipeOnAdEnd = true;
        long t = 0;
        int any = 0;
        for (int n = 5; n >= 1; n--) {
            ScreenSnapshot s = base();
            addText(s, n + "s", 880, 120, 1020, 200);
            any |= d.analyze(s, t, cfg).action;
            t += 500;
        }
        for (int i = 0; i < 6; i++) {
            ScreenSnapshot s = base();
            any |= d.analyze(s, t, cfg).action;
            t += 150;
        }
        check("只点跳过模式：无按钮不动作", any == AdDetector.ACT_NONE, any);
    }

    /** 场景8：普通视频界面（点赞数、时间、标题）不能触发 */
    static void testRandomUiNoAction() {
        AdDetector d = new AdDetector();
        DetectorConfig cfg = new DetectorConfig(0, 200);
        cfg.swipeOnAdEnd = true;
        long t = 0;
        int any = 0;
        for (int i = 0; i < 30; i++) {
            ScreenSnapshot s = base();
            addText(s, "12.3万", 900, 1800, 1040, 1880);
            addText(s, "第 3 集", 60, 180, 300, 240);
            addText(s, "01:23", 900, 2300, 1040, 2360);
            addText(s, "热门短剧推荐", 60, 2100, 500, 2160);
            any |= d.analyze(s, t, cfg).action;
            t += 300;
        }
        check("普通界面不误触发", any == AdDetector.ACT_NONE, any);
    }

    /** 场景9：红果/穿山甲开屏广告——viewId 命中 + 没有读秒，应当稳定后点击 */
    static void testViewIdSkipButton() {
        AdDetector d = new AdDetector();
        DetectorConfig cfg = new DetectorConfig(0, 200);
        long t = 0;
        int action = 0;
        for (int i = 0; i < 8; i++) {
            ScreenSnapshot s = base();
            addText(s, "广告", 24, 100, 120, 160);
            ScreenSnapshot.Item it = addText(s, "跳过", 900, 90, 1050, 170);
            it.viewId = "com.phoenix.read:id/tt_splash_skip_btn";
            it.clickable = true;
            action = d.analyze(s, t, cfg).action;
            if (action != 0) {
                break;
            }
            t += 200;
        }
        check("viewId 命中跳过按钮 → 点击", action == AdDetector.ACT_CLICK, action);
    }

    /** 场景10：短剧的“跳过片头”绝不能被当成广告跳过 */
    static void testSkipIntroNotClicked() {
        AdDetector d = new AdDetector();
        DetectorConfig cfg = new DetectorConfig(0, 200);
        long t = 0;
        int any = 0;
        for (int i = 0; i < 25; i++) {
            ScreenSnapshot s = base();
            addText(s, "第 1 集", 60, 180, 300, 240);
            ScreenSnapshot.Item it = addText(s, "跳过片头", 860, 90, 1040, 170);
            it.clickable = true;
            any |= d.analyze(s, t, cfg).action;
            t += 200;
        }
        check("“跳过片头”不触发", any == AdDetector.ACT_NONE, any);
    }

    /** 场景11：广告 SDK 自绘的跳过按钮（没有文字，只有右上角一个小方块） */
    static void testBlankSkipArea() {
        AdDetector d = new AdDetector();
        DetectorConfig cfg = new DetectorConfig(0, 200);
        long t = 0;
        for (int n = 5; n >= 1; n--) {
            ScreenSnapshot s = base();
            addAdLabel(s, "广告");
            addText(s, n + "s", 500, 120, 620, 190);
            ScreenSnapshot.Item it = blankItem(s, 930, 110, 1050, 190);
            it.clickable = true;
            d.analyze(s, t, cfg);
            t += 400;
        }
        AdDetector.Decision hit = null;
        for (int i = 0; i < 8; i++) {
            ScreenSnapshot s = base();
            addAdLabel(s, "广告");
            ScreenSnapshot.Item it = blankItem(s, 930, 110, 1050, 190);
            it.clickable = true;
            AdDetector.Decision dec = d.analyze(s, t, cfg);
            if (dec.action != 0) {
                hit = dec;
                break;
            }
            t += 150;
        }
        boolean ok = hit != null && hit.action == AdDetector.ACT_CLICK && hit.blankTarget;
        check("无文字自绘按钮 → 结束时坐标点击", ok, hit == null ? 0 : hit.action);
    }

    /** 场景12：广告播完出现“上滑继续”，应当上滑（需要先有广告上下文） */
    static void testContinuePrompt() {
        AdDetector d = new AdDetector();
        DetectorConfig cfg = new DetectorConfig(0, 200);
        long t = 0;
        // 先来 3 帧带“广告”字样的画面，建立广告上下文
        for (int i = 0; i < 3; i++) {
            ScreenSnapshot s = base();
            addAdLabel(s, "广告");
            d.analyze(s, t, cfg);
            t += 200;
        }
        int action = 0;
        for (int i = 0; i < 12; i++) {
            ScreenSnapshot s = base();
            ScreenSnapshot.Item it = addText(s, "上滑继续", 400, 1900, 700, 1980);
            it.clickable = true;
            action = d.analyze(s, t, cfg).action;
            if (action != 0) {
                break;
            }
            t += 200;
        }
        check("广告上下文里出现“上滑继续” → 上滑", action == AdDetector.ACT_SWIPE, action);
    }

    /** 场景13：红果强制 5 秒中插广告（只有读秒，没有按钮）：不动作，只写日志 */
    static void testForcedAdNoButton() {
        AdDetector d = new AdDetector();
        DetectorConfig cfg = new DetectorConfig(0, 200);
        long t = 0;
        String lastReason = "";
        for (int n = 5; n >= 1; n--) {
            ScreenSnapshot s = base();
            addAdLabel(s, "广告");
            addText(s, n + "秒", 500, 110, 640, 190);
            lastReason = d.analyze(s, t, cfg).reason;
            t += 400;
        }
        for (int i = 0; i < 8; i++) {
            ScreenSnapshot s = base();
            addAdLabel(s, "广告");
            AdDetector.Decision dec = d.analyze(s, t, cfg);
            if (!dec.reason.isEmpty()) {
                lastReason = dec.reason;
            }
            if (dec.action != 0) {
                check("强制广告（无按钮）不动作", false, dec.action);
                return;
            }
            t += 150;
        }
        boolean ok = lastReason.contains("没找到跳过按钮") || lastReason.contains("倒计时结束");
        check("强制广告（无按钮）→ 只记录原因", ok, 0);
        System.out.println("      日志：" + lastReason);
    }

    // ---------------------------------------------------------------- 工具

    /** 场景14：红果直播间带货广告——没有跳过按钮，只有“点击进入直播间”+“上滑继续看短剧” */
    static void testLiveShoppingAd() {
        AdDetector d = new AdDetector();
        DetectorConfig cfg = new DetectorConfig(0, 200);
        long t = 0;
        int action = 0;
        for (int i = 0; i < 12; i++) {
            ScreenSnapshot s = base();
            addText(s, "直播中", 30, 1450, 150, 1520);
            addText(s, "点击进入直播间", 500, 1180, 900, 1260);
            addText(s, "上滑继续看短剧", 380, 2280, 700, 2350);
            action = d.analyze(s, t, cfg).action;
            if (action != 0) {
                break;
            }
            t += 200;
        }
        check("直播间带货广告（无跳过按钮）→ 上滑", action == AdDetector.ACT_SWIPE, action);
    }

    /** 场景15：商城推广卡片——有“不感兴趣”，应当点它，比上滑安全 */
    static void testMallAdRejectButton() {
        AdDetector d = new AdDetector();
        DetectorConfig cfg = new DetectorConfig(0, 200);
        long t = 0;
        AdDetector.Decision hit = null;
        for (int i = 0; i < 12; i++) {
            ScreenSnapshot s = base();
            addText(s, "商城好物省心选", 60, 1500, 700, 1580);
            ScreenSnapshot.Item rej = addText(s, "不感兴趣", 100, 1850, 450, 1930);
            rej.clickable = true;
            addText(s, "去商城购买", 600, 1850, 1000, 1930);
            addText(s, "上滑继续看短剧", 380, 2280, 700, 2350);
            AdDetector.Decision dec = d.analyze(s, t, cfg);
            if (dec.action != 0) {
                hit = dec;
                break;
            }
            t += 200;
        }
        boolean ok = hit != null && hit.action == AdDetector.ACT_CLICK
                && hit.reason.contains("不感兴趣");
        check("商城推广卡片 → 点“不感兴趣”", ok, hit == null ? 0 : hit.action);
    }

    /** 场景16：商城推广卡片但没有“不感兴趣”，只能上滑 */
    static void testMallAdNoReject() {
        AdDetector d = new AdDetector();
        DetectorConfig cfg = new DetectorConfig(0, 200);
        long t = 0;
        int action = 0;
        for (int i = 0; i < 12; i++) {
            ScreenSnapshot s = base();
            addText(s, "商城好物省心选", 60, 1500, 700, 1580);
            addText(s, "去商城购买", 600, 1850, 1000, 1930);
            addText(s, "图集 1/5", 900, 1000, 1040, 1070);
            addText(s, "上滑继续看短剧", 380, 2280, 700, 2350);
            action = d.analyze(s, t, cfg).action;
            if (action != 0) {
                break;
            }
            t += 200;
        }
        check("商城推广卡片（无“不感兴趣”）→ 上滑", action == AdDetector.ACT_SWIPE, action);
    }

    /** 场景17：正常追剧界面底部常驻“上滑继续看短剧”，绝不能因此上滑（防回归） */
    static void testNormalPlaybackNotSwiped() {
        AdDetector d = new AdDetector();
        DetectorConfig cfg = new DetectorConfig(0, 200);
        long t = 0;
        int any = 0;
        for (int i = 0; i < 40; i++) {
            ScreenSnapshot s = base();
            addText(s, "第 12 集", 60, 180, 300, 240);
            addText(s, "上滑继续看短剧", 380, 2280, 700, 2350);
            any |= d.analyze(s, t, cfg).action;
            t += 250;
        }
        check("正常追剧的“上滑继续看短剧”不触发", any == AdDetector.ACT_NONE, any);
    }

    /** 场景18：界面完全读不到 + 开启兜底 → 定时上滑 */
    static void testBlindSwipe() {
        AdDetector d = new AdDetector();
        DetectorConfig cfg = new DetectorConfig(0, 200);
        cfg.blindSwipeMs = 5000;
        long t = 0;
        int action = 0;
        for (int i = 0; i < 40; i++) {
            ScreenSnapshot s = base();
            action = d.analyze(s, t, cfg).action;
            if (action != 0) {
                break;
            }
            t += 300;
        }
        check("界面读不到 + 开启兜底 → 定时上滑", action == AdDetector.ACT_SWIPE, action);
    }

    /** 场景19：兜底默认关闭时，界面读不到也绝不动手 */
    static void testBlindSwipeOff() {
        AdDetector d = new AdDetector();
        DetectorConfig cfg = new DetectorConfig(0, 200);
        long t = 0;
        int any = 0;
        for (int i = 0; i < 40; i++) {
            ScreenSnapshot s = base();
            any |= d.analyze(s, t, cfg).action;
            t += 300;
        }
        check("界面读不到 + 兜底关闭 → 不动作", any == AdDetector.ACT_NONE, any);
    }

    // ---------------------------------------------------------------- 工具

    static ScreenSnapshot base() {
        ScreenSnapshot s = new ScreenSnapshot();
        s.width = W;
        s.height = H;
        s.packageName = "test";
        return s;
    }

    static ScreenSnapshot.Item addText(ScreenSnapshot s, String text, int l, int t, int r, int b) {
        ScreenSnapshot.Item it = new ScreenSnapshot.Item();
        it.text = text;
        it.left = l;
        it.top = t;
        it.right = r;
        it.bottom = b;
        it.enabled = true;
        it.visible = true;
        s.add(it);
        return it;
    }

    static ScreenSnapshot.Item blankItem(ScreenSnapshot s, int l, int t, int r, int b) {
        ScreenSnapshot.Item it = new ScreenSnapshot.Item();
        it.text = "";
        it.left = l;
        it.top = t;
        it.right = r;
        it.bottom = b;
        it.enabled = true;
        it.visible = true;
        s.add(it);
        return it;
    }

    static void addAdLabel(ScreenSnapshot s, String text) {
        addText(s, text, 24, 100, 120, 160);
    }

    static void addSkip(ScreenSnapshot s, String text, boolean clickable) {
        ScreenSnapshot.Item it = new ScreenSnapshot.Item();
        it.text = text;
        it.left = 900;
        it.top = 100;
        it.right = 1050;
        it.bottom = 170;
        it.clickable = clickable;
        it.enabled = true;
        it.visible = true;
        s.add(it);
    }

    static void check(String name, boolean ok, int actual) {
        if (ok) {
            passed++;
            System.out.println("[PASS] " + name);
        } else {
            failed++;
            System.out.println("[FAIL] " + name + "  (action=" + actual + ")");
        }
    }
}
