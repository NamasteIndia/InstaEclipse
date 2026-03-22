package ps.reso.instaeclipse.mods.media;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
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

/**
 * MediaDownloadButtonHook
 *
 * Approach (same as AeroInsta):
 *  1. Hook the 3-dot menu bottom sheet to inject "Download" and "Download All" rows.
 *  2. Intercept the media model object that Instagram passes when opening the menu,
 *     extract the media_id (pk) from it via reflection.
 *  3. On tap, call https://i.instagram.com/api/v1/media/{media_id}/info/ using the
 *     session cookies already stored by Instagram's WebView (CookieManager).
 *  4. Parse JSON:
 *       Single image  -> items[0].image_versions2.candidates[0].url
 *       Single video  -> items[0].video_versions[0].url
 *       Carousel      -> items[0].carousel_media[N].(image|video)
 *  5. Download each URL with a background thread + MediaStore.
 */
public class MediaDownloadButtonHook {

    private static final String TAG = "(InstaEclipse | MediaDownload)";
    private static final String API_BASE = "https://i.instagram.com/api/v1/media/";
    private static final String SHEET_TAG = "ie_dl_row";

    // Media type constants (Instagram internal)
    private static final int TYPE_IMAGE    = 1;
    private static final int TYPE_VIDEO    = 2;
    private static final int TYPE_CAROUSEL = 8;

    // Most-recently-seen media object (set when 3-dot menu opens)
    private static volatile Object sCurrentMediaModel = null;
    // Most-recently-seen media_id string
    private static volatile String sCurrentMediaId = null;

    private static final ExecutorService sPool = Executors.newFixedThreadPool(4);

    // ─────────────────────────────────────────────────────────────────────────
    // install()
    // ─────────────────────────────────────────────────────────────────────────

    public void install(XC_LoadPackage.LoadPackageParam lpparam) {
        hookMenuOpening(lpparam.classLoader);
        FeatureStatusTracker.setHooked("MediaDownload");
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Hook: capture media model when user taps the 3-dot menu
    //
    // Instagram sets the media model on the bottom sheet before showing it.
    // We hook the most likely entry points to capture it.
    // ─────────────────────────────────────────────────────────────────────────

    private void hookMenuOpening(ClassLoader cl) {
        // Hook View.setTag — Instagram sets the media object as view tag
        // on action rows before the menu opens. We scan for media-like objects.
        XposedHelpers.findAndHookMethod(View.class, "setTag", Object.class,
                new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        if (!FeatureFlags.enableMediaDownload) return;
                        Object tag = param.args[0];
                        if (tag == null) return;
                        String mediaId = extractMediaId(tag);
                        if (mediaId != null) {
                            sCurrentMediaModel = tag;
                            sCurrentMediaId = mediaId;
                        }
                    }
                });

        // Also hook View.setTag(int, Object) for keyed tags
        XposedHelpers.findAndHookMethod(View.class, "setTag", int.class, Object.class,
                new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        if (!FeatureFlags.enableMediaDownload) return;
                        Object tag = param.args[1];
                        if (tag == null) return;
                        String mediaId = extractMediaId(tag);
                        if (mediaId != null) {
                            sCurrentMediaModel = tag;
                            sCurrentMediaId = mediaId;
                        }
                    }
                });
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Public API — called from BottomSheetHookUtil when a sheet opens
    // ─────────────────────────────────────────────────────────────────────────

    /** No-op — floating button removed */
    public static void attachButtonIfNeeded(Activity activity) {}

    /** No-op — floating button removed */
    public static void ensureActivityObserver(Activity activity) {}

    /**
     * Called by BottomSheetHookUtil when Instagram's 3-dot menu opens.
     * Injects "Download" (and optionally "Download All") at the top of the sheet.
     */
    public static void injectIntoBottomSheet(Activity activity) {
        if (activity == null || !FeatureFlags.enableMediaDownload) return;
        new Handler(Looper.getMainLooper()).postDelayed(() -> {
            try {
                View decor = activity.getWindow().getDecorView();
                ViewGroup sheet = findBottomSheet((ViewGroup) decor);
                if (sheet == null) return;
                if (sheet.findViewWithTag(SHEET_TAG) != null) return;

                // Determine media type from current model so we can label correctly
                int mediaType = getMediaType(sCurrentMediaModel);
                boolean isCarousel = (mediaType == TYPE_CAROUSEL);
                boolean isVideo    = (mediaType == TYPE_VIDEO);
                String mediaId     = sCurrentMediaId;

                String downloadLabel = isVideo ? "⬇  Download Video"
                        : isCarousel ? "⬇  Download Photo"
                        : "⬇  Download Photo";

                // "Download" row
                LinearLayout rowDownload = buildRow(activity, SHEET_TAG, downloadLabel, v -> {
                    dismissSheet(activity);
                    if (mediaId == null) { showToast("Could not find media ID"); return; }
                    fetchAndDownload(activity.getApplicationContext(), mediaId, false);
                });
                sheet.addView(rowDownload, 0);

                // "Download All" row — only for carousels
                if (isCarousel) {
                    LinearLayout rowAll = buildRow(activity, SHEET_TAG + "_all",
                            "⬇  Download All", v -> {
                                dismissSheet(activity);
                                if (mediaId == null) { showToast("Could not find media ID"); return; }
                                fetchAndDownload(activity.getApplicationContext(), mediaId, true);
                            });
                    sheet.addView(rowAll, 1);
                }

            } catch (Throwable t) {
                XposedBridge.log(TAG + ": sheet inject failed: " + t);
            }
        }, 250);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Core: fetch media info from Instagram API, then download
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Calls https://i.instagram.com/api/v1/media/{media_id}/info/
     * using the session cookies Instagram already has in CookieManager.
     * Parses the JSON and downloads the appropriate URL(s).
     */
    private static void fetchAndDownload(Context ctx, String mediaId, boolean downloadAll) {
        showToast("Fetching media info…");
        sPool.submit(() -> {
            try {
                String apiUrl = API_BASE + mediaId + "/info/";
                String cookie = CookieManager.getInstance().getCookie("https://www.instagram.com");
                if (cookie == null || cookie.isEmpty()) {
                    cookie = CookieManager.getInstance().getCookie("https://i.instagram.com");
                }

                // Fetch media info JSON
                String json = fetchJson(apiUrl, cookie);
                if (json == null) {
                    showToast("Failed to fetch media info");
                    return;
                }

                List<String> urls = parseMediaUrls(json, downloadAll);
                if (urls.isEmpty()) {
                    showToast("No downloadable media found");
                    return;
                }

                showToast("Downloading " + urls.size() + " item(s)…");
                for (String url : urls) {
                    downloadFile(ctx, url);
                }

            } catch (Throwable t) {
                XposedBridge.log(TAG + ": fetchAndDownload error: " + t);
                showToast("Download error: " + t.getMessage());
            }
        });
    }

    /**
     * Makes the authenticated GET request to the Instagram private API.
     */
    private static String fetchJson(String apiUrl, String cookie) {
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(apiUrl).openConnection();
            conn.setRequestMethod("GET");
            conn.setRequestProperty("User-Agent",
                    "Instagram 275.0.0.27.98 Android (29/10; 420dpi; 1080x2105; "
                    + "Google/google; Pixel 4; flame; qcom; en_US; 458229237)");
            conn.setRequestProperty("Accept",           "*/*");
            conn.setRequestProperty("Accept-Language",  "en-US");
            conn.setRequestProperty("X-IG-App-ID",      "567067343352427");
            conn.setRequestProperty("X-IG-Capabilities","3brTvwE=");
            conn.setRequestProperty("X-IG-Connection-Type", "WIFI");
            if (cookie != null && !cookie.isEmpty()) {
                conn.setRequestProperty("Cookie", cookie);
            }
            conn.setConnectTimeout(10000);
            conn.setReadTimeout(15000);
            conn.connect();

            int code = conn.getResponseCode();
            if (code != 200) {
                XposedBridge.log(TAG + ": API HTTP " + code + " for " + apiUrl);
                return null;
            }

            StringBuilder sb = new StringBuilder();
            try (BufferedReader br = new BufferedReader(
                    new InputStreamReader(conn.getInputStream(), "UTF-8"))) {
                String line;
                while ((line = br.readLine()) != null) sb.append(line);
            }
            return sb.toString();
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": fetchJson error: " + t.getMessage());
            return null;
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    /**
     * Parses the /info/ JSON response and returns CDN URLs to download.
     *
     * JSON structure:
     * {
     *   "items": [{
     *     "media_type": 1|2|8,
     *     "pk": "12345",
     *     "image_versions2": { "candidates": [{ "url": "...", "width": N, "height": N }] },
     *     "video_versions":  [{ "url": "...", "width": N, "height": N }],
     *     "carousel_media":  [ { same fields as above per slide } ]
     *   }]
     * }
     */
    private static List<String> parseMediaUrls(String json, boolean downloadAll) {
        List<String> urls = new ArrayList<>();
        try {
            JSONObject root = new JSONObject(json);
            JSONArray items = root.getJSONArray("items");
            if (items.length() == 0) return urls;
            JSONObject item = items.getJSONObject(0);
            int mediaType = item.optInt("media_type", TYPE_IMAGE);

            if (mediaType == TYPE_CAROUSEL) {
                JSONArray carousel = item.getJSONArray("carousel_media");
                if (downloadAll) {
                    for (int i = 0; i < carousel.length(); i++) {
                        String url = extractBestUrl(carousel.getJSONObject(i));
                        if (url != null) urls.add(url);
                    }
                } else {
                    // Download just the first (current) item
                    if (carousel.length() > 0) {
                        String url = extractBestUrl(carousel.getJSONObject(0));
                        if (url != null) urls.add(url);
                    }
                }
            } else {
                String url = extractBestUrl(item);
                if (url != null) urls.add(url);
            }
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": JSON parse error: " + t.getMessage());
        }
        return urls;
    }

    /**
     * Picks the highest-quality URL from a single media item.
     * Prefers video_versions (has audio) over image.
     */
    private static String extractBestUrl(JSONObject item) {
        try {
            int type = item.optInt("media_type", TYPE_IMAGE);

            // Video — use video_versions[0].url (highest quality, has audio)
            if (type == TYPE_VIDEO) {
                JSONArray videoVersions = item.optJSONArray("video_versions");
                if (videoVersions != null && videoVersions.length() > 0) {
                    return videoVersions.getJSONObject(0).getString("url");
                }
            }

            // Image — use image_versions2.candidates[0].url (highest quality)
            JSONObject imageVersions = item.optJSONObject("image_versions2");
            if (imageVersions != null) {
                JSONArray candidates = imageVersions.optJSONArray("candidates");
                if (candidates != null && candidates.length() > 0) {
                    return candidates.getJSONObject(0).getString("url");
                }
            }

            // Fallback: try video_versions for carousel video slides
            JSONArray videoVersions = item.optJSONArray("video_versions");
            if (videoVersions != null && videoVersions.length() > 0) {
                return videoVersions.getJSONObject(0).getString("url");
            }

        } catch (Throwable ignored) {}
        return null;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // File download to gallery
    // ─────────────────────────────────────────────────────────────────────────

    private static void downloadFile(Context context, String urlString) {
        HttpURLConnection conn = null;
        InputStream input = null;
        OutputStream output = null;
        try {
            URL url = new URL(urlString);
            conn = (HttpURLConnection) url.openConnection();
            conn.setRequestProperty("User-Agent",
                    "Instagram 275.0.0.27.98 Android (29/10; 420dpi; 1080x2105; "
                    + "Google/google; Pixel 4; flame; qcom; en_US; 458229237)");
            conn.setRequestProperty("Referer", "https://www.instagram.com/");
            conn.setConnectTimeout(15000);
            conn.setReadTimeout(60000);
            conn.setInstanceFollowRedirects(true);
            conn.connect();

            int code = conn.getResponseCode();
            if (code < 200 || code >= 300) {
                showToast("Download failed (HTTP " + code + ")");
                return;
            }

            input = conn.getInputStream();

            // Detect type from Content-Type header, then URL
            String ct = conn.getContentType();
            boolean isVideo = (ct != null && ct.startsWith("video"))
                    || urlString.contains(".mp4")
                    || urlString.contains("video_versions");
            String ext = isVideo ? ".mp4" : ".jpg";
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
                if (!hasLegacyPermission(context)) {
                    showToast("Storage permission required");
                    return;
                }
                File dir = new File(Environment.getExternalStoragePublicDirectory(
                        isVideo ? Environment.DIRECTORY_MOVIES : Environment.DIRECTORY_PICTURES),
                        "InstaEclipse");
                dir.mkdirs();
                output = new FileOutputStream(new File(dir, fileName));
                copy(input, output);
            }
            showToast("Saved ✅ " + (isVideo ? "Movies" : "Pictures") + "/InstaEclipse");

        } catch (Throwable t) {
            XposedBridge.log(TAG + ": downloadFile error: " + t);
            showToast("Download failed");
        } finally {
            try { if (input  != null) input.close();  } catch (Exception ignored) {}
            try { if (output != null) output.close(); } catch (Exception ignored) {}
            if (conn != null) conn.disconnect();
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Media ID extraction via reflection
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Tries to extract a numeric media ID (pk) from any Instagram model object.
     * Instagram media objects always have a "pk" or "id" field that is the media ID.
     */
    private static String extractMediaId(Object obj) {
        if (obj == null) return null;
        String pkg = obj.getClass().getName();
        // Only look at Instagram model objects
        if (!pkg.startsWith("com.instagram") && !pkg.startsWith("X.")) return null;

        // Try common field names for media ID
        for (String fieldName : new String[]{"pk", "mPk", "mId", "mediaId", "id", "mMediaId"}) {
            try {
                java.lang.reflect.Field f = findField(obj.getClass(), fieldName);
                if (f == null) continue;
                f.setAccessible(true);
                Object val = f.get(obj);
                if (val == null) continue;
                String s = val.toString().trim();
                // Media IDs are long numeric strings (10-20 digits)
                if (s.matches("\\d{9,20}")) return s;
            } catch (Throwable ignored) {}
        }

        // Try getter methods
        for (String methodName : new String[]{"getPk", "getMediaId", "getId", "getPkAsString"}) {
            try {
                Method m = findMethod(obj.getClass(), methodName);
                if (m == null) continue;
                m.setAccessible(true);
                Object val = m.invoke(obj);
                if (val == null) continue;
                String s = val.toString().trim();
                if (s.matches("\\d{9,20}")) return s;
            } catch (Throwable ignored) {}
        }
        return null;
    }

    /**
     * Gets the media_type integer from the model object.
     */
    private static int getMediaType(Object obj) {
        if (obj == null) return TYPE_IMAGE;
        for (String fieldName : new String[]{"mediaType", "mMediaType", "media_type", "type"}) {
            try {
                java.lang.reflect.Field f = findField(obj.getClass(), fieldName);
                if (f == null) continue;
                f.setAccessible(true);
                Object val = f.get(obj);
                if (val instanceof Integer) return (Integer) val;
            } catch (Throwable ignored) {}
        }
        for (String methodName : new String[]{"getMediaType", "getType"}) {
            try {
                Method m = findMethod(obj.getClass(), methodName);
                if (m == null) continue;
                m.setAccessible(true);
                Object val = m.invoke(obj);
                if (val instanceof Integer) return (Integer) val;
            } catch (Throwable ignored) {}
        }
        return TYPE_IMAGE;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Bottom sheet finder
    // ─────────────────────────────────────────────────────────────────────────

    private static ViewGroup findBottomSheet(ViewGroup root) {
        java.util.Queue<ViewGroup> q = new java.util.LinkedList<>();
        q.add(root);
        while (!q.isEmpty()) {
            ViewGroup vg = q.poll();
            if (isSheetCandidate(vg)) return vg;
            for (int i = 0; i < vg.getChildCount(); i++) {
                View c = vg.getChildAt(i);
                if (c instanceof ViewGroup) q.add((ViewGroup) c);
            }
        }
        return null;
    }

    private static boolean isSheetCandidate(ViewGroup vg) {
        if (!(vg instanceof LinearLayout)) return false;
        if (((LinearLayout) vg).getOrientation() != LinearLayout.VERTICAL) return false;
        if (vg.getChildCount() < 2) return false;
        int textCount = 0;
        for (int i = 0; i < Math.min(vg.getChildCount(), 6); i++) {
            if (containsText(vg.getChildAt(i))) textCount++;
        }
        return textCount >= 2;
    }

    private static boolean containsText(View v) {
        if (v instanceof TextView) return true;
        if (v instanceof ViewGroup) {
            ViewGroup vg = (ViewGroup) v;
            for (int i = 0; i < vg.getChildCount(); i++)
                if (containsText(vg.getChildAt(i))) return true;
        }
        return false;
    }

    private static void dismissSheet(Activity a) {
        try { a.onBackPressed(); } catch (Throwable ignored) {}
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Helpers
    // ─────────────────────────────────────────────────────────────────────────

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
            for (Method m : cls.getDeclaredMethods()) {
                if (m.getName().equals(name) && m.getParameterCount() == 0) return m;
            }
            cls = cls.getSuperclass();
        }
        return null;
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
    // UI row builder
    // ─────────────────────────────────────────────────────────────────────────

    private static LinearLayout buildRow(Activity ctx, String tag,
                                          String label, View.OnClickListener onClick) {
        LinearLayout row = new LinearLayout(ctx);
        row.setTag(tag);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        int h = dp(ctx, 16);
        row.setPadding(h, dp(ctx, 14), h, dp(ctx, 14));

        StateListDrawable bg = new StateListDrawable();
        bg.addState(new int[]{android.R.attr.state_pressed},
                new ColorDrawable(Color.parseColor("#40FFFFFF")));
        bg.addState(new int[]{}, new ColorDrawable(Color.TRANSPARENT));
        row.setBackground(bg);

        ImageView icon = new ImageView(ctx);
        icon.setImageDrawable(makeIcon(ctx));
        int sz = dp(ctx, 24);
        LinearLayout.LayoutParams iconLp = new LinearLayout.LayoutParams(sz, sz);
        iconLp.setMarginEnd(dp(ctx, 16));
        icon.setLayoutParams(iconLp);
        row.addView(icon);

        TextView tv = new TextView(ctx);
        tv.setText(label);
        tv.setTextColor(Color.WHITE);
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
        p.setColor(Color.WHITE);
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