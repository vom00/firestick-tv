package local.tvhome;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorMatrix;
import android.graphics.ColorMatrixColorFilter;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Rect;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.view.View;

/**
 * A line of text cut from liquid glass, like the clock on an iPhone lock screen: the blurred
 * wallpaper shows through the letters, frosted towards white, with light catching the top edges.
 * Uses the same pre-blurred, screen-aligned wallpaper as {@link Glass}.
 */
final class GlassText extends View {
    private static final float LENS = 1.08f;

    private final Paint body = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final Paint oldBody = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final Paint shadow = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint sheen = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint rim = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Matrix matrix = new Matrix();
    private final int[] location = new int[2];
    private final Path outline = new Path();
    private boolean outlineStale = true;
    private final float dp;
    private final float inkTop, inkBottom;   // where digits actually sit, relative to the baseline
    private String text = "";
    private Bitmap blur, oldBlur;
    private float mix = 1f;
    private ValueAnimator fade;

    GlassText(Context c, Typeface face, float sp) {
        super(c);
        dp = c.getResources().getDisplayMetrics().density;
        float size = sp * c.getResources().getDisplayMetrics().scaledDensity;
        for (Paint p : new Paint[] {body, oldBody, shadow, sheen, rim}) {
            p.setTypeface(face);
            p.setTextSize(size);
        }

        // Frosted: well over halfway to white, more saturated so the photo's colour still tints it.
        ColorMatrix frost = new ColorMatrix();
        frost.setSaturation(1.6f);
        frost.postConcat(new ColorMatrix(new float[] {
                0.4f, 0, 0, 0, 158,
                0, 0.4f, 0, 0, 158,
                0, 0, 0.4f, 0, 158,
                0, 0, 0, 1, 0}));
        ColorMatrixColorFilter filter = new ColorMatrixColorFilter(frost);
        body.setColorFilter(filter);
        oldBody.setColorFilter(filter);
        body.setColor(0xF2FFFFFF);   // also the plain fill until the first wallpaper loads
        // A separate pass, hidden under the glass: a shadow on the glass paint itself comes out in
        // the glass colour, a grey glow on dark photos.
        shadow.setColor(Color.BLACK);
        shadow.setShadowLayer(6 * dp, 0, 2 * dp, 0x4D000000);

        // Size to the digits themselves, not the font's line height: Nunito leaves a lot of room
        // above and below for accents, which spread the greeting, clock and date apart.
        Rect ink = new Rect();
        body.getTextBounds("0123456789:", 0, 11, ink);
        inkTop = ink.top;
        inkBottom = ink.bottom;

        rim.setStyle(Paint.Style.STROKE);
        rim.setStrokeWidth(1.6f * dp);
        rim.setStrokeJoin(Paint.Join.ROUND);
    }

    void setText(CharSequence t) {
        String s = t.toString();
        if (s.equals(text)) return;
        boolean resize = Math.abs(body.measureText(s) - body.measureText(text)) > 0.5f;
        text = s;
        outlineStale = true;
        if (resize) requestLayout();
        invalidate();
    }

    void setBlur(Bitmap next, long fadeMs) {
        if (fade != null) fade.cancel();
        oldBlur = (blur != null && fadeMs > 0) ? blur : null;
        if (oldBlur != null) oldBody.setShader(new BitmapShader(oldBlur, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP));
        blur = next;
        body.setShader(new BitmapShader(next, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP));
        mix = oldBlur == null ? 1f : 0f;
        if (oldBlur != null) {
            fade = ValueAnimator.ofFloat(0f, 1f).setDuration(fadeMs);
            fade.addUpdateListener(a -> {
                mix = (float) a.getAnimatedValue();
                invalidate();
            });
            fade.start();
        }
        invalidate();
    }

    private float pad() {
        return 10 * dp;   // room for the shadow and the rim
    }

    @Override
    protected void onMeasure(int widthSpec, int heightSpec) {
        int w = (int) Math.ceil(body.measureText(text) + 2 * pad());
        int h = (int) Math.ceil(inkBottom - inkTop + 2 * pad());
        setMeasuredDimension(resolveSize(w, widthSpec), resolveSize(h, heightSpec));
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        float top = pad(), bottom = h - pad();
        // Bright along the top, faint down the sides, a little light again at the bottom.
        rim.setShader(new LinearGradient(0, top, 0, bottom,
                new int[] {0xFFFFFFFF, 0x59FFFFFF, 0x40FFFFFF, 0xB3FFFFFF},
                new float[] {0f, 0.35f, 0.75f, 1f}, Shader.TileMode.CLAMP));
        sheen.setShader(new LinearGradient(0, top, 0, top + (bottom - top) * 0.5f,
                0x4DFFFFFF, 0x00FFFFFF, Shader.TileMode.CLAMP));
    }

    @Override
    protected void onDraw(Canvas c) {
        float x = pad(), y = pad() - inkTop;
        c.drawText(text, x, y, shadow);
        if (blur != null) {
            getLocationInWindow(location);
            View root = getRootView();
            // New wallpaper underneath, the old one fading out on top.
            paintBody(c, body, blur, root, 255, x, y);
            if (oldBlur != null && mix < 1f) paintBody(c, oldBody, oldBlur, root, Math.round(255 * (1f - mix)), x, y);
        } else {
            body.setAlpha(0xF2);
            c.drawText(text, x, y, body);
        }
        c.drawText(text, x, y, sheen);
        c.drawPath(outline(x, y), rim);
    }

    /**
     * The letters' outline with overlapping parts merged. Rounded fonts build some digits from
     * overlapping shapes, and stroking those as they are draws seams inside the letters.
     */
    private Path outline(float x, float y) {
        if (outlineStale) {
            Path raw = new Path();
            rim.getTextPath(text, 0, text.length(), x, y, raw);
            outline.reset();
            if (!outline.op(raw, Path.Op.UNION)) outline.set(raw);
            outlineStale = false;
        }
        return outline;
    }

    private void paintBody(Canvas c, Paint p, Bitmap b, View root, int alpha, float x, float y) {
        matrix.setScale(root.getWidth() / (float) b.getWidth(), root.getHeight() / (float) b.getHeight());
        matrix.postTranslate(-location[0], -location[1]);
        matrix.postScale(LENS, LENS, getWidth() / 2f, getHeight() / 2f);   // the lens
        p.getShader().setLocalMatrix(matrix);
        p.setAlpha(alpha);
        c.drawText(text, x, y, p);
    }
}
