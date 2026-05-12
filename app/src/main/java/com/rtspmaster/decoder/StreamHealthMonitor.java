package com.rtspmaster.decoder;

import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Stage 4 — Stream Health Monitor.
 * Tracks FPS, freeze duration, error count.
 * Triggers reconnect if FPS drops below threshold.
 * Triggers strategy switch if too many errors.
 */
public final class StreamHealthMonitor {
    private static final String TAG = "HealthMonitor";
    private static final int LOW_FPS_THRESHOLD = 1;
    private static final long LOW_FPS_DURATION_MS = 30000;
    private static final long STARTUP_GRACE_MS = 15000;
    private static final int ERROR_THRESHOLD = 3;
    private static final long ERROR_WINDOW_MS = 30000;

    public interface HealthCallback {
        void onHealthUpdate(float fps, boolean healthy);
        void onLowFps();
        void onTooManyErrors();
    }

    private final HealthCallback callback;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final AtomicInteger frameCount = new AtomicInteger(0);
    private final AtomicInteger errorCount = new AtomicInteger(0);
    private long fpsWindowStart;
    private long lowFpsStart = 0;
    private long errorWindowStart;
    private float currentFps = 0;
    private volatile boolean running = false;
    private Thread monitorThread;

    public StreamHealthMonitor(HealthCallback callback) {
        this.callback = callback;
    }

    public void start() {
        running = true;
        long startTime = System.currentTimeMillis();
        fpsWindowStart = startTime;
        errorWindowStart = startTime;
        frameCount.set(0);
        errorCount.set(0);
        lowFpsStart = 0;

        monitorThread = new Thread(() -> {
            while (running) {
                try { Thread.sleep(1000); } catch (InterruptedException e) { break; }
                long now = System.currentTimeMillis();
                long elapsed = now - fpsWindowStart;
                if (elapsed > 0) {
                    currentFps = frameCount.getAndSet(0) * 1000f / elapsed;
                    fpsWindowStart = now;

                    boolean healthy = currentFps >= LOW_FPS_THRESHOLD;
                    mainHandler.post(() -> {
                        if (callback != null) callback.onHealthUpdate(currentFps, healthy);
                    });

                    if (!healthy && (now - startTime > STARTUP_GRACE_MS)) {
                        if (lowFpsStart == 0) lowFpsStart = now;
                        else if (now - lowFpsStart > LOW_FPS_DURATION_MS) {
                            mainHandler.post(() -> { if (callback != null) callback.onLowFps(); });
                            lowFpsStart = now; // Reset to avoid spamming
                        }
                    } else {
                        lowFpsStart = 0;
                    }
                }

                // Error window check
                if (now - errorWindowStart > ERROR_WINDOW_MS) {
                    errorCount.set(0);
                    errorWindowStart = now;
                }
            }
        }, "HealthMonitor");
        monitorThread.setDaemon(true);
        monitorThread.start();
    }

    public void onFrameRendered() {
        frameCount.incrementAndGet();
    }

    public void onError() {
        int count = errorCount.incrementAndGet();
        if (count >= ERROR_THRESHOLD) {
            mainHandler.post(() -> { if (callback != null) callback.onTooManyErrors(); });
            errorCount.set(0);
        }
    }

    public float getCurrentFps() { return currentFps; }

    public void stop() {
        running = false;
        if (monitorThread != null) monitorThread.interrupt();
    }
}
