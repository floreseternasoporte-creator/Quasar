package com.drex.quasar;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.View;
import android.view.animation.DecelerateInterpolator;

public class ProgressRingView extends View {

    private final Paint bgPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fgPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint glowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private float progress = 0f; // 0..1 displayed
    private float target = 0f;
    private ValueAnimator anim;
    private final RectF oval = new RectF();

    public ProgressRingView(Context c) { super(c); init(); }
    public ProgressRingView(Context c, AttributeSet a) { super(c, a); init(); }

    private void init() {
        setLayerType(LAYER_TYPE_HARDWARE, null);
        bgPaint.setStyle(Paint.Style.STROKE);
        bgPaint.setStrokeWidth(26f);
        bgPaint.setColor(Color.argb(40, 165, 180, 252));
        bgPaint.setStrokeCap(Paint.Cap.ROUND);
        fgPaint.setStyle(Paint.Style.STROKE);
        fgPaint.setStrokeWidth(26f);
        fgPaint.setColor(Color.rgb(99, 102, 241));
        fgPaint.setStrokeCap(Paint.Cap.ROUND);
        glowPaint.setStyle(Paint.Style.STROKE);
        glowPaint.setStrokeWidth(34f);
        glowPaint.setColor(Color.argb(60, 99, 102, 241));
        glowPaint.setStrokeCap(Paint.Cap.ROUND);
        textPaint.setColor(Color.WHITE);
        textPaint.setTextSize(64f);
        textPaint.setTextAlign(Paint.Align.CENTER);
        textPaint.setFakeBoldText(true);
    }

    public void setProgress(float p) {
        target = Math.max(0f, Math.min(1f, p));
        if (anim != null) anim.cancel();
        anim = ValueAnimator.ofFloat(progress, target);
        anim.setDuration(280);
        anim.setInterpolator(new DecelerateInterpolator());
        anim.addUpdateListener(a -> { progress = (float) a.getAnimatedValue(); invalidate(); });
        anim.start();
    }

    public void setProgressInstant(float p) {
        if (anim != null) anim.cancel();
        progress = target = Math.max(0f, Math.min(1f, p));
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float cx = getWidth() / 2f, cy = getHeight() / 2f;
        float r = Math.min(cx, cy) - 30f;
        oval.set(cx - r, cy - r, cx + r, cy + r);
        canvas.drawArc(oval, 0, 360, false, bgPaint);
        if (progress > 0.001f) {
            float sweep = 360f * progress;
            canvas.drawArc(oval, -90, sweep, false, glowPaint);
            canvas.drawArc(oval, -90, sweep, false, fgPaint);
        }
        String txt = ((int) (progress * 100)) + "%";
        canvas.drawText(txt, cx, cy + 22f, textPaint);
    }
}
