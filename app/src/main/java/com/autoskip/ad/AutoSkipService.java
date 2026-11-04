package com.autoskip.ad;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.accessibilityservice.GestureDescription;
import android.content.Context;
import android.graphics.Path;
import android.graphics.Rect;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.util.DisplayMetrics;
import android.view.Display;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;

import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.Date;
import java.util.Deque;
import java.util.List;
import java.util.Locale;

/**
 * 核心无障碍服务：轮询当前界面 → 抓取节点快照 → 交给 AdDetector 判定 → 执行点击/上滑。
 *
 * <p>关键改动（原版“事件驱动 + 实时读树”在红果上测不出东西）：
 * <ol>
 *   <li>加入 <b>心跳轮询</b>：广告期间画面只有视频帧在动，无障碍事件可能几秒都不发一次，
 *       只靠 {@link #onAccessibilityEvent} 会整段广告都扫不到；</li>
 *   <li>用 {@code getRootInActiveWindow().getPackageName()} 判断前台应用，不再靠事件里飘忽的包名；</li>
 *   <li>节点快照同时收录“没有文字但可点击”的小控件（自绘跳过按钮）；</li>
 *   <li>点击后 0.4 秒复查，按钮还在就补一次坐标点击；</li>
 *   <li>提供 {@link #dumpTree()} 供界面“抓取当前界面”按钮使用，用来确认目标 App 到底暴露了什么。</li>
 * </ol>
 */
public class AutoSkipService extends AccessibilityService {

    private static final int MAX_NODES = 800;
    /** 事件触发的扫描最短间隔 */
    private static final int MIN_SCAN_GAP_MS = 80;
    /** 广告场景中的轮询间隔 */
    private static final int POLL_FAST_MS = 200;
    /** 普通观看时的轮询间隔 */
    private static final int POLL_IDLE_MS = 650;
    /** 不在目标 App 时只做“看一眼包名”的廉价轮询 */
    private static final int POLL_OUTSIDE_MS = 1200;
    /** 广告结束后保持高频轮询的时间 */
    private static final int FAST_WINDOW_MS = 6000;

    private static volatile AutoSkipService sInstance;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final AdDetector detector = new AdDetector();
    private Prefs prefs;

    private boolean destroyed = false;
    private boolean pollScheduled = false;
    private boolean inTarget = false;
    private String targetPkg = "";
    private long lastScanAt = 0;
    private long fastUntil = 0;

    private String lastClickedText = "";
    private int lastTapX = 0;
    private int lastTapY = 0;
    private long lastClickAt = 0;

    /** 最近一次扫描 / 最近一次“广告场景”扫描的快照文本，供诊断使用 */
    private ScreenSnapshot lastSnap;
    private long lastSnapAt = 0;
    private String snapLabel = "";
    private String lastAdDump = "";
    private long lastAdDumpAt = 0;

    private final Runnable tickRunnable = new Runnable() {
        @Override
        public void run() {
            pollScheduled = false;
            tick();
        }
    };

    private final Runnable verifyRunnable = new Runnable() {
        @Override
        public void run() {
            verifyClick();
        }
    };

    public static AutoSkipService peek() {
        return sInstance;
    }

    // ------------------------------------------------------------------ 生命周期

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        sInstance = this;
        prefs = Prefs.get(this);

        AccessibilityServiceInfo info = getServiceInfo();
        if (info != null) {
            info.flags |= AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
                    | AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS
                    | AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS;
            info.notificationTimeout = 40;
            info.eventTypes = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
                    | AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
                    | AccessibilityEvent.TYPE_WINDOWS_CHANGED
                    | AccessibilityEvent.TYPE_VIEW_SCROLLED
                    | AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED
                    | AccessibilityEvent.TYPE_VIEW_CLICKED;
            try {
                setServiceInfo(info);
            } catch (Throwable ignored) {
            }
        }
        LogStore.add("无障碍服务已连接，开始轮询界面");
        schedulePoll(0);
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (destroyed || event == null || prefs == null) {
            return;
        }
        if (!prefs.isMasterEnabled()) {
            return;
        }
        CharSequence pkgCs = event.getPackageName();
        if (pkgCs != null && pkgCs.toString().equals(getPackageName())) {
            return;
        }
        // 事件只用来“催一次扫描”，前台应用是谁由 tick 里自己判断，避免被系统弹窗带偏
        long now = SystemClock.uptimeMillis();
        if (now - lastScanAt < MIN_SCAN_GAP_MS) {
            return;
        }
        handler.removeCallbacks(tickRunnable);
        pollScheduled = true;
        handler.post(tickRunnable);
    }

    @Override
    public void onInterrupt() {
    }

    @Override
    public void onDestroy() {
        destroyed = true;
        handler.removeCallbacksAndMessages(null);
        sInstance = null;
        super.onDestroy();
        LogStore.add("无障碍服务已断开");
    }

    // ------------------------------------------------------------------ 轮询主循环

    private void tick() {
        if (destroyed || prefs == null) {
            return;
        }
        if (!prefs.isMasterEnabled()) {
            // 总开关关闭：只保留低频兜底轮询，用户重新打开后能自己恢复
            schedulePoll(2000);
            return;
        }
        if (!isScreenOn()) {
            schedulePoll(1500);
            return;
        }

        String pkg = activePackage();
        boolean target = pkg != null && prefs.isPackageEnabled(pkg);
        if (!target) {
            if (inTarget) {
                inTarget = false;
                detector.reset();
                LogStore.add("离开 " + safeLabel(targetPkg) + "，暂停识别");
            }
            schedulePoll(POLL_OUTSIDE_MS);
            return;
        }

        if (!inTarget || !pkg.equals(targetPkg)) {
            if (!pkg.equals(targetPkg)) {
                targetPkg = pkg;
                detector.reset();
                LogStore.add("开始识别 " + safeLabel(pkg));
            }
            inTarget = true;
        }

        doScan();
        schedulePoll(SystemClock.uptimeMillis() < fastUntil ? POLL_FAST_MS : POLL_IDLE_MS);
    }

    private void schedulePoll(long delay) {
        if (destroyed || pollScheduled) {
            return;
        }
        pollScheduled = true;
        handler.postDelayed(tickRunnable, Math.max(0, delay));
    }

    private String activePackage() {
        try {
            AccessibilityNodeInfo root = getRootInActiveWindow();
            if (root != null) {
                CharSequence p = root.getPackageName();
                if (p != null && p.length() > 0) {
                    return p.toString();
                }
            }
        } catch (Throwable ignored) {
        }
        return targetPkg.isEmpty() ? null : targetPkg;
    }

    private String safeLabel(String pkg) {
        try {
            return AppLabel.of(this, pkg);
        } catch (Throwable t) {
            return pkg;
        }
    }

    private boolean isScreenOn() {
        try {
            PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
            return pm == null || pm.isInteractive();
        } catch (Throwable t) {
            return true;
        }
    }

    // ------------------------------------------------------------------ 扫描

    private void doScan() {
        long now = SystemClock.uptimeMillis();
        if (now - lastScanAt < MIN_SCAN_GAP_MS) {
            return;
        }
        lastScanAt = now;

        ScreenSnapshot snap = collect();
        if (snap.width <= 0 || snap.height <= 0) {
            return;
        }

        DetectorConfig cfg = buildConfig();
        AdDetector.Decision d = detector.analyze(snap, now, cfg);

        lastSnap = snap;
        lastSnapAt = now;
        snapLabel = new SimpleDateFormat("HH:mm:ss", Locale.CHINA).format(new Date());
        // 广告场景自动留一份快照：用手机排查时人已经切回本应用，现场早没了
        if (d.counting && now - lastAdDumpAt > 1500) {
            lastAdDump = formatSnapshot(snap, snapLabel);
            lastAdDumpAt = now;
        }

        if (d.counting) {
            fastUntil = now + FAST_WINDOW_MS;
        }
        if (!d.reason.isEmpty()) {
            fastUntil = now + FAST_WINDOW_MS;
        }

        if (prefs.isVerboseLog()) {
            LogStore.add("扫描 " + snap.items.size() + " 节点 → "
                    + (d.reason.isEmpty() ? "无动作" : d.reason));
        } else if (d.action == AdDetector.ACT_NONE && !d.reason.isEmpty()) {
            LogStore.add(d.reason);
        }

        switch (d.action) {
            case AdDetector.ACT_CLICK:
                performSkipClick(d);
                break;
            case AdDetector.ACT_SWIPE:
                LogStore.add(d.reason);
                doSwipeUp(0);
                break;
            default:
                break;
        }
    }

    private DetectorConfig buildConfig() {
        DetectorConfig cfg = new DetectorConfig(prefs.getMode(), prefs.getDelayMs());
        cfg.swipeOnAdEnd = prefs.isSwipeOnAdEnd();
        cfg.requireAdWord = prefs.isRequireAdWord();
        return cfg;
    }

    /**
     * 抓取当前界面：优先遍历所有窗口（广告浮层常常是独立的 Dialog/Popup 窗口），
     * 同时把“没有文字但可点击”的小控件也收进来。
     */
    private ScreenSnapshot collect() {
        ScreenSnapshot snap = new ScreenSnapshot();
        int[] size = screenSize();
        snap.width = size[0];
        snap.height = size[1];
        snap.packageName = targetPkg;

        boolean any = false;
        try {
            List<AccessibilityWindowInfo> windows = getWindows();
            if (windows != null) {
                for (AccessibilityWindowInfo w : windows) {
                    AccessibilityNodeInfo root = null;
                    try {
                        root = w.getRoot();
                    } catch (Throwable ignored) {
                    }
                    if (root != null) {
                        String wp = "";
                        try {
                            CharSequence p = root.getPackageName();
                            wp = p == null ? "" : p.toString();
                        } catch (Throwable ignored) {
                        }
                        walk(root, snap, wp);
                        any = true;
                    }
                }
            }
        } catch (Throwable ignored) {
        }

        if (!any) {
            try {
                AccessibilityNodeInfo root = getRootInActiveWindow();
                if (root != null) {
                    walk(root, snap, targetPkg);
                }
            } catch (Throwable ignored) {
            }
        }
        return snap;
    }

    private void walk(AccessibilityNodeInfo root, ScreenSnapshot out, String winPkg) {
        Deque<AccessibilityNodeInfo> queue = new ArrayDeque<>();
        queue.add(root);
        Rect r = new Rect();
        int visited = 0;
        final int screenH = out.height > 0 ? out.height : 1;

        while (!queue.isEmpty() && visited < MAX_NODES) {
            AccessibilityNodeInfo node = queue.poll();
            visited++;
            boolean keep = false;
            try {
                CharSequence text = node.getText();
                CharSequence desc = node.getContentDescription();
                CharSequence use = (text != null && text.length() > 0) ? text : desc;
                boolean hasText = use != null && use.length() > 0;
                boolean clickable = node.isClickable();

                if (hasText || clickable) {
                    node.getBoundsInScreen(r);
                    boolean visible = node.isVisibleToUser();
                    boolean sizeOk = (r.right - r.left) > 0 && (r.bottom - r.top) > 0;
                    // 无文字的节点只收“屏幕上半部分的小控件”，否则整屏容器会把队列塞满
                    boolean worthIt = hasText || r.bottom < screenH * 0.58f;
                    if (visible && sizeOk && worthIt) {
                        ScreenSnapshot.Item it = new ScreenSnapshot.Item();
                        it.text = hasText ? use.toString() : "";
                        it.viewId = node.getViewIdResourceName();
                        it.className = node.getClassName() == null ? "" : node.getClassName().toString();
                        it.left = r.left;
                        it.top = r.top;
                        it.right = r.right;
                        it.bottom = r.bottom;
                        it.clickable = clickable;
                        it.enabled = node.isEnabled();
                        it.visible = true;
                        it.windowPackage = winPkg;
                        it.node = node;
                        out.add(it);
                        keep = true;
                    }
                }

                int n = node.getChildCount();
                for (int i = 0; i < n; i++) {
                    AccessibilityNodeInfo c = node.getChild(i);
                    if (c != null) {
                        queue.add(c);
                    }
                }
            } catch (Throwable ignored) {
            } finally {
                if (!keep) {
                    // 不保留的节点及时回收，避免长时间轮询把 binder 池耗尽导致服务“悄悄死掉”
                    recycle(node);
                }
            }
        }
        while (!queue.isEmpty()) {
            recycle(queue.poll());
        }
    }

    private static void recycle(AccessibilityNodeInfo node) {
        if (node == null || Build.VERSION.SDK_INT >= 33) {
            return;
        }
        try {
            node.recycle();
        } catch (Throwable ignored) {
        }
    }

    // ------------------------------------------------------------------ 动作

    private void performSkipClick(AdDetector.Decision d) {
        AccessibilityNodeInfo target = (d.target instanceof AccessibilityNodeInfo)
                ? (AccessibilityNodeInfo) d.target : null;
        long now = SystemClock.uptimeMillis();

        boolean ok = false;
        if (!d.blankTarget && target != null) {
            ok = clickNodeOrParent(target, 2);
        }
        if (!ok && d.tapX > 0 && d.tapY > 0) {
            ok = tap(d.tapX, d.tapY);
            if (ok) {
                LogStore.add(d.reason + "（坐标点击）");
            }
        } else if (ok) {
            LogStore.add(d.reason);
        }

        if (ok) {
            vibrate();
        }
        lastClickAt = now;
        lastTapX = d.tapX;
        lastTapY = d.tapY;
        lastClickedText = skipTextOf(d);
        if (!lastClickedText.isEmpty()) {
            scheduleVerify();
        }
    }

    /** 从判定理由里取回按钮文字，用于点击后复查 */
    private String skipTextOf(AdDetector.Decision d) {
        String reason = d.reason;
        int a = reason.indexOf('“');
        int b = reason.lastIndexOf('”');
        if (a >= 0 && b > a + 1) {
            return reason.substring(a + 1, b);
        }
        return "";
    }

    private boolean clickNodeOrParent(AccessibilityNodeInfo node, int depth) {
        AccessibilityNodeInfo cur = node;
        Rect self = new Rect();
        try {
            node.getBoundsInScreen(self);
        } catch (Throwable ignored) {
        }
        long selfArea = (long) self.width() * self.height();
        int[] size = screenSize();
        long screenArea = (long) size[0] * size[1];

        for (int i = 0; i < depth && cur != null; i++) {
            try {
                if (cur.isClickable()) {
                    // 只允许点到“和按钮差不多大”的祖先；
                    // 广告根容器整屏可点击，点它等于打开广告落地页，比不点更糟。
                    Rect r = new Rect();
                    cur.getBoundsInScreen(r);
                    long area = (long) r.width() * r.height();
                    boolean sameSize = selfArea <= 0 || area <= selfArea * 4;
                    boolean notFullScreen = area < screenArea * 0.45;
                    if (sameSize && notFullScreen
                            && cur.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                        return true;
                    }
                }
                cur = cur.getParent();
            } catch (Throwable ignored) {
                return false;
            }
        }
        return false;
    }

    /** 点击后复查：按钮还在就补一次坐标点击（有些 SDK 不吃 ACTION_CLICK） */
    private void scheduleVerify() {
        handler.postDelayed(verifyRunnable, 420);
    }

    private void verifyClick() {
        if (destroyed || prefs == null || !prefs.isMasterEnabled()) {
            return;
        }
        if (lastClickedText.isEmpty() || SystemClock.uptimeMillis() - lastClickAt > 2500) {
            return;
        }
        ScreenSnapshot snap = collect();
        boolean stillThere = false;
        for (ScreenSnapshot.Item it : snap.items) {
            if (it.plain().equals(lastClickedText)) {
                stillThere = true;
                break;
            }
        }
        if (stillThere && lastTapX > 0 && lastTapY > 0) {
            if (tap(lastTapX, lastTapY)) {
                LogStore.add("跳过按钮仍在，补一次坐标点击");
            }
        }
    }

    public void doSwipeUp(int times) {
        int[] size = screenSize();
        int w = size[0];
        int h = size[1];
        float x = w / 2f;
        Path path = new Path();
        path.moveTo(x, h * 0.82f);
        path.lineTo(x + 2f, h * 0.70f);
        path.lineTo(x, h * 0.22f);

        GestureDescription.StrokeDescription stroke =
                new GestureDescription.StrokeDescription(path, 0, 240);
        GestureDescription gesture = new GestureDescription.Builder()
                .addStroke(stroke)
                .build();

        try {
            boolean sent = dispatchGesture(gesture, new GestureResultCallback() {
                @Override
                public void onCompleted(GestureDescription g) {
                    int next = times + 1;
                    if (next < prefs.getSwipeCount()) {
                        handler.postDelayed(() -> doSwipeUp(next), 240);
                    } else {
                        vibrate();
                    }
                }

                @Override
                public void onCancelled(GestureDescription g) {
                    LogStore.add("上滑手势被系统取消");
                }
            }, null);
            if (!sent) {
                LogStore.add("上滑未执行：手势被拒绝，请检查服务是否被系统限制");
            }
        } catch (Throwable t) {
            LogStore.add("上滑失败：" + t.getClass().getSimpleName());
        }
    }

    private boolean tap(int x, int y) {
        if (x <= 0 || y <= 0) {
            return false;
        }
        Path p = new Path();
        p.moveTo(x, y);
        GestureDescription g = new GestureDescription.Builder()
                .addStroke(new GestureDescription.StrokeDescription(p, 0, 48))
                .build();
        try {
            return dispatchGesture(g, null, null);
        } catch (Throwable t) {
            return false;
        }
    }

    private void vibrate() {
        if (prefs == null || !prefs.isVibrate()) {
            return;
        }
        try {
            Vibrator v = (Vibrator) getSystemService(Context.VIBRATOR_SERVICE);
            if (v != null && v.hasVibrator()) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    v.vibrate(VibrationEffect.createOneShot(28, VibrationEffect.DEFAULT_AMPLITUDE));
                } else {
                    v.vibrate(28);
                }
            }
        } catch (Throwable ignored) {
        }
    }

    @SuppressWarnings("deprecation")
    private int[] screenSize() {
        WindowManager wm = (WindowManager) getSystemService(Context.WINDOW_SERVICE);
        DisplayMetrics dm = new DisplayMetrics();
        if (wm != null) {
            Display d = wm.getDefaultDisplay();
            d.getRealMetrics(dm);
        }
        if (dm.widthPixels <= 0) {
            dm = getResources().getDisplayMetrics();
        }
        return new int[]{dm.widthPixels, dm.heightPixels};
    }

    // ------------------------------------------------------------------ 诊断

    /**
     * 诊断入口：优先返回“最近一次广告画面”的快照。
     *
     * <p>为什么不直接抓当前界面——用户要按按钮就得切回本应用，那时广告画面已经没了。
     * 所以这里回放服务在目标 App 里最后一次看到的界面。
     */
    public String dumpForDiagnosis() {
        long now = SystemClock.uptimeMillis();
        StringBuilder sb = new StringBuilder();
        if (!lastAdDump.isEmpty() && now - lastAdDumpAt < 10 * 60 * 1000L) {
            sb.append("==== 最近一次广告画面（服务自动留存）====\n");
            sb.append(lastAdDump);
            sb.append("\n==== 以下为当前实时界面 ====\n");
        } else if (lastSnap != null && now - lastSnapAt < 10 * 60 * 1000L) {
            sb.append("==== 最近一次在目标 App 里的画面 ====\n");
            sb.append(formatSnapshot(lastSnap, snapLabel));
            sb.append("\n==== 以下为当前实时界面 ====\n");
        }
        sb.append(dumpTree());
        return sb.toString();
    }

    private String formatSnapshot(ScreenSnapshot s, String timeLabel) {
        StringBuilder sb = new StringBuilder();
        sb.append("时间 ").append(timeLabel)
                .append("  包名 ").append(s.packageName)
                .append("  尺寸 ").append(s.width).append('×').append(s.height)
                .append("  节点 ").append(s.items.size()).append(" 个\n");
        int i = 0;
        for (ScreenSnapshot.Item it : s.items) {
            if (i >= 200) {
                sb.append("…（其余节点省略）\n");
                break;
            }
            i++;
            sb.append(i).append(") ");
            if (it.hasText()) {
                sb.append('"').append(cut(it.text, 40)).append('"');
            } else {
                sb.append("<无文字>");
            }
            String vid = it.viewId;
            if (vid != null && !vid.isEmpty()) {
                int k = vid.lastIndexOf('/');
                sb.append(" id=").append(k >= 0 ? vid.substring(k + 1) : vid);
            }
            sb.append(" [").append(it.left).append(',').append(it.top).append('-')
                    .append(it.right).append(',').append(it.bottom).append(']');
            if (it.clickable) {
                sb.append(" 可点");
            }
            if (!it.enabled) {
                sb.append(" 禁用");
            }
            if (it.windowPackage != null && !it.windowPackage.isEmpty()
                    && !it.windowPackage.equals(s.packageName)) {
                sb.append(" 窗口包名=").append(it.windowPackage);
            }
            sb.append('\n');
        }
        return sb.toString();
    }

    /**
     * 把当前界面的节点树导出成文本，用于确认目标 App 有没有对无障碍做隐藏。
     * 在「调试 → 抓取当前界面」里调用，出的内容可以直接复制出来。
     */
    public String dumpTree() {
        StringBuilder sb = new StringBuilder();
        try {
            int[] size = screenSize();
            sb.append("屏幕 ").append(size[0]).append("×").append(size[1]).append('\n');
            sb.append("前台包名：").append(String.valueOf(activePackage())).append('\n');
            AccessibilityServiceInfo info = getServiceInfo();
            if (info != null) {
                sb.append("服务 flags=0x").append(Integer.toHexString(info.flags))
                        .append(" eventTypes=0x").append(Integer.toHexString(info.eventTypes))
                        .append('\n');
            }
            sb.append("窗口能读取：").append(String.valueOf(canReadWindows())).append('\n');

            List<AccessibilityWindowInfo> windows = getWindows();
            if (windows == null || windows.isEmpty()) {
                sb.append("窗口列表为空（系统没有给到窗口信息）\n");
            } else {
                sb.append("窗口数：").append(windows.size()).append('\n');
                for (AccessibilityWindowInfo w : windows) {
                    AccessibilityNodeInfo root = null;
                    try {
                        root = w.getRoot();
                    } catch (Throwable ignored) {
                    }
                    if (root != null) {
                        sb.append("  #").append(w.getId())
                                .append(" type=").append(windowTypeName(w.getType()))
                                .append(" pkg=").append(String.valueOf(root.getPackageName()))
                                .append(" 子节点=").append(root.getChildCount()).append('\n');
                    } else {
                        sb.append("  #").append(w.getId())
                                .append(" type=").append(windowTypeName(w.getType()))
                                .append(" root=null（该窗口不给根节点）\n");
                    }
                }
            }

            AccessibilityNodeInfo root = getRootInActiveWindow();
            if (root == null) {
                sb.append("当前活动窗口根节点为 null —— 该界面没有向无障碍暴露内容\n");
                return sb.toString();
            }
            sb.append("---- 节点树 ----\n");
            dumpNode(root, sb, 0, 0);
        } catch (Throwable t) {
            sb.append("抓取异常：").append(t).append('\n');
        }
        return sb.toString();
    }

    private int dumpNode(AccessibilityNodeInfo node, StringBuilder sb, int depth, int count) {
        if (node == null || depth > 24 || count > 160) {
            return count;
        }
        count++;
        Rect r = new Rect();
        try {
            node.getBoundsInScreen(r);
            CharSequence text = node.getText();
            CharSequence desc = node.getContentDescription();
            for (int i = 0; i < depth; i++) {
                sb.append("  ");
            }
            sb.append(cls(node.getClassName()))
                    .append(" [").append(r.left).append(',').append(r.top).append('-')
                    .append(r.right).append(',').append(r.bottom).append(']');
            String vid = node.getViewIdResourceName();
            if (vid != null && !vid.isEmpty()) {
                sb.append(" id=").append(vid.substring(Math.max(0, vid.lastIndexOf('/') + 1)));
            }
            if (node.isClickable()) {
                sb.append(" clickable");
            }
            if (text != null && text.length() > 0) {
                sb.append(" text=\"").append(cut(text.toString(), 30)).append('"');
            }
            if (desc != null && desc.length() > 0) {
                sb.append(" desc=\"").append(cut(desc.toString(), 30)).append('"');
            }
            sb.append('\n');

            int n = node.getChildCount();
            for (int i = 0; i < n && count <= 160; i++) {
                try {
                    count = dumpNode(node.getChild(i), sb, depth + 1, count);
                } catch (Throwable ignored) {
                }
            }
        } catch (Throwable ignored) {
        }
        return count;
    }

    private boolean canReadWindows() {
        try {
            List<AccessibilityWindowInfo> list = getWindows();
            return list != null && !list.isEmpty();
        } catch (Throwable t) {
            return false;
        }
    }

    private static String windowTypeName(int type) {
        switch (type) {
            case AccessibilityWindowInfo.TYPE_APPLICATION:
                return "APP";
            case AccessibilityWindowInfo.TYPE_INPUT_METHOD:
                return "IME";
            case AccessibilityWindowInfo.TYPE_SYSTEM:
                return "SYSTEM";
            case AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY:
                return "A11Y_OVERLAY";
            case AccessibilityWindowInfo.TYPE_SPLIT_SCREEN_DIVIDER:
                return "SPLIT";
            default:
                return String.valueOf(type);
        }
    }

    private static String cls(CharSequence c) {
        if (c == null) {
            return "null";
        }
        String s = c.toString();
        int i = s.lastIndexOf('.');
        return i >= 0 ? s.substring(i + 1) : s;
    }

    private static String cut(String s, int n) {
        s = s.replace('\n', ' ');
        return s.length() > n ? s.substring(0, n) + "…" : s;
    }
}
