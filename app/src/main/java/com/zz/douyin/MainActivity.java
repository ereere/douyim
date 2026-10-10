package com.zz.douyin;

import android.app.Activity;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;

import io.github.libxposed.service.XposedService;

public final class MainActivity extends Activity
        implements ModuleApplication.ServiceStateListener {
    private static final int BACKGROUND = Color.rgb(16, 17, 20);
    private static final int CARD = Color.rgb(28, 29, 34);
    private static final int PRIMARY = Color.rgb(254, 44, 85);
    private static final int TEXT_PRIMARY = Color.rgb(245, 245, 248);
    private static final int TEXT_SECONDARY = Color.rgb(169, 171, 180);

    private TextView serviceStatus;
    private Switch immersiveEnabled;
    private SharedPreferences preferences;
    private boolean loading = true;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(buildContent());
        setControlsEnabled(false);
    }

    @Override
    protected void onStart() {
        super.onStart();
        ModuleApplication.addServiceStateListener(this, true);
    }

    @Override
    protected void onStop() {
        ModuleApplication.removeServiceStateListener(this);
        super.onStop();
    }

    @Override
    public void onServiceStateChanged(XposedService service) {
        runOnUiThread(() -> bindPreferences(service));
    }

    private View buildContent() {
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(BACKGROUND);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(20), dp(32), dp(20), dp(32));
        scroll.addView(root, matchWrap());

        TextView title = text("抖音沉浸播放", 28, TEXT_PRIMARY);
        title.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        root.addView(title, matchWrap());

        TextView subtitle = text("沉浸式播放设置", 15, PRIMARY);
        LinearLayout.LayoutParams subtitleParams = matchWrap();
        subtitleParams.topMargin = dp(4);
        root.addView(subtitle, subtitleParams);

        serviceStatus = text("正在连接 LSPosed 服务…", 13, TEXT_SECONDARY);
        LinearLayout.LayoutParams statusParams = matchWrap();
        statusParams.topMargin = dp(16);
        root.addView(serviceStatus, statusParams);

        LinearLayout playbackCard = card();
        immersiveEnabled = addSwitch(playbackCard, "沉浸式播放",
                "播放视频时只保留视频画面并隐藏系统栏，可用悬浮“退/进”按钮随时切换",
                ModulePreferences.KEY_IMMERSIVE_ENABLED);
        LinearLayout.LayoutParams playbackCardParams = matchWrap();
        playbackCardParams.topMargin = dp(20);
        root.addView(playbackCard, playbackCardParams);

        TextView footer = text(
                "设置保存后会同步给抖音进程。若抖音已在后台运行但未立即生效，请强制停止后重新打开。",
                13,
                TEXT_SECONDARY
        );
        footer.setLineSpacing(0, 1.25f);
        LinearLayout.LayoutParams footerParams = matchWrap();
        footerParams.topMargin = dp(20);
        root.addView(footer, footerParams);
        return scroll;
    }

    private void bindPreferences(XposedService service) {
        loading = true;
        if (service == null) {
            preferences = null;
            serviceStatus.setText("未连接 LSPosed 服务，设置暂不可修改");
            serviceStatus.setTextColor(Color.rgb(255, 166, 77));
            setControlsEnabled(false);
            loading = false;
            return;
        }

        preferences = service.getRemotePreferences(ModulePreferences.NAME);
        immersiveEnabled.setChecked(ModulePreferences.readImmersiveEnabled(preferences));
        serviceStatus.setText(
                "已连接 " + service.getFrameworkName()
                        + " · API " + service.getApiVersion()
        );
        serviceStatus.setTextColor(Color.rgb(84, 214, 142));
        setControlsEnabled(true);
        loading = false;
    }

    private Switch addSwitch(
            LinearLayout parent,
            String label,
            String description,
            String preferenceKey
    ) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(16), dp(14), dp(12), dp(14));

        LinearLayout copy = new LinearLayout(this);
        copy.setOrientation(LinearLayout.VERTICAL);
        TextView labelView = text(label, 17, TEXT_PRIMARY);
        labelView.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        copy.addView(labelView, matchWrap());
        TextView descriptionView = text(description, 13, TEXT_SECONDARY);
        descriptionView.setLineSpacing(0, 1.15f);
        LinearLayout.LayoutParams descriptionParams = matchWrap();
        descriptionParams.topMargin = dp(3);
        copy.addView(descriptionView, descriptionParams);
        row.addView(copy, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        Switch toggle = new Switch(this);
        toggle.setContentDescription(label);
        toggle.setShowText(false);
        toggle.setButtonTintList(null);
        toggle.setOnCheckedChangeListener((button, checked) -> {
            if (!loading) {
                saveBoolean(preferenceKey, checked);
            }
        });
        row.addView(toggle, wrapWrap());
        parent.addView(row, matchWrap());
        return toggle;
    }

    private void saveBoolean(String key, boolean value) {
        SharedPreferences current = preferences;
        if (current == null) {
            return;
        }
        SharedPreferences.Editor editor = current.edit();
        if (editor != null) {
            editor.putBoolean(key, value).apply();
        }
    }

    private void setControlsEnabled(boolean enabled) {
        immersiveEnabled.setEnabled(enabled);
    }

    private LinearLayout card() {
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(dp(2), dp(2), dp(2), dp(2));
        layout.setBackground(rounded(CARD, 16));
        return layout;
    }

    private TextView text(CharSequence value, float size, int color) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(size);
        view.setTextColor(color);
        return view;
    }

    private GradientDrawable rounded(int color, int radiusDp) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(color);
        drawable.setCornerRadius(dp(radiusDp));
        return drawable;
    }

    private static LinearLayout.LayoutParams matchWrap() {
        return new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        );
    }

    private static LinearLayout.LayoutParams wrapWrap() {
        return new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        );
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
