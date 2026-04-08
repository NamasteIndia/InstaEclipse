package ps.reso.instaeclipse.utils.media;

import android.app.DownloadManager;
import android.content.Context;
import android.net.Uri;
import android.os.Environment;
import android.webkit.MimeTypeMap;
import android.widget.Toast;

import java.util.Locale;
import java.util.concurrent.atomic.AtomicReference;

import de.robv.android.xposed.XposedBridge;
import ps.reso.instaeclipse.utils.feature.FeatureFlags;

public class MediaDownloadManager {

    private static final AtomicReference<String> lastCapturedMediaUrl = new AtomicReference<>();

    private MediaDownloadManager() {
        // Utility class
    }

    public static void captureCandidateUrl(String url) {
        if (!FeatureFlags.enableMediaDownload || url == null || url.isEmpty()) {
            return;
        }

        String lower = url.toLowerCase(Locale.ROOT);
        boolean looksLikeMedia = (lower.contains(".mp4") || lower.contains(".jpg")
                || lower.contains(".jpeg") || lower.contains(".png") || lower.contains(".webp"))
                || (lower.contains("cdninstagram") && (lower.contains("/vp/") || lower.contains("/v/")));

        if (looksLikeMedia) {
            lastCapturedMediaUrl.set(url);
        }
    }

    public static boolean downloadLastCapturedMedia(Context context) {
        if (context == null) {
            return false;
        }

        if (!FeatureFlags.enableMediaDownload) {
            Toast.makeText(context, "Enable Media Downloader first.", Toast.LENGTH_SHORT).show();
            return false;
        }

        String url = lastCapturedMediaUrl.get();
        if (url == null || url.isEmpty()) {
            Toast.makeText(context, "No media URL captured yet.", Toast.LENGTH_SHORT).show();
            return false;
        }

        try {
            DownloadManager dm = (DownloadManager) context.getSystemService(Context.DOWNLOAD_SERVICE);
            if (dm == null) {
                Toast.makeText(context, "DownloadManager not available.", Toast.LENGTH_SHORT).show();
                return false;
            }

            Uri uri = Uri.parse(url);
            String fileName = buildFileName(url);

            DownloadManager.Request req = new DownloadManager.Request(uri)
                    .setTitle("InstaEclipse Media")
                    .setDescription(fileName)
                    .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                    .setAllowedOverMetered(true)
                    .setAllowedOverRoaming(true);

            try {
                req.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, fileName);
            } catch (Throwable ignored) {
                req.setDestinationInExternalFilesDir(context, Environment.DIRECTORY_DOWNLOADS, fileName);
            }

            dm.enqueue(req);
            Toast.makeText(context, "Download started: " + fileName, Toast.LENGTH_SHORT).show();
            return true;
        } catch (Exception e) {
            XposedBridge.log("InstaEclipse | Media download failed: " + e.getMessage());
            Toast.makeText(context, "Failed to start download.", Toast.LENGTH_SHORT).show();
            return false;
        }
    }

    private static String buildFileName(String url) {
        String lower = url.toLowerCase(Locale.ROOT);
        String ext = "bin";
        if (lower.contains(".mp4")) ext = "mp4";
        else if (lower.contains(".jpg") || lower.contains(".jpeg")) ext = "jpg";
        else if (lower.contains(".png")) ext = "png";
        else if (lower.contains(".webp")) ext = "webp";
        else {
            String maybe = MimeTypeMap.getFileExtensionFromUrl(url);
            if (maybe != null && !maybe.isEmpty()) {
                ext = maybe;
            }
        }
        return "instaeclipse_" + System.currentTimeMillis() + "." + ext;
    }
}
