package dev.local.adbguardian;

import org.junit.Test;
import static dev.local.adbguardian.ScreenGuardPolicy.State.*;
import static org.junit.Assert.*;

public final class VivoScreenPulseTest {
    private static ScreenGuardPolicy.Battery battery(int level, Integer temperature) {
        return new ScreenGuardPolicy.Battery(level, 100, temperature);
    }

    private static ScreenGuardPolicy.State healthy(ScreenGuardPolicy policy) {
        return policy.evaluate(true, true, true, battery(80, 300), 0);
    }

    private static boolean claim(ScreenGuardPolicy policy, ScreenGuardPolicy.State state, long now) {
        return policy.claimVivoPulse("vivo", true, state, true, true, true, true, now);
    }

    private static void assertScreenAndPulseEligible(ScreenGuardPolicy screen, long now) {
        ScreenGuardPolicy.State state = healthy(screen);
        assertEquals(ACTIVE, state);
        assertTrue(claim(screen, state, now));
    }

    @Test public void wirelessRecoveryAndRejectionDoNotGateScreenOrVivoPulse() {
        GuardPolicy wireless = new GuardPolicy(false, false, 0);
        ScreenGuardPolicy screen = new ScreenGuardPolicy();
        assertEquals(GuardPolicy.State.READY, wireless.evaluate(true, true, true, true, 0));
        assertScreenAndPulseEligible(screen, 0);
        assertEquals(GuardPolicy.State.WIFI, wireless.evaluate(true, true, false, false, 30_000));
        assertScreenAndPulseEligible(screen, 30_000);
        // Wi-Fi 离线超过原 10 分钟超时后，屏幕仍独立允许保持和 pulse。
        assertEquals(GuardPolicy.State.WIFI, wireless.evaluate(true, true, false, false, 660_000));
        assertScreenAndPulseEligible(screen, 660_000);
        assertEquals(GuardPolicy.State.ENABLE, wireless.evaluate(true, true, true, false, 690_000));
        assertScreenAndPulseEligible(screen, 690_000);
        wireless.requested(720_000);
        assertEquals(GuardPolicy.State.VERIFY, wireless.evaluate(true, true, true, false, 720_000));
        assertScreenAndPulseEligible(screen, 720_000);
        assertEquals(GuardPolicy.State.ATTENTION, wireless.evaluate(true, true, true, false, 750_000));
        assertScreenAndPulseEligible(screen, 750_000);
        wireless.rejected();
        assertEquals(GuardPolicy.State.PERMISSION, wireless.evaluate(true, false, true, false, 780_000));
        assertScreenAndPulseEligible(screen, 780_000);
        assertEquals(GuardPolicy.State.READY, wireless.evaluate(true, true, true, true, 810_000));
        assertScreenAndPulseEligible(screen, 810_000);
        // 服务/用户选项合成的 enabled 仍是必须满足的屏幕门槛。
        assertEquals(OFF, screen.evaluate(false, true, true, battery(80, 300), 0));
        assertFalse(screen.claimVivoPulse("vivo", true, ACTIVE, false, true, true, true, 840_000));
    }

    @Test public void firstEligibleCheckCanPulseImmediatelyEvenAtMonotonicZero() {
        ScreenGuardPolicy policy = new ScreenGuardPolicy();
        assertTrue(claim(policy, healthy(policy), 0));
        assertFalse(claim(policy, healthy(policy), 0));
    }

    @Test public void manufacturerIsStrictlyVivoAndCaseInsensitive() {
        for (String manufacturer : new String[]{null, "", "xiaomi", "OPPO", "iQOO", "vivo-other"}) {
            ScreenGuardPolicy policy = new ScreenGuardPolicy();
            assertFalse(policy.claimVivoPulse(manufacturer, true, healthy(policy),
                    true, true, true, true, 0));
            // 不符合条件的检查不消耗首次 pulse 额度。
            assertTrue(policy.claimVivoPulse("ViVo", true, healthy(policy),
                    true, true, true, true, 0));
        }
    }

    @Test public void everyLiveGateMustPassEvenWithAnEarlierActiveEvaluation() {
        // 依次模拟关兼容选项、停止服务/取消守护或屏幕保持、关屏、锁屏、撤权或窗口消失。
        for (int gate = 0; gate < 5; gate++) {
            ScreenGuardPolicy policy = new ScreenGuardPolicy();
            ScreenGuardPolicy.State evaluated = healthy(policy);
            assertFalse(policy.claimVivoPulse("vivo", gate != 0, evaluated,
                    gate != 1, gate != 2, gate != 3, gate != 4, 0));
            assertTrue(claim(policy, evaluated, 0));
        }
    }

    @Test public void onlyEvaluatedActiveCanPulseNotRequestedOrOtherDisplayStates() {
        for (ScreenGuardPolicy.State state : ScreenGuardPolicy.State.values()) {
            if (state == ACTIVE) continue;
            ScreenGuardPolicy policy = new ScreenGuardPolicy();
            assertFalse(claim(policy, state, 0));
            assertTrue(claim(policy, healthy(policy), 0));
        }
    }

    @Test public void callbackStormCannotExceedOnePulsePerThirtySeconds() {
        ScreenGuardPolicy policy = new ScreenGuardPolicy();
        int pulses = 0;
        // 模拟电池、热状态、Activity 与例行检查共用入口；在边界上也重复回调。
        for (long now = 0; now <= 60_000; now++) {
            for (int callback = 0; callback < 4; callback++) {
                if (claim(policy, healthy(policy), now)) {
                    pulses++;
                    assertEquals((pulses - 1) * 30_000L, now);
                }
            }
        }
        assertEquals(3, pulses);
    }

    @Test public void disabledAndResumeDoNotResetOrExtendTheRateLimit() {
        ScreenGuardPolicy policy = new ScreenGuardPolicy();
        assertTrue(claim(policy, healthy(policy), 0));
        assertFalse(claim(policy, policy.evaluate(false, true, true, battery(80, 300), 0), 10_000));
        assertFalse(claim(policy, healthy(policy), 29_999));
        assertTrue(claim(policy, healthy(policy), 30_000));
    }

    @Test public void permissionRevocationAndScreenOffCannotPulseOrClearTheLimit() {
        ScreenGuardPolicy policy = new ScreenGuardPolicy();
        ScreenGuardPolicy.State active = healthy(policy);
        assertTrue(claim(policy, active, 0));
        assertFalse(policy.claimVivoPulse("vivo", true, active, true, true, true, false, 30_000));
        assertFalse(policy.claimVivoPulse("vivo", true, active, true, false, true, true, 30_000));
        assertTrue(claim(policy, active, 30_000));
        assertFalse(policy.claimVivoPulse("vivo", false, active, true, true, true, true, 40_000));
        assertFalse(claim(policy, active, 59_999));
        assertTrue(claim(policy, active, 60_000));
    }

    @Test public void lowHotThermalAndUnknownReadingsBlockPulsesThroughExistingPolicy() {
        ScreenGuardPolicy.Battery[] samples = {
                battery(20, 300), battery(80, 400), battery(80, 300),
                null, battery(-1, 300), battery(101, 300), battery(80, null),
                new ScreenGuardPolicy.Battery(80, 0, 300), battery(80, 300), battery(80, 300)
        };
        int[] thermal = {0, 0, 2, 0, 0, 0, 0, 0, -1, 7};
        for (int i = 0; i < samples.length; i++) {
            ScreenGuardPolicy policy = new ScreenGuardPolicy();
            assertTrue(claim(policy, healthy(policy), 0));
            ScreenGuardPolicy.State paused = policy.evaluate(true, true, true, samples[i], thermal[i]);
            assertEquals(PROTECTION, paused);
            assertFalse(claim(policy, paused, 30_000));
            assertEquals(PROTECTION, policy.evaluate(true, true, true, battery(24, 380), 1));
            assertFalse(claim(policy, PROTECTION, 30_000));
            assertTrue(claim(policy, policy.evaluate(true, true, true, battery(25, 380), 1), 30_000));
        }
    }

    @Test public void initialHysteresisBandAndRecoveryWhileOffCannotPulse() {
        ScreenGuardPolicy policy = new ScreenGuardPolicy();
        assertFalse(claim(policy, policy.evaluate(true, true, true, battery(24, 380), 1), 0));
        assertFalse(claim(policy, policy.evaluate(true, false, true, battery(25, 380), 1), 0));
        assertTrue(claim(policy, policy.evaluate(true, true, true, battery(25, 380), 1), 0));
    }

    @Test public void monotonicClaimIsReservedBeforePlatformAttemptAndNeverResetByChecks() {
        ScreenGuardPolicy policy = new ScreenGuardPolicy();
        assertTrue(claim(policy, healthy(policy), 10_000));
        // 平台 acquire 若失败也已占额度，后续回调不能立即重复尝试。
        assertFalse(claim(policy, FAILURE, 10_001));
        assertFalse(claim(policy, healthy(policy), 39_999));
        assertTrue(claim(policy, healthy(policy), 40_000));
        // 不依赖墙钟；异常的倒退时间也不额外放行。
        assertFalse(claim(policy, healthy(policy), 0));
        assertFalse(claim(policy, healthy(policy), 69_999));
        assertTrue(claim(policy, healthy(policy), 70_000));
    }
}
