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

/** Artwork drawn in code: the small icons on buttons and Settings tiles, and tiles for apps without a TV banner. */
final class Art {
    static final int APPS = 0, SEARCH = 1, SETTINGS = 2;
    // Settings tiles
    static final int NETWORK = 3, DISPLAY = 4, CONTROLLERS = 5, PREFERENCES = 6, PRIVACY = 7,
            ACCESSIBILITY = 8, ACCOUNT = 9, DEVICE = 10, SLEEP = 11, PICTURE = 12, INFO = 13;

    private Art() {}

    static Icon icon(int kind) { return new Icon(kind); }

    static Drawable iconTile(Drawable icon) { return new IconTile(icon); }

    private abstract static class Base extends Drawable {
        @Override public void setAlpha(int alpha) {}
        @Override public void setColorFilter(ColorFilter cf) {}
        @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
    }

    /**
     * Simple icons: filled shapes (path) plus rounded strokes (lines). The Settings icons are laid
     * out on a 24-unit grid, like most icon sets.
     */
    static final class Icon extends Base {
        private final int kind;
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path path = new Path();
        private final Path lines = new Path();
        private float u, ox, oy;   // grid unit and origin

        Icon(int kind) {
            this.kind = kind;
            paint.setColor(Color.WHITE);
            stroke.setColor(Color.WHITE);
            stroke.setStyle(Paint.Style.STROKE);
            stroke.setStrokeCap(Paint.Cap.ROUND);
            stroke.setStrokeJoin(Paint.Join.ROUND);
        }

        void setColor(int color) {
            paint.setColor(color);
            stroke.setColor(color);
            invalidateSelf();
        }

        private float x(float v) { return ox + v * u; }
        private float y(float v) { return oy + v * u; }

        private RectF box(float l, float t, float r, float b) { return new RectF(x(l), y(t), x(r), y(b)); }

        private void line(float x1, float y1, float x2, float y2) {
            lines.moveTo(x(x1), y(y1));
            lines.lineTo(x(x2), y(y2));
        }

        private void dot(Path p, float cx, float cy, float r) { p.addCircle(x(cx), y(cy), r * u, Path.Direction.CW); }

        @Override protected void onBoundsChange(Rect b) {
            path.reset();
            lines.reset();
            float s = Math.min(b.width(), b.height()), cx = b.exactCenterX(), cy = b.exactCenterY();
            u = s / 24f;
            ox = cx - 12 * u;
            oy = cy - 12 * u;
            stroke.setStrokeWidth(2f * u);
            if (kind >= NETWORK) {
                settingsIcon();
            } else if (kind == APPS) {
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

        private void settingsIcon() {
            switch (kind) {
                case NETWORK:   // Wi-Fi
                    for (float r : new float[] {4.5f, 9f, 13.5f}) lines.addArc(box(12 - r, 20 - r, 12 + r, 20 + r), 225, 90);
                    dot(path, 12, 20, 1.7f);
                    break;
                case DISPLAY:   // monitor on a stand
                    lines.addRoundRect(box(3, 4, 21, 16), 2 * u, 2 * u, Path.Direction.CW);
                    line(12, 16, 12, 20);
                    line(8, 20, 16, 20);
                    break;
                case CONTROLLERS:   // game controller
                    lines.addRoundRect(box(2, 7, 22, 18), 5 * u, 5 * u, Path.Direction.CW);
                    line(6.5f, 12.5f, 10.5f, 12.5f);
                    line(8.5f, 10.5f, 8.5f, 14.5f);
                    dot(path, 15.5f, 11.5f, 1.3f);
                    dot(path, 17.8f, 13.8f, 1.3f);
                    break;
                case PREFERENCES:   // sliders
                    line(3, 6, 21, 6);
                    line(3, 12, 21, 12);
                    line(3, 18, 21, 18);
                    dot(path, 8, 6, 2.6f);
                    dot(path, 15, 12, 2.6f);
                    dot(path, 10, 18, 2.6f);
                    break;
                case PRIVACY: { // padlock
                    path.addRoundRect(box(5, 11, 19, 21), 2 * u, 2 * u, Path.Direction.CW);
                    Path hole = new Path();
                    dot(hole, 12, 15.6f, 1.7f);
                    path.op(hole, Path.Op.DIFFERENCE);
                    lines.moveTo(x(8), y(11));
                    lines.lineTo(x(8), y(8));
                    lines.arcTo(box(8, 4, 16, 12), 180, 180);
                    lines.lineTo(x(16), y(11));
                    break;
                }
                case ACCESSIBILITY: // person with arms out
                    dot(path, 12, 4.5f, 2.2f);
                    line(5, 9, 19, 9);
                    line(12, 9, 12, 15);
                    line(12, 15, 8.5f, 21);
                    line(12, 15, 15.5f, 21);
                    break;
                case ACCOUNT:   // head and shoulders
                    dot(path, 12, 8, 4);
                    path.moveTo(x(4), y(22));
                    path.arcTo(box(4, 14, 20, 30), 180, 180);
                    path.close();
                    break;
                case DEVICE:    // TV with antenna
                    lines.addRoundRect(box(3, 8, 21, 20), 2 * u, 2 * u, Path.Direction.CW);
                    line(8, 3, 12, 8);
                    line(16, 3, 12, 8);
                    break;
                case SLEEP: {   // crescent moon
                    dot(path, 12, 12, 8.5f);
                    Path bite = new Path();
                    dot(bite, 16.5f, 8.5f, 6.8f);
                    path.op(bite, Path.Op.DIFFERENCE);
                    break;
                }
                case PICTURE:   // photo: frame, hills and sun
                    lines.addRoundRect(box(3, 5, 21, 19), 2 * u, 2 * u, Path.Direction.CW);
                    path.moveTo(x(5.5f), y(17));
                    path.lineTo(x(10), y(11));
                    path.lineTo(x(13.5f), y(15));
                    path.lineTo(x(15.5f), y(12.5f));
                    path.lineTo(x(18.5f), y(17));
                    path.close();
                    dot(path, 16, 9, 1.6f);
                    break;
                default:        // INFO: "i" in a circle
                    lines.addCircle(x(12), y(12), 9 * u, Path.Direction.CW);
                    line(12, 11, 12, 16.5f);
                    dot(path, 12, 7.8f, 1.3f);
                    break;
            }
        }

        @Override public void draw(Canvas c) {
            c.drawPath(lines, stroke);
            c.drawPath(path, paint);
        }
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
