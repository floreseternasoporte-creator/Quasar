package com.drex.quasar;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.util.AttributeSet;
import android.view.View;
import android.view.animation.LinearInterpolator;

/**
 * Quasar 1.1 — radar con oleadas en capas, estela del barrido con brillo,
 * partículas orbitales y blips con entrada elástica.
 */
public class RadarView extends View {
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private ValueAnimator sweep, pulse;
    private float sweepAngle = 0f;
    private float pulsePhase = 0f;
    private long startTime = 0;
    private int peerCount = 1; // 1.1: lo fija la actividad cuando cambia la lista
    private final float[] partAngle = new float[36];
    private final float[] partRadius = new float[36];
    private final float[] partSpeed = new float[36];

    public RadarView(Context c) { super(c); init(); }
    public RadarView(Context c, AttributeSet a) { super(c, a); init(); }

    private void init() {
        setLayerType(LAYER_TYPE_HARDWARE, null);
        startTime = System.currentTimeMillis();
        for (int i = 0; i < partAngle.length; i++) {
            partAngle[i] = (float) (Math.random() * Math.PI * 2);
            partRadius[i] = 0.25f + (float) Math.random() * 0.7f;
            partSpeed[i] = (float) (0.05 + Math.random() * 0.12);
        }
        sweep = ValueAnimator.ofFloat(0f, 360f);
        sweep.setDuration(2600);
        sweep.setRepeatCount(ValueAnimator.INFINITE);
        sweep.setInterpolator(new LinearInterpolator());
        sweep.addUpdateListener(a -> {
            sweepAngle = (float) a.getAnimatedValue();
            invalidate();
        });
        sweep.start();
        pulse = ValueAnimator.ofFloat(0f, 1f);
        pulse.setDuration(2400);
        pulse.setRepeatCount(ValueAnimator.INFINITE);
        pulse.setInterpolator(new LinearInterpolator());
        pulse.addUpdateListener(a -> {
            pulsePhase = (float) a.getAnimatedValue();
            invalidate();
        });
        pulse.start();
    }

    @Override protected void onDetachedFromWindow() {
        if (sweep != null) sweep.cancel();
        if (pulse != null) pulse.cancel();
        super.onDetachedFromWindow();
    }

    /** La actividad llama esto solo cuando la lista de peers cambió. */
    public void setPeerNames(java.util.List<String> names) {
        peerCount = Math.max(1, names == null ? 0 : names.size());
        invalidate();
    }

    @Override protected void onDraw(Canvas c) {
        super.onDraw(c);
        float cx = getWidth() / 2f, cy = getHeight() / 2f;
        float R = Math.min(cx, cy) * 0.94f;

        // anillos base
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(2f);
        for (int i = 1; i <= 3; i++) {
            paint.setColor(Color.argb(70, 99, 102, 241));
            c.drawCircle(cx, cy, R * i / 3f, paint);
        }

        // partículas orbitales lentas
        paint.setStyle(Paint.Style.FILL);
        float tSec = (System.currentTimeMillis() - startTime) / 1000f;
        for (int i = 0; i < partAngle.length; i++) {
            float a = partAngle[i] + tSec * partSpeed[i];
            float rr = R * partRadius[i];
            float x = cx + (float) Math.cos(a) * rr;
            float y = cy + (float) Math.sin(a) * rr;
            paint.setColor(Color.argb(90, 165, 180, 252));
            c.drawCircle(x, y, 2.2f, paint);
        }

        // oleadas expansivas (3, escalonadas)
        for (int i = 0; i < 3; i++) {
            float ph = (pulsePhase + i / 3f) % 1f;
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(3f);
            paint.setColor(Color.argb((int) (110 * (1 - ph)), 34, 211, 238));
            c.drawCircle(cx, cy, R * ph, paint);
        }

        // barrido con estela luminosa (línea principal + 4 ecos)
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeCap(Paint.Cap.ROUND);
        for (int i = 0; i < 5; i++) {
            float a = (float) Math.toRadians(sweepAngle - i * 7);
            float x = cx + (float) Math.cos(a) * R;
            float y = cy + (float) Math.sin(a) * R;
            paint.setStrokeWidth(i == 0 ? 5f : 3f);
            paint.setColor(Color.argb(i == 0 ? 235 : 140 - i * 26, 34, 211, 238));
            c.drawLine(cx, cy, x, y, paint);
        }
        // resplandor en la punta del barrido
        float tipA = (float) Math.toRadians(sweepAngle);
        float tipX = cx + (float) Math.cos(tipA) * R;
        float tipY = cy + (float) Math.sin(tipA) * R;
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(Color.argb(90, 34, 211, 238));
        c.drawCircle(tipX, tipY, 14f, paint);

        // blips de dispositivos con entrada elástica
        int count = peerCount;
        for (int i = 0; i < count; i++) {
            float a = (float) (Math.PI * 2 * i / count + Math.toRadians(sweepAngle * 0.25));
            float rr = R * 0.62f;
            float x = cx + (float) Math.cos(a) * rr;
            float y = cy + (float) Math.sin(a) * rr;
            // edad ficticia escalonada para la entrada elástica
            float age = ((tSec * 0.7f + i * 0.35f) % 2.2f) / 0.45f;
            float scale = age >= 1 ? 1f : overshoot(Math.min(1f, age));
            float breathe = 1f + 0.18f * (float) Math.sin(tSec * 3 + i * 1.7f);
            float r = 9f * scale * breathe;
            if (r <= 0.5f) continue;
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(Color.argb(255, 34, 211, 238));
            c.drawCircle(x, y, r, paint);
            paint.setColor(Color.argb(255, 255, 255, 255));
            c.drawCircle(x, y, r * 0.42f, paint);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(2.5f);
            paint.setColor(Color.argb(150, 34, 211, 238));
            c.drawCircle(x, y, r + 7f * scale, paint);
        }

        // centro
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(Color.argb(255, 99, 102, 241));
        c.drawCircle(cx, cy, 10f, paint);
        paint.setColor(Color.argb(255, 255, 255, 255));
        c.drawCircle(cx, cy, 4f, paint);
    }

    private float overshoot(float x) {
        // easeOutBack: sobrepasa y se asienta
        float c1 = 1.70158f, c3 = c1 + 1f;
        return 1f + c3 * (float) Math.pow(x - 1, 3) + c1 * (float) Math.pow(x - 1, 2);
    }
}
