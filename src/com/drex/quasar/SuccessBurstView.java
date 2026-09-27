package com.drex.quasar;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PathMeasure;
import android.util.AttributeSet;
import android.view.View;
import android.view.animation.DecelerateInterpolator;
import java.util.Random;

/**
 * Quasar 1.1 — celebración de éxito: anillos expansivos, partículas
 * orbitales y check dibujado progresivamente con resplandor.
 */
public class SuccessBurstView extends View {
    private static class P {
        float vx, vy, r;
        int color;
    }

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final P[] parts = new P[28];
    private final Random rnd = new Random();
    private final Path checkPath = new Path();
    private final Path drawPath = new Path();
    private final PathMeasure pm = new PathMeasure();
    private float t = 0f; // 0..1
    private ValueAnimator anim;

    public SuccessBurstView(Context c) { super(c); init(); }
    public SuccessBurstView(Context c, AttributeSet a) { super(c, a); init(); }

    private void init() {
        setLayerType(LAYER_TYPE_HARDWARE, null);
        int[] cols = {0xFF6366F1, 0xFF22D3EE, 0xFF34D399, 0xFFA5B4FC};
        for (int i = 0; i < parts.length; i++) {
            P p = new P();
            double a = rnd.nextDouble() * Math.PI * 2;
            double sp = 0.55 + rnd.nextDouble() * 0.75;
            p.vx = (float) (Math.cos(a) * sp);
            p.vy = (float) (Math.sin(a) * sp);
            p.r = 3 + rnd.nextFloat() * 5;
            p.color = cols[i % cols.length];
            parts[i] = p;
        }
    }

    public void start() {
        if (anim != null) anim.cancel();
        t = 0f;
        anim = ValueAnimator.ofFloat(0f, 1f);
        anim.setDuration(1500);
        anim.setInterpolator(new DecelerateInterpolator());
        anim.addUpdateListener(a -> { t = (float) a.getAnimatedValue(); invalidate(); });
        anim.start();
    }

    @Override protected void onDetachedFromWindow() {
        if (anim != null) anim.cancel();
        super.onDetachedFromWindow();
    }

    @Override protected void onDraw(Canvas c) {
        super.onDraw(c);
        float cx = getWidth() / 2f, cy = getHeight() / 2f;
        float R = Math.min(cx, cy);
        // anillos expansivos
        paint.setStyle(Paint.Style.STROKE);
        for (int i = 0; i < 3; i++) {
            float ph = t * 1.15f - i * 0.22f;
            if (ph < 0 || ph > 1) continue;
            paint.setStrokeWidth(5f);
            paint.setColor(Color.argb((int) (150 * (1 - ph)), 99, 102, 241));
            c.drawCircle(cx, cy, R * (0.25f + 0.75f * ph), paint);
        }
        // partículas radiantes
        paint.setStyle(Paint.Style.FILL);
        for (P p : parts) {
            float px = cx + p.vx * R * t * 1.4f;
            float py = cy + p.vy * R * t * 1.4f;
            paint.setColor(p.color);
            paint.setAlpha((int) (255 * Math.max(0, 1 - t * 1.1f)));
            c.drawCircle(px, py, Math.max(0.5f, p.r * (1 - t * 0.5f)), paint);
        }
        // check progresivo con resplandor
        float ct = Math.max(0, Math.min(1, (t - 0.25f) / 0.5f));
        if (ct > 0) {
            checkPath.reset();
            float s = R * 0.42f;
            checkPath.moveTo(cx - s, cy + s * 0.1f);
            checkPath.lineTo(cx - s * 0.15f, cy + s * 0.75f);
            checkPath.lineTo(cx + s * 1.05f, cy - s * 0.7f);
            pm.setPath(checkPath, false);
            drawPath.reset();
            pm.getSegment(0, pm.getLength() * ct, drawPath, true);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeCap(Paint.Cap.ROUND);
            paint.setStrokeJoin(Paint.Join.ROUND);
            paint.setStrokeWidth(20f);
            paint.setColor(Color.argb(70, 52, 211, 153));
            c.drawPath(drawPath, paint);
            paint.setStrokeWidth(11f);
            paint.setColor(Color.rgb(52, 211, 153));
            c.drawPath(drawPath, paint);
        }
        paint.setAlpha(255);
    }
}
