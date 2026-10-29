package dev.local.adbguardian;

import org.junit.Test;
import static org.junit.Assert.*;

public final class OverlayPositionTest {
    private final OverlayPosition.Area portrait = new OverlayPosition.Area(0, 30, 360, 760);

    private static void assertFrame(int x, int y, int width, int height, OverlayPosition.Frame frame) {
        assertEquals(x, frame.x);
        assertEquals(y, frame.y);
        assertEquals(width, frame.width);
        assertEquals(height, frame.height);
    }

    @Test public void defaultIsRightAndCenteredWithinSafeArea() {
        assertFrame(336, 371, 24, 48, portrait.place(new OverlayPosition(true, 0.5f), 24, 48));
    }

    @Test public void edgePlacementKeepsBothEndsInsideSystemBarsAndCutouts() {
        OverlayPosition.Area landscape = new OverlayPosition.Area(40, 20, 760, 340);
        assertFrame(40, 20, 24, 48, landscape.place(new OverlayPosition(false, 0), 24, 48));
        assertFrame(736, 292, 24, 48, landscape.place(new OverlayPosition(true, 1), 24, 48));
    }

    @Test public void dragClampsAtEveryBoundary() {
        assertFrame(0, 30, 24, 48, portrait.drag(-500, -200, 24, 48));
        assertFrame(336, 712, 24, 48, portrait.drag(999, 999, 24, 48));
        assertFrame(100, 300, 24, 48, portrait.drag(100, 300, 24, 48));
    }

    @Test public void releaseSnapsNearestEdgeWithDeterministicRightTie() {
        assertFalse(portrait.snap(portrait.drag(167, 300, 24, 48)).right);
        assertTrue(portrait.snap(portrait.drag(168, 300, 24, 48)).right);
        assertTrue(portrait.snap(portrait.drag(169, 300, 24, 48)).right);
        assertFalse(portrait.snap(new OverlayPosition.Frame(-200, 300, 24, 48)).right);
    }

    @Test public void expandedLabelStaysInsideEitherEdgeAndCanCollapseAtSameFraction() {
        OverlayPosition saved = new OverlayPosition(false, 0.75f);
        OverlayPosition.Frame expanded = portrait.place(saved, 160, 72);
        assertFrame(0, 524, 160, 72, expanded);
        OverlayPosition recovered = portrait.snap(expanded);
        assertFalse(recovered.right);
        assertEquals(0.75f, recovered.heightFraction, 0.001f);
        assertFrame(336, 542, 24, 48,
                portrait.place(new OverlayPosition(true, recovered.heightFraction), 24, 48));
        assertFrame(200, 30, 160, 72, portrait.place(new OverlayPosition(true, 0), 160, 72));
    }

    @Test public void savedSideAndFractionRestoreAcrossRotationAndServiceRecreation() {
        OverlayPosition saved = portrait.snap(portrait.drag(0, 201, 24, 48));
        // Same scalar values written/read by GuardStore; no pixel coordinates persist.
        OverlayPosition restored = new OverlayPosition(saved.right, saved.heightFraction);
        OverlayPosition.Area landscape = new OverlayPosition.Area(40, 20, 760, 340);
        assertFrame(40, 88, 24, 48, landscape.place(restored, 24, 48));
        assertFrame(0, 201, 24, 48, portrait.place(restored, 24, 48));
    }

    @Test public void persistedNonFiniteValuesRecoverToCenterAndFiniteValuesClamp() {
        for (float invalid : new float[]{Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY}) {
            OverlayPosition repaired = new OverlayPosition(false, invalid);
            assertFalse(repaired.right);
            assertEquals(0.5f, repaired.heightFraction, 0);
        }
        assertEquals(0, new OverlayPosition(true, -3).heightFraction, 0);
        assertEquals(1, new OverlayPosition(false, 5).heightFraction, 0);
        assertEquals(0.25f, new OverlayPosition(true, 0.25f).heightFraction, 0);
    }

    @Test public void tinyAreaAndOversizeLabelRemainVisibleWithoutNegativeTravel() {
        OverlayPosition.Area tiny = new OverlayPosition.Area(10, 20, 22, 38);
        assertFrame(10, 20, 12, 18, tiny.place(new OverlayPosition(true, 1), 200, 48));
        OverlayPosition saved = tiny.snap(new OverlayPosition.Frame(99, -10, 200, 48));
        assertEquals(0.5f, saved.heightFraction, 0);
        assertFrame(336, 371, 24, 48, portrait.place(saved, 24, 48));
    }

    @Test public void invalidRequestedDimensionsStillProduceAVisiblePixel() {
        assertFrame(359, 395, 1, 1, portrait.place(new OverlayPosition(true, 0.5f), 0, -20));
    }

    @Test(expected = IllegalArgumentException.class) public void emptySafeAreaIsRejected() {
        new OverlayPosition.Area(20, 0, 20, 100);
    }
}
