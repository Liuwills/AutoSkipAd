package com.autoskip.ad;

import java.util.ArrayList;
import java.util.List;

/**
 * 一次界面扫描的“文本快照”。
 *
 * <p>刻意不依赖任何 Android 类型（node 用 Object 存），这样检测逻辑可以在 JVM 上直接跑单元测试。
 *
 * <p>注意：除了带文字的节点，也会收录“没有文字但可点击”的小控件（自绘的跳过按钮/关闭叉号
 * 在很多广告 SDK 里就是一个没有 text、没有 contentDescription 的 View），否则完全看不到它们。
 */
public class ScreenSnapshot {

    public static class Item {
        public String text;
        public String viewId;
        public String className;
        public int left;
        public int top;
        public int right;
        public int bottom;
        public boolean clickable;
        public boolean enabled;
        public boolean visible = true;
        /** 节点所属窗口的包名，便于诊断广告是否在独立窗口里 */
        public String windowPackage;

        /** 实际类型是 AccessibilityNodeInfo，仅为可测试性声明为 Object */
        public Object node;

        public int width() {
            return right - left;
        }

        public int height() {
            return bottom - top;
        }

        public long area() {
            return (long) width() * (long) height();
        }

        public int cx() {
            return (left + right) / 2;
        }

        public int cy() {
            return (top + bottom) / 2;
        }

        public String plain() {
            return text == null ? "" : text.trim();
        }

        public boolean hasText() {
            return !plain().isEmpty();
        }
    }

    public final List<Item> items = new ArrayList<>();
    public int width;
    public int height;
    public String packageName;

    public void add(Item it) {
        if (items.size() < 2000) {
            items.add(it);
        }
    }

    public boolean isEmpty() {
        return items.isEmpty();
    }
}
