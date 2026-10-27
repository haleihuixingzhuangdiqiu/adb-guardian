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
        Intent battery = context.registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
        return battery != null && battery.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0;
    }
}
