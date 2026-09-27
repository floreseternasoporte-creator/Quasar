package com.drex.quasar;

import android.view.MotionEvent;
import android.view.View;
import android.view.animation.Interpolator;
import android.view.animation.PathInterpolator;

/**
 * Quasar 2.1 — lenguaje de movimiento cinematográfico compartido por toda la app.
 * Referencia: cinematografía de lanzamientos — todo con peso, suave, 60fps.
 * Easing base: fast-out-slow-in. Sin rebotes de juguete; el elástico solo
 * aparece en acentos puntuales (aparición de un dispositivo en el radar).
 */
public final class Cine {
    private Cine() {}

    /** Fast-out-slow-in cinematográfico (cubic-bezier 0.4, 0, 0.2, 1). */
    public static final Interpolator CINEMATIC = new PathInterpolator(0.4f, 0f, 0.2f, 1f);
    /** Salida suave para micro-interacciones. */
    public static final Interpolator EASE_OUT = new PathInterpolator(0.22f, 1f, 0.36f, 1f);
    /** Entrada con peso, para despegues y salidas. */
    public static final Interpolator EASE_IN = new PathInterpolator(0.55f, 0f, 1f, 0.45f);

    /** Ease-out-back suavizado (menos exagerado que el estándar). */
    public static float easeOutBack(float x) {
        float c1 = 1.30158f;
        float c3 = c1 + 1f;
        float d = x - 1f;
        return 1f + c3 * d * d * d + c1 * d * d;
    }

    public static final Interpolator ELASTIC = new Interpolator() {
        @Override public float getInterpolation(float x) { return easeOutBack(x); }
    };

    public static float dp(View v, float dp) {
        return dp * v.getResources().getDisplayMetrics().density;
    }

    /** Presión cinematográfica: hunde suave, suelta con peso. */
    public static void pressFx(final View v) {
        v.setOnTouchListener((view, ev) -> {
            int a = ev.getAction();
            if (a == MotionEvent.ACTION_DOWN) {
                view.animate().cancel();
                view.animate().scaleX(0.95f).scaleY(0.95f)
                        .setDuration(120).setInterpolator(EASE_OUT).start();
            } else if (a == MotionEvent.ACTION_UP || a == MotionEvent.ACTION_CANCEL) {
                view.animate().cancel();
                view.animate().scaleX(1f).scaleY(1f)
                        .setDuration(420).setInterpolator(CINEMATIC).start();
            }
            return false;
        });
    }

    /** Entrada cinematográfica: fundido + desliz vertical + leve escala. */
    public static void enterCine(View v, long delay) {
        v.animate().cancel();
        v.setAlpha(0f);
        v.setTranslationY(dp(v, 30));
        v.setScaleX(0.985f);
        v.setScaleY(0.985f);
        v.animate().alpha(1f).translationY(0f).scaleX(1f).scaleY(1f)
                .setStartDelay(delay).setDuration(460).setInterpolator(CINEMATIC).start();
    }
}
