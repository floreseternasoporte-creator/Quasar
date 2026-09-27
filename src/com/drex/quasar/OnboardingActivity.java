package com.drex.quasar;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * Quasar 2.1 — onboarding cinematográfico: el swipe SIGUE al dedo con parallax
 * entre slides, snap con easing cinematográfico, contenido escalonado por
 * slide y parallax del fondo estrellado. Guardia anti-solape de animaciones.
 * Solo se muestra la primera vez.
 */
public class OnboardingActivity extends Activity {

    private static final int TOTAL = 4;

    private int page = 0;
    private View[] slides;
    private View slidesBox;
    private StarfieldView stars;
    private ImageView[] dots;
    private TextView btnNext, btnSkip;
    private boolean animating = false;

    // drag
    private float downX;
    private int activePointer = -1;
    private boolean dragging = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_onboarding);

        slides = new View[]{
                findViewById(R.id.slide1), findViewById(R.id.slide2),
                findViewById(R.id.slide3), findViewById(R.id.slide4)};
        slidesBox = findViewById(R.id.ob_slides);
        stars = findViewById(R.id.ob_stars);
        btnNext = findViewById(R.id.ob_next);
        btnSkip = findViewById(R.id.ob_skip);
        Cine.pressFx(btnNext);

        LinearLayout dotsBox = findViewById(R.id.ob_dots);
        dots = new ImageView[TOTAL];
        for (int i = 0; i < TOTAL; i++) {
            ImageView d = new ImageView(this);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(18, 18);
            lp.setMargins(0, 0, 14, 0);
            d.setLayoutParams(lp);
            dotsBox.addView(d);
            dots[i] = d;
        }

        btnNext.setOnClickListener(v -> {
            if (animating) return;
            if (page < TOTAL - 1) goTo(page + 1);
            else finishOnboarding();
        });
        btnSkip.setOnClickListener(v -> finishOnboarding());

        slidesBox.setOnTouchListener(new SwipeListener());

        refresh();
        slidesBox.post(() -> staggerContent(slides[0]));
    }

    // ---------------- navegación ----------------

    private void goTo(int next) {
        if (animating || next < 0 || next >= TOTAL || next == page) return;
        animating = true;
        final boolean fwd = next > page;
        final View out = slides[page];
        final View in = slides[next];
        int w = slidesBox.getWidth();
        if (w == 0) w = 900;
        final int dist = fwd ? w : -w;

        in.setVisibility(View.VISIBLE);
        in.setTranslationX(dist);
        in.animate().translationX(0f).setDuration(380).setInterpolator(Cine.CINEMATIC).start();
        out.animate().translationX(-dist * 0.35f).setDuration(380).setInterpolator(Cine.CINEMATIC)
                .withEndAction(() -> {
                    out.setVisibility(View.GONE);
                    out.setTranslationX(0f);
                    animating = false;
                }).start();

        page = next;
        refresh();
        staggerContent(in);
        stars.setParallax(-page * 90f);
    }

    /** Settle tras un drag: completa o revierte con easing cinematográfico. */
    private void settleDrag(float dx) {
        int w = slidesBox.getWidth();
        if (w == 0) w = 900;
        final View cur = slides[page];
        if (dx < -w * 0.28f && page < TOTAL - 1) {
            settleTo(page + 1, cur, slides[page + 1], -w);
        } else if (dx > w * 0.28f && page > 0) {
            settleTo(page - 1, cur, slides[page - 1], w);
        } else {
            animating = true;
            cur.animate().translationX(0f).setDuration(320).setInterpolator(Cine.CINEMATIC)
                    .withEndAction(() -> animating = false).start();
            View nb = neighborFor(dx);
            if (nb != null) {
                final int dist = dx < 0 ? w : -w;
                nb.animate().translationX(dist).setDuration(320).setInterpolator(Cine.CINEMATIC)
                        .withEndAction(() -> {
                            nb.setVisibility(View.GONE);
                            nb.setTranslationX(0f);
                        }).start();
            }
            stars.setParallax(-page * 90f);
        }
    }

    private View neighborFor(float dx) {
        if (dx < 0 && page < TOTAL - 1) return slides[page + 1];
        if (dx > 0 && page > 0) return slides[page - 1];
        return null;
    }

    private void settleTo(int next, View cur, View nb, int dist) {
        animating = true;
        nb.animate().translationX(0f).setDuration(340).setInterpolator(Cine.CINEMATIC).start();
        cur.animate().translationX(-dist * 0.35f).setDuration(340).setInterpolator(Cine.CINEMATIC)
                .withEndAction(() -> {
                    cur.setVisibility(View.GONE);
                    cur.setTranslationX(0f);
                    animating = false;
                }).start();
        page = next;
        refresh();
        staggerContent(nb);
        stars.setParallax(-page * 90f);
    }

    /** El contenido de cada slide entra escalonado, como títulos de cine. */
    private void staggerContent(View slide) {
        if (!(slide instanceof ViewGroup)) return;
        ViewGroup g = (ViewGroup) slide;
        for (int i = 0; i < g.getChildCount(); i++) {
            View ch = g.getChildAt(i);
            ch.animate().cancel();
            ch.setAlpha(0f);
            ch.setTranslationY(Cine.dp(ch, 44));
            ch.animate().alpha(1f).translationY(0f)
                    .setStartDelay(90 + i * 95L).setDuration(500)
                    .setInterpolator(Cine.CINEMATIC).start();
        }
    }

    private void refresh() {
        for (int i = 0; i < TOTAL; i++) {
            boolean active = i == page;
            dots[i].setImageResource(active ? R.drawable.dot_active : R.drawable.dot_idle);
            dots[i].animate().cancel();
            if (active) {
                dots[i].setScaleX(0.5f);
                dots[i].setScaleY(0.5f);
                dots[i].animate().scaleX(1f).scaleY(1f).setDuration(340)
                        .setInterpolator(Cine.CINEMATIC).start();
            } else {
                dots[i].setScaleX(1f);
                dots[i].setScaleY(1f);
            }
        }
        btnNext.setText(page == TOTAL - 1 ? getString(R.string.start) : getString(R.string.next));
        btnSkip.setVisibility(page == TOTAL - 1 ? View.GONE : View.VISIBLE);
    }

    private void finishOnboarding() {
        getSharedPreferences("quasar_prefs", MODE_PRIVATE)
                .edit().putBoolean("onboarding_done", true).apply();
        startActivity(new Intent(this, MainActivity.class));
        overridePendingTransition(R.anim.fade_in, R.anim.hold);
        finish();
    }

    /** Swipe que sigue al dedo, con parallax entre el slide actual y el vecino. */
    private class SwipeListener implements View.OnTouchListener {
        @Override
        public boolean onTouch(View v, MotionEvent e) {
            int action = e.getActionMasked();
            switch (action) {
                case MotionEvent.ACTION_DOWN:
                    if (animating) return false;
                    activePointer = e.getPointerId(0);
                    downX = e.getX();
                    dragging = false;
                    return true;
                case MotionEvent.ACTION_MOVE: {
                    if (activePointer < 0 || animating) return true;
                    int idx = e.findPointerIndex(activePointer);
                    if (idx < 0) return true;
                    float dx = e.getX(idx) - downX;
                    if (!dragging && Math.abs(dx) > 14) dragging = true;
                    if (!dragging) return true;
                    int w = slidesBox.getWidth();
                    if (w == 0) return true;
                    // resistencia en los bordes
                    if ((dx < 0 && page == TOTAL - 1) || (dx > 0 && page == 0)) dx *= 0.3f;
                    View cur = slides[page];
                    cur.setTranslationX(dx);
                    View nb = neighborFor(dx);
                    if (nb != null) {
                        nb.setVisibility(View.VISIBLE);
                        int dist = dx < 0 ? w : -w;
                        nb.setTranslationX(dist + dx * 0.65f); // parallax: el vecino llega más lento
                    }
                    // parallax sutil del fondo mientras se arrastra
                    stars.setParallax(-page * 90f + dx * 0.12f);
                    return true;
                }
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL: {
                    if (activePointer < 0) return true;
                    int idx = e.findPointerIndex(activePointer);
                    float dx = (idx >= 0 && dragging) ? e.getX(idx) - downX : 0f;
                    activePointer = -1;
                    boolean wasDragging = dragging;
                    dragging = false;
                    if (wasDragging && !animating && action == MotionEvent.ACTION_UP) {
                        settleDrag(dx);
                    } else if (!animating) {
                        slides[page].setTranslationX(0f);
                        stars.setParallax(-page * 90f);
                    }
                    return true;
                }
            }
            return true;
        }
    }
}
