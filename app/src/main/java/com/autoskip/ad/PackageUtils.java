package com.autoskip.ad;

import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.drawable.Drawable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** 安装列表工具 */
public final class PackageUtils {

    public static class AppInfo {
        public String pkg;
        public String label;
        public Drawable icon;
        public boolean selected;
        public String sortKey;

        public String sortKey() {
            return sortKey == null ? pkg : sortKey;
        }
    }

    private PackageUtils() {
    }

    public static List<AppInfo> loadLaunchableApps(Context ctx) {
        PackageManager pm = ctx.getPackageManager();
        Intent main = new Intent(Intent.ACTION_MAIN, null);
        main.addCategory(Intent.CATEGORY_LAUNCHER);

        Set<String> selected = Prefs.get(ctx).getPackages();
        List<AppInfo> list = new ArrayList<>();
        Set<String> seen = new HashSet<>();

        List<ResolveInfo> ris = pm.queryIntentActivities(main, 0);
        for (ResolveInfo ri : ris) {
            ApplicationInfo ai = ri.activityInfo == null ? null : ri.activityInfo.applicationInfo;
            if (ai == null) {
                continue;
            }
            String pkg = ai.packageName;
            if (pkg == null || pkg.equals(ctx.getPackageName()) || !seen.add(pkg)) {
                continue;
            }
            AppInfo info = new AppInfo();
            info.pkg = pkg;
            CharSequence label = ai.loadLabel(pm);
            info.label = label == null ? pkg : label.toString();
            info.icon = ai.loadIcon(pm);
            info.selected = selected.contains(pkg);
            info.sortKey = info.label;
            list.add(info);
        }

        Collections.sort(list, new Comparator<AppInfo>() {
            @Override
            public int compare(AppInfo a, AppInfo b) {
                if (a.selected != b.selected) {
                    return a.selected ? -1 : 1;
                }
                return a.sortKey().compareToIgnoreCase(b.sortKey());
            }
        });
        return list;
    }
}
