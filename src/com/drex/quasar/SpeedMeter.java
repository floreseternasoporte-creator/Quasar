package com.drex.quasar;

import android.os.SystemClock;

public class SpeedMeter {
    private long windowStart = SystemClock.elapsedRealtime();
    private long windowBytes = 0;
    private double smoothed = 0;

    public synchronized void add(long n) {
        windowBytes += n;
        long now = SystemClock.elapsedRealtime();
        long dt = now - windowStart;
        if (dt >= 400) {
            double inst = (windowBytes * 1000.0 / dt) / (1024.0 * 1024.0);
            smoothed = (smoothed == 0) ? inst : smoothed * 0.55 + inst * 0.45;
            windowBytes = 0;
            windowStart = now;
        }
    }

    public synchronized double getMBs() {
        return smoothed;
    }
}
