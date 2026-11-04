package com.autoskip.ad;

import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.graphics.Typeface;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.TextUtils;
import android.view.View;
import android.view.accessibility.AccessibilityManager;
import android.widget.RadioGroup;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.switchmaterial.SwitchMaterial;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

public class MainActivity extends AppCompatActivity {

    private Prefs prefs;

    private View dotService;
    private TextView tvServiceStatus;
    private TextView tvServiceHint;
    private MaterialButton btnOpenSettings;
    private SwitchMaterial swMaster;
    private RadioGroup rgMode;
    private SeekBar sbDelay;
    private SeekBar sbSwipe;
    private TextView tvDelayValue;
    private TextView tvSwipeValue;
    private SwitchMaterial swVibrate;
    private SwitchMaterial swVerbose;
    private SwitchMaterial swSwipeEnd;
    private TextView tvAppsTitle;
    private TextView tvLog;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable logTicker = new Runnable() {
        @Override
        public void run() {
            refreshLog();
            handler.postDelayed(this, 800);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        prefs = Prefs.get(this);

        dotService = findViewById(R.id.dotService);
        tvServiceStatus = findViewById(R.id.tvServiceStatus);
        tvServiceHint = findViewById(R.id.tvServiceHint);
        btnOpenSettings = findViewById(R.id.btnOpenSettings);
        swMaster = findViewById(R.id.swMaster);
        rgMode = findViewById(R.id.rgMode);
        sbDelay = findViewById(R.id.sbDelay);
        sbSwipe = findViewById(R.id.sbSwipe);
        tvDelayValue = findViewById(R.id.tvDelayValue);
        tvSwipeValue = findViewById(R.id.tvSwipeValue);
        swVibrate = findViewById(R.id.swVibrate);
        swVerbose = findViewById(R.id.swVerbose);
        swSwipeEnd = findViewById(R.id.swSwipeEnd);
        tvAppsTitle = findViewById(R.id.tvAppsTitle);
        tvLog = findViewById(R.id.tvLog);

        bindValues();
        bindListeners();
    }

    private void bindValues() {
        swMaster.setChecked(prefs.isMasterEnabled());
        swVibrate.setChecked(prefs.isVibrate());
        swVerbose.setChecked(prefs.isVerboseLog());
        swSwipeEnd.setChecked(prefs.isSwipeOnAdEnd());

        switch (prefs.getMode()) {
            case Prefs.MODE_COUNTDOWN_ONLY:
                rgMode.check(R.id.rbCountdown);
                break;
            case Prefs.MODE_CLICK_ONLY:
                rgMode.check(R.id.rbClick);
                break;
            case Prefs.MODE_LOG_ONLY:
                rgMode.check(R.id.rbLog);
                break;
            default:
                rgMode.check(R.id.rbSmart);
                break;
        }

        sbDelay.setProgress(prefs.getDelayMs());
        tvDelayValue.setText(prefs.getDelayMs() + " ms");

        sbSwipe.setProgress(prefs.getSwipeCount() - 1);
        tvSwipeValue.setText(prefs.getSwipeCount() + " 次");

        refreshAppsSummary();
    }

    private void bindListeners() {
        btnOpenSettings.setOnClickListener(v -> openAccessibilitySettings());

        swMaster.setOnCheckedChangeListener((b, checked) -> {
            prefs.setMasterEnabled(checked);
            if (checked) {
                LogStore.add("已启用自动跳过");
            } else {
                LogStore.add("已暂停");
            }
        });

        swVibrate.setOnCheckedChangeListener((b, checked) -> prefs.setVibrate(checked));
        swVerbose.setOnCheckedChangeListener((b, checked) -> prefs.setVerboseLog(checked));
        swSwipeEnd.setOnCheckedChangeListener((b, checked) -> prefs.setSwipeOnAdEnd(checked));

        rgMode.setOnCheckedChangeListener((group, checkedId) -> {
            int mode = Prefs.MODE_SMART;
            if (checkedId == R.id.rbCountdown) {
                mode = Prefs.MODE_COUNTDOWN_ONLY;
            } else if (checkedId == R.id.rbClick) {
                mode = Prefs.MODE_CLICK_ONLY;
            } else if (checkedId == R.id.rbLog) {
                mode = Prefs.MODE_LOG_ONLY;
            }
            prefs.setMode(mode);
        });

        sbDelay.setOnSeekBarChangeListener(new SimpleSeek() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                int v = Math.round(progress / 50f) * 50;
                tvDelayValue.setText(v + " ms");
                if (fromUser) {
                    prefs.setDelayMs(v);
                }
            }
        });

        sbSwipe.setOnSeekBarChangeListener(new SimpleSeek() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                int v = progress + 1;
                tvSwipeValue.setText(v + " 次");
                if (fromUser) {
                    prefs.setSwipeCount(v);
                }
            }
        });

        findViewById(R.id.rowApps).setOnClickListener(v ->
                startActivity(new Intent(this, AppListActivity.class)));

        findViewById(R.id.btnTestSwipe).setOnClickListener(v -> {
            AutoSkipService svc = AutoSkipService.peek();
            if (svc == null) {
                Toast.makeText(this, R.string.need_service, Toast.LENGTH_SHORT).show();
                return;
            }
            svc.doSwipeUp(0);
            Toast.makeText(this, R.string.test_done, Toast.LENGTH_SHORT).show();
        });

        findViewById(R.id.btnClearLog).setOnClickListener(v -> {
            LogStore.clear();
            refreshLog();
        });

        findViewById(R.id.btnDump).setOnClickListener(v -> dumpCurrentScreen());

        findViewById(R.id.btnCopyLog).setOnClickListener(v -> {
            String text = LogStore.dump();
            if (copy(text)) {
                Toast.makeText(this, R.string.copy_done, Toast.LENGTH_SHORT).show();
            }
        });
    }

    /** 抓取当前界面节点树：用来确认目标 App 有没有把内容藏起来 */
    private void dumpCurrentScreen() {
        AutoSkipService svc = AutoSkipService.peek();
        if (svc == null) {
            Toast.makeText(this, R.string.need_service, Toast.LENGTH_SHORT).show();
            return;
        }
        String text = svc.dumpForDiagnosis();
        if (text == null || text.isEmpty()) {
            Toast.makeText(this, R.string.dump_need_service, Toast.LENGTH_SHORT).show();
            return;
        }

        float d = getResources().getDisplayMetrics().density;
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextSize(10f);
        tv.setTypeface(Typeface.MONOSPACE);
        tv.setTextIsSelectable(true);
        tv.setPadding((int) (14 * d), (int) (10 * d), (int) (14 * d), (int) (10 * d));

        ScrollView sc = new ScrollView(this);
        sc.addView(tv);

        new AlertDialog.Builder(this)
                .setTitle(R.string.btn_dump)
                .setView(sc)
                .setPositiveButton(R.string.btn_copy_log, (dlg, w) -> {
                    if (copy(text)) {
                        Toast.makeText(this, R.string.copy_done, Toast.LENGTH_SHORT).show();
                    }
                })
                .setNegativeButton("关闭", null)
                .show();
    }

    private boolean copy(String text) {
        if (text == null || text.isEmpty()) {
            return false;
        }
        try {
            ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            if (cm == null) {
                return false;
            }
            cm.setPrimaryClip(ClipData.newPlainText("AutoSkip", text));
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        updateServiceState();
        refreshAppsSummary();
        refreshLog();
        handler.removeCallbacks(logTicker);
        handler.post(logTicker);
    }

    @Override
    protected void onPause() {
        super.onPause();
        handler.removeCallbacks(logTicker);
    }

    private void updateServiceState() {
        boolean on = isAccessibilityServiceEnabled();
        dotService.setBackgroundResource(on ? R.drawable.dot_on : R.drawable.dot_off);
        tvServiceStatus.setText(on ? R.string.service_on : R.string.service_off);
        tvServiceHint.setText(on ? R.string.service_on_hint : R.string.service_off_hint);
        btnOpenSettings.setText(on ? R.string.btn_opened : R.string.btn_open_settings);
    }

    private void refreshAppsSummary() {
        Set<String> pkgs = prefs.getPackages();
        int n = pkgs.size();
        if (n == 0) {
            tvAppsTitle.setText(R.string.apps_none);
            return;
        }
        List<String> names = new ArrayList<>();
        for (String p : pkgs) {
            if (names.size() >= 2) {
                break;
            }
            names.add(AppLabel.of(this, p));
        }
        String joined = TextUtils.join("、", names);
        if (n > names.size()) {
            tvAppsTitle.setText(joined + " 等 " + n + " 个应用");
        } else {
            tvAppsTitle.setText(joined + "（" + n + " 个）");
        }
    }

    private void refreshLog() {
        String text = LogStore.dump();
        if (text.isEmpty()) {
            tvLog.setText(R.string.log_empty);
        } else {
            tvLog.setText(text);
        }
    }

    private boolean isAccessibilityServiceEnabled() {
        try {
            AccessibilityManager am = (AccessibilityManager) getSystemService(ACCESSIBILITY_SERVICE);
            if (am == null) {
                return false;
            }
            String prefix = getPackageName() + "/" + AutoSkipService.class.getName();
            List<android.accessibilityservice.AccessibilityServiceInfo> list =
                    am.getEnabledAccessibilityServiceList(android.accessibilityservice.AccessibilityServiceInfo.FEEDBACK_ALL_MASK);
            for (android.accessibilityservice.AccessibilityServiceInfo info : list) {
                String id = info.getId();
                if (id != null && (id.startsWith(prefix) || id.contains(AutoSkipService.class.getSimpleName()))) {
                    return true;
                }
            }
        } catch (Throwable ignored) {
        }
        return AutoSkipService.peek() != null;
    }

    private void openAccessibilitySettings() {
        try {
            Intent i = new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS);
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(i);
        } catch (Throwable t) {
            Toast.makeText(this, "无法打开系统设置，请手动进入 设置 > 无障碍", Toast.LENGTH_LONG).show();
        }
    }

    /** 只关心进度变化的 SeekBar 适配 */
    private abstract static class SimpleSeek implements SeekBar.OnSeekBarChangeListener {
        @Override
        public void onStartTrackingTouch(SeekBar seekBar) {
        }

        @Override
        public void onStopTrackingTouch(SeekBar seekBar) {
        }
    }
}
