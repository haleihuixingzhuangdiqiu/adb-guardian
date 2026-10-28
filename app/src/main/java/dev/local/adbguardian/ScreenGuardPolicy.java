package dev.local.adbguardian;

/** 屏幕保持的保守耗电策略；阈值不代表硬件安全保证。无 Android 依赖。 */
final class ScreenGuardPolicy {
    // ACTIVE 仅表示策略允许申请，不证明厂商系统实际执行了锁的效果。
    enum State { OFF, WAITING_GUARD, SCREEN_OFF, PROTECTION, UNSUPPORTED, ACTIVE, FAILURE,
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

    State evaluate(boolean enabled, boolean ready, boolean interactive, boolean supported,
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
        if (!ready) return State.WAITING_GUARD;
        if (!supported) return State.UNSUPPORTED;
        if (!interactive) return State.SCREEN_OFF;
        return protectionPaused ? State.PROTECTION : State.ACTIVE;
    }
}
