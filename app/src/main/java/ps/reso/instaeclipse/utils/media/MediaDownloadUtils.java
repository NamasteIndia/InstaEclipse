package ps.reso.instaeclipse.utils.media;

import java.net.URI;
import java.util.Locale;
import java.util.Set;

public final class MediaDownloadUtils {
    private static final Set<String> SUPPORTED_EXTENSIONS =
            Set.of(".jpg", ".jpeg", ".png", ".webp", ".mp4");

    private MediaDownloadUtils() {}

    /**
     * Returns true for any CDN URL that looks like Instagram media.
     * Instagram CDN URLs often have no clean file extension in the path —
     * they use query strings like ?efg=... or path segments like /v/t51.xxx/
     * So we accept any trusted-host HTTPS URL here and determine type later.
     */
    public static boolean isSupportedMediaUrl(String url) {
        if (url == null || url.isEmpty()) return false;
        try {
            URI uri = URI.create(url);
            if (!"https".equalsIgnoreCase(uri.getScheme())) return false;
            if (!isTrustedInstagramHost(uri.getHost())) return false;
            // Accept if it has a known extension OR looks like a CDN media path
            String ext = fileExtensionForPath(uri.getPath());
            if (SUPPORTED_EXTENSIONS.contains(ext)) return true;
            // Instagram video/image CDN paths contain these patterns
            String path = uri.getPath().toLowerCase(Locale.ROOT);
            return path.contains("/v/t") || path.contains("/v/f") // video CDN
                    || path.contains("/s") && path.contains("x")  // image CDN scaled
                    || path.contains("/e")                         // encoded media
                    || (uri.getQuery() != null && uri.getQuery().contains("efg=")); // CDN token
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Determines file extension. For CDN URLs without a clean extension,
     * sniff by path patterns: video paths contain /v/t, images contain /s or /e.
     */
    public static String fileExtensionForUrl(String url) {
        if (url == null || url.isEmpty()) return ".jpg";
        try {
            URI uri = URI.create(url);
            String ext = fileExtensionForPath(uri.getPath());
            if (SUPPORTED_EXTENSIONS.contains(ext)) return ext;
            // Sniff from CDN path
            String path = uri.getPath().toLowerCase(Locale.ROOT);
            if (path.contains("/v/t") || path.contains("/v/f")) return ".mp4";
            return ".jpg"; // default to image
        } catch (Exception e) {
            return ".jpg";
        }
    }

    public static boolean isTrustedInstagramHost(String host) {
        if (host == null || host.isEmpty()) return false;
        String lower = host.toLowerCase(Locale.ROOT);
        return lower.equals("instagram.com")
                || lower.endsWith(".instagram.com")
                || lower.equals("cdninstagram.com")
                || lower.endsWith(".cdninstagram.com")
                || lower.equals("fbcdn.net")
                || lower.endsWith(".fbcdn.net");
    }

    public static boolean isVideoUrl(String url) {
        if (url == null) return false;
        String lower = url.toLowerCase(Locale.ROOT);
        return lower.contains(".mp4")
                || lower.contains("/v/t")
                || lower.contains("/v/f")
                || lower.contains("video");
    }

    private static String fileExtensionForPath(String path) {
        if (path == null || path.isEmpty()) return "";
        String lowerPath = path.toLowerCase(Locale.ROOT);
        int lastSlash = lowerPath.lastIndexOf('/');
        String fileName = lastSlash >= 0 ? lowerPath.substring(lastSlash + 1) : lowerPath;
        // Strip query params from filename
        int q = fileName.indexOf('?');
        if (q >= 0) fileName = fileName.substring(0, q);
        int dot = fileName.lastIndexOf('.');
        if (dot < 0 || dot == fileName.length() - 1) return "";
        return fileName.substring(dot);
    }
}