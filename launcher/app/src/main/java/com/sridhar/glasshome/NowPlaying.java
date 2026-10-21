package com.sridhar.glasshome;

import android.content.ComponentName;
import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.media.MediaMetadata;
import android.media.session.MediaController;
import android.media.session.MediaSessionManager;
import android.media.session.PlaybackState;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewOutlineProvider;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.List;

/**
 * "Now Playing" glass card: art, title, artist and prev / play-pause / next for whichever app is
 * playing (Spotify, YouTube Music, ...). Driven by media-session callbacks, never polled.
 * Needs notification-listener access for {@link MediaListener}; without it the card stays hidden.
 */
final class NowPlaying {
    static final int PREV = 0, PLAY = 1, NEXT = 2;

    final FrameLayout card;
    final ImageView[] buttons = new ImageView[3];
    private final Context ctx;
    private final float density;
    private final ImageView art;
    private final TextView source, title, artist;
    private final Runnable onVisibilityChanged;
    private final Handler handler = new Handler(Looper.getMainLooper());

    private MediaSessionManager msm;
    private MediaController controller;
    private boolean shown, listening;

    NowPlaying(Context c, Typeface regular, Typeface medium, Runnable onVisibilityChanged) {
        ctx = c;
        density = c.getResources().getDisplayMetrics().density;
        this.onVisibilityChanged = onVisibilityChanged;

        card = new FrameLayout(c);
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(dp(22));
        bg.setColor(0xD9141416);
        bg.setStroke(Math.max(1, dp(1)), 0x24FFFFFF);
        card.setBackground(bg);
        card.setVisibility(View.GONE);

        art = new ImageView(c);
        art.setScaleType(ImageView.ScaleType.CENTER_CROP);
        GradientDrawable artBg = new GradientDrawable();
        artBg.setCornerRadius(dp(12));
        artBg.setColor(0xFF242428);
        art.setBackground(artBg);
        art.setOutlineProvider(ViewOutlineProvider.BACKGROUND);
        art.setClipToOutline(true);
        card.addView(art, at(dp(94), dp(94), 16, 16));

        source = text(regular, 12, 0xFF8E8E93);
        card.addView(source, at(dp(220), -2, 126, 16));
        title = text(medium, 17, 0xFFFFFFFF);
        card.addView(title, at(dp(220), -2, 126, 34));
        artist = text(regular, 14, 0xFFA1A1A6);
        card.addView(artist, at(dp(220), -2, 126, 58));

        LinearLayout row = new LinearLayout(c);
        row.setOrientation(LinearLayout.HORIZONTAL);
        int[] icons = {R.drawable.ic_prev, R.drawable.ic_play, R.drawable.ic_next};
        for (int i = 0; i < 3; i++) {
            ImageView b = new ImageView(c);
            b.setImageResource(icons[i]);
            b.setScaleType(ImageView.ScaleType.CENTER);
            GradientDrawable bb = new GradientDrawable();
            bb.setShape(GradientDrawable.OVAL);
            bb.setColor(0x1FFFFFFF);
            b.setBackground(bb);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(30), dp(30));
            if (i > 0) lp.leftMargin = dp(12);
            row.addView(b, lp);
            buttons[i] = b;
        }
        card.addView(row, at(-2, dp(30), 126, 80));
    }

    private int dp(float v) { return Math.round(v * density); }

    private FrameLayout.LayoutParams at(int w, int h, int x, int y) {
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(w, h);
        lp.leftMargin = dp(x);
        lp.topMargin = dp(y);
        return lp;
    }

    private TextView text(Typeface face, float sp, int color) {
        TextView t = new TextView(ctx);
        t.setTypeface(face);
        t.setTextSize(sp);
        t.setTextColor(color);
        t.setIncludeFontPadding(false);
        t.setSingleLine();
        t.setEllipsize(TextUtils.TruncateAt.END);
        return t;
    }

    boolean isShown() { return shown; }

    /** Safe to call repeatedly (e.g. every onResume): access may be granted after we start. */
    void start() {
        if (listening) return;
        msm = (MediaSessionManager) ctx.getSystemService(Context.MEDIA_SESSION_SERVICE);
        if (msm == null) return;
        ComponentName listener = new ComponentName(ctx, MediaListener.class);
        try {
            msm.addOnActiveSessionsChangedListener(sessionsListener, listener, handler);
            listening = true;
            pick(msm.getActiveSessions(listener));
        } catch (SecurityException e) {
            // Notification access not granted: no card.
            setShown(false);
        }
    }

    void stop() {
        if (msm != null) {
            try { msm.removeOnActiveSessionsChangedListener(sessionsListener); } catch (Exception ignored) { }
        }
        listening = false;
        attach(null);
    }

    void press(int which) {
        if (controller == null) return;
        MediaController.TransportControls t = controller.getTransportControls();
        if (which == PREV) t.skipToPrevious();
        else if (which == NEXT) t.skipToNext();
        else if (isPlaying()) t.pause();
        else t.play();
    }

    private final MediaSessionManager.OnActiveSessionsChangedListener sessionsListener = this::pick;

    /** Prefer a session that is playing; otherwise a paused one that has something to show. */
    private void pick(List<MediaController> sessions) {
        MediaController best = null;
        if (sessions != null) {
            for (MediaController m : sessions) {
                PlaybackState s = m.getPlaybackState();
                if (s != null && s.getState() == PlaybackState.STATE_PLAYING) { best = m; break; }
            }
            if (best == null) {
                for (MediaController m : sessions) {
                    PlaybackState s = m.getPlaybackState();
                    if (s != null && s.getState() == PlaybackState.STATE_PAUSED && m.getMetadata() != null) { best = m; break; }
                }
            }
        }
        attach(best);
    }

    private void attach(MediaController m) {
        if (controller != null && (m == null || !controller.getSessionToken().equals(m.getSessionToken()))) {
            controller.unregisterCallback(callback);
        }
        boolean changed = controller == null || m == null || !controller.getSessionToken().equals(m.getSessionToken());
        controller = m;
        if (m != null && changed) m.registerCallback(callback, handler);
        render();
    }

    private final MediaController.Callback callback = new MediaController.Callback() {
        @Override public void onPlaybackStateChanged(PlaybackState state) {
            if (state != null && (state.getState() == PlaybackState.STATE_STOPPED
                    || state.getState() == PlaybackState.STATE_NONE) && msm != null) {
                // This app stopped; another might still be playing.
                try { pick(msm.getActiveSessions(new ComponentName(ctx, MediaListener.class))); }
                catch (SecurityException e) { attach(null); }
            } else {
                render();
            }
        }
        @Override public void onMetadataChanged(MediaMetadata metadata) { render(); }
        @Override public void onSessionDestroyed() { attach(null); }
    };

    private boolean isPlaying() {
        PlaybackState s = controller != null ? controller.getPlaybackState() : null;
        return s != null && s.getState() == PlaybackState.STATE_PLAYING;
    }

    private void render() {
        MediaMetadata md = controller != null ? controller.getMetadata() : null;
        if (md == null) { setShown(false); return; }
        CharSequence t = md.getText(MediaMetadata.METADATA_KEY_TITLE);
        if (TextUtils.isEmpty(t)) t = md.getText(MediaMetadata.METADATA_KEY_DISPLAY_TITLE);
        if (TextUtils.isEmpty(t)) { setShown(false); return; }
        CharSequence a = md.getText(MediaMetadata.METADATA_KEY_ARTIST);
        if (TextUtils.isEmpty(a)) a = md.getText(MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE);
        if (TextUtils.isEmpty(a)) a = md.getText(MediaMetadata.METADATA_KEY_ALBUM);
        title.setText(t);
        artist.setText(a);

        PackageManager pm = ctx.getPackageManager();
        String pkg = controller.getPackageName();
        CharSequence app = pkg;
        try { app = pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)); } catch (Exception ignored) { }
        source.setText((isPlaying() ? "Now Playing" : "Paused") + "  ·  " + app);

        Bitmap b = md.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART);
        if (b == null) b = md.getBitmap(MediaMetadata.METADATA_KEY_ART);
        if (b == null) b = md.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON);
        if (b != null) {
            // Keep only a small copy: the TV has little RAM and the card is 94dp.
            int size = dp(94);
            art.setImageBitmap(b.getWidth() > size * 2 ? Bitmap.createScaledBitmap(b, size,
                    Math.max(1, size * b.getHeight() / b.getWidth()), true) : b);
        } else {
            try { art.setImageDrawable(pm.getApplicationIcon(pkg)); } catch (Exception e) { art.setImageDrawable(null); }
        }
        buttons[PLAY].setImageResource(isPlaying() ? R.drawable.ic_pause : R.drawable.ic_play);
        setShown(true);
    }

    private void setShown(boolean s) {
        if (s == shown) return;
        shown = s;
        card.animate().cancel();
        if (s) {
            card.setVisibility(View.VISIBLE);
            card.setAlpha(0f);
            card.animate().alpha(1f).setDuration(250).start();
        } else {
            card.setVisibility(View.GONE);
        }
        onVisibilityChanged.run();
    }
}
