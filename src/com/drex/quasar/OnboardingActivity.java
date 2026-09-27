package com.drex.quasar;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.view.animation.AccelerateInterpolator;
import android.view.animation.DecelerateInterpolator;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * Quasar 2.0 — onboarding con 4 slides deslizables. Solo se muestra la primera vez.
 */
public class OnboardingActivity extends Activity {

    private static final int TOTAL = 4;
    private int page = 0;
    private View[] slides;
    private ImageView[] dots;
    private TextView btnNext, btnSkip;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_onboarding);

        slides = new View[]{
                findViewById(R.id.slide1), findViewById(R.id.slide2),
                findViewById(R.id.slide3), findViewById(R.id.slide4)};
        btnNext = findViewById(R.id.ob_next);
        btnSkip = findViewById(R.id.ob_skip);

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
            if (page < TOTAL - 1) goTo(page + 1, true);
            else finishOnboarding();
        });
        btnSkip.setOnClickListener(v -> finishOnboarding());

        // deslizar con el dedo
        findViewById(R.id.ob_slides).setOnTouchListener(new SwipeListener());

        refresh();
    }

    private void goTo(int next, boolean forward) {
        if (next < 0 || next >= TOTAL || next == page) return;
        View out = slides[page];
        View in = slides[next];
        int w = findViewById(R.id.ob_slides).getWidth();
        if (w == 0) w = 900;
        final int dist = forward ? w : -w;

        in.setVisibility(View.VISIBLE);
        in.setTranslationX(dist);
        in.animate().translationX(0f).setDuration(340)
                .setInterpolator(new DecelerateInterpolator()).start();
        out.animate().translationX(-dist).setDuration(340)
                .setInterpolator(new AccelerateInterpolator())
                .withEndAction(() -> out.setVisibility(View.GONE)).start();

        page = next;
        refresh();
    }

    private void refresh() {
        for (int i = 0; i < TOTAL; i++) {
            boolean active = i == page;
            dots[i].setImageResource(active ? R.drawable.dot_active : R.drawable.dot_idle);
            if (active) {
                dots[i].setScaleX(0.4f);
                dots[i].setScaleY(0.4f);
                dots[i].animate().scaleX(1f).scaleY(1f).setDuration(280)
                        .setInterpolator(new android.view.animation.OvershootInterpolator(2f)).start();
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

    /** Detector de swipe simple sin dependencias. */
    private class SwipeListener implements View.OnTouchListener {
        private float downX;

        @Override
        public boolean onTouch(View v, android.view.MotionEvent e) {
            switch (e.getAction()) {
                case android.view.MotionEvent.ACTION_DOWN:
                    downX = e.getX();
                    return true;
                case android.view.MotionEvent.ACTION_UP:
                    float dx = e.getX() - downX;
                    if (dx < -120) goTo(page + 1, true);
                    else if (dx > 120) goTo(page - 1, false);
                    return true;
            }
            return false;
        }
    }
}
