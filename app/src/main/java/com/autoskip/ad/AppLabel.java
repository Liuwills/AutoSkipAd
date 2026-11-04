package com.autoskip.ad;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;

/** 取应用显示名，取不到就回退到包名。 */
public final class AppLabel {

    private AppLabel() {
    }

    public static String of(Context ctx, String pkg) {
        if (pkg == null || pkg.isEmpty()) {
            return "未知应用";
        }
        try {
            PackageManager pm = ctx.getPackageManager();
            ApplicationInfo ai = pm.getApplicationInfo(pkg, 0);
            CharSequence label = pm.getApplicationLabel(ai);
            if (label != null && label.length() > 0) {
                return label.toString();
            }
        } catch (Throwable ignored) {
        }
        return pkg;
    }
}
