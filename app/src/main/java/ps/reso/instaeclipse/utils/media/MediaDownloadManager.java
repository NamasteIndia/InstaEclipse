package ps.reso.instaeclipse.utils.media;

import android.app.DownloadManager;
import android.content.Context;
import android.net.Uri;
import android.os.Environment;
import android.webkit.MimeTypeMap;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;

import de.robv.android.xposed.XposedBridge;
import ps.reso.instaeclipse.utils.feature.FeatureFlags;

public class MediaDownloadManager {

    private static final Object lock = new Object();
    private static final int MAX_RECENT_MEDIA = 25;
    private static final LinkedHashSet<String> recentMediaUrls = new LinkedHashSet<>();

    private MediaDownloadManager() {
        // Utility class
    }

    public static void captureCandidateUrl(String url) {
        if (!FeatureFlags.enableMediaDownload || url == null || url.isEmpty()) {
            return;
        }

        String lower = url.toLowerCase(Locale.ROOT);
        int score = scoreCandidate(lower);
        if (score <= 0) {
            return;
        }

        synchronized (lock) {
            recentMediaUrls.remove(url);
            recentMediaUrls.add(url);
            while (recentMediaUrls.size() > MAX_RECENT_MEDIA) {
                String first = recentMediaUrls.iterator().next();
                recentMediaUrls.remove(first);
            }
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

        String url = pickBestRecentCandidate();
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

            if (scoreCandidate(url.toLowerCase(Locale.ROOT)) <= 0) {
                Toast.makeText(context, "Captured URL is not a valid media file.", Toast.LENGTH_SHORT).show();
                return false;
            }

            enqueueDownload(dm, context, url);
            Toast.makeText(context, "Download started.", Toast.LENGTH_SHORT).show();
            return true;
        } catch (Exception e) {
            XposedBridge.log("InstaEclipse | Media download failed: " + e.getMessage());
            Toast.makeText(context, "Failed to start download.", Toast.LENGTH_SHORT).show();
            return false;
        }
    }

    public static boolean downloadCurrentMedia(Context context) {
        return downloadLastCapturedMedia(context);
    }

    public static boolean downloadAllRecentMedia(Context context) {
        if (context == null) {
            return false;
        }
        if (!FeatureFlags.enableMediaDownload) {
            Toast.makeText(context, "Enable Media Downloader first.", Toast.LENGTH_SHORT).show();
            return false;
        }

        List<String> toDownload = new ArrayList<>();
        synchronized (lock) {
            toDownload.addAll(recentMediaUrls);
        }

        if (toDownload.isEmpty()) {
            Toast.makeText(context, "No media URLs captured yet.", Toast.LENGTH_SHORT).show();
            return false;
        }

        try {
            DownloadManager dm = (DownloadManager) context.getSystemService(Context.DOWNLOAD_SERVICE);
            if (dm == null) {
                Toast.makeText(context, "DownloadManager not available.", Toast.LENGTH_SHORT).show();
                return false;
            }

            int queued = 0;
            for (String url : toDownload) {
                if (scoreCandidate(url.toLowerCase(Locale.ROOT)) <= 0) continue;
                enqueueDownload(dm, context, url);
                queued++;
            }
            if (queued == 0) {
                Toast.makeText(context, "No valid media URLs to download.", Toast.LENGTH_SHORT).show();
                return false;
            }
            Toast.makeText(context, "Queued " + queued + " media downloads.", Toast.LENGTH_SHORT).show();
            return true;
        } catch (Exception e) {
            XposedBridge.log("InstaEclipse | Batch media download failed: " + e.getMessage());
            Toast.makeText(context, "Failed to queue all downloads.", Toast.LENGTH_SHORT).show();
            return false;
        }
    }

    private static void enqueueDownload(DownloadManager dm, Context context, String url) {
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

    private static int scoreCandidate(String lowerUrl) {
        // Ignore known non-media or adaptive stream artifacts that commonly produce black video/audio-only outputs.
        if (lowerUrl.contains(".m3u8") || lowerUrl.contains(".mpd") || lowerUrl.contains("manifest")
                || lowerUrl.contains("dash") || lowerUrl.contains("audio")
                || lowerUrl.contains("mime=audio") || lowerUrl.contains("type=audio")) {
            return 0;
        }

        // De-prioritize probable video-only adaptive segment paths.
        int score = 0;
        if (lowerUrl.contains("/vp/")) score += 15;
        if (lowerUrl.contains("/v/t")) score += 25;
        if (lowerUrl.contains("bytestart=") || lowerUrl.contains("byteend=")) score -= 20;

        // Strong signals for directly downloadable video/image assets.
        if (lowerUrl.contains(".mp4")) score += 70;
        if (lowerUrl.contains(".jpg") || lowerUrl.contains(".jpeg") || lowerUrl.contains(".png") || lowerUrl.contains(".webp")) return 95;

        // Medium confidence: IG CDN video path hints.
        if ((lowerUrl.contains("cdninstagram") || lowerUrl.contains("fbcdn"))
                && (lowerUrl.contains("/vp/") || lowerUrl.contains("/v/"))) {
            score += 10;
        }

        return Math.max(score, 0);
    }

    private static String pickBestRecentCandidate() {
        synchronized (lock) {
            String bestUrl = null;
            int bestScore = -1;
            for (String candidate : recentMediaUrls) {
                int s = scoreCandidate(candidate.toLowerCase(Locale.ROOT));
                if (s > bestScore) {
                    bestScore = s;
                    bestUrl = candidate;
                }
            }
            return bestUrl;
        }
    }
}
