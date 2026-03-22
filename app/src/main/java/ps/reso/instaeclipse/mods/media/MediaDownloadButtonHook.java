package ps.reso.instaeclipse.mods.media;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.AndroidAppHelper;
import android.content.ContentValues;
import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedDeque;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;
import ps.reso.instaeclipse.utils.feature.FeatureFlags;
import ps.reso.instaeclipse.utils.feature.FeatureStatusTracker;
import ps.reso.instaeclipse.utils.media.MediaDownloadUtils;

public class MediaDownloadButtonHook {

    private static final String BTN_TAG    = "ie_dl_btn";
    private static final String SHEET_TAG  = "ie_dl_sheet_item";

    // Separate caches for video and image URLs
    private static final ConcurrentLinkedDeque<String> VIDEO_CACHE = new ConcurrentLinkedDeque<>();
    private static final ConcurrentLinkedDeque<String> IMAGE_CACHE = new ConcurrentLinkedDeque<>();
    private static final int CACHE_MAX = 20;

    private static volatile long lastClickTs = 0;

    // ─────────────────────────────────────────────────────────────────────────
    // install()
    // ─────────────────────────────────────────────────────────────────────────

    public void install(XC_LoadPackage.LoadPackageParam lpparam) {
        hookTigonNetwork(lpparam);
        hookExoPlayer(lpparam.classLoader);
        hookOkHttp(lpparam.classLoader);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // URL capture — TigonServiceLayer
    // ─────────────────────────────────────────────────────────────────────────

    private void hookTigonNetwork(XC_LoadPackage.LoadPackageParam lpparam) {
        try {
            ClassLoader cl = lpparam.classLoader;
            Class<?> tigonClass = cl.loadClass("com.instagram.api.tigon.TigonServiceLayer");
            Class<?> param1 = null;
            String uriField = null;
            for (Method m : tigonClass.getDeclaredMethods()) {
                if (m.getName().equals("startRequest") && m.getParameterCount() == 3) {
                    param1 = m.getParameterTypes()[0]; break;
                }
            }
            if (param1 != null) {
                for (Field f : param1.getDeclaredFields()) {
                    if (f.getType().equals(URI.class)) { uriField = f.getName(); break; }
                }
            }
            if (param1 == null || uriField == null) return;
            final String finalUri = uriField;
            XposedBridge.hookAllMethods(tigonClass, "startRequest", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    if (!FeatureFlags.enableMediaDownload) return;
                    try {
                        URI uri = (URI) XposedHelpers.getObjectField(param.args[0], finalUri);
                        if (uri == null) return;
                        String url = uri.toString();
                        if (MediaDownloadUtils.isTrustedInstagramHost(uri.getHost())) {
                            cacheByType(url);
                            FeatureStatusTracker.setHooked("MediaDownload");
                        }
                    } catch (Throwable ignored) {}
                }
            });
        } catch (Throwable t) {
            XposedBridge.log("(InstaEclipse | MediaDownload): Tigon hook failed: " + t.getMessage());
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // URL capture — ExoPlayer (reels / videos with audio)
    // ─────────────────────────────────────────────────────────────────────────

    private void hookExoPlayer(ClassLoader cl) {
        // Hook setMediaItem and addMediaItem
        for (String method : new String[]{"setMediaItem", "addMediaItem"}) {
            try {
                Class<?> exo = cl.loadClass("com.google.android.exoplayer2.ExoPlayer");
                Class<?> mi  = cl.loadClass("com.google.android.exoplayer2.MediaItem");
                XposedHelpers.findAndHookMethod(exo, method, mi, new XC_MethodHook() {
                    @Override protected void beforeHookedMethod(MethodHookParam p) {
                        if (!FeatureFlags.enableMediaDownload) return;
                        String url = extractUrlFromMediaItem(p.args[0]);
                        if (url != null) cacheByType(url);
                    }
                });
            } catch (Throwable ignored) {}
        }

        // MediaPlayer fallback
        try {
            XposedHelpers.findAndHookMethod(android.media.MediaPlayer.class,
                    "setDataSource", String.class, new XC_MethodHook() {
                        @Override protected void beforeHookedMethod(MethodHookParam p) {
                            if (!FeatureFlags.enableMediaDownload) return;
                            String url = (String) p.args[0];
                            if (url != null && MediaDownloadUtils.isTrustedInstagramHost(getHost(url)))
                                cacheByType(url);
                        }
                    });
        } catch (Throwable ignored) {}
    }

    // ─────────────────────────────────────────────────────────────────────────
    // URL capture — OkHttp response (catches image URLs Instagram loads lazily)
    // ─────────────────────────────────────────────────────────────────────────

    private void hookOkHttp(ClassLoader cl) {
        try {
            // Hook OkHttp3 RealCall.execute to intercept response URLs
            Class<?> realCall = cl.loadClass("okhttp3.internal.connection.RealCall");
            XposedHelpers.findAndHookMethod(realCall, "execute", new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam param) {
                    if (!FeatureFlags.enableMediaDownload) return;
                    try {
                        Object response = param.getResult();
                        if (response == null) return;
                        // Get request URL from response
                        Object request = XposedHelpers.callMethod(response, "request");
                        Object httpUrl = XposedHelpers.callMethod(request, "url");
                        String url = httpUrl.toString();
                        if (MediaDownloadUtils.isTrustedInstagramHost(getHost(url)))
                            cacheByType(url);
                    } catch (Throwable ignored) {}
                }
            });
        } catch (Throwable ignored) {}
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Public API — called from UIHookManager.setupHooks()
    // ─────────────────────────────────────────────────────────────────────────

    public static void attachButtonIfNeeded(Activity activity) {
        if (activity == null || !FeatureFlags.enableMediaDownload) return;
        new Handler(Looper.getMainLooper()).postDelayed(() -> {
            try {
                View decor = activity.getWindow().getDecorView();
                if (!(decor instanceof ViewGroup)) return;
                ViewGroup root = (ViewGroup) decor;
                if (root.findViewWithTag(BTN_TAG) != null) return;

                ImageButton btn = buildFloatingButton(activity);
                FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(dp(activity, 48), dp(activity, 48));
                lp.gravity = Gravity.BOTTOM | Gravity.END;
                lp.bottomMargin = dp(activity, 120);
                lp.rightMargin  = dp(activity, 12);
                btn.setLayoutParams(lp);
                btn.setOnClickListener(v -> onDownloadClick(activity));
                root.addView(btn);
            } catch (Throwable t) {
                XposedBridge.log("(InstaEclipse | MediaDownload): attach failed: " + t);
            }
        }, 600);
    }

    public static void ensureActivityObserver(Activity activity) {
        if (activity == null || !FeatureFlags.enableMediaDownload) return;
        try {
            View decor = activity.getWindow().getDecorView();
            decor.getViewTreeObserver().addOnGlobalLayoutListener(() -> {
                if (!FeatureFlags.enableMediaDownload) return;
                if (decor instanceof ViewGroup
                        && ((ViewGroup) decor).findViewWithTag(BTN_TAG) == null) {
                    attachButtonIfNeeded(activity);
                }
            });
        } catch (Throwable ignored) {}
    }

    /**
     * Called when Instagram's 3-dot bottom sheet is shown.
     * Injects a "⬇ Download" row into the sheet's LinearLayout.
     */
    public static void injectIntoBottomSheet(Activity activity) {
        if (activity == null || !FeatureFlags.enableMediaDownload) return;
        new Handler(Looper.getMainLooper()).postDelayed(() -> {
            try {
                View decor = activity.getWindow().getDecorView();
                ViewGroup sheet = findBottomSheet((ViewGroup) decor);
                if (sheet == null) return;
                if (sheet.findViewWithTag(SHEET_TAG) != null) return;

                // Build a row matching Instagram's bottom sheet style
                LinearLayout row = new LinearLayout(activity);
                row.setTag(SHEET_TAG);
                row.setOrientation(LinearLayout.HORIZONTAL);
                row.setGravity(Gravity.CENTER_VERTICAL);
                int rowPad = dp(activity, 16);
                row.setPadding(rowPad, dp(activity, 14), rowPad, dp(activity, 14));
                row.setBackground(makeRowBackground());

                // Icon
                ImageView icon = new ImageView(activity);
                icon.setImageDrawable(makeDownloadIcon(activity));
                int iconSize = dp(activity, 24);
                LinearLayout.LayoutParams iconLp = new LinearLayout.LayoutParams(iconSize, iconSize);
                iconLp.setMarginEnd(dp(activity, 16));
                icon.setLayoutParams(iconLp);
                row.addView(icon);

                // Label
                TextView label = new TextView(activity);
                label.setText("Download");
                label.setTextColor(Color.WHITE);
                label.setTextSize(16f);
                row.addView(label);

                // Click handler
                row.setOnClickListener(v -> {
                    onDownloadClick(activity);
                    // Dismiss the sheet by simulating back press
                    try { activity.onBackPressed(); } catch (Throwable ignored) {}
                });

                // Insert at top of sheet
                sheet.addView(row, 0);
            } catch (Throwable t) {
                XposedBridge.log("(InstaEclipse | MediaDownload): sheet inject failed: " + t);
            }
        }, 200);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Bottom sheet finder — BFS for the NestedScrollView / LinearLayout
    // ─────────────────────────────────────────────────────────────────────────

    private static ViewGroup findBottomSheet(ViewGroup root) {
        // Walk tree looking for a tall vertical LinearLayout near the bottom
        // that contains TextViews with menu-like content
        java.util.Queue<ViewGroup> q = new java.util.LinkedList<>();
        q.add(root);
        while (!q.isEmpty()) {
            ViewGroup vg = q.poll();
            if (isSheetCandidate(vg)) return vg;
            for (int i = 0; i < vg.getChildCount(); i++) {
                View child = vg.getChildAt(i);
                if (child instanceof ViewGroup) q.add((ViewGroup) child);
            }
        }
        return null;
    }

    private static boolean isSheetCandidate(ViewGroup vg) {
        if (vg.getChildCount() < 2) return false;
        String name = vg.getClass().getName();
        // Instagram's bottom sheet is typically a LinearLayout inside a FrameLayout
        if (!(vg instanceof LinearLayout)) return false;
        if (((LinearLayout) vg).getOrientation() != LinearLayout.VERTICAL) return false;
        // Must have TextView children (menu items)
        int textCount = 0;
        for (int i = 0; i < Math.min(vg.getChildCount(), 5); i++) {
            View c = vg.getChildAt(i);
            if (c instanceof TextView || containsTextView(c)) textCount++;
        }
        return textCount >= 2;
    }

    private static boolean containsTextView(View v) {
        if (v instanceof TextView) return true;
        if (v instanceof ViewGroup) {
            ViewGroup vg = (ViewGroup) v;
            for (int i = 0; i < vg.getChildCount(); i++)
                if (containsTextView(vg.getChildAt(i))) return true;
        }
        return false;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Download
    // ─────────────────────────────────────────────────────────────────────────

    private static void onDownloadClick(Activity activity) {
        long now = System.currentTimeMillis();
        if (now - lastClickTs < 1500) return;
        lastClickTs = now;

        // Prefer video, then image
        String url = getBestVideo();
        if (url == null) url = getBestImage();
        if (url == null) {
            showToast("No media found — open a post or scroll first");
            return;
        }
        final String finalUrl = url;
        showToast("Downloading…");
        Context ctx = activity.getApplicationContext();
        new Thread(() -> downloadToGallery(ctx, finalUrl)).start();
    }

    private static String getBestVideo() {
        return VIDEO_CACHE.isEmpty() ? null : VIDEO_CACHE.peekFirst();
    }

    private static String getBestImage() {
        return IMAGE_CACHE.isEmpty() ? null : IMAGE_CACHE.peekFirst();
    }

    private static void downloadToGallery(Context context, String urlString) {
        HttpURLConnection connection = null;
        InputStream input = null;
        OutputStream output = null;
        try {
            URL url = new URL(urlString);
            if (!MediaDownloadUtils.isTrustedInstagramHost(url.getHost())) {
                showToast("Blocked: untrusted host"); return;
            }
            connection = (HttpURLConnection) url.openConnection();
            connection.setRequestProperty("User-Agent",
                    "Mozilla/5.0 (Linux; Android 12; Pixel 6) AppleWebKit/537.36 "
                    + "(KHTML, like Gecko) Chrome/112.0.0.0 Mobile Safari/537.36");
            connection.setRequestProperty("Referer", "https://www.instagram.com/");
            connection.setRequestProperty("Accept", "*/*");
            connection.setConnectTimeout(15000);
            connection.setReadTimeout(60000);
            connection.setInstanceFollowRedirects(true);
            connection.connect();

            int code = connection.getResponseCode();
            if (code < 200 || code >= 300) {
                showToast("Download failed (HTTP " + code + ")"); return;
            }

            input = connection.getInputStream();
            String ext = MediaDownloadUtils.fileExtensionForUrl(urlString);
            boolean isVideo = ext.equals(".mp4");
            String fileName = "InstaEclipse_" + System.currentTimeMillis()
                    + "_" + UUID.randomUUID().toString().substring(0, 8) + ext;

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ContentValues cv = new ContentValues();
                cv.put(MediaStore.MediaColumns.DISPLAY_NAME, fileName);
                cv.put(MediaStore.MediaColumns.MIME_TYPE, isVideo ? "video/mp4" : "image/jpeg");
                cv.put(MediaStore.MediaColumns.RELATIVE_PATH,
                        (isVideo ? Environment.DIRECTORY_MOVIES : Environment.DIRECTORY_PICTURES)
                                + "/InstaEclipse");
                cv.put(MediaStore.MediaColumns.IS_PENDING, 1);
                Uri col = isVideo
                        ? MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
                        : MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY);
                Uri item = context.getContentResolver().insert(col, cv);
                if (item == null) { showToast("Download failed"); return; }
                output = context.getContentResolver().openOutputStream(item);
                if (output == null) { showToast("Download failed"); return; }
                copy(input, output);
                ContentValues done = new ContentValues();
                done.put(MediaStore.MediaColumns.IS_PENDING, 0);
                context.getContentResolver().update(item, done, null, null);
            } else {
                if (!hasLegacyPermission(context)) { showToast("Storage permission required"); return; }
                File dir = new File(Environment.getExternalStoragePublicDirectory(
                        isVideo ? Environment.DIRECTORY_MOVIES : Environment.DIRECTORY_PICTURES),
                        "InstaEclipse");
                dir.mkdirs();
                output = new FileOutputStream(new File(dir, fileName));
                copy(input, output);
            }
            showToast("Saved ✅ " + (isVideo ? "Movies" : "Pictures") + "/InstaEclipse");
        } catch (Throwable t) {
            XposedBridge.log("(InstaEclipse | MediaDownload): DL error: " + t);
            showToast("Download failed: " + t.getMessage());
        } finally {
            try { if (input  != null) input.close();  } catch (Exception ignored) {}
            try { if (output != null) output.close(); } catch (Exception ignored) {}
            if (connection != null) connection.disconnect();
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Cache helpers
    // ─────────────────────────────────────────────────────────────────────────

    private static void cacheByType(String url) {
        if (url == null || url.isEmpty()) return;
        if (MediaDownloadUtils.isVideoUrl(url)) {
            VIDEO_CACHE.remove(url);
            VIDEO_CACHE.addFirst(url);
            while (VIDEO_CACHE.size() > CACHE_MAX) VIDEO_CACHE.removeLast();
        } else if (MediaDownloadUtils.isTrustedInstagramHost(getHost(url))) {
            IMAGE_CACHE.remove(url);
            IMAGE_CACHE.addFirst(url);
            while (IMAGE_CACHE.size() > CACHE_MAX) IMAGE_CACHE.removeLast();
        }
    }

    private static String getHost(String url) {
        try { return new URL(url).getHost(); } catch (Throwable e) { return ""; }
    }

    private static String extractUrlFromMediaItem(Object item) {
        if (item == null) return null;
        try {
            Field lc = findFieldUp(item.getClass(), "localConfiguration");
            if (lc == null) lc = findFieldUp(item.getClass(), "playbackProperties");
            if (lc == null) return null;
            lc.setAccessible(true);
            Object cfg = lc.get(item);
            if (cfg == null) return null;
            Field uriField = findFieldUp(cfg.getClass(), "uri");
            if (uriField == null) return null;
            uriField.setAccessible(true);
            Object uri = uriField.get(cfg);
            return uri != null ? uri.toString() : null;
        } catch (Throwable ignored) { return null; }
    }

    private static Field findFieldUp(Class<?> cls, String name) {
        while (cls != null && cls != Object.class) {
            try { return cls.getDeclaredField(name); }
            catch (NoSuchFieldException ignored) {}
            cls = cls.getSuperclass();
        }
        return null;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Misc helpers
    // ─────────────────────────────────────────────────────────────────────────

    private static void copy(InputStream in, OutputStream out) throws Exception {
        byte[] buf = new byte[8192]; int n;
        while ((n = in.read(buf)) != -1) out.write(buf, 0, n);
        out.flush();
    }

    private static boolean hasLegacyPermission(Context ctx) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) return true;
        return ctx.checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                == PackageManager.PERMISSION_GRANTED;
    }

    private static void showToast(String msg) {
        try {
            Context ctx = AndroidAppHelper.currentApplication().getApplicationContext();
            new Handler(Looper.getMainLooper()).post(() ->
                    Toast.makeText(ctx, msg, Toast.LENGTH_SHORT).show());
        } catch (Throwable ignored) {}
    }

    // ─────────────────────────────────────────────────────────────────────────
    // UI drawing
    // ─────────────────────────────────────────────────────────────────────────

    private static ImageButton buildFloatingButton(Context ctx) {
        ImageButton btn = new ImageButton(ctx);
        btn.setTag(BTN_TAG);
        btn.setImageDrawable(makeDownloadIcon(ctx));
        btn.setBackground(makeCircleBg());
        int p = dp(ctx, 8);
        btn.setPadding(p, p, p, p);
        btn.setElevation(dp(ctx, 8));
        btn.setContentDescription("Download media");
        return btn;
    }

    private static GradientDrawable makeCircleBg() {
        GradientDrawable gd = new GradientDrawable();
        gd.setShape(GradientDrawable.OVAL);
        gd.setColor(0xDD000000);
        gd.setStroke(3, 0x88FFFFFF);
        return gd;
    }

    private static StateListDrawable makeRowBackground() {
        StateListDrawable sl = new StateListDrawable();
        sl.addState(new int[]{android.R.attr.state_pressed},
                new ColorDrawable(Color.parseColor("#40FFFFFF")));
        sl.addState(new int[]{}, new ColorDrawable(Color.TRANSPARENT));
        return sl;
    }

    private static BitmapDrawable makeDownloadIcon(Context ctx) {
        int size = dp(ctx, 26);
        Bitmap bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bmp);
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        paint.setColor(0xFFFFFFFF);
        paint.setStrokeWidth(dp(ctx, 2.5f));
        paint.setStrokeCap(Paint.Cap.ROUND);
        paint.setStyle(Paint.Style.STROKE);
        float cx = size / 2f, top = size * .10f, tip = size * .62f;
        float ah = size * .22f, ty = size * .80f, th = size * .33f;
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
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v,
                ctx.getResources().getDisplayMetrics()));
    }
}