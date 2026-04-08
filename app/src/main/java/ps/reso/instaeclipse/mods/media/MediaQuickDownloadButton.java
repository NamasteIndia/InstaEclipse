package ps.reso.instaeclipse.mods.media;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Color;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.Toast;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import ps.reso.instaeclipse.utils.feature.FeatureFlags;
import ps.reso.instaeclipse.utils.media.MediaDownloadManager;

public class MediaQuickDownloadButton {

    private static final String BTN_TAG = "ie_media_download_btn";

    public void install() {
        try {
            XposedHelpers.findAndHookMethod(View.class, "onAttachedToWindow", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    if (!FeatureFlags.enableMediaDownload) return;

                    View view = (View) param.thisObject;
                    if (!(view.getParent() instanceof ViewGroup parent)) return;
                    if (!looksLikeMediaContainer(view)) return;

                    injectButton(parent);
                }
            });
        } catch (Throwable t) {
            XposedBridge.log("(InstaEclipse | Downloader): failed to install media button hook: " + t.getMessage());
        }
    }

    private boolean looksLikeMediaContainer(View view) {
        Context context = view.getContext();
        String className = view.getClass().getName().toLowerCase();
        if (className.contains("reel") || className.contains("video") || className.contains("image")
                || className.contains("media") || className.contains("carousel")) {
            return true;
        }

        int id = view.getId();
        if (id == View.NO_ID || context == null) return false;

        try {
            @SuppressLint("DiscouragedApi")
            String entryName = context.getResources().getResourceEntryName(id).toLowerCase();
            return entryName.contains("reel")
                    || entryName.contains("video")
                    || entryName.contains("image")
                    || entryName.contains("media")
                    || entryName.contains("carousel")
                    || entryName.contains("player");
        } catch (Throwable ignored) {
            return false;
        }
    }

    private void injectButton(ViewGroup parent) {
        if (parent.findViewWithTag(BTN_TAG) != null) return;
        Context ctx = parent.getContext();
        if (ctx == null) return;

        ImageButton btn = new ImageButton(ctx);
        btn.setTag(BTN_TAG);
        btn.setImageResource(android.R.drawable.stat_sys_download_done);
        btn.setColorFilter(Color.WHITE);
        btn.setBackgroundColor(Color.parseColor("#55000000"));

        int size = dp(ctx, 34);
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(size, size);
        lp.gravity = Gravity.END | Gravity.TOP;
        lp.topMargin = dp(ctx, 10);
        lp.rightMargin = dp(ctx, 10);
        btn.setLayoutParams(lp);

        btn.setOnClickListener(v -> {
            boolean ok = MediaDownloadManager.downloadCurrentMedia(ctx);
            if (!ok) {
                Toast.makeText(ctx, "No current media detected yet.", Toast.LENGTH_SHORT).show();
            }
        });
        btn.setOnLongClickListener(v -> {
            MediaDownloadManager.downloadAllRecentMedia(ctx);
            return true;
        });

        parent.post(() -> {
            try {
                parent.addView(btn);
                btn.bringToFront();
            } catch (Throwable ignored) {
            }
        });
    }

    private int dp(Context ctx, int value) {
        return (int) (value * ctx.getResources().getDisplayMetrics().density);
    }
}
