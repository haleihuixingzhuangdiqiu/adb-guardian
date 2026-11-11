package dev.local.adbguardian;

import org.junit.Test;
import static dev.local.adbguardian.ScreenGuardPolicy.State.*;
import static org.junit.Assert.assertEquals;

public final class ScreenGuardPolicyTest {
    private static ScreenGuardPolicy.Battery battery(int percent, Integer temperature) {
        return new ScreenGuardPolicy.Battery(percent, 100, temperature);
    }

    private static ScreenGuardPolicy.State evaluate(ScreenGuardPolicy policy, int percent, int temperature, int thermal) {
        return policy.evaluate(true, true, true, battery(percent, temperature), thermal);
    }

    private static ScreenGuardPolicy activePolicy() {
        ScreenGuardPolicy policy = new ScreenGuardPolicy();
        assertEquals(ACTIVE, evaluate(policy, 25, 380, 1));
        return policy;
    }

    @Test public void initialEnableRequiresAllRecoveryLimits() {
        assertEquals(PROTECTION, evaluate(new ScreenGuardPolicy(), 24, 380, 1));
        assertEquals(PROTECTION, evaluate(new ScreenGuardPolicy(), 25, 381, 1));
        assertEquals(PROTECTION, evaluate(new ScreenGuardPolicy(), 25, 380, 2));
        assertEquals(ACTIVE, evaluate(new ScreenGuardPolicy(), 25, 380, 1));
    }

    @Test public void lowBatteryCutoffIsInclusiveAndRecoveryHasHysteresis() {
        ScreenGuardPolicy policy = activePolicy();
        assertEquals(ACTIVE, evaluate(policy, 21, 399, 1));
        assertEquals(PROTECTION, evaluate(policy, 20, 399, 1));
        assertEquals(PROTECTION, evaluate(policy, 24, 380, 1));
        assertEquals(ACTIVE, evaluate(policy, 25, 380, 1));
        assertEquals(PROTECTION, evaluate(policy, 0, 380, 1));
    }

    @Test public void hotBatteryCutoffIsInclusiveAndRequiresCoolingBeforeRecovery() {
        ScreenGuardPolicy policy = activePolicy();
        assertEquals(ACTIVE, evaluate(policy, 80, 399, 1));
        assertEquals(PROTECTION, evaluate(policy, 80, 400, 1));
        assertEquals(PROTECTION, evaluate(policy, 80, 381, 1));
        assertEquals(ACTIVE, evaluate(policy, 80, 380, 1));
    }

    @Test public void moderateAndHigherThermalStatusPause() {
        for (int thermal = 2; thermal <= 6; thermal++) {
            ScreenGuardPolicy policy = activePolicy();
            assertEquals(PROTECTION, evaluate(policy, 80, 300, thermal));
            assertEquals(ACTIVE, evaluate(policy, 80, 300, 1));
        }
    }

    @Test public void moderateThermalPausesAt375AndReportsThermalRecoveryGate() {
        ScreenGuardPolicy policy = activePolicy();
        assertEquals(PROTECTION, evaluate(policy, 80, 375, 2));
        assertEquals("等待系统热状态≤轻度（1）", ScreenGuardPolicy.protectionReason(battery(80, 375), 2));
        assertEquals(PROTECTION, evaluate(policy, 80, 390, 1));
        assertEquals("等待电池温度≤38℃", ScreenGuardPolicy.protectionReason(battery(80, 390), 1));
        assertEquals(ACTIVE, evaluate(policy, 80, 380, 1));
    }

    @Test public void protectionReasonIncludesAllUnmetRecoveryRequirementsAndUnknowns() {
        assertEquals("等待电量≥25%；等待电池温度≤38℃；等待系统热状态≤轻度（1）",
                ScreenGuardPolicy.protectionReason(battery(20, 400), 2));
        assertEquals("电量读数不可用；电池温度读数不可用；系统热状态不可用",
                ScreenGuardPolicy.protectionReason(null, -1));
        assertEquals("电量读数不可用", ScreenGuardPolicy.protectionReason(battery(101, 300), 0));
    }

    @Test public void recoveryRequiresBatteryTemperatureAndThermalTogether() {
        ScreenGuardPolicy policy = activePolicy();
        assertEquals(PROTECTION, evaluate(policy, 20, 400, 2));
        assertEquals(PROTECTION, evaluate(policy, 25, 381, 1));
        assertEquals(PROTECTION, evaluate(policy, 24, 380, 1));
        assertEquals(PROTECTION, evaluate(policy, 25, 380, 2));
        assertEquals(ACTIVE, evaluate(policy, 25, 380, 1));
    }

    @Test public void unknownAndInvalidBatteryDataPauseEvenAfterActive() {
        ScreenGuardPolicy.Battery[] unknown = {
                null, battery(-1, 300), battery(101, 300), battery(80, null),
                new ScreenGuardPolicy.Battery(0, 0, 300),
                new ScreenGuardPolicy.Battery(80, -1, 300)
        };
        for (ScreenGuardPolicy.Battery sample : unknown) {
            ScreenGuardPolicy policy = activePolicy();
            assertEquals(PROTECTION, policy.evaluate(true, true, true, sample, 0));
            assertEquals(PROTECTION, evaluate(policy, 24, 380, 1));
            assertEquals(ACTIVE, evaluate(policy, 25, 380, 1));
        }
    }

    @Test public void unknownThermalPausesAndResetsRecoveryGate() {
        for (int thermal : new int[]{-1, 7}) {
            ScreenGuardPolicy policy = activePolicy();
            assertEquals(PROTECTION, evaluate(policy, 80, 300, thermal));
            assertEquals(PROTECTION, evaluate(policy, 80, 381, 1));
            assertEquals(ACTIVE, evaluate(policy, 80, 380, 1));
        }
    }

    @Test public void nonStandardBatteryScaleUsesActualPercentageWithoutRounding() {
        ScreenGuardPolicy policy = activePolicy();
        assertEquals(ACTIVE, policy.evaluate(true, true, true,
                new ScreenGuardPolicy.Battery(201, 1000, 300), 0));
        assertEquals(PROTECTION, policy.evaluate(true, true, true,
                new ScreenGuardPolicy.Battery(200, 1000, 300), 0));
        assertEquals(PROTECTION, policy.evaluate(true, true, true,
                new ScreenGuardPolicy.Battery(249, 1000, 300), 0));
        assertEquals(ACTIVE, policy.evaluate(true, true, true,
                new ScreenGuardPolicy.Battery(250, 1000, 300), 0));
    }

    @Test public void manualScreenOffAndDelayedRenewalsCannotBecomeActiveUntilInteractive() {
        ScreenGuardPolicy policy = activePolicy();
        for (int renewal = 0; renewal < 5; renewal++) {
            assertEquals(SCREEN_OFF, policy.evaluate(true, false, true, battery(80, 300), 0));
        }
        assertEquals(ACTIVE, policy.evaluate(true, true, true, battery(80, 300), 0));
    }

    @Test public void healthRecoveryWhileScreenOffDoesNotActivate() {
        ScreenGuardPolicy policy = activePolicy();
        assertEquals(PROTECTION, evaluate(policy, 20, 400, 2));
        assertEquals(SCREEN_OFF, policy.evaluate(true, false, true, battery(25, 380), 1));
        assertEquals(ACTIVE, evaluate(policy, 25, 380, 1));
    }

    @Test public void disabledScreenGuardAndUnsupportedLevelNeverActivate() {
        ScreenGuardPolicy policy = activePolicy();
        assertEquals(OFF, policy.evaluate(false, true, true, battery(80, 300), 0));
        assertEquals(PROTECTION, evaluate(policy, 24, 380, 1));
        assertEquals(UNSUPPORTED, policy.evaluate(true, true, false, battery(80, 300), 0));
        assertEquals(OFF, policy.evaluate(false, false, false, null, -1));
    }
}
