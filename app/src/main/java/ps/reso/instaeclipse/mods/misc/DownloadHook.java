package com.rso.instaeclipse.misc;

import android.app.DownloadManager;
import android.content.ContentValues;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.drawable.BitmapDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.Toast;

import com.rso.instaeclipse.utils.PrefsHelper;

import java.io.FileOutputStream;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.net.HttpURLConnection;
import java.net.URL;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedList;
import java.util.List;
import java.util.Locale;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/**
 * DownloadHook — integrates into InstaEclipse's Misc feature set.
 *
 * Controlled by the "misc_download_button" preference key.
 * Call DownloadHook.init(context, classLoader) from MiscHooks after the
 * preference guard, exactly like other Misc features.
 */
public class DownloadHook {

    public static final String PREF_KEY = "misc_download_button";
    private static final String TAG = "InstaEclipse/Download";
    private static final String SAVE_DIR = "InstaEclipse";

    // Ring-buffer of recently intercepted video URLs from ExoPlayer/MediaPlayer
    private static final ConcurrentLinkedDeque<String> VIDEO_URL_CACHE = new ConcurrentLinkedDeque<>();
    private static final int CACHE_MAX = 20;

    private static ExecutorService sExecutor;
    private static Context sAppContext;

    // ─────────────────────────────────────────────────────────────────────────
    // Entry point — call this from MiscHooks.initHooks()
    // ─────────────────────────────────────────────────────────────────────────

    public static void init(Context context, ClassLoader cl) {
        if (!PrefsHelper.getBoolean(PREF_KEY, false)) return;

        sAppContext = context.getApplicationContext();
        sExecutor = Executors.newFixedThreadPool(3);

        hookActionBar(cl);
        hookCarousel(cl);
        hookReels(cl);
        hookVideoUrls(cl);

        XposedBridge.log(TAG + ": Download hooks active");
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 1.  Feed action bar — injects ⬇ next to like/comment/share
    // ─────────────────────────────────────────────────────────────────────────

    private static void hookActionBar(ClassLoader cl) {
        // Strategy A: hook known action-bar class names
        String[] candidates = {
            "com.instagram.feed.rows.media.FeedActionBarView",
            "com.instagram.feed.ui.FeedActionBarView",
        };
        for (String cls : candidates) {
            try {
                XposedHelpers.findAndHookMethod(cl.loadClass(cls),
                    "onFinishInflate", new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam p) {
                            ViewGroup bar = (ViewGroup) p.thisObject;
                            injectFeedButton(bar);
                        }
                    });
                XposedBridge.log(TAG + ": hooked " + cls);
                return;
            } catch (ClassNotFoundException ignored) {}
        }

        // Strategy B: ViewGroup.addView fallback — detect action bar heuristically
        XposedHelpers.findAndHookMethod(ViewGroup.class, "addView",
            View.class, int.class, ViewGroup.LayoutParams.class,
            new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam p) {
                    ViewGroup parent = (ViewGroup) p.thisObject;
                    if (parent.findViewWithTag(TAG) != null) return;
                    if (!isActionBar(parent)) return;
                    injectFeedButton(parent);
                }
            });
    }

    private static boolean isActionBar(ViewGroup vg) {
        String name = vg.getClass().getName();
        if (!name.startsWith("com.instagram")) return false;
        if (vg.getChildCount() < 3 || vg.getChildCount() > 5) return false;
        int ibCount = 0;
        for (int i = 0; i < vg.getChildCount(); i++)
            if (vg.getChildAt(i) instanceof ImageButton) ibCount++;
        return ibCount >= 2;
    }

    private static void injectFeedButton(ViewGroup bar) {
        if (bar.findViewWithTag(TAG + "_feed") != null) return;
        try {
            ImageButton btn = buildButton(bar.getContext(), TAG + "_feed");
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                dp(bar.getContext(), 36), dp(bar.getContext(), 36));
            lp.gravity = Gravity.CENTER_VERTICAL;
            lp.setMargins(dp(bar.getContext(), 4), 0, dp(bar.getContext(), 4), 0);
            btn.setLayoutParams(lp);
            btn.setOnClickListener(v -> downloadFromContainer(bar, false));
            bar.addView(btn);
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": feed inject error: " + t);
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 2.  Carousel — per-slide button + "⬇ All" badge
    // ─────────────────────────────────────────────────────────────────────────

    private static void hookCarousel(ClassLoader cl) {
        String[] candidates = {
            "com.instagram.feed.rows.media.carousel.CarouselMediaViewHolder",
            "com.instagram.feed.view.CarouselView",
        };
        for (String cls : candidates) {
            try {
                XposedHelpers.findAndHookMethod(cl.loadClass(cls),
                    "onFinishInflate", new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam p) {
                            ViewGroup vg = (ViewGroup) p.thisObject;
                            injectDownloadAll(vg);
                        }
                    });
                return;
            } catch (ClassNotFoundException ignored) {}
        }
    }

    private static void injectDownloadAll(ViewGroup container) {
        if (!(container instanceof FrameLayout)) return;
        if (container.findViewWithTag(TAG + "_all") != null) return;
        try {
            Context ctx = container.getContext();
            ImageButton btn = buildButton(ctx, TAG + "_all");

            FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                dp(ctx, 36), dp(ctx, 36));
            lp.gravity = Gravity.TOP | Gravity.END;
            lp.setMargins(0, dp(ctx, 48), dp(ctx, 8), 0);
            btn.setLayoutParams(lp);

            btn.setOnClickListener(v -> downloadFromContainer(container, true));
            container.addView(btn);
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": carousel inject error: " + t);
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 3.  Reels — floating button bottom-right
    // ─────────────────────────────────────────────────────────────────────────

    private static void hookReels(ClassLoader cl) {
        String[] candidates = {
            "com.instagram.reels.fragment.ReelsViewerFragment",
            "com.instagram.clips.fragment.ClipsViewerFragment",
        };
        for (String cls : candidates) {
            try {
                XposedHelpers.findAndHookMethod(cl.loadClass(cls),
                    "onViewCreated", View.class, android.os.Bundle.class,
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam p) {
                            View root = (View) p.args[0];
                            if (root instanceof FrameLayout)
                                injectReelButton((FrameLayout) root);
                        }
                    });
                return;
            } catch (ClassNotFoundException ignored) {}
        }
    }

    private static void injectReelButton(FrameLayout root) {
        if (root.findViewWithTag(TAG + "_reel") != null) return;
        try {
            Context ctx = root.getContext();
            ImageButton btn = buildButton(ctx, TAG + "_reel");

            FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                dp(ctx, 40), dp(ctx, 40));
            lp.gravity = Gravity.BOTTOM | Gravity.END;
            lp.setMargins(0, 0, dp(ctx, 16), dp(ctx, 90));
            btn.setLayoutParams(lp);

            btn.setOnClickListener(v -> {
                String url = VIDEO_URL_CACHE.isEmpty() ? null : VIDEO_URL_CACHE.peekFirst();
                if (url != null) enqueueDownload(url);
                else downloadFromContainer(root, false);
            });
            root.addView(btn);
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": reel inject error: " + t);
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 4.  Intercept ExoPlayer / MediaPlayer to cache video URLs
    // ─────────────────────────────────────────────────────────────────────────

    private static void hookVideoUrls(ClassLoader cl) {
        // ExoPlayer
        try {
            Class<?> exo = cl.loadClass("com.google.android.exoplayer2.ExoPlayer");
            Class<?> mediaItem = cl.loadClass("com.google.android.exoplayer2.MediaItem");
            XposedHelpers.findAndHookMethod(exo, "setMediaItem", mediaItem,
                new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam p) {
                        cacheFromMediaItem(p.args[0]);
                    }
                });
        } catch (Throwable ignored) {}

        // MediaPlayer fallback
        try {
            XposedHelpers.findAndHookMethod(android.media.MediaPlayer.class,
                "setDataSource", String.class,
                new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam p) {
                        String u = (String) p.args[0];
                        if (u != null && isMediaUrl(u)) cacheVideoUrl(u);
                    }
                });
        } catch (Throwable ignored) {}
    }

    private static void cacheFromMediaItem(Object item) {
        if (item == null) return;
        try {
            Field lc = findField(item.getClass(), "localConfiguration");
            if (lc == null) lc = findField(item.getClass(), "playbackProperties");
            if (lc == null) return;
            lc.setAccessible(true);
            Object cfg = lc.get(item);
            if (cfg == null) return;
            Field uriField = findField(cfg.getClass(), "uri");
            if (uriField == null) return;
            uriField.setAccessible(true);
            Object uri = uriField.get(cfg);
            if (uri != null) cacheVideoUrl(uri.toString());
        } catch (Throwable ignored) {}
    }

    private static void cacheVideoUrl(String url) {
        VIDEO_URL_CACHE.addFirst(url);
        while (VIDEO_URL_CACHE.size() > CACHE_MAX) VIDEO_URL_CACHE.removeLast();
    }

    // ─────────────────────────────────────────────────────────────────────────
    // URL extraction helpers
    // ─────────────────────────────────────────────────────────────────────────

    private static void downloadFromContainer(ViewGroup container, boolean all) {
        List<String> urls = extractUrls(container);
        if (urls.isEmpty() && !VIDEO_URL_CACHE.isEmpty())
            urls.add(VIDEO_URL_CACHE.peekFirst());

        if (urls.isEmpty()) {
            Toast.makeText(sAppContext, "Could not find media URL", Toast.LENGTH_SHORT).show();
            return;
        }

        List<String> toDownload = all ? urls : urls.subList(0, 1);
        for (String u : toDownload) enqueueDownload(u);

        String msg = toDownload.size() == 1
            ? "Downloading…"
            : "Downloading " + toDownload.size() + " items…";
        Toast.makeText(sAppContext, msg, Toast.LENGTH_SHORT).show();
    }

    private static List<String> extractUrls(ViewGroup root) {
        List<String> urls = new ArrayList<>();
        Queue<View> q = new LinkedList<>();
        q.add(root);
        while (!q.isEmpty()) {
            View v = q.poll();
            // check view tag
            Object tag = v.getTag();
            if (tag instanceof String && isMediaUrl((String) tag))
                urls.add((String) tag);
            // check keyed tags
            for (int id : new int[]{0x7f0b0001, 0x7f0b0002, 0x7f0b0003}) {
                try {
                    Object t = v.getTag(id);
                    if (t instanceof String && isMediaUrl((String) t)) urls.add((String) t);
                } catch (Throwable ignored) {}
            }
            // recurse
            if (v instanceof ViewGroup) {
                ViewGroup vg = (ViewGroup) v;
                for (int i = 0; i < vg.getChildCount(); i++) q.add(vg.getChildAt(i));
            }
        }
        // also try reflection on any model object stored as tag
        Object model = root.getTag();
        if (model != null && !model.getClass().getName().equals("java.lang.String")) {
            urls.addAll(reflectUrls(model));
        }
        return urls;
    }

    private static List<String> reflectUrls(Object obj) {
        List<String> out = new ArrayList<>();
        Class<?> cls = obj.getClass();
        while (cls != null && cls != Object.class) {
            for (Field f : cls.getDeclaredFields()) {
                if (f.getType() == String.class) {
                    try {
                        f.setAccessible(true);
                        String v = (String) f.get(obj);
                        if (isMediaUrl(v)) out.add(v);
                    } catch (Throwable ignored) {}
                }
            }
            cls = cls.getSuperclass();
        }
        return out;
    }

    private static boolean isMediaUrl(String u) {
        if (u == null || u.isEmpty()) return false;
        return (u.startsWith("http://") || u.startsWith("https://"))
            && (u.contains("cdninstagram") || u.contains("fbcdn")
                || u.contains(".jpg") || u.contains(".mp4") || u.contains(".webp"));
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Download
    // ─────────────────────────────────────────────────────────────────────────

    private static void enqueueDownload(String url) {
        boolean isVideo = url.contains(".mp4") || url.contains("video");
        String filename = "IE_" + new SimpleDateFormat("yyyyMMdd_HHmmss_SSS",
            Locale.US).format(new Date()) + (isVideo ? ".mp4" : ".jpg");
        try {
            DownloadManager dm = (DownloadManager)
                sAppContext.getSystemService(Context.DOWNLOAD_SERVICE);
            DownloadManager.Request req = new DownloadManager.Request(Uri.parse(url));
            req.addRequestHeader("User-Agent",
                "Mozilla/5.0 (Linux; Android 11) AppleWebKit/537.36");
            req.addRequestHeader("Referer", "https://www.instagram.com/");
            req.setTitle("InstaEclipse: " + filename);
            req.setNotificationVisibility(
                DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
            req.setMimeType(isVideo ? "video/mp4" : "image/jpeg");
            req.setDestinationInExternalPublicDir(
                isVideo ? Environment.DIRECTORY_MOVIES : Environment.DIRECTORY_PICTURES,
                SAVE_DIR + "/" + filename);
            req.allowScanningByMediaScanner();
            dm.enqueue(req);
        } catch (Throwable t) {
            // DownloadManager failed — fall back to manual
            boolean finalIsVideo = isVideo;
            String finalFilename = filename;
            sExecutor.submit(() -> manualDownload(url, finalFilename, finalIsVideo));
        }
    }

    private static void manualDownload(String url, String filename, boolean isVideo) {
        try {
            HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
            c.setRequestProperty("User-Agent", "Instagram 195.0.0.31.123 Android");
            c.setRequestProperty("Referer", "https://www.instagram.com/");
            c.setConnectTimeout(15000);
            c.setReadTimeout(30000);
            c.connect();
            if (c.getResponseCode() != 200) return;

            try (InputStream in = c.getInputStream()) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    ContentValues cv = new ContentValues();
                    cv.put(MediaStore.MediaColumns.DISPLAY_NAME, filename);
                    cv.put(MediaStore.MediaColumns.MIME_TYPE,
                        isVideo ? "video/mp4" : "image/jpeg");
                    cv.put(MediaStore.MediaColumns.RELATIVE_PATH,
                        (isVideo ? Environment.DIRECTORY_MOVIES
                                 : Environment.DIRECTORY_PICTURES) + "/" + SAVE_DIR);
                    Uri col = isVideo
                        ? MediaStore.Video.Media.EXTERNAL_CONTENT_URI
                        : MediaStore.Images.Media.EXTERNAL_CONTENT_URI;
                    Uri item = sAppContext.getContentResolver().insert(col, cv);
                    if (item == null) return;
                    try (FileOutputStream out = (FileOutputStream)
                            sAppContext.getContentResolver().openOutputStream(item)) {
                        pipe(in, out);
                    }
                } else {
                    java.io.File dir = new java.io.File(
                        Environment.getExternalStoragePublicDirectory(
                            isVideo ? Environment.DIRECTORY_MOVIES
                                    : Environment.DIRECTORY_PICTURES), SAVE_DIR);
                    dir.mkdirs();
                    try (FileOutputStream out =
                             new FileOutputStream(new java.io.File(dir, filename))) {
                        pipe(in, out);
                    }
                }
            }
            XposedBridge.log(TAG + ": saved " + filename);
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": manual DL error: " + t);
        }
    }

    private static void pipe(InputStream in, FileOutputStream out) throws Exception {
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) != -1) out.write(buf, 0, n);
        out.flush();
    }

    // ─────────────────────────────────────────────────────────────────────────
    // UI helpers
    // ─────────────────────────────────────────────────────────────────────────

    private static ImageButton buildButton(Context ctx, String tag) {
        ImageButton btn = new ImageButton(ctx);
        btn.setTag(tag);
        btn.setImageDrawable(makeDownloadIcon(ctx));
        btn.setBackground(makeCircleBg());
        int p = dp(ctx, 6);
        btn.setPadding(p, p, p, p);
        btn.setContentDescription("Download");
        return btn;
    }

    private static android.graphics.drawable.GradientDrawable makeCircleBg() {
        android.graphics.drawable.GradientDrawable gd =
            new android.graphics.drawable.GradientDrawable();
        gd.setShape(android.graphics.drawable.GradientDrawable.OVAL);
        gd.setColor(0xAA000000);
        return gd;
    }

    private static BitmapDrawable makeDownloadIcon(Context ctx) {
        int size = dp(ctx, 24);
        Bitmap bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bmp);
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        paint.setColor(0xFFFFFFFF);
        paint.setStrokeWidth(dp(ctx, 2.5f));
        paint.setStrokeCap(Paint.Cap.ROUND);
        paint.setStyle(Paint.Style.STROKE);
        float cx = size / 2f, top = size * .12f, tip = size * .62f;
        float ah = size * .22f, ty = size * .80f, th = size * .35f;
        canvas.drawLine(cx, top, cx, tip, paint);
        Path head = new Path();
        head.moveTo(cx - ah, tip - ah);
        head.lineTo(cx, tip);
        head.lineTo(cx + ah, tip - ah);
        canvas.drawPath(head, paint);
        canvas.drawLine(cx - th, ty, cx + th, ty, paint);
        return new BitmapDrawable(ctx.getResources(), bmp);
    }

    private static int dp(Context ctx, float v) {
        return Math.round(TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP, v, ctx.getResources().getDisplayMetrics()));
    }

    private static Field findField(Class<?> cls, String name) {
        while (cls != null && cls != Object.class) {
            try { return cls.getDeclaredField(name); }
            catch (NoSuchFieldException ignored) {}
            cls = cls.getSuperclass();
        }
        return null;
    }
}
