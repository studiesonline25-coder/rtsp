package com.rtspmaster.decoder;

import android.graphics.Bitmap;
import android.graphics.PixelFormat;
import android.hardware.HardwareBuffer;
import android.media.Image;
import android.media.ImageReader;
import android.os.Handler;
import android.os.HandlerThread;
import android.util.Log;
import android.view.Surface;

/**
 * Stage 3 — Green Frame Detector
 *
 * Samples decoded frames via ImageReader and analyzes pixel color distribution.
 * If >60% of sampled pixels have a dominant Green channel with R,B below threshold,
 * it triggers a strategy switch callback.
 *
 * This runs as a lightweight sampling pipeline — NOT on every frame.
 */
public final class GreenFrameDetector {

    private static final String TAG = "GreenFrameDetector";
    private static final int SAMPLE_WIDTH = 64;   // Downsample for speed
    private static final int SAMPLE_HEIGHT = 64;
    private static final int MAX_SAMPLES = 5;      // Check 5 frames over 10 seconds
    private static final long SAMPLE_INTERVAL_MS = 2000; // Every 2 seconds
    private static final float GREEN_THRESHOLD = 0.60f;  // 60% green pixels = green frame
    private static final int GREEN_CHANNEL_MIN = 100;
    private static final int RB_CHANNEL_MAX = 50;

    public interface OnGreenDetectedListener {
        void onGreenFrameDetected(int sampleIndex, float greenPercentage);
    }

    private ImageReader imageReader;
    private Surface samplerSurface;
    private HandlerThread handlerThread;
    private Handler handler;
    private OnGreenDetectedListener listener;
    private int sampleCount = 0;
    private boolean detected = false;
    private boolean active = false;

    /**
     * Start the green frame detector.
     * Returns a Surface that should be used as a SECONDARY output alongside
     * the display surface (via MediaCodec.setOutputSurface or parallel pipeline).
     *
     * For practical use: call analyzeFrame() manually with a captured Bitmap
     * if you cannot create a parallel ImageReader surface.
     */
    public void start(OnGreenDetectedListener listener) {
        this.listener = listener;
        this.sampleCount = 0;
        this.detected = false;
        this.active = true;

        handlerThread = new HandlerThread("GreenFrameDetector");
        handlerThread.start();
        handler = new Handler(handlerThread.getLooper());

        try {
            imageReader = ImageReader.newInstance(
                    SAMPLE_WIDTH, SAMPLE_HEIGHT,
                    PixelFormat.RGBA_8888, 2);
            imageReader.setOnImageAvailableListener(reader -> {
                if (!active || detected) return;
                Image image = null;
                try {
                    image = reader.acquireLatestImage();
                    if (image != null) {
                        boolean isGreen = analyzeImage(image);
                        sampleCount++;
                        if (isGreen) {
                            detected = true;
                        }
                        if (sampleCount >= MAX_SAMPLES && !detected) {
                            Log.i(TAG, "All " + MAX_SAMPLES + " samples clean — no green detected");
                            active = false;
                        }
                    }
                } catch (Exception e) {
                    Log.e(TAG, "Error analyzing frame", e);
                } finally {
                    if (image != null) image.close();
                }
            }, handler);

            samplerSurface = imageReader.getSurface();
            Log.i(TAG, "Green frame detector started — will sample " + MAX_SAMPLES + " frames");
        } catch (Exception e) {
            Log.e(TAG, "Failed to create ImageReader for sampling", e);
        }
    }

    /**
     * Analyze a Bitmap directly (alternative to ImageReader path).
     * Call this with a frame grabbed from the decoder output.
     */
    public boolean analyzeFrame(Bitmap bitmap) {
        if (!active || detected || bitmap == null) return false;

        Bitmap scaled = Bitmap.createScaledBitmap(bitmap, SAMPLE_WIDTH, SAMPLE_HEIGHT, false);
        int totalPixels = SAMPLE_WIDTH * SAMPLE_HEIGHT;
        int greenPixels = 0;

        int[] pixels = new int[totalPixels];
        scaled.getPixels(pixels, 0, SAMPLE_WIDTH, 0, 0, SAMPLE_WIDTH, SAMPLE_HEIGHT);
        if (scaled != bitmap) scaled.recycle();

        for (int pixel : pixels) {
            int r = (pixel >> 16) & 0xFF;
            int g = (pixel >> 8) & 0xFF;
            int b = pixel & 0xFF;

            if (g > GREEN_CHANNEL_MIN && r < RB_CHANNEL_MAX && b < RB_CHANNEL_MAX) {
                greenPixels++;
            }
        }

        float greenPct = (float) greenPixels / totalPixels;
        sampleCount++;

        Log.d(TAG, "Sample " + sampleCount + ": green=" + (int)(greenPct * 100) + "%"
                + " (" + greenPixels + "/" + totalPixels + ")");

        if (greenPct > GREEN_THRESHOLD) {
            detected = true;
            Log.w(TAG, "GREEN FRAME DETECTED! " + (int)(greenPct * 100) + "% green pixels");
            if (listener != null) {
                listener.onGreenFrameDetected(sampleCount, greenPct);
            }
            return true;
        }

        if (sampleCount >= MAX_SAMPLES) {
            Log.i(TAG, "All samples clean");
            active = false;
        }
        return false;
    }

    private boolean analyzeImage(Image image) {
        try {
            Image.Plane[] planes = image.getPlanes();
            if (planes.length == 0) return false;

            java.nio.ByteBuffer buffer = planes[0].getBuffer();
            int rowStride = planes[0].getRowStride();
            int pixelStride = planes[0].getPixelStride();
            int width = image.getWidth();
            int height = image.getHeight();

            int totalPixels = 0;
            int greenPixels = 0;

            // Sample every 4th pixel for speed
            for (int y = 0; y < height; y += 2) {
                for (int x = 0; x < width; x += 2) {
                    int offset = y * rowStride + x * pixelStride;
                    if (offset + 3 >= buffer.capacity()) continue;

                    int r = buffer.get(offset) & 0xFF;
                    int g = buffer.get(offset + 1) & 0xFF;
                    int b = buffer.get(offset + 2) & 0xFF;
                    totalPixels++;

                    if (g > GREEN_CHANNEL_MIN && r < RB_CHANNEL_MAX && b < RB_CHANNEL_MAX) {
                        greenPixels++;
                    }
                }
            }

            if (totalPixels == 0) return false;
            float greenPct = (float) greenPixels / totalPixels;
            sampleCount++;

            Log.d(TAG, "ImageReader sample " + sampleCount + ": green=" + (int)(greenPct * 100) + "%");

            if (greenPct > GREEN_THRESHOLD) {
                detected = true;
                Log.w(TAG, "GREEN FRAME DETECTED via ImageReader!");
                if (listener != null) {
                    listener.onGreenFrameDetected(sampleCount, greenPct);
                }
                return true;
            }
        } catch (Exception e) {
            Log.e(TAG, "Error in image analysis", e);
        }
        return false;
    }

    /** Get the sampler Surface for parallel output (if ImageReader mode). */
    public Surface getSamplerSurface() {
        return samplerSurface;
    }

    public boolean isActive() {
        return active && !detected;
    }

    public boolean wasGreenDetected() {
        return detected;
    }

    public void stop() {
        active = false;
        if (imageReader != null) {
            try { imageReader.close(); } catch (Exception ignored) {}
            imageReader = null;
        }
        samplerSurface = null;
        if (handlerThread != null) {
            handlerThread.quitSafely();
            handlerThread = null;
        }
        handler = null;
    }

    public void reset() {
        stop();
        detected = false;
        sampleCount = 0;
    }
}
