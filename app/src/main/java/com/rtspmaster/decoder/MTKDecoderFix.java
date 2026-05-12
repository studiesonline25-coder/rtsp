package com.rtspmaster.decoder;

import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaCodecList;
import android.media.MediaFormat;
import android.os.Handler;
import android.os.HandlerThread;
import android.util.Log;
import android.view.Surface;
import androidx.annotation.NonNull;
import java.nio.ByteBuffer;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Clean MediaCodec Decoder — No hacks, no double-injection.
 * 
 * Architecture:
 *   - SPS/PPS are provided via MediaFormat csd-0/csd-1 at configure time
 *   - NAL units arrive pre-combined from NALAssembler (SPS+PPS+IDR in one buffer)
 *   - The decoder just feeds what it receives — no manipulation needed
 *   - Async callback mode for non-blocking operation
 */
public final class MTKDecoderFix {
    private static final String TAG = "MTKDecoderFix";
    private static final byte[] START_CODE = {0, 0, 0, 1};

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
    private HandlerThread callbackThread;
    private Handler callbackHandler;
    private final AtomicBoolean isConfigured = new AtomicBoolean(false);
    private final AtomicBoolean isStarted = new AtomicBoolean(false);
    private final AtomicBoolean firstFrameFired = new AtomicBoolean(false);
    private boolean useSoftwareFallback = false;
    private String activeCodecName = null;
    private long firstTimestamp = -1;
    private final LinkedBlockingQueue<Integer> inputBufferQueue = new LinkedBlockingQueue<>();

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
    public boolean isReady() { return isConfigured.get() && isStarted.get(); }

    public boolean configure(boolean forceSoftware) {
        this.useSoftwareFallback = forceSoftware;
        if (outputSurface == null || !outputSurface.isValid()) {
            Log.e(TAG, "Surface not valid!");
            return false;
        }
        // SPS/PPS may arrive later via in-stream NAL units
        if (videoWidth <= 0) { videoWidth = 720; videoHeight = 1280; }

        Log.i(TAG, "Configuring: " + videoWidth + "x" + videoHeight
                + " | Software: " + useSoftwareFallback
                + " | Hardware: " + android.os.Build.HARDWARE);

        try {
            callbackThread = new HandlerThread("MTKDecoder-CB", android.os.Process.THREAD_PRIORITY_URGENT_DISPLAY);
            callbackThread.start();
            callbackHandler = new Handler(callbackThread.getLooper());

            String codecName = selectCodec();
            activeCodecName = codecName;
            Log.i(TAG, "Selected Codec: " + codecName + (useSoftwareFallback ? " (SOFTWARE)" : " (HARDWARE)"));
            decoder = MediaCodec.createByCodecName(codecName);

            MediaFormat fmt = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, videoWidth, videoHeight);
            fmt.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 1048576); // 1MB
            fmt.setInteger(MediaFormat.KEY_PRIORITY, 0); // Real-time

            // Provide SPS/PPS via csd if available at configure time
            if (spsData != null && ppsData != null) {
                fmt.setByteBuffer("csd-0", ByteBuffer.wrap(withStartCode(spsData)));
                fmt.setByteBuffer("csd-1", ByteBuffer.wrap(withStartCode(ppsData)));
                Log.i(TAG, "CSD provided: SPS=" + spsData.length + "b PPS=" + ppsData.length + "b");
            }

            // MTK-specific optimizations
            if (profiler.isMediaTek()) {
                try {
                    fmt.setInteger("vendor.mtk-vdec.input-buf-count", 8);
                    fmt.setInteger("low-latency", 1);
                } catch (Exception ignored) {}
            }

            decoder.setCallback(new MediaCodec.Callback() {
                @Override
                public void onInputBufferAvailable(@NonNull MediaCodec c, int idx) {
                    inputBufferQueue.offer(idx);
                }

                @Override
                public void onOutputBufferAvailable(@NonNull MediaCodec c, int idx, @NonNull MediaCodec.BufferInfo info) {
                    try {
                        c.releaseOutputBuffer(idx, true);
                        if (callback != null) callback.onFrameRendered();
                        if (!firstFrameFired.getAndSet(true) && callback != null) {
                            Log.i(TAG, "★ FIRST FRAME RENDERED ★");
                            callback.onFirstFrame();
                        }
                    } catch (Exception e) {
                        Log.e(TAG, "Output error", e);
                    }
                }

                @Override
                public void onError(@NonNull MediaCodec c, @NonNull MediaCodec.CodecException e) {
                    Log.e(TAG, "Decoder error: " + e.getDiagnosticInfo(), e);
                    if (!useSoftwareFallback) {
                        useSoftwareFallback = true;
                        restartWithSoftware();
                    }
                    if (callback != null) callback.onDecoderError(e);
                }

                @Override
                public void onOutputFormatChanged(@NonNull MediaCodec c, @NonNull MediaFormat f) {
                    int w = f.getInteger(MediaFormat.KEY_WIDTH);
                    int h = f.getInteger(MediaFormat.KEY_HEIGHT);
                    int stride = f.containsKey(MediaFormat.KEY_STRIDE) ? f.getInteger(MediaFormat.KEY_STRIDE) : w;
                    int sliceH = f.containsKey(MediaFormat.KEY_SLICE_HEIGHT) ? f.getInteger(MediaFormat.KEY_SLICE_HEIGHT) : h;
                    Log.i(TAG, "Format Changed: " + w + "x" + h + " (Stride: " + stride + "x" + sliceH + ")");
                    if (callback != null) callback.onFormatChanged(stride, sliceH);
                }
            }, callbackHandler);

            decoder.configure(fmt, outputSurface, null, 0);
            decoder.start();
            isConfigured.set(true);
            isStarted.set(true);

            // Brief settle time for MTK hardware
            if (profiler.isMediaTek()) Thread.sleep(100);

            Log.i(TAG, "Decoder ready: " + codecName);
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
     * Feed a complete NAL unit to the decoder.
     * 
     * The NAL unit should already include start codes (00 00 00 01).
     * Combined SPS+PPS+IDR buffers are handled natively by MediaCodec.
     */
    public void feedNalUnit(byte[] nalData, long rtpTimestamp) {
        if (!isStarted.get() || decoder == null) return;

        // Convert 90kHz RTP timestamp to microseconds and normalize
        long ptsUs = (rtpTimestamp * 1000000L) / 90000L;
        if (firstTimestamp == -1) firstTimestamp = ptsUs;
        long normalizedPts = ptsUs - firstTimestamp;

        int nalType = getNalType(nalData);

        Integer idx;
        try {
            idx = inputBufferQueue.poll(50, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) { return; }

        if (idx == null) {
            return; // No buffer available — drop frame silently
        }

        try {
            ByteBuffer buf = decoder.getInputBuffer(idx);
            if (buf == null) return;
            buf.clear();
            buf.put(nalData);

            int flags = 0;
            if (nalType == 7 || nalType == 8) {
                flags = MediaCodec.BUFFER_FLAG_CODEC_CONFIG;
            } else if (nalType == 5) {
                flags = MediaCodec.BUFFER_FLAG_KEY_FRAME;
            }

            decoder.queueInputBuffer(idx, 0, buf.position(), normalizedPts, flags);
        } catch (IllegalStateException e) {
            Log.w(TAG, "Feed error", e);
        }
    }

    public void release() {
        isStarted.set(false);
        isConfigured.set(false);
        inputBufferQueue.clear();
        if (decoder != null) {
            try { decoder.stop(); } catch (Exception ignored) {}
            try { decoder.release(); } catch (Exception ignored) {}
            decoder = null;
        }
        if (callbackThread != null) {
            callbackThread.quitSafely();
            callbackThread = null;
        }
        activeCodecName = null;
        firstFrameFired.set(false);
        firstTimestamp = -1;
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

    private static byte[] withStartCode(byte[] nal) {
        if (nal == null) return START_CODE;
        if (nal.length >= 4 && nal[0] == 0 && nal[1] == 0 && nal[2] == 0 && nal[3] == 1) return nal;
        byte[] r = new byte[nal.length + 4];
        System.arraycopy(START_CODE, 0, r, 0, 4);
        System.arraycopy(nal, 0, r, 4, nal.length);
        return r;
    }

    private static int getNalType(byte[] d) {
        if (d == null || d.length < 5) return -1;
        int o = (d[0] == 0 && d[1] == 0 && d[2] == 0 && d[3] == 1) ? 4
              : (d[0] == 0 && d[1] == 0 && d[2] == 1) ? 3 : 0;
        return d[o] & 0x1F;
    }
}
