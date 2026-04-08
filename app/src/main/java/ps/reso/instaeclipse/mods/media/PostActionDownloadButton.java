package ps.reso.instaeclipse.mods.media;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Color;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageButton;
import android.widget.LinearLayout;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import ps.reso.instaeclipse.utils.feature.FeatureFlags;
import ps.reso.instaeclipse.utils.media.MediaDownloadManager;

public class PostActionDownloadButton {

    private static final String BTN_TAG = "ie_post_download_btn";

    public void install() {
        try {
            XposedHelpers.findAndHookMethod(View.class, "onAttachedToWindow", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    if (!FeatureFlags.enableMediaDownload) return;

                    View view = (View) param.thisObject;
                    Context ctx = view.getContext();
                    if (ctx == null) return;

                    if (!isPostActionButton(view, ctx)) return;
                    if (!(view.getParent() instanceof ViewGroup actionBar)) return;
                    if (!looksLikeActionBarContainer(actionBar, ctx)) return;

                    injectDownloadButton(actionBar, ctx);
                }
            });
        } catch (Throwable t) {
            XposedBridge.log("(InstaEclipse | Downloader): post action hook failed: " + t.getMessage());
        }
    }

    private boolean isPostActionButton(View view, Context ctx) {
        @SuppressLint("DiscouragedApi") int likeId = ctx.getResources().getIdentifier("row_feed_button_like", "id", ctx.getPackageName());
        @SuppressLint("DiscouragedApi") int commentId = ctx.getResources().getIdentifier("row_feed_button_comment", "id", ctx.getPackageName());
        @SuppressLint("DiscouragedApi") int shareId = ctx.getResources().getIdentifier("row_feed_button_share", "id", ctx.getPackageName());
        @SuppressLint("DiscouragedApi") int sendId = ctx.getResources().getIdentifier("row_feed_button_send", "id", ctx.getPackageName());

        int id = view.getId();
        if (id != View.NO_ID && (id == likeId || id == commentId || id == shareId || id == sendId)) {
            return true;
        }

        CharSequence desc = view.getContentDescription();
        if (desc != null) {
            String d = desc.toString().toLowerCase();
            return d.contains("like") || d.contains("comment") || d.contains("share") || d.contains("send");
        }
        return false;
    }

    private boolean looksLikeActionBarContainer(ViewGroup container, Context ctx) {
        @SuppressLint("DiscouragedApi") int likeId = ctx.getResources().getIdentifier("row_feed_button_like", "id", ctx.getPackageName());
        @SuppressLint("DiscouragedApi") int commentId = ctx.getResources().getIdentifier("row_feed_button_comment", "id", ctx.getPackageName());
        @SuppressLint("DiscouragedApi") int shareId = ctx.getResources().getIdentifier("row_feed_button_share", "id", ctx.getPackageName());
        @SuppressLint("DiscouragedApi") int sendId = ctx.getResources().getIdentifier("row_feed_button_send", "id", ctx.getPackageName());

        int matchCount = 0;
        if (likeId != 0 && container.findViewById(likeId) != null) matchCount++;
        if (commentId != 0 && container.findViewById(commentId) != null) matchCount++;
        if (shareId != 0 && container.findViewById(shareId) != null) matchCount++;
        if (sendId != 0 && container.findViewById(sendId) != null) matchCount++;
        if (matchCount >= 2) return true;

        // Fallback for obfuscated builds/localized labels.
        int clickableImageButtons = 0;
        int clueCount = 0;
        for (int i = 0; i < container.getChildCount(); i++) {
            View child = container.getChildAt(i);
            if (child instanceof ImageButton && child.isClickable()) clickableImageButtons++;
            CharSequence desc = child.getContentDescription();
            if (desc != null) {
                String d = desc.toString().toLowerCase();
                if (d.contains("like") || d.contains("comment") || d.contains("share") || d.contains("send")) {
                    clueCount++;
                }
            }
        }
        return clickableImageButtons >= 3 || clueCount >= 2;
    }

    private void injectDownloadButton(ViewGroup actionBar, Context ctx) {
        if (actionBar.findViewWithTag(BTN_TAG) != null) return;

        ImageButton btn = new ImageButton(ctx);
        btn.setTag(BTN_TAG);
        btn.setImageResource(android.R.drawable.stat_sys_download_done);
        btn.setColorFilter(Color.WHITE);
        btn.setBackground(null);
        btn.setPadding(dp(ctx, 6), dp(ctx, 6), dp(ctx, 6), dp(ctx, 6));

        ViewGroup.LayoutParams existing = actionBar.getLayoutParams();
        if (existing instanceof LinearLayout.LayoutParams) {
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(ctx, 34), dp(ctx, 34));
            lp.setMarginStart(dp(ctx, 6));
            btn.setLayoutParams(lp);
        } else {
            ViewGroup.MarginLayoutParams lp = new ViewGroup.MarginLayoutParams(dp(ctx, 34), dp(ctx, 34));
            lp.setMarginStart(dp(ctx, 6));
            btn.setLayoutParams(lp);
        }

        btn.setOnClickListener(v -> MediaDownloadManager.downloadCurrentMedia(ctx));
        btn.setOnLongClickListener(v -> {
            MediaDownloadManager.downloadAllRecentMedia(ctx);
            return true;
        });

        actionBar.post(() -> {
            try {
                actionBar.addView(btn, Math.min(3, actionBar.getChildCount()));
                btn.bringToFront();
            } catch (Throwable ignored) {
            }
        });
    }

    private int dp(Context ctx, int value) {
        return (int) (value * ctx.getResources().getDisplayMetrics().density);
    }
}
