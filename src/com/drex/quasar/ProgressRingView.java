package com.drex.quasar;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.Choreographer;
import android.view.View;

/**
 * Quasar 2.1 — anillo de progreso con easing persistente: el valor mostrado
 * persigue al objetivo en cada frame (60fps) en vez de crear y cancelar un
 * ValueAnimator en cada actualización — eso causaba el temblor visible.
 * Cero allocations en onDraw.
 */
public class ProgressRingView extends View {

    private final Paint bgPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fgPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint glowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint tipPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF oval = new RectF();

    private float shown = 0f;   // valor dibujado
    private float target = 0f;  // objetivo
    private boolean easing = false;

    private final Choreographer.FrameCallback frame = new Choreographer.FrameCallback() {
        @Override public void doFrame(long t) {
            if (!easing) return;
            shown += (target - shown) * 0.22f;
            if (Math.abs(target - shown) < 0.0025f) {
                shown = target;
                easing = false;
            } else {
                Choreographer.getInstance().postFrameCallback(this);
            }
            invalidate();
        }
    };

    public ProgressRingView(Context c) { super(c); init(); }
    public ProgressRingView(Context c, AttributeSet a) { super(c, a); init(); }

    private void init() {
        setLayerType(LAYER_TYPE_HARDWARE, null);
        bgPaint.setStyle(Paint.Style.STROKE);
        bgPaint.setStrokeWidth(26f);
        bgPaint.setColor(Color.argb(40, 165, 180, 252));
        bgPaint.setStrokeCap(Paint.Cap.ROUND);
        glowPaint.setStyle(Paint.Style.STROKE);
        glowPaint.setStrokeWidth(36f);
        glowPaint.setColor(Color.argb(55, 99, 102, 241));
        glowPaint.setStrokeCap(Paint.Cap.ROUND);
        fgPaint.setStyle(Paint.Style.STROKE);
        fgPaint.setStrokeWidth(26f);
        fgPaint.setColor(Color.rgb(99, 102, 241));
        fgPaint.setStrokeCap(Paint.Cap.ROUND);
        tipPaint.setStyle(Paint.Style.FILL);
        tipPaint.setColor(Color.argb(255, 165, 180, 252));
        textPaint.setColor(Color.WHITE);
        textPaint.setTextSize(64f);
        textPaint.setTextAlign(Paint.Align.CENTER);
        textPaint.setFakeBoldText(true);
    }

    /** Actualiza el objetivo; el anillo lo alcanza con suavidad a 60fps. */
    public void setProgress(float p) {
        target = Math.max(0f, Math.min(1f, p));
        if (!easing && isAttachedToWindow()) {
            easing = true;
            Choreographer.getInstance().postFrameCallback(frame);
        }
    }

    public void setProgressInstant(float p) {
        easing = false;
        Choreographer.getInstance().removeFrameCallback(frame);
        shown = target = Math.max(0f, Math.min(1f, p));
        invalidate();
    }

    @Override protected void onDetachedFromWindow() {
        easing = false;
        Choreographer.getInstance().removeFrameCallback(frame);
        super.onDetachedFromWindow();
    }

    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float cx = getWidth() / 2f, cy = getHeight() / 2f;
        float r = Math.min(cx, cy) - 32f;
        if (r <= 0) return;
        oval.set(cx - r, cy - r, cx + r, cy + r);
        canvas.drawArc(oval, 0, 360, false, bgPaint);
        if (shown > 0.001f) {
            float sweep = 360f * shown;
            canvas.drawArc(oval, -90, sweep, false, glowPaint);
            canvas.drawArc(oval, -90, sweep, false, fgPaint);
            // punto luminoso en la punta del progreso
            double a = Math.toRadians(-90 + sweep);
            canvas.drawCircle(cx + (float) Math.cos(a) * r,
                    cy + (float) Math.sin(a) * r, 9f, tipPaint);
        }
        String txt = ((int) (shown * 100)) + "%";
        canvas.drawText(txt, cx, cy + 22f, textPaint);
    }
}
