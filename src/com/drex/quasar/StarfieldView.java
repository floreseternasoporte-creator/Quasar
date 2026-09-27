package com.drex.quasar;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.os.SystemClock;
import android.util.AttributeSet;
import android.view.View;
import java.util.Random;

public class StarfieldView extends View {
    private static class Star {
        float x, y, r, phase, speed;
    }

    private Star[] stars = new Star[0];
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Random rnd = new Random();
    private boolean running = false;
    // 1.1: estrella fugaz ocasional
    private long nextShoot = 0;
    private float shootX, shootY, shootVx, shootVy, shootLife;
    private final Runnable tick = new Runnable() {
        @Override public void run() {
            if (!running) return;
            invalidate();
            postDelayed(this, 66);
        }
    };

    public StarfieldView(Context c) { super(c); init(); }
    public StarfieldView(Context c, AttributeSet a) { super(c, a); init(); }

    private void init() {
        setLayerType(LAYER_TYPE_HARDWARE, null);
    }

    @Override
    protected void onSizeChanged(int w, int h, int ow, int oh) {
        super.onSizeChanged(w, h, ow, oh);
        int n = Math.max(60, (w * h) / 12000);
        stars = new Star[n];
        for (int i = 0; i < n; i++) {
            Star s = new Star();
            s.x = rnd.nextFloat() * w;
            s.y = rnd.nextFloat() * h;
            s.r = 0.8f + rnd.nextFloat() * 1.8f;
            s.phase = rnd.nextFloat() * (float) (Math.PI * 2);
            s.speed = 0.6f + rnd.nextFloat() * 1.6f;
            stars[i] = s;
        }
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        running = true;
        post(tick);
    }

    @Override
    protected void onDetachedFromWindow() {
        running = false;
        removeCallbacks(tick);
        super.onDetachedFromWindow();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        long t = SystemClock.uptimeMillis();
        for (Star s : stars) {
            float tw = 0.35f + 0.65f * (0.5f + 0.5f * (float) Math.sin(s.phase + t * 0.001 * s.speed));
            int a = (int) (200 * tw);
            boolean indigo = (s.phase > Math.PI);
            paint.setColor(Color.argb(a, indigo ? 165 : 255, indigo ? 180 : 255, 255));
            canvas.drawCircle(s.x, s.y, s.r, paint);
        }
        // 1.1: estrella fugaz cada 3-6 segundos
        if (shootLife <= 0 && t >= nextShoot && getWidth() > 0) {
            shootX = rnd.nextFloat() * getWidth() * 0.7f;
            shootY = rnd.nextFloat() * getHeight() * 0.4f;
            double ang = Math.toRadians(30 + rnd.nextFloat() * 25);
            float sp = 14 + rnd.nextFloat() * 10;
            shootVx = (float) Math.cos(ang) * sp;
            shootVy = (float) Math.sin(ang) * sp;
            shootLife = 1f;
            nextShoot = t + 3000 + (long) (rnd.nextFloat() * 3000);
        }
        if (shootLife > 0) {
            shootX += shootVx;
            shootY += shootVy;
            shootLife -= 0.055f;
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeCap(Paint.Cap.ROUND);
            // estela de 6 segmentos con degradado de alfa
            for (int i = 0; i < 6; i++) {
                float f = i / 6f;
                float x1 = shootX - shootVx * f * 6;
                float y1 = shootY - shootVy * f * 6;
                float x2 = shootX - shootVx * (f + 0.16f) * 6;
                float y2 = shootY - shootVy * (f + 0.16f) * 6;
                paint.setStrokeWidth(3.2f * (1 - f) + 0.5f);
                paint.setColor(Color.argb((int) (220 * (1 - f) * Math.max(0, shootLife)), 180, 200, 255));
                canvas.drawLine(x1, y1, x2, y2, paint);
            }
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(Color.argb((int) (255 * Math.max(0, shootLife)), 255, 255, 255));
            canvas.drawCircle(shootX, shootY, 3f, paint);
        }
    }
}
