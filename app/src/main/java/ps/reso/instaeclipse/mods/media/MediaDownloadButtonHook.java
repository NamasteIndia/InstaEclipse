package ps.reso.instaeclipse.mods.media;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.AndroidAppHelper;
import android.content.ContentValues;
import android.content.Context;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.Toast;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.callbacks.XC_LoadPackage;
import ps.reso.instaeclipse.utils.feature.FeatureFlags;
import ps.reso.instaeclipse.utils.feature.FeatureStatusTracker;
import ps.reso.instaeclipse.utils.media.MediaDownloadUtils;

public class MediaDownloadButtonHook {
    private static final String BUTTON_TAG = "instaeclipse_download_button";
    private static final Set<Integer> observedActivities = Collections.synchronizedSet(new HashSet<>());
    private static volatile String latestMediaUrl;
    private static volatile long lastClickTs;

    public void install(XC_LoadPackage.LoadPackageParam lpparam) {
        try {
            Class<?> tigonClass = lpparam.classLoader.loadClass("com.instagram.api.tigon.TigonServiceLayer");
            XposedBridge.hookAllMethods(tigonClass, "startRequest", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    if (!FeatureFlags.enableMediaDownload) return;
                    Object requestObj = param.args.length > 0 ? param.args[0] : null;
                    if (requestObj == null) return;

                    URI uri = findUriField(requestObj);
                    if (uri == null) return;
                    String requestUrl = uri.toString();
                    if (!MediaDownloadUtils.isSupportedMediaUrl(requestUrl)) return;
                    if (!MediaDownloadUtils.isTrustedInstagramHost(uri.getHost())) return;
                    latestMediaUrl = requestUrl;
                    FeatureStatusTracker.setHooked("MediaDownload");
                }
            });
        } catch (Throwable ignored) {
            XposedBridge.log("(InstaEclipse | MediaDownload): failed to hook network capture");
        }
    }

    @SuppressLint("DiscouragedApi")
    public static void attachButtonIfNeeded(Activity activity) {
        if (activity == null || !FeatureFlags.enableMediaDownload) return;

        int[] rowIds = new int[]{
                activity.getResources().getIdentifier("feed_post_footer_like_button", "id", activity.getPackageName()),
                activity.getResources().getIdentifier("row_feed_button_like", "id", activity.getPackageName()),
                activity.getResources().getIdentifier("row_feed_button_comment", "id", activity.getPackageName()),
                activity.getResources().getIdentifier("row_feed_button_share", "id", activity.getPackageName())
        };

        for (int id : rowIds) {
            if (id == 0) continue;
            View view = activity.findViewById(id);
            if (view == null) continue;
            ViewGroup group = nearestHorizontalContainer(view);
            if (group == null) continue;
            if (group.findViewWithTag(BUTTON_TAG) != null) continue;
            injectDownloadButton(activity, group);
        }
    }

    public static void ensureActivityObserver(Activity activity) {
        if (activity == null || !FeatureFlags.enableMediaDownload) return;
        int key = System.identityHashCode(activity);
        if (!observedActivities.add(key)) return;
        View decorView = activity.getWindow().getDecorView();
        decorView.getViewTreeObserver().addOnGlobalLayoutListener(() -> attachButtonIfNeeded(activity));
    }

    private static ViewGroup nearestHorizontalContainer(View view) {
        View current = view;
        for (int i = 0; i < 5 && current != null; i++) {
            ViewParent vp = current.getParent();
            if (!(vp instanceof View parent)) return null;
            if (parent instanceof LinearLayout linear && linear.getOrientation() == LinearLayout.HORIZONTAL) {
                return linear;
            }
            if (parent instanceof ViewGroup vg && vg.getChildCount() >= 3) {
                return vg;
            }
            current = parent;
        }
        return null;
    }

    private static void injectDownloadButton(Activity activity, ViewGroup group) {
        try {
            ImageButton button = new ImageButton(activity);
            button.setTag(BUTTON_TAG);
            button.setContentDescription("Download media");
            button.setImageResource(android.R.drawable.stat_sys_download_done);
            button.setBackground(null);
            int size = (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 24, activity.getResources().getDisplayMetrics());
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(size, size);
            lp.gravity = Gravity.CENTER_VERTICAL;
            lp.setMarginStart((int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 8, activity.getResources().getDisplayMetrics()));
            button.setLayoutParams(lp);

            button.setOnClickListener(v -> {
                long now = System.currentTimeMillis();
                if (now - lastClickTs < 1000) return;
                lastClickTs = now;

                String url = latestMediaUrl;
                if (!MediaDownloadUtils.isSupportedMediaUrl(url)) {
                    Toast.makeText(activity, "No downloadable media found yet", Toast.LENGTH_SHORT).show();
                    return;
                }
                Toast.makeText(activity, "Downloading media...", Toast.LENGTH_SHORT).show();
                new Thread(() -> downloadToGallery(activity.getApplicationContext(), url)).start();
            });
            group.addView(button);
        } catch (Throwable t) {
            XposedBridge.log("(InstaEclipse | MediaDownload): inject failed " + t.getMessage());
        }
    }

    private static URI findUriField(Object requestObj) {
        try {
            for (java.lang.reflect.Field field : requestObj.getClass().getDeclaredFields()) {
                if (field.getType() == URI.class) {
                    field.setAccessible(true);
                    Object value = field.get(requestObj);
                    if (value instanceof URI uri) return uri;
                }
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    private static void downloadToGallery(Context context, String urlString) {
        if (context == null || urlString == null) return;
        HttpURLConnection connection = null;
        InputStream input = null;
        OutputStream output = null;
        try {
            URL url = new URL(urlString);
            if (!MediaDownloadUtils.isTrustedInstagramHost(url.getHost())) {
                showToast("Download blocked");
                return;
            }
            connection = (HttpURLConnection) url.openConnection();
            connection.setConnectTimeout(10000);
            connection.setReadTimeout(20000);
            connection.setInstanceFollowRedirects(true);
            connection.connect();
            if (connection.getResponseCode() < 200 || connection.getResponseCode() >= 300) {
                showToast("Download failed");
                return;
            }

            input = connection.getInputStream();
            String ext = MediaDownloadUtils.fileExtensionForUrl(urlString);
            String fileName = "InstaEclipse_" + System.currentTimeMillis() + ext;
            String mimeType = ext.equals(".mp4") ? "video/mp4" : "image/*";

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ContentValues values = new ContentValues();
                values.put(MediaStore.MediaColumns.DISPLAY_NAME, fileName);
                values.put(MediaStore.MediaColumns.MIME_TYPE, mimeType);
                values.put(MediaStore.MediaColumns.RELATIVE_PATH, ext.equals(".mp4")
                        ? Environment.DIRECTORY_MOVIES + "/InstaEclipse"
                        : Environment.DIRECTORY_PICTURES + "/InstaEclipse");
                values.put(MediaStore.MediaColumns.IS_PENDING, 1);

                Uri collection = ext.equals(".mp4")
                        ? MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
                        : MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY);
                Uri item = context.getContentResolver().insert(collection, values);
                if (item == null) {
                    showToast("Download failed");
                    return;
                }

                output = context.getContentResolver().openOutputStream(item);
                if (output == null) {
                    showToast("Download failed");
                    return;
                }
                copy(input, output);

                ContentValues done = new ContentValues();
                done.put(MediaStore.MediaColumns.IS_PENDING, 0);
                context.getContentResolver().update(item, done, null, null);
            } else {
                if (!hasLegacyStoragePermission(context)) {
                    showToast("Storage permission required");
                    return;
                }
                File baseDir = ext.equals(".mp4")
                        ? Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES)
                        : Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES);
                File folder = new File(baseDir, "InstaEclipse");
                if (!folder.exists() && !folder.mkdirs()) {
                    showToast("Download failed");
                    return;
                }
                File outFile = new File(folder, fileName);
                output = new FileOutputStream(outFile);
                copy(input, output);
            }
            showToast("Downloaded successfully");
        } catch (Throwable ignored) {
            showToast("Download failed");
        } finally {
            try {
                if (input != null) input.close();
            } catch (Exception ignored) {
            }
            try {
                if (output != null) output.close();
            } catch (Exception ignored) {
            }
            if (connection != null) connection.disconnect();
        }
    }

    private static boolean hasLegacyStoragePermission(Context context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) return true;
        return context.checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED;
    }

    private static void copy(InputStream input, OutputStream output) throws Exception {
        byte[] buffer = new byte[8192];
        int read;
        while ((read = input.read(buffer)) != -1) {
            output.write(buffer, 0, read);
        }
        output.flush();
    }

    private static void showToast(String message) {
        try {
            Context context = AndroidAppHelper.currentApplication().getApplicationContext();
            if (context == null) return;
            android.os.Handler handler = new android.os.Handler(android.os.Looper.getMainLooper());
            handler.post(() -> Toast.makeText(context, message, Toast.LENGTH_SHORT).show());
        } catch (Throwable ignored) {
        }
    }
}
