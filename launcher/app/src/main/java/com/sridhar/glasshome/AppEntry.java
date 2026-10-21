package com.sridhar.glasshome;

import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.drawable.Drawable;

/** One launchable app: label, banner (or icon fallback) and launch intent. */
final class AppEntry {
    final String pkg;
    final String label;
    final Drawable banner; // null when the app has no TV banner
    final Drawable icon;
    final Intent launch;
    private int glow; // 0 until sampled

    AppEntry(String pkg, String label, Drawable banner, Drawable icon, Intent launch) {
        this.pkg = pkg;
        this.label = label;
        this.banner = banner;
        this.icon = icon;
        this.launch = launch;
    }

    /** A vivid colour sampled from the banner, for the ambient glow behind the app name. */
    int glowColor() {
        if (glow != 0) return glow;
        Drawable d = banner != null ? banner : icon;
        int best = 0xFF5A6CFF;
        if (d != null) {
            Bitmap b = Bitmap.createBitmap(32, 18, Bitmap.Config.ARGB_8888);
            Canvas c = new Canvas(b);
            // The drawable is shared with the tile's ImageView: put its bounds back afterwards.
            android.graphics.Rect old = new android.graphics.Rect(d.getBounds());
            d.setBounds(0, 0, 32, 18);
            d.draw(c);
            d.setBounds(old);
            float[] hsv = new float[3];
            float bestScore = 0.08f;
            // Average the hue of the most colourful pixels rather than taking a single one.
            double sx = 0, sy = 0, ss = 0, sv = 0, n = 0;
            for (int y = 0; y < 18; y++) {
                for (int x = 0; x < 32; x++) {
                    int p = b.getPixel(x, y);
                    if (Color.alpha(p) < 128) continue;
                    Color.colorToHSV(p, hsv);
                    float score = hsv[1] * hsv[2];
                    if (score < 0.25f) continue;
                    double rad = Math.toRadians(hsv[0]);
                    sx += Math.cos(rad) * score; sy += Math.sin(rad) * score;
                    ss += hsv[1] * score; sv += hsv[2] * score; n += score;
                }
            }
            b.recycle();
            if (n > bestScore) {
                float h = (float) Math.toDegrees(Math.atan2(sy, sx));
                if (h < 0) h += 360f;
                float s = (float) Math.min(0.8, ss / n);
                float v = (float) Math.max(0.85, sv / n);
                best = Color.HSVToColor(new float[]{h, s, v});
            }
        }
        glow = best;
        return glow;
    }
}
