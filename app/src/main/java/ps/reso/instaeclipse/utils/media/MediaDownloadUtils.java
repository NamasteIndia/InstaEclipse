package ps.reso.instaeclipse.mods.media;

import android.Manifest;
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
import android.webkit.CookieManager;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.lang.reflect.Method;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;
import ps.reso.instaeclipse.utils.feature.FeatureFlags;
import ps.reso.instaeclipse.utils.feature.FeatureStatusTracker;

public class MediaDownloadButtonHook {

    private static final String TAG        = "(InstaEclipse | MediaDownload)";
    private static final String API_BASE   = "https://i.instagram.com/api/v1/media/";
    private static final String ROW_TAG    = "ie_dl_row";
    private static final String ROW_ALL_TAG = "ie_dl_row_all";

    private static final int TYPE_IMAGE    = 1;
    private static final int TYPE_VIDEO    = 2;
    private static final int TYPE_CAROUSEL = 8;

    // Latest media model captured when user opens a 3-dot menu
    private static volatile Object sCurrentMediaModel = null;
    private static volatile String sCurrentMediaId    = null;

    private static final ExecutorService sPool = Executors.newFixedThreadPool(4);

    // ─────────────────────────────────────────────────────────────────────────
    // install()
    // ─────────────────────────────────────────────────────────────────────────

    public void install(XC_LoadPackage.LoadPackageParam lpparam) {
        captureMediaModel();
        FeatureStatusTracker.setHooked("MediaDownload");
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Capture media model via View.setTag hooks
    // ─────────────────────────────────────────────────────────────────────────

    private void captureMediaModel() {
        XposedHelpers.findAndHookMethod(View.class, "setTag", Object.class,
                new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        if (!FeatureFlags.enableMediaDownload) return;
                        tryCapture(param.args[0]);
                    }
                });

        XposedHelpers.findAndHookMethod(View.class, "setTag", int.class, Object.class,
                new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        if (!FeatureFlags.enableMediaDownload) return;
                        tryCapture(param.args[1]);
                    }
                });
    }

    private static void tryCapture(Object tag) {
        if (tag == null) return;
        String pkg = tag.getClass().getName();
        if (!pkg.startsWith("com.instagram") && !pkg.startsWith("X.")) return;
        String id = extractMediaId(tag);
        if (id != null) {
            sCurrentMediaModel = tag;
            sCurrentMediaId    = id;
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Public API — no-ops kept for backward compat
    // ─────────────────────────────────────────────────────────────────────────

    public static void attachButtonIfNeeded(Activity activity) {}
    public static void ensureActivityObserver(Activity activity) {}
    /** Legacy entry-point kept for BottomSheetHookUtil — delegates to injectIntoSheetView */
    public static void injectIntoBottomSheet(Activity activity) {}

    // ─────────────────────────────────────────────────────────────────────────
    // Main injection — called by BottomSheetHookUtil with the ACTUAL sheet view
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Called with the sheet's root view immediately after it is created.
     * We walk the tree to find the vertical list container and prepend our rows.
     *
     * Instagram's post menu sheet structure (from screenshot analysis):
     *   FrameLayout (sheet container)
     *     LinearLayout (vertical — the menu list)
     *       LinearLayout (row: Unsave/QR icon buttons, horizontal)
     *       LinearLayout (row: Why you're seeing this)
     *       LinearLayout (row: Not interested)
     *       ...
     *
     * We find the first vertical LinearLayout that has ≥ 2 children
     * containing text-like content and insert our rows at position 0.
     */
    public static void injectIntoSheetView(View sheetRoot, Activity activity) {
        if (!FeatureFlags.enableMediaDownload) return;
        if (sheetRoot == null) return;

        new Handler(Looper.getMainLooper()).postDelayed(() -> {
            try {
                // Already injected into this exact view?
                if (sheetRoot.findViewWithTag(ROW_TAG) != null) return;

                ViewGroup container = findMenuContainer(sheetRoot);
                if (container == null) {
                    XposedBridge.log(TAG + ": could not find menu container in sheet");
                    return;
                }

                int mediaType  = getMediaType(sCurrentMediaModel);
                boolean isCarousel = (mediaType == TYPE_CAROUSEL);
                boolean isVideo    = (mediaType == TYPE_VIDEO);
                String mediaId     = sCurrentMediaId;

                String downloadLabel = isVideo    ? "⬇  Download Video"
                                     : isCarousel ? "⬇  Download Photo"
                                     :              "⬇  Download Photo";

                // "Download" row
                View rowDownload = buildRow(activity, ROW_TAG, downloadLabel, v -> {
                    dismissSheet(activity);
                    if (mediaId == null) { showToast("Could not find media ID"); return; }
                    fetchAndDownload(activity.getApplicationContext(), mediaId, false);
                });
                container.addView(rowDownload, 0);

                // "Download All" row — only for carousels
                if (isCarousel) {
                    View rowAll = buildRow(activity, ROW_ALL_TAG, "⬇  Download All", v -> {
                        dismissSheet(activity);
                        if (mediaId == null) { showToast("Could not find media ID"); return; }
                        fetchAndDownload(activity.getApplicationContext(), mediaId, true);
                    });
                    container.addView(rowAll, 1);
                }

                XposedBridge.log(TAG + ": injected into sheet, mediaId=" + mediaId
                        + " type=" + mediaType);

            } catch (Throwable t) {
                XposedBridge.log(TAG + ": injectIntoSheetView failed: " + t);
            }
        }, 150);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Find the vertical menu list inside the sheet
    // ─────────────────────────────────────────────────────────────────────────

    private static ViewGroup findMenuContainer(View root) {
        // BFS — find the vertical LinearLayout with the most text-containing children
        java.util.Queue<View> q = new java.util.LinkedList<>();
        q.add(root);
        ViewGroup best = null;
        int bestScore  = 0;

        while (!q.isEmpty()) {
            View v = q.poll();
            if (v instanceof LinearLayout) {
                LinearLayout ll = (LinearLayout) v;
                if (ll.getOrientation() == LinearLayout.VERTICAL && ll.getChildCount() >= 2) {
                    int score = countTextChildren(ll);
                    if (score > bestScore) {
                        bestScore = score;
                        best = ll;
                    }
                }
            }
            if (v instanceof ViewGroup) {
                ViewGroup vg = (ViewGroup) v;
                for (int i = 0; i < vg.getChildCount(); i++) q.add(vg.getChildAt(i));
            }
        }
        return best;
    }

    /** Count direct or shallow children that contain a TextView */
    private static int countTextChildren(ViewGroup vg) {
        int count = 0;
        for (int i = 0; i < vg.getChildCount(); i++) {
            if (containsText(vg.getChildAt(i))) count++;
        }
        return count;
    }

    private static boolean containsText(View v) {
        if (v instanceof TextView) return true;
        if (v instanceof ViewGroup) {
            ViewGroup vg = (ViewGroup) v;
            for (int i = 0; i < Math.min(vg.getChildCount(), 4); i++)
                if (vg.getChildAt(i) instanceof TextView) return true;
        }
        return false;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Instagram private API: fetch /info/ and download
    // ─────────────────────────────────────────────────────────────────────────

    private static void fetchAndDownload(Context ctx, String mediaId, boolean all) {
        showToast("Fetching media info…");
        sPool.submit(() -> {
            try {
                String apiUrl = API_BASE + mediaId + "/info/";
                String cookie = CookieManager.getInstance().getCookie("https://www.instagram.com");
                if (cookie == null || cookie.isEmpty())
                    cookie = CookieManager.getInstance().getCookie("https://i.instagram.com");

                String json = fetchJson(apiUrl, cookie);
                if (json == null) { showToast("Failed to fetch media info"); return; }

                List<String> urls = parseMediaUrls(json, all);
                if (urls.isEmpty()) { showToast("No downloadable media found"); return; }

                showToast("Downloading " + urls.size() + " item(s)…");
                for (String url : urls) downloadFile(ctx, url);

            } catch (Throwable t) {
                XposedBridge.log(TAG + ": fetchAndDownload: " + t);
                showToast("Download error");
            }
        });
    }

    private static String fetchJson(String apiUrl, String cookie) {
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(apiUrl).openConnection();
            conn.setRequestMethod("GET");
            conn.setRequestProperty("User-Agent",
                    "Instagram 275.0.0.27.98 Android (29/10; 420dpi; 1080x2105; "
                    + "Google/google; Pixel 4; flame; qcom; en_US; 458229237)");
            conn.setRequestProperty("Accept",               "*/*");
            conn.setRequestProperty("Accept-Language",      "en-US");
            conn.setRequestProperty("X-IG-App-ID",          "567067343352427");
            conn.setRequestProperty("X-IG-Capabilities",    "3brTvwE=");
            conn.setRequestProperty("X-IG-Connection-Type", "WIFI");
            if (cookie != null && !cookie.isEmpty())
                conn.setRequestProperty("Cookie", cookie);
            conn.setConnectTimeout(10000);
            conn.setReadTimeout(15000);
            conn.connect();

            if (conn.getResponseCode() != 200) return null;

            StringBuilder sb = new StringBuilder();
            try (BufferedReader br = new BufferedReader(
                    new InputStreamReader(conn.getInputStream(), "UTF-8"))) {
                String line;
                while ((line = br.readLine()) != null) sb.append(line);
            }
            return sb.toString();
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": fetchJson: " + t.getMessage());
            return null;
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    private static List<String> parseMediaUrls(String json, boolean downloadAll) {
        List<String> urls = new ArrayList<>();
        try {
            JSONObject root  = new JSONObject(json);
            JSONArray  items = root.getJSONArray("items");
            if (items.length() == 0) return urls;

            JSONObject item      = items.getJSONObject(0);
            int        mediaType = item.optInt("media_type", TYPE_IMAGE);

            if (mediaType == TYPE_CAROUSEL) {
                JSONArray carousel = item.getJSONArray("carousel_media");
                int count = downloadAll ? carousel.length() : 1;
                for (int i = 0; i < count; i++) {
                    String url = bestUrl(carousel.getJSONObject(i));
                    if (url != null) urls.add(url);
                }
            } else {
                String url = bestUrl(item);
                if (url != null) urls.add(url);
            }
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": parseMediaUrls: " + t.getMessage());
        }
        return urls;
    }

    private static String bestUrl(JSONObject item) {
        try {
            int type = item.optInt("media_type", TYPE_IMAGE);
            // Video: video_versions[0].url (muxed, has audio)
            if (type == TYPE_VIDEO) {
                JSONArray vv = item.optJSONArray("video_versions");
                if (vv != null && vv.length() > 0)
                    return vv.getJSONObject(0).getString("url");
            }
            // Image: image_versions2.candidates[0].url
            JSONObject iv2 = item.optJSONObject("image_versions2");
            if (iv2 != null) {
                JSONArray cands = iv2.optJSONArray("candidates");
                if (cands != null && cands.length() > 0)
                    return cands.getJSONObject(0).getString("url");
            }
            // Fallback to video_versions for carousel video slides
            JSONArray vv = item.optJSONArray("video_versions");
            if (vv != null && vv.length() > 0)
                return vv.getJSONObject(0).getString("url");
        } catch (Throwable ignored) {}
        return null;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // File download
    // ─────────────────────────────────────────────────────────────────────────

    private static void downloadFile(Context ctx, String urlString) {
        HttpURLConnection conn = null;
        InputStream  input  = null;
        OutputStream output = null;
        try {
            conn = (HttpURLConnection) new URL(urlString).openConnection();
            conn.setRequestProperty("User-Agent",
                    "Instagram 275.0.0.27.98 Android (29/10; 420dpi; 1080x2105; "
                    + "Google/google; Pixel 4; flame; qcom; en_US; 458229237)");
            conn.setRequestProperty("Referer", "https://www.instagram.com/");
            conn.setConnectTimeout(15000);
            conn.setReadTimeout(60000);
            conn.setInstanceFollowRedirects(true);
            conn.connect();

            if (conn.getResponseCode() < 200 || conn.getResponseCode() >= 300) {
                showToast("Download failed (HTTP " + conn.getResponseCode() + ")");
                return;
            }

            input = conn.getInputStream();
            String ct = conn.getContentType();
            boolean isVideo = (ct != null && ct.startsWith("video"))
                    || urlString.contains(".mp4")
                    || urlString.contains("video_versions");
            String ext      = isVideo ? ".mp4" : ".jpg";
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
                Uri col  = isVideo
                        ? MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
                        : MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY);
                Uri item = ctx.getContentResolver().insert(col, cv);
                if (item == null) { showToast("Download failed"); return; }
                output = ctx.getContentResolver().openOutputStream(item);
                if (output == null) { showToast("Download failed"); return; }
                copy(input, output);
                ContentValues done = new ContentValues();
                done.put(MediaStore.MediaColumns.IS_PENDING, 0);
                ctx.getContentResolver().update(item, done, null, null);
            } else {
                if (!hasLegacyPermission(ctx)) { showToast("Storage permission required"); return; }
                File dir = new File(Environment.getExternalStoragePublicDirectory(
                        isVideo ? Environment.DIRECTORY_MOVIES : Environment.DIRECTORY_PICTURES),
                        "InstaEclipse");
                dir.mkdirs();
                output = new FileOutputStream(new File(dir, fileName));
                copy(input, output);
            }
            showToast("Saved ✅ " + (isVideo ? "Movies" : "Pictures") + "/InstaEclipse");
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": downloadFile: " + t);
            showToast("Download failed");
        } finally {
            try { if (input  != null) input.close();  } catch (Exception ignored) {}
            try { if (output != null) output.close(); } catch (Exception ignored) {}
            if (conn != null) conn.disconnect();
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Reflection helpers
    // ─────────────────────────────────────────────────────────────────────────

    private static String extractMediaId(Object obj) {
        if (obj == null) return null;
        for (String name : new String[]{"pk", "mPk", "mId", "mediaId", "id", "mMediaId"}) {
            try {
                java.lang.reflect.Field f = findField(obj.getClass(), name);
                if (f == null) continue;
                f.setAccessible(true);
                Object v = f.get(obj);
                if (v == null) continue;
                String s = v.toString().trim();
                if (s.matches("\\d{9,20}")) return s;
            } catch (Throwable ignored) {}
        }
        for (String name : new String[]{"getPk", "getMediaId", "getId", "getPkAsString"}) {
            try {
                Method m = findMethod(obj.getClass(), name);
                if (m == null) continue;
                m.setAccessible(true);
                Object v = m.invoke(obj);
                if (v == null) continue;
                String s = v.toString().trim();
                if (s.matches("\\d{9,20}")) return s;
            } catch (Throwable ignored) {}
        }
        return null;
    }

    private static int getMediaType(Object obj) {
        if (obj == null) return TYPE_IMAGE;
        for (String name : new String[]{"mediaType", "mMediaType", "media_type", "type"}) {
            try {
                java.lang.reflect.Field f = findField(obj.getClass(), name);
                if (f == null) continue;
                f.setAccessible(true);
                Object v = f.get(obj);
                if (v instanceof Integer) return (Integer) v;
            } catch (Throwable ignored) {}
        }
        for (String name : new String[]{"getMediaType", "getType"}) {
            try {
                Method m = findMethod(obj.getClass(), name);
                if (m == null) continue;
                m.setAccessible(true);
                Object v = m.invoke(obj);
                if (v instanceof Integer) return (Integer) v;
            } catch (Throwable ignored) {}
        }
        return TYPE_IMAGE;
    }

    private static java.lang.reflect.Field findField(Class<?> cls, String name) {
        while (cls != null && cls != Object.class) {
            try { return cls.getDeclaredField(name); }
            catch (NoSuchFieldException ignored) {}
            cls = cls.getSuperclass();
        }
        return null;
    }

    private static Method findMethod(Class<?> cls, String name) {
        while (cls != null && cls != Object.class) {
            for (Method m : cls.getDeclaredMethods())
                if (m.getName().equals(name) && m.getParameterCount() == 0) return m;
            cls = cls.getSuperclass();
        }
        return null;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Misc helpers
    // ─────────────────────────────────────────────────────────────────────────

    private static void dismissSheet(Activity a) {
        try { a.onBackPressed(); } catch (Throwable ignored) {}
    }

    private static void copy(InputStream in, OutputStream out) throws Exception {
        byte[] buf = new byte[32768]; int n;
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
    // Row builder
    // ─────────────────────────────────────────────────────────────────────────

    private static View buildRow(Context ctx, String tag,
                                  String label, View.OnClickListener onClick) {
        LinearLayout row = new LinearLayout(ctx);
        row.setTag(tag);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        int h = dp(ctx, 16);
        row.setPadding(h, dp(ctx, 14), h, dp(ctx, 14));

        StateListDrawable bg = new StateListDrawable();
        bg.addState(new int[]{android.R.attr.state_pressed},
                new ColorDrawable(Color.parseColor("#20000000")));
        bg.addState(new int[]{}, new ColorDrawable(Color.TRANSPARENT));
        row.setBackground(bg);

        ImageView icon = new ImageView(ctx);
        icon.setImageDrawable(makeIcon(ctx));
        int sz = dp(ctx, 24);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(sz, sz);
        lp.setMarginEnd(dp(ctx, 16));
        icon.setLayoutParams(lp);
        row.addView(icon);

        TextView tv = new TextView(ctx);
        tv.setText(label);
        // Match Instagram's menu text colour (dark on light sheet)
        tv.setTextColor(Color.parseColor("#262626"));
        tv.setTextSize(16f);
        row.addView(tv);

        row.setOnClickListener(onClick);
        return row;
    }

    private static BitmapDrawable makeIcon(Context ctx) {
        int size = dp(ctx, 24);
        Bitmap bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(bmp);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setColor(Color.parseColor("#262626"));
        p.setStrokeWidth(dp(ctx, 2f));
        p.setStrokeCap(Paint.Cap.ROUND);
        p.setStyle(Paint.Style.STROKE);
        float cx = size / 2f, top = size * .10f, tip = size * .60f;
        float ah = size * .20f, ty = size * .78f, th = size * .32f;
        c.drawLine(cx, top, cx, tip, p);
        Path head = new Path();
        head.moveTo(cx - ah, tip - ah); head.lineTo(cx, tip); head.lineTo(cx + ah, tip - ah);
        c.drawPath(head, p);
        c.drawLine(cx - th, ty, cx + th, ty, p);
        return new BitmapDrawable(ctx.getResources(), bmp);
    }

    private static int dp(Context ctx, float v) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v,
                ctx.getResources().getDisplayMetrics()));
    }
}