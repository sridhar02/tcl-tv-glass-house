package com.sridhar.glasshome;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Shader;
import android.view.View;

/**
 * The single translucent "glass" focus highlight. It springs (slightly underdamped) from one
 * target rectangle to the next and re-reads the target every frame, so it follows tiles while
 * the page or the grid is scrolling.
 */
public class GlassHighlight extends View {

    /** Supplies the current target rectangle in this view's parent coordinates. */
    public interface Target {
        /** Fill {@code out} and return the corner radius, or return -1 if there is no target. */
        float get(RectF out);
    }

    private static final float STIFFNESS = 420f;
    private static final float DAMPING = 2f * (float) Math.sqrt(STIFFNESS) * 0.74f;

    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint sheen = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final float density;

    // Spring state: x, y, w, h, radius.
    private final float[] pos = new float[5];
    private final float[] vel = new float[5];
    private final float[] goal = new float[5];
    private final RectF tmp = new RectF();
    private final RectF draw = new RectF();

    private Target target;
    private boolean placed;
    private boolean running;
    private long lastFrameNanos;
    private float sheenTop = Float.NaN, sheenHeight = Float.NaN;

    public GlassHighlight(Context c) {
        super(c);
        density = c.getResources().getDisplayMetrics().density;
        fill.setColor(0x1AFFFFFF);
        stroke.setStyle(Paint.Style.STROKE);
        stroke.setStrokeWidth(1.5f * density);
        stroke.setColor(0x73FFFFFF);
        setWillNotDraw(false);
    }

    /** Lifted = an app is being moved: a brighter, thicker glass so it reads as "picked up". */
    public void setLifted(boolean l) {
        fill.setColor(l ? 0x33FFFFFF : 0x1AFFFFFF);
        stroke.setColor(l ? 0xF2FFFFFF : 0x73FFFFFF);
        stroke.setStrokeWidth((l ? 3f : 1.5f) * density);
        invalidate();
    }

    public void setTarget(Target t, boolean jump) {
        target = t;
        // A target that is momentarily not laid out keeps the previous goal.
        if (!readGoal() && !placed) { setVisibility(INVISIBLE); return; }
        setVisibility(VISIBLE);
        if (jump || !placed) {
            System.arraycopy(goal, 0, pos, 0, 5);
            java.util.Arrays.fill(vel, 0);
            placed = true;
        }
        start();
    }

    /** Call when the target may have moved without a focus change (scroll, relayout). */
    public void kick() {
        if (target != null) start();
    }

    private void start() {
        if (running) return;
        running = true;
        lastFrameNanos = 0;
        postOnAnimation(frame);
    }

    private boolean readGoal() {
        if (target == null) return false;
        float r = target.get(tmp);
        if (r < 0) return false;
        goal[0] = tmp.left; goal[1] = tmp.top; goal[2] = tmp.width(); goal[3] = tmp.height(); goal[4] = r;
        return true;
    }

    private final Runnable frame = new Runnable() {
        @Override public void run() {
            long now = System.nanoTime();
            float dt = lastFrameNanos == 0 ? 1f / 60f : Math.min((now - lastFrameNanos) / 1e9f, 1f / 20f);
            lastFrameNanos = now;
            readGoal();
            boolean settled = true;
            // Two half steps keep the integration stable at low frame rates.
            for (int step = 0; step < 2; step++) {
                float h = dt / 2f;
                for (int i = 0; i < 5; i++) {
                    float a = -STIFFNESS * (pos[i] - goal[i]) - DAMPING * vel[i];
                    vel[i] += a * h;
                    pos[i] += vel[i] * h;
                }
            }
            for (int i = 0; i < 5; i++) {
                if (Math.abs(pos[i] - goal[i]) > 0.4f || Math.abs(vel[i]) > 4f) { settled = false; break; }
            }
            if (settled) {
                System.arraycopy(goal, 0, pos, 0, 5);
                java.util.Arrays.fill(vel, 0);
            }
            invalidate();
            if (settled) running = false; else postOnAnimation(this);
        }
    };

    @Override
    protected void onDraw(Canvas c) {
        if (!placed) return;
        draw.set(pos[0], pos[1], pos[0] + pos[2], pos[1] + pos[3]);
        float r = pos[4];
        if (draw.top != sheenTop || draw.height() != sheenHeight) {
            sheenTop = draw.top; sheenHeight = draw.height();
            sheen.setShader(new LinearGradient(0, draw.top, 0, draw.top + draw.height() * 0.55f,
                    0x2EFFFFFF, 0x00FFFFFF, Shader.TileMode.CLAMP));
        }
        c.drawRoundRect(draw, r, r, fill);
        c.drawRoundRect(draw, r, r, sheen);
        float inset = stroke.getStrokeWidth() / 2f;
        draw.inset(inset, inset);
        c.drawRoundRect(draw, r, r, stroke);
    }
}
