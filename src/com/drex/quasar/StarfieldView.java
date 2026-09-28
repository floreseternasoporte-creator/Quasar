package com.drex.quasar;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.os.SystemClock;
import android.util.AttributeSet;
import android.view.Choreographer;
import android.view.View;

import java.util.Random;

/**
 * Quasar 2.1 — fondo estrellado a 60fps reales (Choreographer), parpadeo por
 * tiempo, estrellas fugaces con física por tiempo y soporte de parallax
 * cinematográfico (las capas lejanas se mueven menos).
 * Cero allocations en onDraw.
 */
public class StarfieldView extends View implements Choreographer.FrameCallback {

    private static class Star {
        float x, y, r, phase, spd, depth;
    }

    private Star[] stars = new Star[0];
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Random rnd = new Random(77031L);
    private boolean running = false;
    private long lastMs = 0;
    private long startMs = 0;

    // parallax cinematográfico (px). setParallax() es el objetivo; el valor
    // real lo alcanza con suavizado para que se sienta como cámara.
    private float parallaxTarget = 0f;
    private float parallax = 0f;

    // estrella fugaz (todo por tiempo, independiente del framerate)
    private long nextShootMs = 0;
    private float shootX, shootY, shootVx, shootVy; // px/s
    private float shootAge, shootLife; // segundos

    public StarfieldView(Context c) { super(c); init(); }
    public StarfieldView(Context c, AttributeSet a) { super(c, a); init(); }

    private void init() {
        setLayerType(LAYER_TYPE_HARDWARE, null);
        startMs = SystemClock.uptimeMillis();
        nextShootMs = startMs + 2500;
    }

    public void setParallax(float px) {
        parallaxTarget = px;
    }

    /** 2.3: la activity pausa el fondo a 60fps cuando va a segundo plano
     * (ahorra batería mientras la galería/ajustes están encima). */
    public void setRunning(boolean want) {
        want = want && getVisibility() == VISIBLE && isAttachedToWindow();
        if (want == running) return;
        running = want;
        if (want) {
            lastMs = 0;
            Choreographer.getInstance().postFrameCallback(this);
        } else {
            Choreographer.getInstance().removeFrameCallback(this);
        }
    }

    @Override
    protected void onSizeChanged(int w, int h, int ow, int oh) {
        super.onSizeChanged(w, h, ow, oh);
        int n = Math.max(70, Math.min(260, (w * h) / 11000));
        stars = new Star[n];
        for (int i = 0; i < n; i++) {
            Star s = new Star();
            s.x = rnd.nextFloat() * w;
            s.y = rnd.nextFloat() * h;
            s.r = 0.8f + rnd.nextFloat() * 1.9f;
            s.phase = rnd.nextFloat() * (float) (Math.PI * 2);
            s.spd = 0.6f + rnd.nextFloat() * 1.7f;
            s.depth = 0.25f + rnd.nextFloat() * 0.75f; // para parallax
            stars[i] = s;
        }
    }

    @Override protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        running = true;
        lastMs = 0;
        Choreographer.getInstance().postFrameCallback(this);
    }

    @Override protected void onDetachedFromWindow() {
        running = false;
        Choreographer.getInstance().removeFrameCallback(this);
        super.onDetachedFromWindow();
    }

    @Override protected void onVisibilityChanged(View changed, int vis) {
        super.onVisibilityChanged(changed, vis);
        boolean want = vis == VISIBLE && isAttachedToWindow();
        if (want && !running) {
            running = true;
            lastMs = 0;
            Choreographer.getInstance().postFrameCallback(this);
        } else if (!want && running) {
            running = false;
            Choreographer.getInstance().removeFrameCallback(this);
        }
    }

    @Override public void doFrame(long frameTimeNanos) {
        if (!running) return;
        long now = SystemClock.uptimeMillis();
        float dt = lastMs == 0 ? 0.016f : Math.min(0.05f, (now - lastMs) / 1000f);
        lastMs = now;

        // suavizado del parallax (como cámara)
        parallax += (parallaxTarget - parallax) * Math.min(1f, dt * 3.5f);

        // estrella fugaz por tiempo
        if (shootAge >= shootLife && now >= nextShootMs && getWidth() > 0) {
            shootX = rnd.nextFloat() * getWidth() * 0.7f;
            shootY = rnd.nextFloat() * getHeight() * 0.35f;
            double ang = Math.toRadians(28 + rnd.nextFloat() * 24);
            float sp = (getWidth() * 0.9f + rnd.nextFloat() * getWidth() * 0.5f);
            shootVx = (float) Math.cos(ang) * sp;
            shootVy = (float) Math.sin(ang) * sp;
            shootAge = 0f;
            shootLife = 0.85f;
            nextShootMs = now + 3000 + (long) (rnd.nextFloat() * 3500);
        }
        if (shootAge < shootLife) {
            shootAge += dt;
            shootX += shootVx * dt;
            shootY += shootVy * dt;
        }

        invalidate();
        Choreographer.getInstance().postFrameCallback(this);
    }

    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        int w = getWidth(), h = getHeight();
        if (w == 0 || h == 0) return;
        long now = SystemClock.uptimeMillis();
        float tSec = (now - startMs) / 1000f;

        paint.setStyle(Paint.Style.FILL);
        for (int i = 0, n = stars.length; i < n; i++) {
            Star s = stars[i];
            float tw = 0.35f + 0.65f * (0.5f + 0.5f * (float) Math.sin(s.phase + tSec * s.spd));
            // deriva lenta + parallax por profundidad (con wrap)
            float x = s.x + tSec * 1.5f * s.depth + parallax * s.depth;
            x = x % w;
            if (x < 0) x += w;
            int a = (int) (205 * tw);
            boolean indigo = (s.phase > Math.PI);
            paint.setColor(Color.argb(a, indigo ? 165 : 235, indigo ? 180 : 240, 255));
            canvas.drawCircle(x, s.y, s.r, paint);
        }

        // estrella fugaz con estela en degradado
        if (shootAge < shootLife) {
            float k = shootAge / shootLife; // 0..1
            float fade = k < 0.15f ? k / 0.15f : 1f - (k - 0.15f) / 0.85f;
            if (fade < 0) fade = 0;
            float tx = -shootVx, ty = -shootVy; // dirección de la estela
            float tlen = (float) Math.sqrt(tx * tx + ty * ty);
            if (tlen > 0) { tx /= tlen; ty /= tlen; }
            float trailLen = 190f;
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeCap(Paint.Cap.ROUND);
            for (int i = 0; i < 6; i++) {
                float f0 = i / 6f, f1 = (i + 1) / 6f;
                paint.setStrokeWidth(3.4f * (1 - f0) + 0.6f);
                paint.setColor(Color.argb((int) (215 * (1 - f0) * fade), 190, 205, 255));
                canvas.drawLine(shootX + tx * trailLen * f0, shootY + ty * trailLen * f0,
                        shootX + tx * trailLen * f1, shootY + ty * trailLen * f1, paint);
            }
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(Color.argb((int) (255 * fade), 255, 255, 255));
            canvas.drawCircle(shootX, shootY, 3.2f, paint);
        }
        paint.setStyle(Paint.Style.FILL);
    }
}
