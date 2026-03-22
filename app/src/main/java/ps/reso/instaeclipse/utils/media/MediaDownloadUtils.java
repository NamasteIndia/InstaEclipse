package ps.reso.instaeclipse.utils.media;

import java.net.URI;
import java.util.Locale;
import java.util.Set;

public final class MediaDownloadUtils {

    private static final Set<String> SUPPORTED_EXTENSIONS =
            Set.of(".jpg", ".jpeg", ".png", ".webp", ".mp4");

    private MediaDownloadUtils() {}

    public static boolean isSupportedMediaUrl(String url) {
        if (url == null || url.isEmpty()) return false;
        try {
            URI uri = URI.create(url);
            if (!"https".equalsIgnoreCase(uri.getScheme())) return false;
            if (!isTrustedInstagramHost(uri.getHost())) return false;
            String ext = extensionOf(uri.getPath());
            return SUPPORTED_EXTENSIONS.contains(ext);
        } catch (Exception e) { return false; }
    }

    public static boolean isVideoUrl(String url) {
        if (url == null) return false;
        return url.toLowerCase(Locale.ROOT).contains(".mp4")
                || url.contains("video_versions");
    }

    public static boolean isImageUrl(String url) {
        if (url == null) return false;
        try {
            URI uri = URI.create(url);
            if (!isTrustedInstagramHost(uri.getHost())) return false;
            String ext = extensionOf(uri.getPath());
            return ext.equals(".jpg") || ext.equals(".jpeg")
                    || ext.equals(".png") || ext.equals(".webp")
                    || uri.getPath().contains("/t51.");
        } catch (Exception e) { return false; }
    }

    public static String fileExtensionForUrl(String url) {
        if (isVideoUrl(url)) return ".mp4";
        return ".jpg";
    }

    public static boolean isTrustedInstagramHost(String host) {
        if (host == null || host.isEmpty()) return false;
        String lower = host.toLowerCase(Locale.ROOT);
        return lower.equals("instagram.com")
                || lower.endsWith(".instagram.com")
                || lower.equals("cdninstagram.com")
                || lower.endsWith(".cdninstagram.com")
                || lower.equals("fbcdn.net")
                || lower.endsWith(".fbcdn.net")
                || lower.equals("i.instagram.com");
    }

    private static String extensionOf(String path) {
        if (path == null) return "";
        int q = path.indexOf('?');
        if (q >= 0) path = path.substring(0, q);
        int dot = path.lastIndexOf('.');
        int slash = path.lastIndexOf('/');
        if (dot < 0 || dot < slash || dot == path.length() - 1) return "";
        return path.substring(dot).toLowerCase(Locale.ROOT);
    }
}