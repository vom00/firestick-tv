package local.tvhome;

import android.accessibilityservice.AccessibilityService;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;
import android.view.KeyEvent;
import android.view.View;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.ImageView;

/**
 * Makes the remote's Home button land on TV Home.
 *
 * Fire OS sends Home straight to Amazon's launcher (only Amazon-signed apps may take it over), and
 * Android then holds back other apps' launches for about 5 seconds. So when Amazon's home screen
 * appears, this service:
 *   1. covers it with a blurred copy of the wallpaper, so its ads never show;
 *   2. presses the TV Home tile in Amazon's favourites row, which Amazon's launcher may open
 *      right away (about 1-2 s);
 *   3. also asks for TV Home directly, which Android runs once the 5-second hold ends.
 * Leaving Amazon's quick menu via Apps or Settings is let through, since those live in Amazon's
 * launcher. On devices that do pass Home to accessibility services, the key is handled directly
 * and holding Home opens the quick menu (Sleep, Mirroring, Profiles).
 */
public class HomeKeyService extends AccessibilityService {
    static final String TAG = "TVHome";
    private static final String AMAZON_LAUNCHER = "com.amazon.tv.launcher";
    private static final String AMAZON_SETTINGS = "com.amazon.tv.settings.v2";
    private static final String QUICK_MENU = "com.amazon.device.settings.action.SHOW_HUD";
    // Our app's name in Amazon's favourites row. Amazon caches names, so it may still say "Home".
    private static final String[] TILE_LABELS = {"TV Home", "Home"};
    private static final String APP_TILE_CLASS = "android.widget.FrameLayout";
    private static final long LONG_PRESS_MS = 600;
    private static final long COVER_MAX_MS = 7_000;
    private static final long QUICK_MENU_GRACE_MS = 2_000;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private boolean longPressFired;
    // Picking Apps or Settings in Amazon's quick menu opens screens inside Amazon's launcher; that
    // one transition is let through. Any later return to Amazon's home switches back to TV Home.
    private long amazonHomeAllowedUntil;
    private String lastPackage = "";
    private String lastClass = "";
    private int tileAttempts;
    private View cover;

    private final Runnable longPress = () -> {
        longPressFired = true;
        openQuickMenu(this);
    };
    private final Runnable tapTile = this::tapOurTile;
    private final Runnable removeCover = this::hideCover;

    @Override
    protected boolean onKeyEvent(KeyEvent event) {
        if (event.getKeyCode() != KeyEvent.KEYCODE_HOME) return false;
        if (event.getAction() == KeyEvent.ACTION_DOWN && event.getRepeatCount() == 0) {
            longPressFired = false;
            handler.postDelayed(longPress, LONG_PRESS_MS);
        } else if (event.getAction() == KeyEvent.ACTION_UP) {
            handler.removeCallbacks(longPress);
            if (!longPressFired) openHome(this);
        }
        return true;
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event.getEventType() != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return;
        String pkg = String.valueOf(event.getPackageName());
        String cls = String.valueOf(event.getClassName());

        if (pkg.equals(getPackageName())) {
            handler.removeCallbacks(tapTile);
            handler.removeCallbacks(removeCover);
            handler.postDelayed(removeCover, 250);   // let TV Home draw first
        } else if (pkg.equals(AMAZON_LAUNCHER) && cls.startsWith(AMAZON_LAUNCHER + ".ui.HomeActivity")) {
            long now = SystemClock.uptimeMillis();
            if (lastPackage.equals(AMAZON_SETTINGS) && lastClass.contains(".hud.")) {
                amazonHomeAllowedUntil = now + QUICK_MENU_GRACE_MS;
            }
            if (now >= amazonHomeAllowedUntil) leaveAmazonHome();
        } else if (!pkg.equals(AMAZON_LAUNCHER) && cover != null) {
            hideCover();   // something else came up instead
        }
        lastPackage = pkg;
        lastClass = cls;
    }

    @Override
    public void onInterrupt() {}

    @Override
    public void onDestroy() {
        hideCover();
        super.onDestroy();
    }

    private void leaveAmazonHome() {
        showCover();
        openHome(this);
        tileAttempts = 0;
        handler.removeCallbacks(tapTile);
        handler.post(tapTile);
    }

    private void tapOurTile() {
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root != null && AMAZON_LAUNCHER.equals(String.valueOf(root.getPackageName()))) {
            for (String label : TILE_LABELS) {
                for (AccessibilityNodeInfo n : root.findAccessibilityNodeInfosByText(label)) {
                    // App tiles are FrameLayouts; Amazon's own "Home" button is a ViewGroup.
                    if (!label.equals(String.valueOf(n.getContentDescription()))
                            || !APP_TILE_CLASS.equals(String.valueOf(n.getClassName()))) continue;
                    if (n.isClickable() && n.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return;
                }
            }
        }
        if (++tileAttempts < 12) handler.postDelayed(tapTile, 120);
        else Log.i(TAG, "TV Home tile not found in Amazon's favourites row; waiting for Android instead");
    }

    /** A full-screen picture of the current wallpaper over Amazon's home while we switch. */
    private void showCover() {
        if (cover != null) return;
        ImageView v = new ImageView(this);
        v.setScaleType(ImageView.ScaleType.CENTER_CROP);
        v.setBackgroundColor(Color.BLACK);
        Bitmap wallpaper = HomeActivity.coverImage;
        if (wallpaper != null) v.setImageBitmap(wallpaper);
        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN | WindowManager.LayoutParams.FLAG_FULLSCREEN,
                PixelFormat.OPAQUE);
        try {
            ((WindowManager) getSystemService(WINDOW_SERVICE)).addView(v, lp);
            cover = v;
            handler.postDelayed(removeCover, COVER_MAX_MS);
        } catch (RuntimeException e) {
            Log.w(TAG, "can't show cover", e);
        }
    }

    private void hideCover() {
        handler.removeCallbacks(removeCover);
        if (cover == null) return;
        try {
            ((WindowManager) getSystemService(WINDOW_SERVICE)).removeViewImmediate(cover);
        } catch (RuntimeException e) {
            Log.w(TAG, "can't remove cover", e);
        }
        cover = null;
    }

    static void openHome(Context context) {
        Intent home = new Intent(context, HomeActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
        context.startActivity(home);
    }

    static void openQuickMenu(Context context) {
        try {
            context.startActivity(new Intent(QUICK_MENU).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (ActivityNotFoundException | SecurityException e) {
            Log.w(TAG, "quick menu unavailable", e);
        }
    }
}
