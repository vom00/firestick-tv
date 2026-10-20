package local.tvhome;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.ActivityInfo;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.text.format.DateFormat;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.DecelerateInterpolator;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Apple TV-style home screen: a greeting and clock, glass Apps / Search / Settings buttons, and a
 * liquid-glass dock of favourites over rotating nature photos. Pressing Down on the dock slides it
 * to the top and shows all your other apps underneath, like tvOS.
 */
public class HomeActivity extends Activity {
    private static final String AMAZON_LAUNCHER = "com.amazon.tv.launcher";
    private static final String SILK = "com.amazon.cloud9";
    private static final String SEARCH_URL = "https://www.bing.com/search?q=";
    private static final List<String> KEEP_SYSTEM_APPS = Arrays.asList("com.amazon.venezia", SILK);
    private static final List<String> DEFAULT_DOCK = Arrays.asList(
            "com.netflix.ninja", "com.amazon.firetv.youtube", "com.amazon.firebat",
            "com.crunchyroll.crunchyroid", "com.mvas.stb.emu.pro", SILK);
    private static final int COLUMNS = 6;   // dock and app grid; a longer dock scrolls
    private static final long ROTATE_MS = 45_000, FADE_MS = 1_500, SLIDE_MS = 360;
    private static final String SEARCH = "search", SETTINGS = "settings";
    private static final DecelerateInterpolator EASE = new DecelerateInterpolator(1.8f);

    /** Blurred copy of the wallpaper on screen; HomeKeyService shows it while leaving Amazon's home. */
    static volatile Bitmap coverImage;

    /** Settings shortcuts: label, intent action. Ones the device doesn't have are hidden. */
    private static final String[][] SETTINGS_ITEMS = {
            {"Network", "android.settings.WIFI_SETTINGS"},
            {"Display & Sounds", "com.amazon.device.settings.action.DISPLAY_AND_SOUNDS"},
            {"Applications", "com.amazon.device.settings.action.APPLICATIONS"},
            {"Controllers & Bluetooth", "com.amazon.device.settings.action.CONTROLLERS"},
            {"Preferences", "com.amazon.device.settings.action.PREFERENCES"},
            {"Privacy", "com.amazon.device.settings.action.PRIVACY_SETTINGS"},
            {"Accessibility", "com.amazon.device.settings.action.ACCESSIBILITY"},
            {"Account & Profiles", "com.amazon.device.settings.action.MY_ACCOUNT"},
            {"My Fire TV", "com.amazon.device.settings.action.DEVICE"},
            {"Sleep, Mirroring & Profiles", "com.amazon.device.settings.action.SHOW_HUD"},
    };

    private static final class AppEntry {
        final String pkg, label;
        final Intent launch;
        final ActivityInfo info;

        AppEntry(String pkg, String label, Intent launch, ActivityInfo info) {
            this.pkg = pkg;
            this.label = label;
            this.launch = launch;
            this.info = info;
        }
    }

    private final Handler ui = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final Map<String, AppEntry> apps = new LinkedHashMap<>();
    private final Map<String, Drawable> art = new HashMap<>();
    private final List<Glass> glass = new ArrayList<>();
    private final List<Tile> gridTiles = new ArrayList<>();
    private SharedPreferences prefs;
    private Wallpapers wallpapers;
    private Wallpapers.Frame current;
    private float dp;
    private boolean started;
    private boolean released;   // full-size photo given back while hidden; see releaseWhileHidden()
    private boolean library;    // all-apps grid showing
    private int tileW, tileH, gap;

    private FrameLayout root;
    private ImageView wallBottom, wallTop, libraryBg;
    private View libraryDim;
    private LinearLayout head, bar, bottom, dockRow, gridRows;
    private ScrollView grid;
    private TextView greeting, date, title, dockHint;
    private GlassText clock;
    private final Map<Integer, Typeface> fonts = new HashMap<>();
    private Pill appsPill, searchPill, settingsPill, lastPill;
    private Glass dock;
    private View lastDockFocus;
    private FrameLayout overlay;
    private String overlayKind;
    private Glass searchBox;
    private ImageView overlayBg;

    // Timed rotation waits while the app grid, Search or Settings is open.
    private final Runnable rotate = () -> {
        if (overlay != null || library) ui.postDelayed(this.rotate, ROTATE_MS); else nextWallpaper();
    };
    private final Runnable release = this::releaseWhileHidden;
    private final BroadcastReceiver timeReceiver = new Receiver(this::updateClock);
    private final BroadcastReceiver packageReceiver = new Receiver(this::reloadApps);

    /** Remembers which button had focus, so Up from the dock goes back to it. */
    private static final class PillFocus implements View.OnFocusChangeListener {
        private final HomeActivity home;
        private final Pill pill;
        private final View.OnFocusChangeListener inner;

        PillFocus(HomeActivity home, Pill pill, View.OnFocusChangeListener inner) {
            this.home = home;
            this.pill = pill;
            this.inner = inner;
        }

        @Override public void onFocusChange(View v, boolean hasFocus) {
            if (hasFocus) home.lastPill = pill;
            if (inner != null) inner.onFocusChange(v, hasFocus);
        }
    }

    /**
     * The dock's scroller. Android scrolls just far enough to show a focused tile's edge, so the
     * last tile's focus lift was cut off flat; this keeps the row's padding in view as well.
     */
    private static final class DockScroll extends HorizontalScrollView {
        private final int margin;

        DockScroll(Context c, int margin) {
            super(c);
            this.margin = margin;
        }

        @Override protected int computeScrollDeltaToGetChildRectOnScreen(Rect rect) {
            Rect r = new Rect(rect);
            r.inset(-margin, 0);
            return super.computeScrollDeltaToGetChildRectOnScreen(r);
        }
    }

    private static final class Receiver extends BroadcastReceiver {
        private final Runnable action;

        Receiver(Runnable action) { this.action = action; }

        @Override public void onReceive(Context c, Intent i) { action.run(); }
    }

    // ---- lifecycle -------------------------------------------------------------------------

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        dp = getResources().getDisplayMetrics().density;
        prefs = getSharedPreferences("home", MODE_PRIVATE);
        wallpapers = new Wallpapers(this);
        // Six tiles across, with room between them for a focused tile to grow.
        gap = px(16);
        int inner = getResources().getDisplayMetrics().widthPixels - px(44) * 2 - px(12) * 2;
        tileW = (inner - gap * (COLUMNS - 1)) / COLUMNS;
        tileH = tileW * 9 / 16;
        buildUi();
        setContentView(root);
        reloadApps();
    }

    @Override
    protected void onStart() {
        super.onStart();
        started = true;
        IntentFilter time = new IntentFilter(Intent.ACTION_TIME_TICK);
        time.addAction(Intent.ACTION_TIME_CHANGED);
        time.addAction(Intent.ACTION_TIMEZONE_CHANGED);
        registerReceiver(timeReceiver, time);
        IntentFilter pkgs = new IntentFilter(Intent.ACTION_PACKAGE_ADDED);
        pkgs.addAction(Intent.ACTION_PACKAGE_REMOVED);
        pkgs.addAction(Intent.ACTION_PACKAGE_CHANGED);
        pkgs.addDataScheme("package");
        registerReceiver(packageReceiver, pkgs);
        updateClock();
        ui.removeCallbacks(release);
        if (current == null || released) {
            released = false;
            nextWallpaper();
        } else {
            ui.postDelayed(rotate, ROTATE_MS);
        }
    }

    @Override
    protected void onStop() {
        super.onStop();
        started = false;
        ui.removeCallbacks(rotate);
        ui.postDelayed(release, 3_000);
        unregisterReceiver(timeReceiver);
        unregisterReceiver(packageReceiver);
    }

    /**
     * While an app is in front, hand back the full-size photo and the GPU memory (about 25 MB on a
     * Fire TV Stick). Coming home shows the blurred copy, then fades in the next photo.
     */
    private void releaseWhileHidden() {
        if (started || current == null) return;
        wallTop.animate().cancel();
        wallTop.setImageDrawable(null);
        wallBottom.setImageBitmap(current.blur);
        if (!current.full.isRecycled()) current.full.recycle();
        released = true;
        trimGraphics(TRIM_MEMORY_COMPLETE);   // what Android itself does to background apps
    }

    /**
     * Asks the renderer to drop cached GPU memory. COMPLETE (while hidden) frees everything;
     * UI_HIDDEN (while showing) only drops what isn't on screen, which Android otherwise keeps,
     * so each wallpaper change used to leave 30-40 MB of old textures behind.
     */
    private static void trimGraphics(int level) {
        try {
            Class<?> wmg = Class.forName("android.view.WindowManagerGlobal");
            Object instance = wmg.getMethod("getInstance").invoke(null);
            wmg.getMethod("trimMemory", int.class).invoke(instance, level);
        } catch (ReflectiveOperationException | RuntimeException e) {
            Log.w(HomeKeyService.TAG, "can't release graphics memory", e);
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        worker.shutdownNow();
    }

    /** Home pressed while we're already showing: back to the dock. */
    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        if (overlay != null) closeOverlay();
        if (library) hideLibrary(); else focusDock();
    }

    @Override
    public void onBackPressed() {
        if (overlay != null) closeOverlay();
        else if (library) hideLibrary();
        // Otherwise stay put: this is the home screen.
    }

    // ---- layout ----------------------------------------------------------------------------

    private void buildUi() {
        root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);
        root.setClipChildren(false);   // focused buttons grow past their row

        wallBottom = wallpaperView();
        wallTop = wallpaperView();
        wallTop.setAlpha(0f);
        root.addView(wallBottom, fill());
        root.addView(wallTop, fill());

        // Soft shading so the clock, buttons and dock stay readable on bright photos.
        root.addView(shade(GradientDrawable.Orientation.TOP_BOTTOM, 0x80000000),
                new FrameLayout.LayoutParams(-1, px(230), Gravity.TOP));
        root.addView(shade(GradientDrawable.Orientation.BOTTOM_TOP, 0x99000000),
                new FrameLayout.LayoutParams(-1, px(300), Gravity.BOTTOM));

        // Behind the app grid: the wallpaper, blurred and dimmed.
        libraryBg = wallpaperView();
        libraryBg.setAlpha(0f);
        root.addView(libraryBg, fill());
        libraryDim = new View(this);
        libraryDim.setBackgroundColor(0x59000000);
        libraryDim.setAlpha(0f);
        root.addView(libraryDim, fill());

        // Greeting, clock and date, top left.
        head = new LinearLayout(this);
        head.setOrientation(LinearLayout.VERTICAL);
        greeting = caps(11);
        clock = new GlassText(this, font(700), 66);
        date = caps(11);
        // Explicit wrap_content: LinearLayout's default (match_parent) sizes the greeting and
        // date to the clock's width, which cut "Good afternoon" to "Good" when the time was short.
        head.addView(greeting, new LinearLayout.LayoutParams(-2, -2));
        // The glass clock keeps 10dp around its digits for the shadow; pull it back into line.
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(-2, -2);
        clp.setMargins(-px(10), px(8) - px(10), 0, px(9) - px(10));
        head.setClipChildren(false);
        head.addView(clock, clp);
        head.addView(date, new LinearLayout.LayoutParams(-2, -2));
        FrameLayout.LayoutParams hlp = new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.START);
        hlp.setMargins(px(50), px(30), 0, 0);
        root.addView(head, hlp);

        // Apps / Search / Settings, top right.
        bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setClipChildren(false);
        appsPill = new Pill(this, Art.APPS, "Apps");
        searchPill = new Pill(this, Art.SEARCH, "Search");
        settingsPill = new Pill(this, Art.SETTINGS, "Settings");
        appsPill.setOnClickListener(v -> showLibrary(0));
        searchPill.setOnClickListener(v -> openOverlay(SEARCH));
        settingsPill.setOnClickListener(v -> openOverlay(SETTINGS));
        for (final Pill p : new Pill[] {appsPill, searchPill, settingsPill}) {
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(p == searchPill ? px(150) : -2, px(30));
            lp.leftMargin = px(9);
            // Keep focus in the button row: Left/Right stop at the ends, Down returns to the dock.
            p.setOnKeyListener((v, code, e) -> {
                boolean stop = code == KeyEvent.KEYCODE_DPAD_UP
                        || (code == KeyEvent.KEYCODE_DPAD_LEFT && p == appsPill)
                        || (code == KeyEvent.KEYCODE_DPAD_RIGHT && p == settingsPill);
                boolean down = code == KeyEvent.KEYCODE_DPAD_DOWN && lastDockFocus != null;
                if (!stop && !down) return false;
                if (down && e.getAction() == KeyEvent.ACTION_DOWN) lastDockFocus.requestFocus();
                return true;
            });
            p.setOnFocusChangeListener(new PillFocus(this, p, p.getOnFocusChangeListener()));
            bar.addView(p, lp);
            glass.add(p);
        }
        FrameLayout.LayoutParams blp = new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.END);
        blp.setMargins(0, px(36), px(48), 0);
        root.addView(bar, blp);

        // All your other apps, shown under the dock when you press Down.
        grid = new ScrollView(this);
        grid.setVerticalScrollBarEnabled(false);
        grid.setClipToPadding(true);
        grid.setVisibility(View.GONE);
        gridRows = new LinearLayout(this);
        gridRows.setOrientation(LinearLayout.VERTICAL);
        gridRows.setClipChildren(false);
        gridRows.setClipToPadding(false);   // a focused tile in the first column grows into the margin
        gridRows.setPadding(px(44) + px(12), 0, px(44) + px(12), 0);
        grid.addView(gridRows);
        root.addView(grid, fill());

        // The focused app's name, then the dock.
        bottom = new LinearLayout(this);
        bottom.setOrientation(LinearLayout.VERTICAL);
        bottom.setClipChildren(false);
        title = text(27, "sans-serif", Color.WHITE);
        title.setSingleLine();
        title.setAlpha(0f);
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(-2, -2);
        tlp.setMargins(px(10), 0, 0, px(10));
        bottom.addView(title, tlp);

        dock = new Glass(this, px(26));
        dock.setElevation(px(12));
        HorizontalScrollView scroll = new DockScroll(this, px(12));
        scroll.setHorizontalScrollBarEnabled(false);
        scroll.setOverScrollMode(View.OVER_SCROLL_NEVER);
        dockRow = new LinearLayout(this);
        dockRow.setOrientation(LinearLayout.HORIZONTAL);
        dockRow.setClipChildren(false);
        dockRow.setClipToPadding(false);
        dockRow.setPadding(px(12), px(14), px(12), px(14));
        scroll.addView(dockRow);
        dock.addView(scroll, new FrameLayout.LayoutParams(-1, -2));
        dockHint = text(14, "sans-serif", 0xCCFFFFFF);
        dockHint.setText("Your dock is empty. Open Apps and press Menu on an app to add it here.");
        dockHint.setVisibility(View.GONE);
        dock.addView(dockHint, new FrameLayout.LayoutParams(-2, -2, Gravity.CENTER));
        bottom.addView(dock, new LinearLayout.LayoutParams(-1, -2));
        glass.add(dock);
        FrameLayout.LayoutParams bot = new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM);
        bot.setMargins(px(44), 0, px(44), px(30));
        root.addView(bottom, bot);
    }

    private ImageView wallpaperView() {
        ImageView v = new ImageView(this);
        v.setScaleType(ImageView.ScaleType.CENTER_CROP);
        return v;
    }

    private View shade(GradientDrawable.Orientation o, int color) {
        View v = new View(this);
        v.setBackground(new GradientDrawable(o, new int[] {color, 0x00000000}));
        return v;
    }

    private TextView text(float sp, String family, int color) {
        TextView t = new TextView(this);
        t.setTextSize(sp);
        t.setTypeface(Typeface.create(family, Typeface.NORMAL));
        t.setTextColor(color);
        t.setShadowLayer(px(6), 0, px(1), 0x66000000);
        return t;
    }

    /** Small spaced-out capitals, like "GOOD AFTERNOON". */
    private TextView caps(float sp) {
        TextView t = text(sp, "sans-serif-medium", 0xD9FFFFFF);
        t.setTypeface(font(700));
        t.setIncludeFontPadding(false);
        t.setLetterSpacing(0.08f);   // Nunito is wider than Roboto, so less than before
        t.setAllCaps(true);
        return t;
    }

    /** Nunito, a free rounded font close to the one on the iPhone lock screen, at a given weight. */
    private Typeface font(int weight) {
        Typeface f = fonts.get(weight);
        if (f == null) {
            f = Build.VERSION.SDK_INT >= 26
                    ? new Typeface.Builder(getAssets(), "fonts/Nunito.ttf").setFontVariationSettings("'wght' " + weight).build()
                    : Typeface.createFromAsset(getAssets(), "fonts/Nunito.ttf");
            fonts.put(weight, f);
        }
        return f;
    }

    private static FrameLayout.LayoutParams fill() {
        return new FrameLayout.LayoutParams(-1, -1);
    }

    private int px(float dips) {
        return Math.round(dips * dp);
    }

    private void updateClock() {
        Date now = new Date();
        int hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY);
        greeting.setText(hour >= 5 && hour < 12 ? "Good morning" : hour >= 12 && hour < 17 ? "Good afternoon" : "Good evening");
        clock.setText(DateFormat.format(DateFormat.is24HourFormat(this) ? "H:mm" : "h:mm", now));
        date.setText(DateFormat.format("EEEE, d MMMM", now));
    }

    // ---- wallpaper -------------------------------------------------------------------------

    private void nextWallpaper() {
        ui.removeCallbacks(rotate);
        DisplayMetrics m = new DisplayMetrics();
        getWindowManager().getDefaultDisplay().getRealMetrics(m);
        final int w = m.widthPixels, h = m.heightPixels;
        worker.execute(() -> {
            final Wallpapers.Frame f = wallpapers.next(w, h);
            ui.post(() -> {
                if (f != null) showWallpaper(f);
                if (started) ui.postDelayed(rotate, ROTATE_MS);
            });
        });
    }

    private void showWallpaper(final Wallpapers.Frame f) {
        coverImage = f.blur;
        if (overlayBg != null) overlayBg.setImageBitmap(f.blur);
        libraryBg.setImageBitmap(f.blur);
        if (current == null) {
            current = f;
            wallBottom.setImageBitmap(f.full);
            wallBottom.setAlpha(0f);
            wallBottom.animate().alpha(1f).setDuration(600).start();
            for (Glass g : glass) g.setBlur(f.blur, 0);
            clock.setBlur(f.blur, 0);
            return;
        }
        final Wallpapers.Frame previous = current;
        current = f;
        wallTop.setImageBitmap(f.full);
        wallTop.setAlpha(0f);
        // No hardware layer here: on a 1 GB stick a full-screen layer costs 8 MB for a 1.5 s fade.
        wallTop.animate().alpha(1f).setDuration(FADE_MS).withEndAction(() -> {
            wallBottom.setImageBitmap(f.full);
            wallTop.setImageDrawable(null);
            wallTop.setAlpha(0f);
            // Free the old photo once the render thread has moved on from it.
            ui.postDelayed(() -> {
                if (previous.full != current.full && !previous.full.isRecycled()) previous.full.recycle();
                trimGraphics(TRIM_MEMORY_UI_HIDDEN);
            }, 1_000);
        }).start();
        for (Glass g : glass) g.setBlur(f.blur, FADE_MS);
        clock.setBlur(f.blur, FADE_MS);
    }

    // ---- apps ------------------------------------------------------------------------------

    private void reloadApps() {
        worker.execute(() -> {
            final Map<String, AppEntry> found = queryApps();
            final Map<String, Drawable> artwork = new HashMap<>();
            PackageManager pm = getPackageManager();
            for (AppEntry a : found.values()) artwork.put(a.pkg, loadArt(a, pm));
            ui.post(() -> {
                apps.clear();
                apps.putAll(found);
                art.clear();
                art.putAll(artwork);
                buildDock(null);
                if (library) buildGrid();
            });
        });
    }

    private Map<String, AppEntry> queryApps() {
        PackageManager pm = getPackageManager();
        Map<String, AppEntry> byPkg = new HashMap<>();
        for (String category : new String[] {Intent.CATEGORY_LEANBACK_LAUNCHER, Intent.CATEGORY_LAUNCHER}) {
            boolean tvCategory = category.equals(Intent.CATEGORY_LEANBACK_LAUNCHER);
            Intent q = new Intent(Intent.ACTION_MAIN).addCategory(category);
            for (ResolveInfo ri : pm.queryIntentActivities(q, 0)) {
                String pkg = ri.activityInfo.packageName;
                if (pkg.equals(getPackageName()) || pkg.equals(AMAZON_LAUNCHER) || byPkg.containsKey(pkg)) continue;
                // Phone-style launcher entries from the system are plumbing (Downloads, Screensaver...).
                boolean system = (ri.activityInfo.applicationInfo.flags & ApplicationInfo.FLAG_SYSTEM) != 0;
                if (!tvCategory && system && !KEEP_SYSTEM_APPS.contains(pkg)) continue;
                Intent launch = new Intent(Intent.ACTION_MAIN).addCategory(category)
                        .setClassName(pkg, ri.activityInfo.name)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
                byPkg.put(pkg, new AppEntry(pkg, ri.loadLabel(pm).toString().trim(), launch, ri.activityInfo));
            }
        }
        List<AppEntry> sorted = new ArrayList<>(byPkg.values());
        Collections.sort(sorted, (a, b) -> a.label.compareToIgnoreCase(b.label));
        Map<String, AppEntry> out = new LinkedHashMap<>();
        for (AppEntry a : sorted) out.put(a.pkg, a);
        return out;
    }

    private static Drawable loadArt(AppEntry a, PackageManager pm) {
        Drawable banner = null;
        try {
            banner = a.info.loadBanner(pm);
            if (banner == null) banner = pm.getApplicationBanner(a.pkg);
        } catch (PackageManager.NameNotFoundException | RuntimeException ignored) {
        }
        return banner != null ? banner : Art.iconTile(a.info.loadIcon(pm));
    }

    private void launch(AppEntry a) {
        try {
            startActivity(a.launch);
        } catch (ActivityNotFoundException | SecurityException e) {
            toast("Couldn't open " + a.label);
        }
    }

    // ---- dock ------------------------------------------------------------------------------

    private List<String> dockIds() {
        String saved = prefs.getString("dock", null);
        List<String> ids = new ArrayList<>();
        for (String id : saved == null ? DEFAULT_DOCK : Arrays.asList(TextUtils.split(saved, ","))) {
            if (!id.startsWith("@")) ids.add(id);   // older versions kept All Apps and Settings in the dock
        }
        return ids;
    }

    private void saveDock(List<String> ids) {
        prefs.edit().putString("dock", TextUtils.join(",", ids)).apply();
    }

    /** Dock apps that can be shown right now (skips apps that aren't installed). */
    private List<String> visibleDock() {
        List<String> out = new ArrayList<>();
        for (String id : dockIds()) if (apps.containsKey(id)) out.add(id);
        return out;
    }

    private void buildDock(String focusId) {
        if (focusId == null && lastDockFocus != null) focusId = (String) lastDockFocus.getTag();
        dockRow.removeAllViews();
        lastDockFocus = null;
        List<String> ids = visibleDock();
        Tile focusTile = null;
        for (int i = 0; i < ids.size(); i++) {
            final String id = ids.get(i);
            final AppEntry a = apps.get(id);
            final Tile t = Tile.art(this, tileW, tileH, art.get(id), a.label, false, 1.1f);
            t.card.setTag(id);
            t.card.setOnClickListener(v -> launch(a));
            t.card.setOnLongClickListener(v -> {
                dockMenu(id);
                return true;
            });
            final View.OnKeyListener menu = menuKey(() -> dockMenu(id));
            t.card.setOnKeyListener((v, code, e) -> {
                int column = dockRow.indexOfChild(t);
                if (code == KeyEvent.KEYCODE_DPAD_DOWN) {
                    if (e.getAction() == KeyEvent.ACTION_DOWN) {
                        if (library) focusGrid(column); else showLibrary(column);
                    }
                    return true;
                }
                if (code == KeyEvent.KEYCODE_DPAD_UP) {
                    if (e.getAction() == KeyEvent.ACTION_DOWN) {
                        if (library) hideLibrary(); else (lastPill != null ? lastPill : appsPill).requestFocus();
                    }
                    return true;
                }
                if (code == KeyEvent.KEYCODE_DPAD_LEFT || code == KeyEvent.KEYCODE_DPAD_RIGHT) {
                    // Stay in the dock at either end (dock tiles carry their app id as tag).
                    View next = v.focusSearch(code == KeyEvent.KEYCODE_DPAD_LEFT ? View.FOCUS_LEFT : View.FOCUS_RIGHT);
                    return next == null || !(next.getTag() instanceof String);
                }
                return menu.onKey(v, code, e);
            });
            t.onFocus((v, f) -> {
                if (f) {
                    lastDockFocus = v;
                    title.setText(a.label);
                    if (!library) title.animate().alpha(1f).setDuration(150).start();
                } else {
                    // Fade the name only if focus left the dock (dock tiles carry their app id as tag).
                    ui.post(() -> {
                        View now = getCurrentFocus();
                        if (now == null || !(now.getTag() instanceof String)) {
                            title.animate().alpha(0f).setDuration(150).start();
                        }
                    });
                }
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
            if (i > 0) lp.leftMargin = gap;
            dockRow.addView(t, lp);
            if (focusTile == null || id.equals(focusId)) focusTile = t;
        }
        dockHint.setVisibility(ids.isEmpty() ? View.VISIBLE : View.GONE);
        dockRow.setMinimumHeight(ids.isEmpty() ? px(60) : 0);
        View now = getCurrentFocus();
        boolean inGrid = now != null && now.getTag() instanceof Integer;
        if (focusTile != null) {
            lastDockFocus = focusTile.card;
            if (overlay == null && !inGrid) focusTile.card.requestFocus();
        } else if (overlay == null && !library) {
            appsPill.requestFocus();
        }
    }

    private void focusDock() {
        if (lastDockFocus != null) lastDockFocus.requestFocus(); else appsPill.requestFocus();
    }

    /** Menu button (or long-press) on a dock app: reorder or remove. */
    private void dockMenu(final String id) {
        final List<String> ids = visibleDock();
        final int pos = ids.indexOf(id);
        new AlertDialog.Builder(this, AlertDialog.THEME_DEVICE_DEFAULT_DARK)
                .setTitle(apps.get(id).label)
                .setItems(new String[] {"Move left", "Move right", "Remove from Dock"}, (d, which) -> {
                    String focus = id;
                    if (which == 0 && pos > 0) Collections.swap(ids, pos, pos - 1);
                    else if (which == 1 && pos < ids.size() - 1) Collections.swap(ids, pos, pos + 1);
                    else if (which == 2) {
                        ids.remove(pos);
                        focus = ids.isEmpty() ? null : ids.get(Math.min(pos, ids.size() - 1));
                    }
                    saveDock(ids);
                    lastDockFocus = null;
                    buildDock(focus);
                    if (library) buildGrid();
                })
                .show();
    }

    private void toggleDock(AppEntry a) {
        List<String> ids = visibleDock();
        if (ids.remove(a.pkg)) {
            toast(a.label + " removed from the Dock");
        } else {
            ids.add(a.pkg);
            toast(a.label + " added to the Dock");
        }
        saveDock(ids);
        buildDock(null);
    }

    private static View.OnKeyListener menuKey(final Runnable action) {
        return (v, code, e) -> {
            if (code != KeyEvent.KEYCODE_MENU) return false;
            if (e.getAction() == KeyEvent.ACTION_UP) action.run();
            return true;
        };
    }

    // ---- all-apps grid (Down from the dock) -------------------------------------------------

    /** Slides the dock to the top, blurs the wallpaper and shows every other app below. */
    private void showLibrary(int column) {
        if (library) {
            focusGrid(column);
            return;
        }
        library = true;
        buildGrid();
        for (Pill p : new Pill[] {appsPill, searchPill, settingsPill}) p.setFocusable(false);

        int[] at = new int[2];
        dock.getLocationOnScreen(at);
        float restingTop = at[1] - bottom.getTranslationY();
        bottom.animate().translationY(px(30) - restingTop).setDuration(SLIDE_MS).setInterpolator(EASE).start();
        title.animate().alpha(0f).setDuration(150).start();
        for (View v : new View[] {head, bar}) {
            v.animate().alpha(0f).translationY(-px(24)).setDuration(SLIDE_MS).setInterpolator(EASE).start();
        }
        libraryBg.animate().alpha(1f).setDuration(SLIDE_MS).start();
        libraryDim.animate().alpha(1f).setDuration(SLIDE_MS).start();

        grid.setPadding(0, px(30) + dock.getHeight() + px(12), 0, px(36));
        grid.scrollTo(0, 0);
        grid.setVisibility(View.VISIBLE);
        grid.setAlpha(0f);
        grid.setTranslationY(px(70));
        grid.animate().alpha(1f).translationY(0f).setDuration(SLIDE_MS).setInterpolator(EASE).start();
        focusGrid(column);
    }

    private void hideLibrary() {
        if (!library) return;
        library = false;
        bottom.animate().translationY(0f).setDuration(SLIDE_MS).setInterpolator(EASE).start();
        for (View v : new View[] {head, bar}) {
            v.animate().alpha(1f).translationY(0f).setDuration(SLIDE_MS).setInterpolator(EASE).start();
        }
        libraryBg.animate().alpha(0f).setDuration(SLIDE_MS).start();
        libraryDim.animate().alpha(0f).setDuration(SLIDE_MS).start();
        grid.animate().alpha(0f).translationY(px(70)).setDuration(SLIDE_MS).setInterpolator(EASE)
                .withEndAction(() -> {
                    if (!library) grid.setVisibility(View.GONE);
                }).start();
        for (Pill p : new Pill[] {appsPill, searchPill, settingsPill}) p.setFocusable(true);
        if (lastDockFocus != null) {
            lastDockFocus.requestFocus();
            title.animate().alpha(1f).setDuration(SLIDE_MS).start();
        } else {
            appsPill.requestFocus();
        }
    }

    private void focusGrid(int column) {
        if (!gridTiles.isEmpty()) gridTiles.get(Math.max(0, Math.min(column, gridTiles.size() - 1))).card.requestFocus();
    }

    /** Every app that isn't in the dock, six across, lined up with the dock's tiles. */
    private void buildGrid() {
        View now = getCurrentFocus();
        int focusIndex = now != null && now.getTag() instanceof Integer ? (Integer) now.getTag() : -1;
        gridRows.removeAllViews();
        gridTiles.clear();
        List<String> docked = visibleDock();
        LinearLayout row = null;
        for (final AppEntry a : apps.values()) {
            if (docked.contains(a.pkg)) continue;
            final int index = gridTiles.size();
            if (index % COLUMNS == 0) {
                row = new LinearLayout(this);
                row.setOrientation(LinearLayout.HORIZONTAL);
                row.setClipChildren(false);
                LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(-2, -2);
                rlp.topMargin = px(index == 0 ? 10 : 4);
                gridRows.addView(row, rlp);
            }
            Tile t = Tile.art(this, tileW, tileH, art.get(a.pkg), a.label, true, 1.1f);
            t.card.setTag(index);
            t.card.setOnClickListener(v -> launch(a));
            t.card.setOnLongClickListener(v -> {
                toggleDock(a);
                buildGrid();
                return true;
            });
            final View.OnKeyListener menu = menuKey(() -> {
                toggleDock(a);
                buildGrid();
            });
            t.card.setOnKeyListener((v, code, e) -> {
                if (code == KeyEvent.KEYCODE_DPAD_UP && index < COLUMNS) {
                    // Top row: up into the dock, to the tile above if there is one.
                    if (e.getAction() == KeyEvent.ACTION_DOWN && dockRow.getChildCount() > 0) {
                        int col = Math.min(index, dockRow.getChildCount() - 1);
                        ((Tile) dockRow.getChildAt(col)).card.requestFocus();
                    }
                    return true;
                }
                if (code == KeyEvent.KEYCODE_DPAD_DOWN || code == KeyEvent.KEYCODE_DPAD_LEFT
                        || code == KeyEvent.KEYCODE_DPAD_RIGHT || code == KeyEvent.KEYCODE_DPAD_UP) {
                    int dir = code == KeyEvent.KEYCODE_DPAD_DOWN ? View.FOCUS_DOWN
                            : code == KeyEvent.KEYCODE_DPAD_UP ? View.FOCUS_UP
                            : code == KeyEvent.KEYCODE_DPAD_LEFT ? View.FOCUS_LEFT : View.FOCUS_RIGHT;
                    View next = v.focusSearch(dir);
                    return next == null || !(next.getTag() instanceof Integer);   // stay in the grid
                }
                return menu.onKey(v, code, e);
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
            if (index % COLUMNS > 0) lp.leftMargin = gap;
            row.addView(t, lp);
            gridTiles.add(t);
        }
        TextView hint = text(12, "sans-serif", 0x99FFFFFF);
        hint.setText(gridTiles.isEmpty()
                ? "All your apps are in the dock."
                : "Press Menu on an app to add it to the dock. Press Menu on a dock app to move or remove it.");
        LinearLayout.LayoutParams hlp = new LinearLayout.LayoutParams(-2, -2);
        hlp.topMargin = px(14);
        gridRows.addView(hint, hlp);
        if (focusIndex >= 0 && library) {
            if (gridTiles.isEmpty()) focusDock(); else focusGrid(Math.min(focusIndex, gridTiles.size() - 1));
        }
    }

    // ---- Search / Settings screens ----------------------------------------------------------

    private void openOverlay(final String kind) {
        if (overlay != null) root.removeView(overlay);
        overlayKind = kind;
        overlay = new FrameLayout(this);
        overlay.setClickable(true);
        overlay.setClipChildren(false);   // focused tiles at the grid's edge grow past it

        overlayBg = wallpaperView();
        if (current != null) overlayBg.setImageBitmap(current.blur);
        overlay.addView(overlayBg, fill());
        View tint = new View(this);
        tint.setBackgroundColor(0x8C000000);
        overlay.addView(tint, fill());

        TextView heading = text(30, "sans-serif-light", Color.WHITE);
        heading.setText(SEARCH.equals(kind) ? "Search" : "Settings");
        FrameLayout.LayoutParams tlp = new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.START);
        tlp.setMargins(px(64), px(34), 0, 0);
        overlay.addView(heading, tlp);

        if (SETTINGS.equals(kind)) {
            TextView hint = text(13, "sans-serif", 0xB3FFFFFF);
            hint.setText("Add your own wallpapers with add-wallpapers.ps1 on your PC");
            FrameLayout.LayoutParams hlp = new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.END);
            hlp.setMargins(0, px(48), px(64), 0);
            overlay.addView(hint, hlp);
        }

        LinearLayout rows = new LinearLayout(this);
        rows.setOrientation(LinearLayout.VERTICAL);
        rows.setClipChildren(false);
        rows.setClipToPadding(false);
        ScrollView scroll = new ScrollView(this);
        scroll.setVerticalScrollBarEnabled(false);
        scroll.setClipChildren(false);
        scroll.setClipToPadding(false);
        scroll.setPadding(0, px(22), 0, px(56));
        scroll.addView(rows);
        FrameLayout.LayoutParams slp = new FrameLayout.LayoutParams(-2, -1, Gravity.CENTER_HORIZONTAL);
        slp.topMargin = px(SEARCH.equals(kind) ? 150 : 96);

        View first;
        if (SEARCH.equals(kind)) {
            first = searchField(rows);
        } else {
            List<Tile> tiles = settingsTiles();
            fillGrid(rows, tiles);
            first = tiles.isEmpty() ? null : tiles.get(0).card;
        }
        overlay.addView(scroll, slp);

        dock.setDescendantFocusability(ViewGroup.FOCUS_BLOCK_DESCENDANTS);
        for (Pill p : new Pill[] {appsPill, searchPill, settingsPill}) p.setFocusable(false);
        title.animate().alpha(0f).setDuration(100).start();
        root.addView(overlay, fill());
        overlay.setAlpha(0f);
        overlay.animate().alpha(1f).setDuration(160).start();
        if (first != null) first.requestFocus();
    }

    private void closeOverlay() {
        if (overlay == null) return;
        final FrameLayout closing = overlay;
        String kind = overlayKind;
        overlay = null;
        overlayKind = null;
        overlayBg = null;
        if (searchBox != null) {
            glass.remove(searchBox);
            searchBox = null;
        }
        hideKeyboard(closing);
        closing.animate().alpha(0f).setDuration(140).withEndAction(() -> root.removeView(closing)).start();
        dock.setDescendantFocusability(ViewGroup.FOCUS_AFTER_DESCENDANTS);
        for (Pill p : new Pill[] {appsPill, searchPill, settingsPill}) p.setFocusable(true);
        (SEARCH.equals(kind) ? searchPill : settingsPill).requestFocus();
    }

    private void fillGrid(LinearLayout rows, List<Tile> tiles) {
        rows.removeAllViews();
        final int cols = 5, spacing = px(30);
        LinearLayout row = null;
        for (int i = 0; i < tiles.size(); i++) {
            if (i % cols == 0) {
                row = new LinearLayout(this);
                row.setOrientation(LinearLayout.HORIZONTAL);
                row.setClipChildren(false);
                row.setClipToPadding(false);
                LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(-2, -2);
                rlp.bottomMargin = px(22);
                rows.addView(row, rlp);
            }
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
            if (i % cols > 0) lp.leftMargin = spacing;
            row.addView(tiles.get(i), lp);
        }
    }

    private List<Tile> appTiles(Iterable<AppEntry> list) {
        List<Tile> out = new ArrayList<>();
        int w = px(148), h = w * 9 / 16;
        for (final AppEntry a : list) {
            Tile t = Tile.art(this, w, h, art.get(a.pkg), a.label, true, 1.1f);
            t.card.setOnClickListener(v -> {
                launch(a);
                closeOverlay();
            });
            t.card.setOnLongClickListener(v -> {
                toggleDock(a);
                return true;
            });
            t.card.setOnKeyListener(menuKey(() -> toggleDock(a)));
            out.add(t);
        }
        return out;
    }

    private List<Tile> settingsTiles() {
        List<Tile> out = new ArrayList<>();
        int w = px(148), h = w * 9 / 16;
        PackageManager pm = getPackageManager();
        for (String[] item : SETTINGS_ITEMS) {
            final Intent intent = new Intent(item[1]).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            if (intent.resolveActivity(pm) == null) continue;
            Tile t = Tile.text(this, w, h, item[0]);
            t.card.setOnClickListener(v -> {
                try {
                    startActivity(intent);
                } catch (ActivityNotFoundException | SecurityException e) {
                    toast("That setting isn't available");
                }
            });
            out.add(t);
        }
        Tile next = Tile.text(this, w, h, "Next Wallpaper");
        next.card.setOnClickListener(v -> {
            closeOverlay();
            nextWallpaper();
        });
        out.add(next);
        Tile credits = Tile.text(this, w, h, "Wallpaper Credits");
        credits.card.setOnClickListener(v -> showCredits());
        out.add(credits);
        return out;
    }

    /** The bundled photos are CC-licensed, so their credits live here instead of on the home screen. */
    private void showCredits() {
        StringBuilder text = new StringBuilder();
        if (current != null) {
            text.append("On screen now: ")
                    .append(current.credit.isEmpty() ? "one of your own photos" : current.credit)
                    .append("\n\n");
        }
        text.append("Nature photos from Wikimedia Commons:\n");
        for (String c : wallpapers.credits()) text.append("\n• ").append(c);
        new AlertDialog.Builder(this, AlertDialog.THEME_DEVICE_DEFAULT_DARK)
                .setTitle("Wallpaper Credits")
                .setMessage(text)
                .setPositiveButton("OK", null)
                .show();
    }

    /** Glass search field. Typing filters your apps; Search on the keyboard asks Silk. */
    private EditText searchField(final LinearLayout rows) {
        Glass box = new Glass(this, px(28));
        searchBox = box;
        glass.add(box);
        if (current != null) box.setBlur(current.blur, 0);

        LinearLayout line = new LinearLayout(this);
        line.setOrientation(LinearLayout.HORIZONTAL);
        line.setGravity(Gravity.CENTER_VERTICAL);
        line.setPadding(px(20), 0, px(20), 0);
        Art.Icon icon = Art.icon(Art.SEARCH);
        icon.setColor(0xCCFFFFFF);
        ImageView iv = new ImageView(this);
        iv.setImageDrawable(icon);
        line.addView(iv, new LinearLayout.LayoutParams(px(18), px(18)));

        final EditText field = new EditText(this);
        field.setBackground(null);
        field.setSingleLine();
        field.setTextSize(18);
        field.setTextColor(Color.WHITE);
        field.setHintTextColor(0x99FFFFFF);
        field.setHint("Search your apps, or the web with Silk");
        field.setImeOptions(EditorInfo.IME_ACTION_SEARCH | EditorInfo.IME_FLAG_NO_EXTRACT_UI
                | EditorInfo.IME_FLAG_NO_FULLSCREEN);
        LinearLayout.LayoutParams flp = new LinearLayout.LayoutParams(0, -2, 1f);
        flp.leftMargin = px(12);
        line.addView(field, flp);
        box.addView(line, new FrameLayout.LayoutParams(-1, -1));

        FrameLayout.LayoutParams blp = new FrameLayout.LayoutParams(px(620), px(56), Gravity.TOP | Gravity.CENTER_HORIZONTAL);
        blp.topMargin = px(84);
        overlay.addView(box, blp);

        field.addTextChangedListener(new SearchWatcher(this, rows));
        field.setOnEditorActionListener((v, actionId, e) -> {
            boolean enter = e != null && e.getKeyCode() == KeyEvent.KEYCODE_ENTER && e.getAction() == KeyEvent.ACTION_DOWN;
            if (actionId != EditorInfo.IME_ACTION_SEARCH && !enter) return false;
            searchWeb(field.getText().toString().trim());
            return true;
        });
        ui.postDelayed(() -> {
            InputMethodManager imm = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
            if (imm != null && field.hasFocus()) imm.showSoftInput(field, InputMethodManager.SHOW_IMPLICIT);
        }, 250);
        showResults(rows, "");
        return field;
    }

    private static final class SearchWatcher implements TextWatcher {
        private final HomeActivity home;
        private final LinearLayout rows;

        SearchWatcher(HomeActivity home, LinearLayout rows) {
            this.home = home;
            this.rows = rows;
        }

        @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}

        @Override public void onTextChanged(CharSequence s, int start, int before, int count) {}

        @Override public void afterTextChanged(Editable s) { home.showResults(rows, s.toString().trim()); }
    }

    private void showResults(LinearLayout rows, final String query) {
        List<Tile> tiles = new ArrayList<>();
        if (!query.isEmpty()) {
            Tile web = Tile.text(this, px(148), px(148) * 9 / 16, "Search the web for “" + query + "”");
            web.card.setOnClickListener(v -> searchWeb(query));
            tiles.add(web);
        }
        List<AppEntry> matches = new ArrayList<>();
        String q = query.toLowerCase(Locale.getDefault());
        for (AppEntry a : apps.values()) {
            if (q.isEmpty() || a.label.toLowerCase(Locale.getDefault()).contains(q)) matches.add(a);
        }
        tiles.addAll(appTiles(matches));
        fillGrid(rows, tiles);
    }

    /**
     * Opens Bing results in Silk (any browser if Silk is off). Silk ignores web-search intents and
     * just reopens its last tab, and Google tends to answer Silk with a robot check.
     */
    private void searchWeb(String query) {
        if (query.isEmpty()) return;
        Intent page = new Intent(Intent.ACTION_VIEW, Uri.parse(SEARCH_URL + Uri.encode(query)))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            startActivity(new Intent(page).setPackage(SILK));
        } catch (ActivityNotFoundException | SecurityException e) {
            try {
                startActivity(page);
            } catch (ActivityNotFoundException | SecurityException e2) {
                toast("No web browser is available");
                return;
            }
        }
        closeOverlay();
    }

    private void hideKeyboard(View v) {
        InputMethodManager imm = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
        if (imm != null) imm.hideSoftInputFromWindow(v.getWindowToken(), 0);
    }

    private void toast(String msg) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show();
    }
}
