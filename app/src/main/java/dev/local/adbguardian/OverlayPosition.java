package dev.local.adbguardian;

/** Physical left/right edge and fraction of the available vertical travel, independent of pixels. */
final class OverlayPosition {
    final boolean right;
    final float heightFraction;

    OverlayPosition(boolean right, float heightFraction) {
        this.right = right;
        this.heightFraction = Float.isFinite(heightFraction)
                ? Math.max(0, Math.min(1, heightFraction)) : 0.5f;
    }

    static final class Frame {
        final int x, y, width, height;

        Frame(int x, int y, int width, int height) {
            this.x = x;
            this.y = y;
            this.width = width;
            this.height = height;
        }
    }

    /** Safe display rectangle, excluding system bars and cutouts. */
    static final class Area {
        final int left, top, right, bottom;

        Area(int left, int top, int right, int bottom) {
            if (right <= left || bottom <= top) throw new IllegalArgumentException("Empty safe area");
            this.left = left;
            this.top = top;
            this.right = right;
            this.bottom = bottom;
        }

        int width() { return right - left; }
        int height() { return bottom - top; }

        Frame place(OverlayPosition position, int width, int height) {
            int w = Math.max(1, Math.min(width(), width));
            int h = Math.max(1, Math.min(height(), height));
            return new Frame(position.right ? right - w : left,
                    top + Math.round((height() - h) * position.heightFraction), w, h);
        }

        Frame drag(int x, int y, int width, int height) {
            int w = Math.max(1, Math.min(width(), width));
            int h = Math.max(1, Math.min(height(), height));
            return new Frame(Math.max(left, Math.min(right - w, x)),
                    Math.max(top, Math.min(bottom - h, y)), w, h);
        }

        OverlayPosition snap(Frame frame) {
            Frame bounded = drag(frame.x, frame.y, frame.width, frame.height);
            boolean toRight = bounded.x - left >= right - bounded.x - bounded.width;
            int travel = height() - bounded.height;
            return new OverlayPosition(toRight, travel == 0 ? 0.5f
                    : (bounded.y - top) / (float) travel);
        }
    }
}
