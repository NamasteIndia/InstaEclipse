package ps.reso.instaeclipse.utils.media;

import java.util.Locale;

public final class MediaDownloadUtils {
    private MediaDownloadUtils() {
    }

    public static boolean isSupportedMediaUrl(String url) {
        if (url == null || url.isEmpty()) return false;
        String lower = url.toLowerCase(Locale.ROOT);
        if (!lower.startsWith("https://")) return false;
        return lower.contains(".jpg")
                || lower.contains(".jpeg")
                || lower.contains(".png")
                || lower.contains(".webp")
                || lower.contains(".mp4");
    }

    public static String fileExtensionForUrl(String url) {
        if (url == null) return ".bin";
        String lower = url.toLowerCase(Locale.ROOT);
        if (lower.contains(".mp4")) return ".mp4";
        if (lower.contains(".jpeg")) return ".jpeg";
        if (lower.contains(".jpg")) return ".jpg";
        if (lower.contains(".png")) return ".png";
        if (lower.contains(".webp")) return ".webp";
        return ".bin";
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
}
