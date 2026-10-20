package local.tvhome;

import android.content.Context;
import android.graphics.Color;
import android.graphics.Outline;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewOutlineProvider;
import android.view.animation.DecelerateInterpolator;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * A rounded card that lifts toward you when focused, tvOS-style: it grows, casts a soft shadow and
 * catches a little light. Artwork tiles can show their name underneath while focused; text tiles
 * (Settings) are frosted glass that turns white when focused.
 */
final class Tile extends LinearLayout {
    private static final DecelerateInterpolator EASE = new DecelerateInterpolator(1.6f);

    final FrameLayout card;
    private final TextView label;     // under the card while focused, or null
    private final TextView inner;     // inside the card (text tiles), or null
    private final View gloss;
    private final GradientDrawable glass;
    private final float focusScale, dp;
    private OnFocusChangeListener listener;

    static Tile art(Context c, int w, int h, Drawable artwork, String name, boolean showLabel, float focusScale) {
        return new Tile(c, w, h, artwork, name, showLabel, focusScale);
    }

    static Tile text(Context c, int w, int h, String name) {
        return new Tile(c, w, h, null, name, false, 1.08f);
    }

    private Tile(Context c, int w, int h, Drawable artwork, String name, boolean showLabel, float focusScale) {
        super(c);
        dp = c.getResources().getDisplayMetrics().density;
        this.focusScale = focusScale;
        setOrientation(VERTICAL);
        setGravity(Gravity.CENTER_HORIZONTAL);
        setClipChildren(false);
        setClipToPadding(false);

        float radius = 12 * dp;
        card = new FrameLayout(c);
        card.setFocusable(true);
        card.setClickable(true);
        card.setOutlineProvider(new RoundOutline(radius));
        card.setClipToOutline(true);
        card.setContentDescription(name);

        if (artwork != null) {
            glass = null;
            card.setBackgroundColor(0xFF2A2D33);
            ImageView img = new ImageView(c);
            img.setScaleType(ImageView.ScaleType.CENTER_CROP);
            img.setImageDrawable(artwork);
            card.addView(img, new FrameLayout.LayoutParams(-1, -1));
            inner = null;
        } else {
            glass = new GradientDrawable();
            glass.setCornerRadius(radius);
            glass.setColor(0x2EFFFFFF);
            glass.setStroke(Math.max(1, Math.round(dp)), 0x40FFFFFF);
            card.setBackground(glass);
            inner = new TextView(c);
            inner.setText(name);
            inner.setTextColor(Color.WHITE);
            inner.setTextSize(15);
            inner.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
            inner.setGravity(Gravity.CENTER);
            inner.setMaxLines(2);
            int pad = Math.round(10 * dp);
            inner.setPadding(pad, 0, pad, 0);
            card.addView(inner, new FrameLayout.LayoutParams(-1, -1));
        }

        gloss = new View(c);
        gloss.setBackground(new GradientDrawable(GradientDrawable.Orientation.TL_BR,
                new int[] {0x38FFFFFF, 0x0AFFFFFF, 0x00FFFFFF}));
        gloss.setAlpha(0f);
        card.addView(gloss, new FrameLayout.LayoutParams(-1, -1));
        addView(card, new LayoutParams(w, h));

        if (showLabel) {
            label = new TextView(c);
            label.setText(name);
            label.setTextColor(Color.WHITE);
            label.setTextSize(13);
            label.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
            label.setSingleLine();
            label.setEllipsize(TextUtils.TruncateAt.END);
            label.setGravity(Gravity.CENTER);
            label.setShadowLayer(4 * dp, 0, dp, 0xAA000000);
            label.setAlpha(0f);
            LayoutParams lp = new LayoutParams(w, -2);
            lp.topMargin = Math.round(8 * dp);
            addView(label, lp);
        } else {
            label = null;
        }

        card.setOnFocusChangeListener((v, hasFocus) -> {
            setFocused(hasFocus);
            if (listener != null) listener.onFocusChange(v, hasFocus);
        });
    }

    /** Extra focus callback, e.g. to show the focused app's name above the dock. */
    void onFocus(OnFocusChangeListener l) {
        listener = l;
    }

    private void setFocused(boolean f) {
        card.animate().scaleX(f ? focusScale : 1f).scaleY(f ? focusScale : 1f)
                .translationZ(f ? 10 * dp : 0).setDuration(170).setInterpolator(EASE).start();
        gloss.animate().alpha(f ? 1f : 0f).setDuration(170).start();
        if (label != null) {
            label.animate().alpha(f ? 1f : 0f).translationY(f ? 4 * dp : 0)
                    .setDuration(170).setInterpolator(EASE).start();
        }
        if (inner != null) {
            glass.setColor(f ? 0xF5FFFFFF : 0x2EFFFFFF);
            inner.setTextColor(f ? 0xFF1C1C1E : Color.WHITE);
        }
    }

    private static final class RoundOutline extends ViewOutlineProvider {
        private final float radius;

        RoundOutline(float radius) { this.radius = radius; }

        @Override public void getOutline(View v, Outline o) {
            o.setRoundRect(0, 0, v.getWidth(), v.getHeight(), radius);
        }
    }
}
