package com.sridhar.glasshome;

import android.content.Context;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewOutlineProvider;
import android.view.animation.DecelerateInterpolator;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.TextView;

/** A banner tile with its name underneath, used both in the dock and in the grid. */
final class Tile {
    static final float FOCUS_SCALE = 1.08f;

    final FrameLayout root;
    final FrameLayout card;
    private final ImageView image;
    private final ImageView icon;
    private final TextView fallbackLabel;
    private final TextView label;
    private boolean focused;

    /** Root is cellW x cellH; the card sits centred horizontally, cardTop from the top. */
    Tile(Context c, Metrics m, Typeface labelFace) {
        root = new FrameLayout(c);
        root.setClipChildren(false);
        root.setLayoutParams(new FrameLayout.LayoutParams(m.cellW, m.cellH));

        card = new FrameLayout(c);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0xFF1C1C1E);
        bg.setCornerRadius(m.tileRadius);
        card.setBackground(bg);
        card.setOutlineProvider(ViewOutlineProvider.BACKGROUND);
        card.setClipToOutline(true);
        FrameLayout.LayoutParams clp = new FrameLayout.LayoutParams(m.tileW, m.tileH);
        clp.leftMargin = (m.cellW - m.tileW) / 2;
        clp.topMargin = m.cardTop;
        root.addView(card, clp);

        image = new ImageView(c);
        image.setScaleType(ImageView.ScaleType.CENTER_CROP);
        card.addView(image, new FrameLayout.LayoutParams(-1, -1));

        icon = new ImageView(c);
        FrameLayout.LayoutParams ilp = new FrameLayout.LayoutParams(m.dp(46), m.dp(46), Gravity.CENTER);
        ilp.bottomMargin = m.dp(14);
        card.addView(icon, ilp);

        fallbackLabel = new TextView(c);
        fallbackLabel.setTypeface(labelFace);
        fallbackLabel.setTextSize(12);
        fallbackLabel.setTextColor(0xFFB8B8BD);
        fallbackLabel.setSingleLine();
        fallbackLabel.setEllipsize(TextUtils.TruncateAt.END);
        fallbackLabel.setGravity(Gravity.CENTER);
        FrameLayout.LayoutParams flp = new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM);
        flp.bottomMargin = m.dp(9);
        flp.leftMargin = flp.rightMargin = m.dp(8);
        card.addView(fallbackLabel, flp);

        label = new TextView(c);
        label.setTypeface(labelFace);
        label.setTextSize(15);
        label.setTextColor(0xFFF2F2F7);
        label.setSingleLine();
        label.setEllipsize(TextUtils.TruncateAt.END);
        label.setGravity(Gravity.CENTER_HORIZONTAL);
        label.setAlpha(0f);
        FrameLayout.LayoutParams llp = new FrameLayout.LayoutParams(m.cellW - m.dp(8), -2, Gravity.CENTER_HORIZONTAL);
        llp.topMargin = m.cardTop + m.tileH + m.dp(11);
        root.addView(label, llp);
    }

    void bind(AppEntry e) {
        label.setText(e.label);
        if (e.banner != null) {
            image.setImageDrawable(e.banner);
            image.setVisibility(View.VISIBLE);
            icon.setVisibility(View.GONE);
            fallbackLabel.setVisibility(View.GONE);
        } else {
            image.setImageDrawable(null);
            image.setVisibility(View.GONE);
            icon.setImageDrawable(e.icon);
            icon.setVisibility(View.VISIBLE);
            fallbackLabel.setText(e.label);
            fallbackLabel.setVisibility(View.VISIBLE);
        }
    }

    void setFocused(boolean f, boolean animate) {
        if (f == focused && animate) return;
        focused = f;
        float s = f ? FOCUS_SCALE : 1f;
        float a = f ? 1f : 0f;
        card.animate().cancel();
        label.animate().cancel();
        if (animate) {
            DecelerateInterpolator di = new DecelerateInterpolator(2f);
            card.animate().scaleX(s).scaleY(s).setDuration(200).setInterpolator(di).start();
            label.animate().alpha(a).setDuration(f ? 220 : 120).setInterpolator(di).start();
        } else {
            card.setScaleX(s);
            card.setScaleY(s);
            label.setAlpha(a);
        }
    }

    /** Shared sizes, all in px. */
    static final class Metrics {
        final float density;
        final int cellW, cellH, tileW, tileH, cardTop, tileRadius;

        Metrics(float density, int cellWdp) {
            this.density = density;
            cellW = dp(cellWdp);
            tileW = dp(188);
            tileH = dp(106);
            cardTop = dp(12);
            cellH = dp(158);
            tileRadius = dp(14);
        }

        int dp(float v) { return Math.round(v * density); }
    }
}
