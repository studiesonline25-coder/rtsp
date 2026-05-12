package com.rtspmaster.decoder;

import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaCodecList;
import android.media.MediaFormat;
import android.os.Build;
import android.os.Process;
import android.util.Log;
import android.view.Surface;
import java.nio.ByteBuffer;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Production-grade MediaCodec Decoder — Synchronous mode.
 *
 * Modeled after alexvas VideoDecodeThread.kt:
 *   - Uses SYNCHRONOUS dequeueInputBuffer/dequeueOutputBuffer (NOT async callbacks)
 *   - Combined SPS+PPS+IDR buffers are flagged as KEY_FRAME (not CODEC_CONFIG)
 *   - After queuing a keyframe, calls dequeueOutputBuffer multiple times
 *     to handle both the format-change and the decoded frame
 *   - Runs on a dedicated thread with VIDEO priority
 */
public final class MTKDecoderFix {
    private static final String TAG = "MTKDecoderFix";
    private static final byte[] START_CODE = {0, 0, 0, 1};

    private static final long DEQUEUE_INPUT_TIMEOUT_US = 500_000;  // 500ms
    private static final long DEQUEUE_OUTPUT_TIMEOUT_US = 100_000; // 100ms

    public interface DecoderCallback {
        void onFrameRendered();
        void onDecoderError(Exception e);
        void onFormatChanged(int width, int height);
        void onFirstFrame();
    }

    private MediaCodec decoder;
    private final DeviceProfiler profiler;
    private final DecoderCallback callback;
    private Surface outputSurface;
    private byte[] spsData, ppsData;
    private int videoWidth, videoHeight;
    private final AtomicBoolean isConfigured = new AtomicBoolean(false);
    private final AtomicBoolean isRunning = new AtomicBoolean(false);
    private boolean firstFrameRendered = false;
    private boolean useSoftwareFallback = false;
    private String activeCodecName = null;

    // Frame queue: NAL units pushed by RTSP thread, consumed by decoder thread
    private final ArrayBlockingQueue<FrameData> frameQueue = new ArrayBlockingQueue<>(120);

    private Thread decoderThread;

    private static class FrameData {
        final byte[] data;
        final int offset;
        final int length;
        final long timestampMs;
        final boolean isKeyframe;

        FrameData(byte[] data, int offset, int length, long timestampMs, boolean isKeyframe) {
            this.data = data;
            this.offset = offset;
            this.length = length;
            this.timestampMs = timestampMs;
            this.isKeyframe = isKeyframe;
        }
    }

    public MTKDecoderFix(DeviceProfiler profiler, DecoderCallback callback) {
        this.profiler = profiler;
        this.callback = callback;
    }

    public void setSurface(Surface surface) { this.outputSurface = surface; }
    public void setSpsAndPps(byte[] sps, byte[] pps) {
        if (sps != null) this.spsData = sps;
        if (pps != null) this.ppsData = pps;
    }
    public void setVideoDimensions(int w, int h) { this.videoWidth = w; this.videoHeight = h; }
    public String getActiveCodecName() { return activeCodecName; }
    public boolean isUsingSoftwareFallback() { return useSoftwareFallback; }
    public boolean isReady() { return isConfigured.get() && isRunning.get(); }

    /**
     * Configure and start the decoder. Launches the synchronous decode thread.
     */
    public boolean configure(boolean forceSoftware) {
        this.useSoftwareFallback = forceSoftware;
        if (outputSurface == null || !outputSurface.isValid()) {
            Log.e(TAG, "Surface not valid!");
            return false;
        }
        if (videoWidth <= 0) { videoWidth = 720; videoHeight = 1280; }

        Log.i(TAG, "Configuring: " + videoWidth + "x" + videoHeight
                + " | Software: " + useSoftwareFallback
                + " | Hardware: " + Build.HARDWARE);

        try {
            String codecName = selectCodec();
            activeCodecName = codecName;
            Log.i(TAG, "Selected Codec: " + codecName + (useSoftwareFallback ? " (SOFTWARE)" : " (HARDWARE)"));

            decoder = MediaCodec.createByCodecName(codecName);

            MediaFormat fmt = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, videoWidth, videoHeight);
            fmt.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 1048576); // 1MB
            fmt.setInteger(MediaFormat.KEY_ROTATION, 0);

            // Configure and start
            decoder.configure(fmt, outputSurface, null, 0);
            decoder.start();
            isConfigured.set(true);

            Log.i(TAG, "Decoder ready: " + codecName);

            // Start the synchronous decode loop on a dedicated thread
            isRunning.set(true);
            decoderThread = new Thread(this::decodeLoop, "VideoDecoder");
            decoderThread.start();

            return true;
        } catch (Exception e) {
            Log.e(TAG, "Configure failed", e);
            if (!useSoftwareFallback) {
                useSoftwareFallback = true;
                return configure(true);
            }
            return false;
        }
    }

    /**
     * Feed a complete NAL unit to the decoder via the frame queue.
     * Called from the RTSP receive thread.
     *
     * The NAL unit should include start codes (00 00 00 01).
     * Combined SPS+PPS+IDR buffers are correctly identified as keyframes.
     */
    public void feedNalUnit(byte[] nalData, long rtpTimestamp) {
        if (!isRunning.get()) return;

        long timestampMs = (rtpTimestamp * 1000L) / 90000L;

        // Detect if this is a keyframe (IDR).
        // For combined SPS+PPS+IDR buffers, we scan for the IDR NAL within.
        boolean isKeyframe = containsIdr(nalData);

        FrameData frame = new FrameData(nalData, 0, nalData.length, timestampMs, isKeyframe);
        if (!frameQueue.offer(frame)) {
            // Queue full — drop oldest frame
            frameQueue.poll();
            frameQueue.offer(frame);
        }
    }

    /**
     * THE SYNCHRONOUS DECODE LOOP — Modeled after alexvas VideoDecodeThread.run()
     *
     * This is the critical architectural difference. The reference uses synchronous
     * dequeueInputBuffer/dequeueOutputBuffer in a loop, NOT async callbacks.
     *
     * After queuing a combined SPS+PPS+IDR buffer with BUFFER_FLAG_KEY_FRAME,
     * the loop calls dequeueOutputBuffer multiple times:
     *   1st call → INFO_OUTPUT_FORMAT_CHANGED (SPS/PPS processed)
     *   2nd call → Frame index (IDR frame decoded and ready to render)
     */
    private void decodeLoop() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            Process.setThreadPriority(Process.THREAD_PRIORITY_VIDEO);
        } else {
            Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_DISPLAY);
        }

        MediaCodec.BufferInfo bufferInfo = new MediaCodec.BufferInfo();
        long firstTimestamp = -1;

        Log.i(TAG, "Decode loop started");

        while (isRunning.get()) {
            try {
                // === INPUT: Get a frame first, THEN a buffer ===
                // This prevents leaking input buffers if the queue is empty
                FrameData frame = frameQueue.poll(20, TimeUnit.MILLISECONDS);
                if (frame != null) {
                    int inIndex = decoder.dequeueInputBuffer(DEQUEUE_INPUT_TIMEOUT_US);
                    if (inIndex >= 0) {
                        ByteBuffer inputBuffer = decoder.getInputBuffer(inIndex);
                        if (inputBuffer != null) {
                            inputBuffer.clear();
                            
                            // Normalize timestamp
                            if (firstTimestamp == -1) firstTimestamp = frame.timestampMs;
                            long pts = (frame.timestampMs - firstTimestamp) * 1000;

                            inputBuffer.put(frame.data, frame.offset, frame.length);

                            int flags = frame.isKeyframe ? MediaCodec.BUFFER_FLAG_KEY_FRAME : 0;
                            decoder.queueInputBuffer(inIndex, 0, frame.length, pts, flags);
                        }
                    } else {
                        // Could not get a buffer — put the frame back (or drop if too old)
                        frameQueue.offer(frame);
                    }
                }

                // === OUTPUT: Drain all available output buffers ===
                // After a combined SPS+PPS+IDR, the decoder may produce:
                //   1. INFO_OUTPUT_FORMAT_CHANGED (processes SPS/PPS)
                //   2. A decoded frame (the IDR)
                // We loop to handle both events.
                boolean frameAlreadyDequeued = false;
                int outIndex;
                do {
                    long timeout = frameAlreadyDequeued ? 0L : DEQUEUE_OUTPUT_TIMEOUT_US;
                    outIndex = decoder.dequeueOutputBuffer(bufferInfo, timeout);

                    switch (outIndex) {
                        case MediaCodec.INFO_OUTPUT_FORMAT_CHANGED:
                        case MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED:
                            MediaFormat newFormat = decoder.getOutputFormat();
                            int w = newFormat.getInteger(MediaFormat.KEY_WIDTH);
                            int h = newFormat.getInteger(MediaFormat.KEY_HEIGHT);
                            // Use crop parameters for accurate dimensions (Samsung fix)
                            if (newFormat.containsKey("crop-right") && newFormat.containsKey("crop-left")) {
                                w = newFormat.getInteger("crop-right") - newFormat.getInteger("crop-left") + 1;
                            }
                            if (newFormat.containsKey("crop-bottom") && newFormat.containsKey("crop-top")) {
                                h = newFormat.getInteger("crop-bottom") - newFormat.getInteger("crop-top") + 1;
                                h = h / 16 * 16; // Align to 16 (fixes 1088→1080)
                            }
                            Log.i(TAG, "Format Changed: " + w + "x" + h);
                            if (callback != null) callback.onFormatChanged(w, h);
                            frameAlreadyDequeued = true;
                            break;

                        case MediaCodec.INFO_TRY_AGAIN_LATER:
                            frameAlreadyDequeued = true;
                            break;

                        default:
                            if (outIndex >= 0) {
                                boolean render = bufferInfo.size != 0;
                                decoder.releaseOutputBuffer(outIndex, render);
                                if (render) {
                                    if (callback != null) callback.onFrameRendered();
                                    if (!firstFrameRendered) {
                                        firstFrameRendered = true;
                                        Log.i(TAG, "★ FIRST FRAME RENDERED ★");
                                        if (callback != null) callback.onFirstFrame();
                                    }
                                }
                                frameAlreadyDequeued = false;
                            }
                            break;
                    }
                // Keep draining until we get TRY_AGAIN_LATER
                } while (outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED
                      || outIndex == MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED);

            } catch (MediaCodec.CodecException e) {
                Log.e(TAG, "Codec error: " + e.getDiagnosticInfo());
                if (e.isRecoverable()) {
                    Log.i(TAG, "Recoverable error, resetting decoder...");
                    try {
                        decoder.stop();
                        MediaFormat fmt = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, videoWidth, videoHeight);
                        fmt.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 1048576);
                        decoder.configure(fmt, outputSurface, null, 0);
                        decoder.start();
                    } catch (Exception e2) {
                        Log.e(TAG, "Recovery failed", e2);
                        break;
                    }
                } else {
                    if (callback != null) callback.onDecoderError(e);
                    if (!useSoftwareFallback) {
                        useSoftwareFallback = true;
                        restartWithSoftware();
                        return;
                    }
                    break;
                }
            } catch (IllegalStateException e) {
                Log.e(TAG, "Decoder error: " + e.getMessage());
                if (callback != null) callback.onDecoderError(e);
                // Try software fallback
                if (!useSoftwareFallback) {
                    useSoftwareFallback = true;
                    restartWithSoftware();
                    return;
                }
                break;
            } catch (InterruptedException e) {
                // Expected on shutdown
                break;
            }
        }

        Log.i(TAG, "Decode loop ended");
    }

    public void release() {
        isRunning.set(false);
        isConfigured.set(false);
        frameQueue.clear();
        if (decoderThread != null) {
            decoderThread.interrupt();
            try { decoderThread.join(2000); } catch (Exception ignored) {}
            decoderThread = null;
        }
        if (decoder != null) {
            try { decoder.stop(); } catch (Exception ignored) {}
            try { decoder.release(); } catch (Exception ignored) {}
            decoder = null;
        }
        activeCodecName = null;
        firstFrameRendered = false;
    }

    /**
     * Check if a NAL buffer contains an IDR slice (type 5).
     * For combined SPS+PPS+IDR buffers, we scan through start codes.
     */
    private static boolean containsIdr(byte[] data) {
        if (data == null || data.length < 5) return false;
        for (int i = 0; i < data.length - 4; i++) {
            if (data[i] == 0 && data[i+1] == 0 && data[i+2] == 0 && data[i+3] == 1) {
                int nalType = data[i+4] & 0x1F;
                if (nalType == 5) return true; // IDR
            }
        }
        return false;
    }

    private String selectCodec() {
        if (useSoftwareFallback) {
            String s = profiler.getSoftwareDecoderName();
            return s != null ? s : "OMX.google.h264.decoder";
        }
        String hw = profiler.getBestHwDecoderName();
        if (hw != null) return hw;

        MediaCodecList list = new MediaCodecList(MediaCodecList.ALL_CODECS);
        String best = null;
        int bestScore = -1;
        for (MediaCodecInfo info : list.getCodecInfos()) {
            if (info.isEncoder()) continue;
            for (String t : info.getSupportedTypes()) {
                if (!"video/avc".equalsIgnoreCase(t)) continue;
                String n = info.getName();
                String lo = n.toLowerCase();
                int sc = lo.contains("omx.mtk") || lo.contains("c2.mtk") ? 10
                        : lo.contains("omx.qcom") || lo.contains("c2.qti") ? 10
                        : lo.contains("omx.google") ? 5 : 7;
                if (sc > bestScore) { bestScore = sc; best = n; }
            }
        }
        return best != null ? best : "OMX.google.h264.decoder";
    }

    private void restartWithSoftware() {
        release();
        try { Thread.sleep(200); configure(true); } catch (Exception ignored) {}
    }
}
