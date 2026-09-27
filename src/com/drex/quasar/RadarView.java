package com.drex.quasar;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.SweepGradient;
import android.os.SystemClock;
import android.util.AttributeSet;
import android.view.Choreographer;
import android.view.View;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Quasar 2.1 — radar cinematográfico de nave.
 * Barrido con estela luminosa larga (SweepGradient rotado), retícula de
 * escaneo con marcas de grados, anillos de pulso que nacen del centro,
 * partículas orbitales, y blips con entrada elástica ÚNICA + anillo de
 * fijación de objetivo + onda de señal al aparecer.
 * 60fps por Choreographer. Cero allocations en onDraw.
 */
public class RadarView extends View implements Choreographer.FrameCallback {

    private static final float SWEEP_DEG_PER_SEC = 360f / 2.8f;
    private static final float TRAIL_DEG = 82f;
    private static final long BLIP_ENTER_MS = 520;

    // ---- paints (precreados, sin allocations en onDraw) ----
    private final Paint ringPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint tickPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint crossPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint pulsePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint trailPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint edgePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint tipGlowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint partPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint blipPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint blipCorePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint lockPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint wavePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint centerGlowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint centerPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    private final Matrix gradMatrix = new Matrix();
    private final RectF arcOval = new RectF();
    private final RectF blipOval = new RectF();
    private SweepGradient sweepGrad;

    // partículas orbitales (params fijos, sin alloc)
    private final float[] pAng = new float[44];
    private final float[] pRad = new float[44];
    private final float[] pSpd = new float[44];
    private final float[] pSz = new float[44];

    // blips: nombre + instante de nacimiento (entrada elástica una sola vez)
    private final ArrayList<String> peers = new ArrayList<>();
    private final ArrayList<Long> births = new ArrayList<>();

    private float sweepDeg = 0f;
    private long lastMs = 0;
    private long startMs = 0;
    private boolean running = false;
    private float cx, cy, R;

    public RadarView(Context c) { super(c); init(); }
    public RadarView(Context c, AttributeSet a) { super(c, a); init(); }

    private void init() {
        setLayerType(LAYER_TYPE_HARDWARE, null);

        ringPaint.setStyle(Paint.Style.STROKE);
        ringPaint.setStrokeWidth(2f);
        ringPaint.setColor(Color.argb(64, 99, 102, 241));

        tickPaint.setStyle(Paint.Style.STROKE);
        tickPaint.setStrokeWidth(2f);
        tickPaint.setColor(Color.argb(90, 165, 180, 252));

        crossPaint.setStyle(Paint.Style.STROKE);
        crossPaint.setStrokeWidth(1.5f);
        crossPaint.setColor(Color.argb(22, 165, 180, 252));

        pulsePaint.setStyle(Paint.Style.STROKE);
        pulsePaint.setStrokeWidth(3f);

        trailPaint.setStyle(Paint.Style.FILL);

        edgePaint.setStyle(Paint.Style.STROKE);
        edgePaint.setStrokeWidth(4f);
        edgePaint.setStrokeCap(Paint.Cap.ROUND);
        edgePaint.setColor(Color.argb(255, 34, 211, 238));

        tipGlowPaint.setStyle(Paint.Style.FILL);
        tipGlowPaint.setColor(Color.argb(90, 34, 211, 238));

        partPaint.setStyle(Paint.Style.FILL);

        blipPaint.setStyle(Paint.Style.FILL);
        blipPaint.setColor(Color.rgb(34, 211, 238));
        blipCorePaint.setStyle(Paint.Style.FILL);
        blipCorePaint.setColor(Color.WHITE);

        lockPaint.setStyle(Paint.Style.STROKE);
        lockPaint.setStrokeWidth(3f);
        lockPaint.setStrokeCap(Paint.Cap.ROUND);
        lockPaint.setColor(Color.argb(200, 34, 211, 238));

        wavePaint.setStyle(Paint.Style.STROKE);
        wavePaint.setStrokeWidth(3f);

        centerGlowPaint.setStyle(Paint.Style.FILL);
        centerGlowPaint.setColor(Color.argb(70, 99, 102, 241));
        centerPaint.setStyle(Paint.Style.FILL);
        centerPaint.setColor(Color.rgb(99, 102, 241));

        Random rnd = new Random(20260927L);
        for (int i = 0; i < pAng.length; i++) {
            pAng[i] = rnd.nextFloat() * (float) (Math.PI * 2);
            pRad[i] = 0.16f + rnd.nextFloat() * 0.80f;
            pSpd[i] = (0.05f + rnd.nextFloat() * 0.13f) * (rnd.nextBoolean() ? 1f : -1f);
            pSz[i] = 1.4f + rnd.nextFloat() * 2.2f;
        }
        startMs = SystemClock.uptimeMillis();
    }

    /** La actividad llama esto cuando cambia la lista de peers. */
    public void setPeerNames(List<String> names) {
        long now = SystemClock.uptimeMillis();
        for (int i = peers.size() - 1; i >= 0; i--) {
            if (names == null || !names.contains(peers.get(i))) {
                peers.remove(i);
                births.remove(i);
            }
        }
        if (names != null) {
            for (int i = 0, n = names.size(); i < n; i++) {
                String s = names.get(i);
                if (!peers.contains(s)) {
                    peers.add(s);
                    births.add(now);
                }
            }
        }
    }

    // ---- ciclo de vida 60fps ----
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
        sweepDeg = (sweepDeg + dt * SWEEP_DEG_PER_SEC) % 360f;
        invalidate();
        Choreographer.getInstance().postFrameCallback(this);
    }

    @Override protected void onSizeChanged(int w, int h, int ow, int oh) {
        super.onSizeChanged(w, h, ow, oh);
        cx = w / 2f;
        cy = h / 2f;
        R = Math.min(cx, cy) * 0.94f;
        arcOval.set(cx - R, cy - R, cx + R, cy + R);
        int[] cols = {
                Color.argb(0, 34, 211, 238),
                Color.argb(36, 34, 211, 238),
                Color.argb(120, 34, 211, 238),
                Color.argb(235, 34, 211, 238)};
        float[] pos = {0f, 0.70f, 0.93f, 1f};
        sweepGrad = new SweepGradient(cx, cy, cols, pos);
        trailPaint.setShader(sweepGrad);
    }

    @Override protected void onDraw(Canvas c) {
        super.onDraw(c);
        if (R <= 0) return;
        long now = SystemClock.uptimeMillis();
        float tSec = (now - startMs) / 1000f;

        // retícula: cruz sutil
        c.drawLine(cx - R, cy, cx + R, cy, crossPaint);
        c.drawLine(cx, cy - R, cx, cy + R, crossPaint);

        // anillos base
        for (int i = 1; i <= 3; i++) {
            c.drawCircle(cx, cy, R * i / 3f, ringPaint);
        }

        // marcas de grados en el anillo exterior
        for (int i = 0; i < 72; i++) {
            double a = Math.PI * 2 * i / 72.0;
            float ca = (float) Math.cos(a), sa = (float) Math.sin(a);
            boolean major = (i % 6 == 0);
            float r1 = R * (major ? 0.94f : 0.965f);
            c.drawLine(cx + ca * r1, cy + sa * r1, cx + ca * R, cy + sa * R, tickPaint);
        }

        // partículas orbitales (tiempo real, sin saltos)
        for (int i = 0; i < pAng.length; i++) {
            float a = pAng[i] + tSec * pSpd[i];
            float rr = R * pRad[i];
            float x = cx + (float) Math.cos(a) * rr;
            float y = cy + (float) Math.sin(a) * rr;
            float tw = 55 + 45 * (float) Math.sin(tSec * 2.1 + i * 1.3);
            partPaint.setColor(Color.argb((int) tw, 165, 180, 252));
            c.drawCircle(x, y, pSz[i], partPaint);
        }

        // anillos de pulso que nacen del centro (3, escalonados)
        for (int i = 0; i < 3; i++) {
            float ph = (tSec / 2.6f + i / 3f) % 1f;
            pulsePaint.setColor(Color.argb((int) (105 * (1f - ph)), 34, 211, 238));
            c.drawCircle(cx, cy, R * (0.06f + 0.94f * ph), pulsePaint);
        }

        // estela luminosa del barrido (gradiente rotado, sin segmentos)
        if (sweepGrad != null) {
            gradMatrix.setRotate(sweepDeg, cx, cy);
            sweepGrad.setLocalMatrix(gradMatrix);
            c.drawArc(arcOval, sweepDeg - TRAIL_DEG, TRAIL_DEG, true, trailPaint);
        }

        // borde brillante del barrido + resplandor en la punta
        double sa = Math.toRadians(sweepDeg);
        float tipX = cx + (float) Math.cos(sa) * R;
        float tipY = cy + (float) Math.sin(sa) * R;
        c.drawLine(cx, cy, tipX, tipY, edgePaint);
        c.drawCircle(tipX, tipY, 15f, tipGlowPaint);
        c.drawCircle(tipX, tipY, 5f, blipCorePaint);

        // blips con entrada elástica ÚNICA + fijación de objetivo
        int n = peers.size();
        for (int i = 0; i < n; i++) {
            double a = -Math.PI / 2 + Math.PI * 2 * i / n;
            float rr = R * 0.62f;
            float x = cx + (float) Math.cos(a) * rr;
            float y = cy + (float) Math.sin(a) * rr;

            long ageMs = now - births.get(i);
            float scale, alpha;
            if (ageMs < BLIP_ENTER_MS) {
                float k = ageMs / (float) BLIP_ENTER_MS;
                scale = Math.max(0.01f, Cine.easeOutBack(k));
                alpha = k;
                // onda de señal expandiéndose al aparecer
                wavePaint.setColor(Color.argb((int) (170 * (1f - k)), 34, 211, 238));
                c.drawCircle(x, y, 12f + k * R * 0.30f, wavePaint);
            } else {
                scale = 1f + 0.07f * (float) Math.sin(tSec * 2.6 + i * 1.7);
                alpha = 1f;
            }
            float r = 10f * scale;
            blipPaint.setAlpha((int) (255 * alpha));
            c.drawCircle(x, y, r, blipPaint);
            blipCorePaint.setAlpha((int) (255 * alpha));
            c.drawCircle(x, y, r * 0.42f, blipCorePaint);

            // anillo de fijación rotando alrededor del blip
            float lockR = r + 11f * scale;
            blipOval.set(x - lockR, y - lockR, x + lockR, y + lockR);
            float lockRot = (tSec * 95f + i * 47f) % 360f;
            lockPaint.setAlpha((int) (200 * alpha));
            c.drawArc(blipOval, lockRot, 75f, false, lockPaint);
            c.drawArc(blipOval, lockRot + 180f, 75f, false, lockPaint);
        }
        blipPaint.setAlpha(255);
        blipCorePaint.setAlpha(255);
        lockPaint.setAlpha(255);

        // centro: resplandor + núcleo
        float pulse = 1f + 0.12f * (float) Math.sin(tSec * 3.2);
        c.drawCircle(cx, cy, 22f * pulse, centerGlowPaint);
        c.drawCircle(cx, cy, 10f, centerPaint);
        c.drawCircle(cx, cy, 4f, blipCorePaint);
    }
}
