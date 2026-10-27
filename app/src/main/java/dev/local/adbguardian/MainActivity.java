package dev.local.adbguardian;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Insets;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.View;
import android.view.WindowInsets;
import android.widget.Button;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;
import java.text.DateFormat;
import java.util.Date;

public final class MainActivity extends Activity {
    private final Handler handler = new Handler(Looper.getMainLooper());
    private GuardStore store;
    private final Runnable refresh = new Runnable() {
        @Override public void run() { render(); handler.postDelayed(this, 2_000); }
    };

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        store = new GuardStore(this);
        getWindow().setDecorFitsSystemWindows(false);
        setContentView(R.layout.activity_main);
        View root = findViewById(R.id.root);
        root.setOnApplyWindowInsetsListener((view, windowInsets) -> {
            Insets insets = windowInsets.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
            view.setPadding(insets.left, insets.top, insets.right, insets.bottom);
            return windowInsets;
        });
        findViewById(R.id.toggle).setOnClickListener(view -> toggle());
        findViewById(R.id.retry).setOnClickListener(view -> startGuard(true));
        findViewById(R.id.wireless_settings).setOnClickListener(view -> openWirelessSettings());
        findViewById(R.id.copy).setOnClickListener(view -> {
            getSystemService(ClipboardManager.class).setPrimaryClip(ClipData.newPlainText("ADB 授权", getString(R.string.permission_command)));
            Toast.makeText(this, R.string.copied, Toast.LENGTH_SHORT).show();
        });
        Switch awake = findViewById(R.id.keep_awake);
        awake.setChecked(store.keepAwake());
        awake.setOnCheckedChangeListener((button, checked) -> {
            store.setKeepAwake(checked);
            if (GuardService.running) startGuard(false);
        });
        findViewById(R.id.background).setOnClickListener(view -> new AlertDialog.Builder(this)
                .setTitle(R.string.background_dialog_title).setMessage(R.string.background_dialog_body)
                .setNegativeButton(R.string.cancel, null)
                .setPositiveButton(R.string.app_settings, (dialog, which) -> openSettings(
                        new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + getPackageName()))))
                .show());
    }

    private void toggle() {
        if (store.enabled()) {
            store.setEnabled(false);
            store.status("守护已停止", "自动恢复与加强保活已停止，现有无线调试设置保持不变。");
            stopService(new Intent(this, GuardService.class));
            render();
        } else {
            if (!DeviceState.permission(this)) {
                startGuard(false);
                return;
            }
            if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 1);
                return;
            }
            startGuard(false);
        }
    }

    @Override public void onRequestPermissionsResult(int code, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(code, permissions, results);
        if (code == 1) startGuard(false);
    }

    private void startGuard(boolean retry) {
        if (!DeviceState.permission(this)) {
            new AlertDialog.Builder(this).setMessage(R.string.permission_required).setPositiveButton(android.R.string.ok, null).show();
            return;
        }
        store.setEnabled(true);
        try {
            Intent intent = new Intent(this, GuardService.class);
            if (retry) intent.setAction(GuardService.ACTION_RETRY);
            startForegroundService(intent);
        } catch (SecurityException | IllegalStateException error) {
            store.status("无法启动守护", "请检查系统后台限制，并在本页面重新开始。");
        }
        handler.postDelayed(this::render, 150);
    }

    private void openWirelessSettings() {
        try { startActivity(new Intent("android.settings.WIRELESS_DEBUGGING_SETTINGS")); }
        catch (ActivityNotFoundException error) { openSettings(new Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS)); }
    }
    private void openSettings(Intent intent) {
        try { startActivity(intent); }
        catch (ActivityNotFoundException error) { Toast.makeText(this, R.string.settings_unavailable, Toast.LENGTH_LONG).show(); }
    }

    private void render() {
        boolean running = GuardService.running;
        String title = store.title();
        String detail = store.detail();
        if (!running && store.enabled()) {
            title = "守护当前未运行";
            detail = "系统可能限制了后台运行。请点击开始守护，并检查自启动设置。";
        }
        ((TextView) findViewById(R.id.status)).setText(title);
        ((TextView) findViewById(R.id.detail)).setText(detail);
        long last = store.checkedAt();
        ((TextView) findViewById(R.id.last_check)).setText(last == 0 ? "尚未检查" :
                "最近检查  " + DateFormat.getTimeInstance(DateFormat.MEDIUM).format(new Date(last)));
        String setting;
        try { setting = DeviceState.wirelessEnabled(this) ? "已开启" : "已关闭"; }
        catch (SecurityException error) { setting = "系统不允许读取"; }
        String facts = "授权  " + (DeviceState.permission(this) ? "已授予" : "未授予")
                + "    ·    Wi-Fi  " + (DeviceState.wifi(this) ? "已连接" : "未连接")
                + "\n无线调试开关  " + setting + "    ·    " + (DeviceState.charging(this) ? "正在接电" : "使用电池");
        ((TextView) findViewById(R.id.facts)).setText(facts);
        ((Button) findViewById(R.id.toggle)).setText(store.enabled() ? R.string.stop : R.string.start);
        Button retry = findViewById(R.id.retry);
        retry.setVisibility(store.blocked() || (store.enabled() && !running) ? View.VISIBLE : View.GONE);
        retry.setText(store.blocked() ? R.string.retry : R.string.restart);
        String events = store.events();
        ((TextView) findViewById(R.id.events)).setText(events.isEmpty() ? getString(R.string.no_events) : events);
    }
    @Override protected void onResume() { super.onResume(); handler.post(refresh); }
    @Override protected void onPause() { handler.removeCallbacksAndMessages(null); super.onPause(); }
}
