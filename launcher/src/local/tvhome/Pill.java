package local.tvhome;

import android.content.Context;
import android.graphics.Typeface;
import android.view.Gravity;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

/** A small liquid-glass capsule button with an icon, like the Apps / Search / Settings bar. */
final class Pill extends Glass {
    private static final int TEXT = 0xF2FFFFFF, TEXT_FOCUSED = 0xFF1C1C1E;

    private final Art.Icon icon;
    private final TextView text;

    Pill(Context c, int iconKind, String label) {
        super(c, 100 * c.getResources().getDisplayMetrics().density);
        float dp = c.getResources().getDisplayMetrics().density;
        setFocusable(true);
        setClickable(true);
        setContentDescription(label);

        LinearLayout row = new LinearLayout(c);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        int padH = Math.round(13 * dp);
        row.setPadding(padH, 0, Math.round(15 * dp), 0);

        icon = Art.icon(iconKind);
        icon.setColor(TEXT);
        ImageView iv = new ImageView(c);
        iv.setImageDrawable(icon);
        int size = Math.round(13 * dp);
        row.addView(iv, new LinearLayout.LayoutParams(size, size));

        text = new TextView(c);
        text.setText(label);
        text.setTextSize(12);
        text.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        text.setTextColor(TEXT);
        text.setSingleLine();
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(-2, -2);
        tlp.leftMargin = Math.round(7 * dp);
        row.addView(text, tlp);
        addView(row, new LayoutParams(-2, -1));

        setOnFocusChangeListener((v, f) -> {
            setSolid(f);
            icon.setColor(f ? TEXT_FOCUSED : TEXT);
            text.setTextColor(f ? TEXT_FOCUSED : TEXT);
            animate().scaleX(f ? 1.06f : 1f).scaleY(f ? 1.06f : 1f).setDuration(140).start();
        });
    }
}
