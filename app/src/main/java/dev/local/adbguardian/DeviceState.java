package dev.local.adbguardian;

import android.Manifest;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.os.BatteryManager;
import android.provider.Settings;

final class DeviceState {
    // AOSP 的隐藏设置键；OEM 可能限制读写，必须显式报告，不能退回修改认证或开放明文端口。
    static final String WIFI_DEBUG_SETTING = "adb_wifi_enabled";
    static boolean permission(Context context) {
        return context.checkSelfPermission(Manifest.permission.WRITE_SECURE_SETTINGS) == PackageManager.PERMISSION_GRANTED;
    }
    static boolean wirelessEnabled(Context context) {
        return Settings.Global.getInt(context.getContentResolver(), WIFI_DEBUG_SETTING, 0) == 1;
    }
    static boolean wifi(Context context) {
        ConnectivityManager manager = context.getSystemService(ConnectivityManager.class);
        // 不要求公网连通；隔离测试局域网同样可以承载 ADB。也不把 VPN 默认网络误判为 Wi-Fi 离线。
        for (Network network : manager.getAllNetworks()) {
            NetworkCapabilities caps = manager.getNetworkCapabilities(network);
            if (caps != null && caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) return true;
        }
        return false;
    }
    static boolean charging(Context context) {
        return battery(context).charging;
    }
    static final class BatterySnapshot {
        final boolean charging;
        final ScreenGuardPolicy.Battery screenBattery;
        BatterySnapshot(boolean charging, ScreenGuardPolicy.Battery screenBattery) {
            this.charging = charging;
            this.screenBattery = screenBattery;
        }
    }
    static BatterySnapshot battery(Context context) {
        Intent battery = context.registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
        if (battery == null) return new BatterySnapshot(false, null);
        Integer temperature = battery.hasExtra(BatteryManager.EXTRA_TEMPERATURE)
                ? battery.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) : null;
        return new BatterySnapshot(battery.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0,
                new ScreenGuardPolicy.Battery(battery.getIntExtra(BatteryManager.EXTRA_LEVEL, -1),
                        battery.getIntExtra(BatteryManager.EXTRA_SCALE, -1), temperature));
    }
}
