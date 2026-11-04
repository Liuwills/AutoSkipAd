package com.autoskip.ad;

import android.os.Bundle;
import android.text.Editable;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.appbar.MaterialToolbar;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

public class AppListActivity extends AppCompatActivity {

    private Prefs prefs;
    private final List<PackageUtils.AppInfo> all = new ArrayList<>();
    private final List<PackageUtils.AppInfo> shown = new ArrayList<>();
    private Adapter adapter;
    private TextView tvTip;
    private String keyword = "";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_app_list);
        prefs = Prefs.get(this);

        MaterialToolbar toolbar = findViewById(R.id.toolbar);
        toolbar.setNavigationOnClickListener(v -> finish());

        tvTip = findViewById(R.id.tvTip);
        RecyclerView rv = findViewById(R.id.rv);
        rv.setLayoutManager(new LinearLayoutManager(this));
        adapter = new Adapter();
        rv.setAdapter(adapter);

        EditText etSearch = findViewById(R.id.etSearch);
        etSearch.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int a, int b, int c) {
            }

            @Override
            public void onTextChanged(CharSequence s, int a, int b, int c) {
                keyword = s == null ? "" : s.toString().trim().toLowerCase(Locale.ROOT);
                applyFilter();
            }

            @Override
            public void afterTextChanged(Editable s) {
            }
        });

        findViewById(R.id.btnAll).setOnClickListener(v -> {
            for (PackageUtils.AppInfo a : all) {
                a.selected = true;
                prefs.setPackageEnabled(a.pkg, true);
            }
            adapter.notifyDataSetChanged();
            updateTip();
        });

        findViewById(R.id.btnNone).setOnClickListener(v -> {
            for (PackageUtils.AppInfo a : all) {
                a.selected = false;
                prefs.setPackageEnabled(a.pkg, false);
            }
            adapter.notifyDataSetChanged();
            updateTip();
        });

        loadAsync();
    }

    private void loadAsync() {
        tvTip.setText("正在读取应用列表…");
        new Thread(() -> {
            List<PackageUtils.AppInfo> list = PackageUtils.loadLaunchableApps(getApplicationContext());
            runOnUiThread(() -> {
                all.clear();
                all.addAll(list);
                applyFilter();
            });
        }).start();
    }

    private void applyFilter() {
        shown.clear();
        for (PackageUtils.AppInfo a : all) {
            if (TextUtils.isEmpty(keyword)
                    || a.label.toLowerCase(Locale.ROOT).contains(keyword)
                    || a.pkg.toLowerCase(Locale.ROOT).contains(keyword)) {
                shown.add(a);
            }
        }
        adapter.notifyDataSetChanged();
        updateTip();
    }

    private void updateTip() {
        Set<String> sel = prefs.getPackages();
        int total = all.size();
        if (total == 0) {
            tvTip.setText("没有读取到应用");
            return;
        }
        tvTip.setText("共 " + total + " 个应用，已选择 " + sel.size() + " 个");
    }

    private class Adapter extends RecyclerView.Adapter<Adapter.VH> {

        class VH extends RecyclerView.ViewHolder {
            ImageView iv;
            TextView name;
            TextView pkg;
            CheckBox cb;

            VH(@NonNull View v) {
                super(v);
                iv = v.findViewById(R.id.ivIcon);
                name = v.findViewById(R.id.tvName);
                pkg = v.findViewById(R.id.tvPkg);
                cb = v.findViewById(R.id.cb);
            }
        }

        @NonNull
        @Override
        public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View v = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_app, parent, false);
            return new VH(v);
        }

        @Override
        public void onBindViewHolder(@NonNull VH h, int position) {
            PackageUtils.AppInfo a = shown.get(position);
            h.name.setText(a.label);
            h.pkg.setText(a.pkg);
            h.cb.setChecked(a.selected);
            if (a.icon != null) {
                h.iv.setImageDrawable(a.icon);
            } else {
                h.iv.setImageResource(R.drawable.ic_launcher_foreground);
            }
            View.OnClickListener toggle = v -> {
                a.selected = !a.selected;
                h.cb.setChecked(a.selected);
                prefs.setPackageEnabled(a.pkg, a.selected);
                updateTip();
            };
            h.itemView.setOnClickListener(toggle);
            h.cb.setOnClickListener(toggle);
        }

        @Override
        public int getItemCount() {
            return shown.size();
        }
    }
}
