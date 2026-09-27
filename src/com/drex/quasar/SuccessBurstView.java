package com.drex.quasar;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PathMeasure;
import android.graphics.RadialGradient;
import android.graphics.Shader;
import android.util.AttributeSet;
import android.view.View;

import java.util.Random;

/**
 * Quasar 2.1 — celebración como "misión cumplida": resplandor central que
 * florece, ondas de choque expansivas, partículas radiantes con salida
 * suave y check dibujado progresivamente con brillo. Sin allocations en onDraw.
 */
public class SuccessBurstView extends View {
    private static class P {
        float vx, vy, r;
        int color;
    }

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint bloomPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final P[] parts = new P[30];
    private final Random rnd = new Random(4242L);
    private final Path checkPath = new Path();
    private final Path drawPath = new Path();
    private final PathMeasure pm = new PathMeasure();
    private RadialGradient bloomGrad;
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
            double sp = 0.45 + rnd.nextDouble() * 0.85;
            p.vx = (float) (Math.cos(a) * sp);
            p.vy = (float) (Math.sin(a) * sp);
            p.r = 3 + rnd.nextFloat() * 5;
            p.color = cols[i % cols.length];
            parts[i] = p;
        }
    }

    @Override protected void onSizeChanged(int w, int h, int ow, int oh) {
        super.onSizeChanged(w, h, ow, oh);
        float R = Math.min(w, h) / 2f;
        bloomGrad = new RadialGradient(w / 2f, h / 2f, R,
                new int[]{Color.argb(190, 52, 211, 153), Color.argb(0, 52, 211, 153)},
                new float[]{0f, 1f}, Shader.TileMode.CLAMP);
        bloomPaint.setShader(bloomGrad);
    }

    public void start() {
        if (anim != null) anim.cancel();
        t = 0f;
        anim = ValueAnimator.ofFloat(0f, 1f);
        anim.setDuration(1700);
        anim.setInterpolator(Cine.EASE_OUT);
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
        if (R <= 0) return;

        // resplandor central que florece y se asienta
        float bloomK = Math.min(1f, t * 2.2f);
        float bloomR = R * (0.25f + 0.75f * (1f - (1f - bloomK) * (1f - bloomK)));
        bloomPaint.setAlpha((int) (200 * (1f - t * 0.85f)));
        c.drawCircle(cx, cy, bloomR, bloomPaint);

        // ondas de choque (2, escalonadas)
        paint.setStyle(Paint.Style.STROKE);
        for (int i = 0; i < 2; i++) {
            float ph = t * 1.25f - i * 0.3f;
            if (ph < 0 || ph > 1) continue;
            float e = 1f - (1f - ph) * (1f - ph); // ease-out
            paint.setStrokeWidth(6f * (1f - ph) + 1.5f);
            paint.setColor(Color.argb((int) (160 * (1f - ph)), 34, 211, 238));
            c.drawCircle(cx, cy, R * (0.15f + 0.85f * e), paint);
        }

        // partículas radiantes con salida suave
        paint.setStyle(Paint.Style.FILL);
        float pe = 1f - (1f - Math.min(1f, t * 1.15f));
        pe = pe * pe * (3f - 2f * pe); // smoothstep
        for (int i = 0; i < parts.length; i++) {
            P p = parts[i];
            float px = cx + p.vx * R * pe * 1.5f;
            float py = cy + p.vy * R * pe * 1.5f;
            paint.setColor(p.color);
            paint.setAlpha((int) (255 * Math.max(0f, 1f - t * 1.05f)));
            c.drawCircle(px, py, Math.max(0.5f, p.r * (1f - t * 0.45f)), paint);
        }

        // check progresivo con resplandor
        float ct = Math.max(0f, Math.min(1f, (t - 0.28f) / 0.45f));
        if (ct > 0) {
            checkPath.reset();
            float s = R * 0.40f;
            checkPath.moveTo(cx - s, cy + s * 0.1f);
            checkPath.lineTo(cx - s * 0.15f, cy + s * 0.75f);
            checkPath.lineTo(cx + s * 1.05f, cy - s * 0.7f);
            pm.setPath(checkPath, false);
            drawPath.reset();
            pm.getSegment(0, pm.getLength() * ct, drawPath, true);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeCap(Paint.Cap.ROUND);
            paint.setStrokeJoin(Paint.Join.ROUND);
            paint.setStrokeWidth(22f);
            paint.setColor(Color.argb(80, 52, 211, 153));
            c.drawPath(drawPath, paint);
            paint.setStrokeWidth(12f);
            paint.setColor(Color.rgb(52, 211, 153));
            c.drawPath(drawPath, paint);
        }
        paint.setAlpha(255);
    }
}
