package local.tvhome;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Matrix;
import android.media.ExifInterface;
import android.util.Log;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Shuffles through wallpapers. Your own photos win: anything in
 * /sdcard/Android/data/local.tvhome/files/wallpapers (see add-wallpapers.ps1) replaces the
 * bundled nature photos. The folder is re-checked on every rotation, so new photos show up
 * without restarting.
 */
final class Wallpapers {
    static final class Frame {
        final Bitmap full;
        final Bitmap blur;
        final String credit;

        Frame(Bitmap full, Bitmap blur, String credit) {
            this.full = full;
            this.blur = blur;
            this.credit = credit;
        }
    }

    private static final String ASSET_DIR = "wallpapers";
    private static final int BLUR_W = 192, BLUR_H = 108;

    private final Context context;
    private final Map<String, String> credits = new HashMap<>();
    private final List<String> deck = new ArrayList<>();
    private String last;

    Wallpapers(Context context) {
        this.context = context.getApplicationContext();
        loadCredits();
    }

    File userFolder() {
        return context.getExternalFilesDir(ASSET_DIR);
    }

    /** Decodes the next wallpaper, cropped to w x h. Runs on a background thread. */
    Frame next(int w, int h) {
        List<String> all = sources();
        if (all.isEmpty()) return null;
        deck.retainAll(all);
        if (deck.isEmpty()) {
            deck.addAll(all);
            Collections.shuffle(deck);
            if (deck.size() > 1 && deck.get(0).equals(last)) Collections.swap(deck, 0, deck.size() - 1);
        }
        String src = deck.remove(0);
        last = src;
        try {
            Bitmap full = decode(src, w, h);
            return full == null ? null : new Frame(full, blur(full), creditFor(src));
        } catch (IOException | RuntimeException | OutOfMemoryError e) {
            Log.w(HomeKeyService.TAG, "can't load wallpaper " + src, e);
            return null;
        }
    }

    private List<String> sources() {
        List<String> out = new ArrayList<>();
        File dir = userFolder();
        File[] files = dir == null ? null : dir.listFiles();
        if (files != null) {
            for (File f : files) {
                String n = f.getName().toLowerCase(Locale.ROOT);
                if (n.endsWith(".jpg") || n.endsWith(".jpeg") || n.endsWith(".png") || n.endsWith(".webp")) {
                    out.add("file:" + f.getAbsolutePath());
                }
            }
        }
        if (out.isEmpty()) {
            try {
                for (String n : context.getAssets().list(ASSET_DIR)) {
                    if (n.endsWith(".jpg")) out.add("asset:" + ASSET_DIR + "/" + n);
                }
            } catch (IOException e) {
                Log.w(HomeKeyService.TAG, "no bundled wallpapers", e);
            }
        }
        Collections.sort(out);
        return out;
    }

    private InputStream open(String src) throws IOException {
        return src.startsWith("file:")
                ? new FileInputStream(src.substring(5))
                : context.getAssets().open(src.substring(6));
    }

    private Bitmap decode(String src, int w, int h) throws IOException {
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        try (InputStream in = open(src)) {
            BitmapFactory.decodeStream(in, null, bounds);
        }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null;
        int sample = 1;
        while (bounds.outWidth / (sample * 2) >= w && bounds.outHeight / (sample * 2) >= h) sample *= 2;

        BitmapFactory.Options opts = new BitmapFactory.Options();
        opts.inSampleSize = sample;
        opts.inPreferredConfig = Bitmap.Config.RGB_565;
        Bitmap b;
        try (InputStream in = open(src)) {
            b = BitmapFactory.decodeStream(in, null, opts);
        }
        if (b == null) return null;

        int rotation = src.startsWith("file:") ? exifRotation(src.substring(5)) : 0;
        if (rotation != 0) {
            Matrix r = new Matrix();
            r.postRotate(rotation);
            b = replace(b, Bitmap.createBitmap(b, 0, 0, b.getWidth(), b.getHeight(), r, true));
        }

        // Center-crop to the screen so the GPU never has to scale a large texture.
        float scale = Math.max(w / (float) b.getWidth(), h / (float) b.getHeight());
        int cropW = Math.min(b.getWidth(), Math.round(w / scale));
        int cropH = Math.min(b.getHeight(), Math.round(h / scale));
        Matrix m = new Matrix();
        m.setScale(scale, scale);
        return replace(b, Bitmap.createBitmap(b, (b.getWidth() - cropW) / 2, (b.getHeight() - cropH) / 2, cropW, cropH, m, true));
    }

    /** Frees an intermediate bitmap right away instead of waiting for garbage collection. */
    private static Bitmap replace(Bitmap old, Bitmap next) {
        if (next != old) old.recycle();
        return next;
    }

    private static int exifRotation(String path) {
        try {
            switch (new ExifInterface(path).getAttributeInt(ExifInterface.TAG_ORIENTATION, 1)) {
                case ExifInterface.ORIENTATION_ROTATE_90: return 90;
                case ExifInterface.ORIENTATION_ROTATE_180: return 180;
                case ExifInterface.ORIENTATION_ROTATE_270: return 270;
                default: return 0;
            }
        } catch (IOException e) {
            return 0;
        }
    }

    /** A small, heavily blurred copy used behind the dock and the Apps and Settings screens. */
    private static Bitmap blur(Bitmap full) {
        Bitmap small = Bitmap.createScaledBitmap(full, BLUR_W, BLUR_H, true);
        int[] px = new int[BLUR_W * BLUR_H];
        small.getPixels(px, 0, BLUR_W, 0, 0, BLUR_W, BLUR_H);
        small.recycle();
        int[] tmp = new int[px.length];
        for (int i = 0; i < 3; i++) {
            boxPass(px, tmp, BLUR_W, BLUR_H, 4, true);
            boxPass(tmp, px, BLUR_W, BLUR_H, 4, false);
        }
        return Bitmap.createBitmap(px, BLUR_W, BLUR_H, Bitmap.Config.ARGB_8888);
    }

    private static void boxPass(int[] in, int[] out, int w, int h, int r, boolean horizontal) {
        int len = horizontal ? w : h, lines = horizontal ? h : w, div = 2 * r + 1;
        for (int line = 0; line < lines; line++) {
            int rs = 0, gs = 0, bs = 0;
            for (int i = -r; i <= r; i++) {
                int p = in[index(line, Math.max(0, Math.min(len - 1, i)), w, horizontal)];
                rs += (p >> 16) & 255;
                gs += (p >> 8) & 255;
                bs += p & 255;
            }
            for (int i = 0; i < len; i++) {
                out[index(line, i, w, horizontal)] = 0xFF000000 | (rs / div) << 16 | (gs / div) << 8 | (bs / div);
                int add = in[index(line, Math.min(i + r + 1, len - 1), w, horizontal)];
                int sub = in[index(line, Math.max(i - r, 0), w, horizontal)];
                rs += ((add >> 16) & 255) - ((sub >> 16) & 255);
                gs += ((add >> 8) & 255) - ((sub >> 8) & 255);
                bs += (add & 255) - (sub & 255);
            }
        }
    }

    private static int index(int line, int i, int w, boolean horizontal) {
        return horizontal ? line * w + i : i * w + line;
    }

    /** Credits for all bundled photos, in file order. */
    List<String> credits() {
        List<String> names = new ArrayList<>(credits.keySet());
        Collections.sort(names);
        List<String> out = new ArrayList<>();
        for (String n : names) out.add(credits.get(n));
        return out;
    }

    private String creditFor(String src) {
        if (!src.startsWith("asset:")) return "";
        String name = src.substring(src.lastIndexOf('/') + 1);
        String c = credits.get(name);
        return c == null ? "" : c;
    }

    /** credits.txt: one "file<TAB>credit line" per bundled photo. */
    private void loadCredits() {
        try (BufferedReader r = new BufferedReader(new InputStreamReader(
                context.getAssets().open(ASSET_DIR + "/credits.txt"), "UTF-8"))) {
            String line;
            while ((line = r.readLine()) != null) {
                int tab = line.indexOf('\t');
                if (tab > 0) credits.put(line.substring(0, tab), line.substring(tab + 1));
            }
        } catch (IOException e) {
            Log.w(HomeKeyService.TAG, "no wallpaper credits", e);
        }
    }
}
