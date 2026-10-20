package local.tvhome;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorFilter;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.drawable.Drawable;

/** Artwork drawn in code: the small icons on the glass buttons, and tiles for apps without a TV banner. */
final class Art {
    static final int APPS = 0, SEARCH = 1, SETTINGS = 2;

    private Art() {}

    static Icon icon(int kind) { return new Icon(kind); }

    static Drawable iconTile(Drawable icon) { return new IconTile(icon); }

    private abstract static class Base extends Drawable {
        @Override public void setAlpha(int alpha) {}
        @Override public void setColorFilter(ColorFilter cf) {}
        @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
    }

    /** Simple filled icons: a 2x2 app grid, a magnifier, a gear. */
    static final class Icon extends Base {
        private final int kind;
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path path = new Path();

        Icon(int kind) {
            this.kind = kind;
            paint.setColor(Color.WHITE);
        }

        void setColor(int color) {
            paint.setColor(color);
            invalidateSelf();
        }

        @Override protected void onBoundsChange(Rect b) {
            path.reset();
            float s = Math.min(b.width(), b.height()), cx = b.exactCenterX(), cy = b.exactCenterY();
            if (kind == APPS) {
                float sq = s * 0.4f, gap = s * 0.14f, r = sq * 0.28f, left = cx - sq - gap / 2, top = cy - sq - gap / 2;
                for (int i = 0; i < 4; i++) {
                    float x = left + (i % 2) * (sq + gap), y = top + (i / 2) * (sq + gap);
                    path.addRoundRect(new RectF(x, y, x + sq, y + sq), r, r, Path.Direction.CW);
                }
            } else if (kind == SEARCH) {
                float r = s * 0.3f, w = s * 0.11f, ox = cx - s * 0.08f, oy = cy - s * 0.08f;
                path.addCircle(ox, oy, r + w / 2, Path.Direction.CW);
                Path hole = new Path();
                hole.addCircle(ox, oy, r - w / 2, Path.Direction.CW);
                path.op(hole, Path.Op.DIFFERENCE);
                Path handle = new Path();
                handle.addRoundRect(new RectF(-w / 2, 0, w / 2, s * 0.3f), w / 2, w / 2, Path.Direction.CW);
                Matrix m = new Matrix();
                m.setRotate(-45);
                m.postTranslate(ox + (r + w / 4) * 0.7071f, oy + (r + w / 4) * 0.7071f);
                handle.transform(m);
                path.op(handle, Path.Op.UNION);
            } else {
                gear(path, cx, cy, s / 2);
            }
        }

        @Override public void draw(Canvas c) { c.drawPath(path, paint); }
    }

    private static void gear(Path path, float cx, float cy, float outer) {
        float body = outer * 0.76f, toothW = outer * 0.36f, hole = outer * 0.34f;
        path.addCircle(cx, cy, body, Path.Direction.CW);
        Matrix rot = new Matrix();
        for (int i = 0; i < 8; i++) {
            Path tooth = new Path();
            tooth.addRoundRect(new RectF(cx - toothW / 2, cy - outer, cx + toothW / 2, cy - body * 0.8f),
                    toothW * 0.18f, toothW * 0.18f, Path.Direction.CW);
            rot.setRotate(i * 45f, cx, cy);
            tooth.transform(rot);
            path.op(tooth, Path.Op.UNION);
        }
        Path h = new Path();
        h.addCircle(cx, cy, hole, Path.Direction.CW);
        path.op(h, Path.Op.DIFFERENCE);
    }

    /** For apps without a TV banner: their icon on a gradient of the icon's own colour. */
    private static final class IconTile extends Base {
        private final Drawable icon;
        private final Paint bg = new Paint();
        private final int color;

        IconTile(Drawable icon) {
            this.icon = icon;
            this.color = averageColor(icon);
        }

        @Override public int getOpacity() { return PixelFormat.OPAQUE; }

        @Override protected void onBoundsChange(Rect b) {
            bg.setShader(new LinearGradient(0, b.top, 0, b.bottom,
                    blend(color, Color.WHITE, 0.12f), blend(color, Color.BLACK, 0.45f), Shader.TileMode.CLAMP));
            int size = Math.round(b.height() * 0.62f);
            int left = b.centerX() - size / 2, top = b.centerY() - size / 2;
            icon.setBounds(left, top, left + size, top + size);
        }

        @Override public void draw(Canvas c) {
            c.drawRect(getBounds(), bg);
            icon.draw(c);
        }

        private static int averageColor(Drawable d) {
            Bitmap b = Bitmap.createBitmap(12, 12, Bitmap.Config.ARGB_8888);
            d.setBounds(0, 0, 12, 12);
            d.draw(new Canvas(b));
            long r = 0, g = 0, bl = 0, n = 0;
            for (int y = 0; y < 12; y++) {
                for (int x = 0; x < 12; x++) {
                    int p = b.getPixel(x, y);
                    if (Color.alpha(p) < 128) continue;
                    r += Color.red(p); g += Color.green(p); bl += Color.blue(p); n++;
                }
            }
            b.recycle();
            return n == 0 ? 0xFF3A3F47 : Color.rgb((int) (r / n), (int) (g / n), (int) (bl / n));
        }

        private static int blend(int a, int b, float t) {
            return Color.rgb(Math.round(Color.red(a) + (Color.red(b) - Color.red(a)) * t),
                    Math.round(Color.green(a) + (Color.green(b) - Color.green(a)) * t),
                    Math.round(Color.blue(a) + (Color.blue(b) - Color.blue(a)) * t));
        }
    }
}
