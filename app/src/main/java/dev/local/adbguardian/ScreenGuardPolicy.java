package dev.local.adbguardian;

import java.util.StringJoiner;

/** 屏幕保持的保守耗电策略；阈值不代表硬件安全保证。无 Android 依赖。 */
final class ScreenGuardPolicy {
    // ACTIVE 仅表示策略允许申请，不证明厂商系统实际执行了锁的效果。
    enum State { OFF, SCREEN_OFF, PROTECTION, UNSUPPORTED, ACTIVE, FAILURE,
        WAITING_UNLOCK, OVERLAY_PERMISSION, OVERLAY_FAILURE, OVERLAY_CLEANUP_FAILURE,
        REQUESTED_OVERLAY, REQUESTED_BACKGROUND_LIMITED }

    static final class Battery {
        final int level;
        final int scale;
        final Integer temperatureTenths;

        Battery(int level, int scale, Integer temperatureTenths) {
            this.level = level;
            this.scale = scale;
            this.temperatureTenths = temperatureTenths;
        }

        boolean known() {
            return scale > 0 && level >= 0 && level <= scale && temperatureTenths != null;
        }

        double percent() { return level * 100.0 / scale; }
    }

    // 初次启用也须达到恢复门槛，不能在滞回区直接取得锁。
    private boolean protectionPaused = true;
    private boolean vivoPulseClaimed;
    private long lastVivoPulseAt;

    // 复用 evaluate 的 ACTIVE，不另设电池/热阈值。由服务主线程在 acquire 前复查实时门槛。
    // 先占用限频额度，调用失败也不能被密集回调放大；关闭/暂停不会清除此时间。
    boolean claimVivoPulse(String manufacturer, boolean overlayEnabled, State evaluated,
            boolean enabled, boolean interactive, boolean unlocked, boolean overlayAvailable,
            long elapsedRealtime) {
        if (!"vivo".equalsIgnoreCase(manufacturer) || !overlayEnabled || evaluated != State.ACTIVE
                || !enabled || !interactive || !unlocked || !overlayAvailable) return false;
        if (vivoPulseClaimed && elapsedRealtime - lastVivoPulseAt < 30_000) return false;
        vivoPulseClaimed = true;
        lastVivoPulseAt = elapsedRealtime;
        return true;
    }

    // enabled 由运行中的服务及用户选项决定；无线网络/调试恢复状态不属于屏幕策略输入。
    State evaluate(boolean enabled, boolean interactive, boolean supported,
            Battery battery, int thermalStatus) {
        if (!enabled) {
            protectionPaused = true;
            return State.OFF;
        }
        if (battery == null || !battery.known() || thermalStatus < 0 || thermalStatus > 6) {
            protectionPaused = true;
        } else if (battery.percent() <= 20 || battery.temperatureTenths >= 400 || thermalStatus >= 2) {
            protectionPaused = true;
        } else if (battery.percent() >= 25 && battery.temperatureTenths <= 380 && thermalStatus <= 1) {
            protectionPaused = false;
        }
        if (!supported) return State.UNSUPPORTED;
        if (!interactive) return State.SCREEN_OFF;
        return protectionPaused ? State.PROTECTION : State.ACTIVE;
    }

    // 仅在 PROTECTION 时显示；直接说明仍未达到的恢复门槛，包含未知数据。
    static String protectionReason(Battery battery, int thermalStatus) {
        StringJoiner reason = new StringJoiner("；");
        if (battery == null || battery.scale <= 0 || battery.level < 0 || battery.level > battery.scale) {
            reason.add("电量读数不可用");
        } else if (battery.percent() < 25) {
            reason.add("等待电量≥25%");
        }
        if (battery == null || battery.temperatureTenths == null) {
            reason.add("电池温度读数不可用");
        } else if (battery.temperatureTenths > 380) {
            reason.add("等待电池温度≤38℃");
        }
        if (thermalStatus < 0 || thermalStatus > 6) {
            reason.add("系统热状态不可用");
        } else if (thermalStatus > 1) {
            reason.add("等待系统热状态≤轻度（1）");
        }
        return reason.toString();
    }
}
