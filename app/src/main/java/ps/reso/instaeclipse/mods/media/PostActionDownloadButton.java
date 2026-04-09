package ps.reso.instaeclipse.mods.media;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Context;
import android.graphics.Color;
import android.util.TypedValue;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageButton;
import android.widget.LinearLayout;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import ps.reso.instaeclipse.utils.media.MediaDownloadManager;

public class PostActionDownloadButton {

    private static final String BTN_TAG = "ie_post_download_btn";
    private static int rowFeedMediaActionsId = -1;
    private static int clipsLikeId = -1;
    private static int clipsCommentId = -1;
    private static int clipsShareId = -1;

    public void install() {
        try {
            XposedHelpers.findAndHookMethod(View.class, "onAttachedToWindow", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    View view = (View) param.thisObject;
                    Context ctx = view.getContext();
                    if (ctx == null) return;
                    ensureKnownIds(ctx);

                    if (view.getId() == rowFeedMediaActionsId && view instanceof ViewGroup directRow) {
                        injectDownloadButton(directRow, ctx);
                        return;
                    }
                    if ((view.getId() == clipsLikeId || view.getId() == clipsCommentId || view.getId() == clipsShareId)
                            && view.getParent() instanceof ViewGroup clipsRow) {
                        injectDownloadButton(clipsRow, ctx);
                        return;
                    }

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

    public static void scanAndInject(Activity activity) {
        if (activity == null) return;
        PostActionDownloadButton helper = new PostActionDownloadButton();
        helper.ensureKnownIds(activity);
        helper.injectKnownContainers(activity);
    }

    private boolean isPostActionButton(View view, Context ctx) {
        ensureKnownIds(ctx);
        @SuppressLint("DiscouragedApi") int likeId = ctx.getResources().getIdentifier("row_feed_button_like", "id", ctx.getPackageName());
        @SuppressLint("DiscouragedApi") int commentId = ctx.getResources().getIdentifier("row_feed_button_comment", "id", ctx.getPackageName());
        @SuppressLint("DiscouragedApi") int shareId = ctx.getResources().getIdentifier("row_feed_button_share", "id", ctx.getPackageName());
        @SuppressLint("DiscouragedApi") int sendId = ctx.getResources().getIdentifier("row_feed_button_send", "id", ctx.getPackageName());

        int id = view.getId();
        if (id != View.NO_ID && (id == likeId || id == commentId || id == shareId || id == sendId
                || id == clipsLikeId || id == clipsCommentId || id == clipsShareId)) {
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
        ensureKnownIds(ctx);
        @SuppressLint("DiscouragedApi") int likeId = ctx.getResources().getIdentifier("row_feed_button_like", "id", ctx.getPackageName());
        @SuppressLint("DiscouragedApi") int commentId = ctx.getResources().getIdentifier("row_feed_button_comment", "id", ctx.getPackageName());
        @SuppressLint("DiscouragedApi") int shareId = ctx.getResources().getIdentifier("row_feed_button_share", "id", ctx.getPackageName());
        @SuppressLint("DiscouragedApi") int sendId = ctx.getResources().getIdentifier("row_feed_button_send", "id", ctx.getPackageName());

        if (container.getId() == rowFeedMediaActionsId) return true;

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
        btn.setColorFilter(actionBarIconTint(ctx));
        btn.setContentDescription("Download");
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

    /** Match feed action icons (visible on both light and dark Instagram themes). */
    private int actionBarIconTint(Context ctx) {
        TypedValue tv = new TypedValue();
        if (ctx.getTheme().resolveAttribute(android.R.attr.textColorPrimary, tv, true)) {
            return tv.data;
        }
        return Color.WHITE;
    }

    private void injectKnownContainers(Activity activity) {
        tryInjectById(activity, rowFeedMediaActionsId, false);
        tryInjectById(activity, clipsLikeId, true);
        tryInjectById(activity, clipsCommentId, true);
        tryInjectById(activity, clipsShareId, true);
    }

    private void tryInjectById(Activity activity, int id, boolean useParent) {
        if (id <= 0) return;
        try {
            View v = activity.findViewById(id);
            if (v == null) return;
            if (useParent && v.getParent() instanceof ViewGroup parent) {
                injectDownloadButton(parent, activity);
            } else if (v instanceof ViewGroup group) {
                injectDownloadButton(group, activity);
            }
        } catch (Throwable ignored) {
        }
    }

    private void ensureKnownIds(Context ctx) {
        if (rowFeedMediaActionsId == -1) {
            @SuppressLint("DiscouragedApi")
            int id = ctx.getResources().getIdentifier("row_feed_media_actions", "id", ctx.getPackageName());
            rowFeedMediaActionsId = id;
        }
        if (clipsLikeId == -1) {
            @SuppressLint("DiscouragedApi")
            int id = ctx.getResources().getIdentifier("clips_ufi_like_button", "id", ctx.getPackageName());
            clipsLikeId = id;
        }
        if (clipsCommentId == -1) {
            @SuppressLint("DiscouragedApi")
            int id = ctx.getResources().getIdentifier("clips_ufi_comments_button", "id", ctx.getPackageName());
            clipsCommentId = id;
        }
        if (clipsShareId == -1) {
            @SuppressLint("DiscouragedApi")
            int id = ctx.getResources().getIdentifier("clips_ufi_share_button", "id", ctx.getPackageName());
            clipsShareId = id;
        }
    }
}
