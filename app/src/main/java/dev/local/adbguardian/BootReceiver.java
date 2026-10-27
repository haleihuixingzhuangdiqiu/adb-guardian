package dev.local.adbguardian;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

public final class BootReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context context, Intent intent) {
        String action = intent.getAction();
        if (!Intent.ACTION_BOOT_COMPLETED.equals(action) && !Intent.ACTION_MY_PACKAGE_REPLACED.equals(action)) return;
        GuardStore store = new GuardStore(context);
        if (!store.enabled()) return;
        try {
            context.startForegroundService(new Intent(context, GuardService.class));
        } catch (SecurityException | IllegalStateException error) {
            store.status("开机恢复被系统限制", "请打开应用重新开始，并在系统中允许自启动和后台运行。");
        }
    }
}
