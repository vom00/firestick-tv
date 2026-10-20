package local.tvhome;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.ColorMatrix;
import android.graphics.ColorMatrixColorFilter;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Outline;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Shader;
import android.view.View;
import android.view.ViewOutlineProvider;
import android.widget.FrameLayout;

/**
 * Liquid glass. Shows the wallpaper behind it blurred, slightly magnified and more saturated, so
 * the panel reads as a thick lens, with a bright rim that catches light at the corners and a soft
 * sheen across the top. It paints a small pre-blurred copy of the wallpaper lined up with the
 * screen instead of blurring live, which a Fire TV Stick couldn't do smoothly. Focused buttons
 * fill in white, tvOS-style.
 */
class Glass extends FrameLayout {
    private static final float LENS = 1.08f;

    private final float radius;
    private final Paint newPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final Paint oldPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final Paint tint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint sheen = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint rim = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();
    private final Matrix matrix = new Matrix();
    private final int[] location = new int[2];
    private Bitmap blur, oldBlur;
    private float mix = 1f, solid;
    private ValueAnimator fade, solidAnim;

    Glass(Context c, float radius) {
        super(c);
        this.radius = radius;
        float dp = c.getResources().getDisplayMetrics().density;
        setWillNotDraw(false);

        ColorMatrix vivid = new ColorMatrix();
        vivid.setSaturation(1.6f);
        ColorMatrix dim = new ColorMatrix();
        dim.setScale(0.78f, 0.78f, 0.78f, 1f);
        vivid.postConcat(dim);
        ColorMatrixColorFilter filter = new ColorMatrixColorFilter(vivid);
        newPaint.setColorFilter(filter);
        oldPaint.setColorFilter(filter);

        tint.setColor(0x14FFFFFF);
        fill.setColor(0xF5FFFFFF);
        rim.setStyle(Paint.Style.STROKE);
        rim.setStrokeWidth(dp);

        setOutlineProvider(new Shape(radius));   // for the soft shadow
    }

    private static final class Shape extends ViewOutlineProvider {
        private final float radius;

        Shape(float radius) { this.radius = radius; }

        @Override public void getOutline(View v, Outline o) {
            o.setRoundRect(0, 0, v.getWidth(), v.getHeight(), Math.min(radius, v.getHeight() / 2f));
            o.setAlpha(0.55f);
        }
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        // Light catches the rounded ends; the long straight edges stay nearly invisible, so they
        // don't read as bars on a TV that sharpens the picture.
        float end = Math.min(0.2f, Math.min(radius, h / 2f) * 1.2f / Math.max(1, w));
        rim.setShader(new LinearGradient(0, 0, w, 0,
                new int[] {0x8CFFFFFF, 0x0FFFFFFF, 0x0FFFFFFF, 0x66FFFFFF},
                new float[] {0f, end, 1f - end, 1f}, Shader.TileMode.CLAMP));
        sheen.setShader(new LinearGradient(0, 0, 0, h * 0.6f, 0x1FFFFFFF, 0x00FFFFFF, Shader.TileMode.CLAMP));
    }

    void setBlur(Bitmap next, long fadeMs) {
        if (fade != null) fade.cancel();
        oldBlur = (blur != null && fadeMs > 0) ? blur : null;
        if (oldBlur != null) oldPaint.setShader(new BitmapShader(oldBlur, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP));
        blur = next;
        newPaint.setShader(new BitmapShader(next, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP));
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

    /** White frosted fill for a focused button. */
    void setSolid(boolean on) {
        if (solidAnim != null) solidAnim.cancel();
        solidAnim = ValueAnimator.ofFloat(solid, on ? 1f : 0f).setDuration(140);
        solidAnim.addUpdateListener(a -> {
            solid = (float) a.getAnimatedValue();
            invalidate();
        });
        solidAnim.start();
    }

    @Override
    protected void onDraw(Canvas c) {
        float r = Math.min(radius, getHeight() / 2f);
        rect.set(0, 0, getWidth(), getHeight());
        if (blur != null) {
            getLocationInWindow(location);
            View root = getRootView();
            if (oldBlur != null && mix < 1f) paintBlur(c, oldBlur, oldPaint, root, 255, r);
            paintBlur(c, blur, newPaint, root, Math.round(255 * mix), r);
        }
        c.drawRoundRect(rect, r, r, tint);
        c.drawRoundRect(rect, r, r, sheen);
        if (solid > 0f) {
            fill.setAlpha(Math.round(245 * solid));
            c.drawRoundRect(rect, r, r, fill);
        }
        float half = rim.getStrokeWidth() / 2;
        rect.inset(half, half);
        c.drawRoundRect(rect, r - half, r - half, rim);
    }

    private void paintBlur(Canvas c, Bitmap b, Paint p, View root, int alpha, float r) {
        matrix.setScale(root.getWidth() / (float) b.getWidth(), root.getHeight() / (float) b.getHeight());
        matrix.postTranslate(-location[0], -location[1]);
        matrix.postScale(LENS, LENS, getWidth() / 2f, getHeight() / 2f);   // the lens
        p.getShader().setLocalMatrix(matrix);
        p.setAlpha(alpha);
        c.drawRoundRect(rect, r, r, p);
    }
}
