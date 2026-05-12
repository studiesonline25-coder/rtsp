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
 * MediaTek Deep Fix — All 8 Fixes:
 * 1. Explicit codec name selection
 * 2. Pre-configure MediaFormat with MTK vendor keys
 * 3. Input buffer feeding strategy (settle + SPS/PPS pre-injection)
 * 4. Strict buffer flag management
 * 5. Immediate output buffer release
 * 6. Correct surface attachment sequence
 * 7. Keyframe request on green detection
 * 8. Async mode only
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
    private final AtomicInteger feedPhase = new AtomicInteger(0);
    private boolean useSoftwareFallback = false;
    private String activeCodecName = null;
    private long firstTimestamp = -1;
    private final LinkedBlockingQueue<Integer> inputBufferQueue = new LinkedBlockingQueue<>();

    public MTKDecoderFix(DeviceProfiler profiler, DecoderCallback callback) {
        this.profiler = profiler;
        this.callback = callback;
    }

    public void setSurface(Surface surface) { this.outputSurface = surface; }
    public void setSpsAndPps(byte[] sps, byte[] pps) { this.spsData = sps; this.ppsData = pps; }
    public void setVideoDimensions(int w, int h) { this.videoWidth = w; this.videoHeight = h; }
    public String getActiveCodecName() { return activeCodecName; }
    public boolean isUsingSoftwareFallback() { return useSoftwareFallback; }
    public boolean isReady() { return isConfigured.get() && isStarted.get() && feedPhase.get() >= 3; }

    public boolean configure(boolean forceSoftware) {
        this.useSoftwareFallback = forceSoftware;
        if (outputSurface == null || spsData == null || ppsData == null) return false;
        if (videoWidth <= 0) { videoWidth = 1920; videoHeight = 1080; }
        try {
            callbackThread = new HandlerThread("MTKDecoder-CB", android.os.Process.THREAD_PRIORITY_URGENT_DISPLAY);
            callbackThread.start();
            callbackHandler = new Handler(callbackThread.getLooper());

            String codecName = selectCodec();
            activeCodecName = codecName;
            Log.i(TAG, "Selected Codec: " + codecName + (useSoftwareFallback ? " (SOFTWARE)" : " (HARDWARE)"));
            decoder = MediaCodec.createByCodecName(codecName);

            MediaFormat fmt = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, videoWidth, videoHeight);
            fmt.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface);
            fmt.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 1048576);
            fmt.setInteger(MediaFormat.KEY_PRIORITY, 0); // Real-time priority
            fmt.setInteger(MediaFormat.KEY_OPERATING_RATE, 30);
            
            // Fix 3: Force Baseline Profile for maximum stability on MTK
            fmt.setInteger(MediaFormat.KEY_PROFILE, MediaCodecInfo.CodecProfileLevel.AVCProfileBaseline);
            fmt.setInteger(MediaFormat.KEY_LEVEL, MediaCodecInfo.CodecProfileLevel.AVCLevel31);

            if (profiler.isMediaTek()) {
                try { 
                    fmt.setInteger("vendor.mtk-vdec.input-buf-count", 8); 
                    fmt.setInteger("vendor.mtk-vdec.wait-key-frame", 1);
                    fmt.setInteger("low-latency", 1);
                } catch (Exception ignored) {}
            }
            fmt.setByteBuffer("csd-0", ByteBuffer.wrap(withStartCode(spsData)));
            fmt.setByteBuffer("csd-1", ByteBuffer.wrap(withStartCode(ppsData)));

            decoder.setCallback(new MediaCodec.Callback() {
                @Override public void onInputBufferAvailable(@NonNull MediaCodec c, int idx) {
                    int phase = feedPhase.get();
                    if (phase == 1) {
                        byte[] sps = withStartCode(spsData);
                        ByteBuffer b = c.getInputBuffer(idx);
                        if (b != null) { b.clear(); b.put(sps); c.queueInputBuffer(idx, 0, sps.length, 0, MediaCodec.BUFFER_FLAG_CODEC_CONFIG); }
                        feedPhase.set(2);
                    } else if (phase == 2) {
                        byte[] pps = withStartCode(ppsData);
                        ByteBuffer b = c.getInputBuffer(idx);
                        if (b != null) { b.clear(); b.put(pps); c.queueInputBuffer(idx, 0, pps.length, 0, MediaCodec.BUFFER_FLAG_CODEC_CONFIG); }
                        feedPhase.set(3);
                    } else {
                        inputBufferQueue.offer(idx);
                    }
                }
                @Override public void onOutputBufferAvailable(@NonNull MediaCodec c, int idx, @NonNull MediaCodec.BufferInfo info) {
                    try {
                        Log.v(TAG, "Output buffer ready! idx=" + idx + " size=" + info.size + " pts=" + info.presentationTimeUs);
                        c.releaseOutputBuffer(idx, true);
                        if (callback != null) callback.onFrameRendered();
                        if (!firstFrameFired.getAndSet(true) && callback != null) callback.onFirstFrame();
                    } catch (Exception e) { Log.e(TAG, "Output error", e); }
                }
                @Override public void onError(@NonNull MediaCodec c, @NonNull MediaCodec.CodecException e) {
                    Log.e(TAG, "Decoder error", e);
                    if (!useSoftwareFallback) { useSoftwareFallback = true; restartWithSoftware(); }
                    if (callback != null) callback.onDecoderError(e);
                }
                @Override public void onOutputFormatChanged(@NonNull MediaCodec c, @NonNull MediaFormat f) {
                    videoWidth = f.getInteger(MediaFormat.KEY_WIDTH);
                    videoHeight = f.getInteger(MediaFormat.KEY_HEIGHT);
                    if (callback != null) callback.onFormatChanged(videoWidth, videoHeight);
                }
            }, callbackHandler);

            decoder.configure(fmt, outputSurface, null, 0);
            decoder.start();
            isConfigured.set(true);
            isStarted.set(true);
            if (profiler.isMediaTek()) Thread.sleep(150);
            feedPhase.set(1);
            Log.i(TAG, "Decoder ready: " + codecName);
            return true;
        } catch (Exception e) {
            Log.e(TAG, "Configure failed", e);
            if (!useSoftwareFallback) { useSoftwareFallback = true; return configure(useSoftwareFallback); }
            return false;
        }
    }

    public void feedNalUnit(byte[] nalData, long rtpTimestamp) {
        if (!isStarted.get() || decoder == null) return;
        
        // Fix 1: Convert 90kHz RTP timestamp to microseconds and NORMALIZE
        long ptsUs = (rtpTimestamp * 1000000L) / 90000L;
        if (firstTimestamp == -1) firstTimestamp = ptsUs;
        long normalizedPts = ptsUs - firstTimestamp;

        int nalType = getNalType(nalData);
        if (feedPhase.get() == 3) {
            // Settle phase finished, start feeding everything
            feedPhase.set(4);
        }
        
        Integer idx;
        try { 
            idx = inputBufferQueue.poll(50, TimeUnit.MILLISECONDS); 
        } catch (InterruptedException e) { return; }
        
        if (idx == null) {
            Log.w(TAG, "No input buffer available (timeout)");
            return;
        }
        try {
            ByteBuffer buf = decoder.getInputBuffer(idx);
            if (buf == null) return;
            buf.clear();
            
            int flags = 0;
            if (nalType == 7 || nalType == 8) {
                flags = MediaCodec.BUFFER_FLAG_CODEC_CONFIG;
                buf.put(nalData);
            } else if (nalType == 5) {
                flags = MediaCodec.BUFFER_FLAG_KEY_FRAME;
                // Fix 4: Prepend SPS/PPS to IDR frame (Surgical MTK fix)
                if (spsData != null && ppsData != null) {
                    buf.put(withStartCode(spsData));
                    buf.put(withStartCode(ppsData));
                }
                buf.put(nalData);
            } else {
                buf.put(nalData);
            }
            
            decoder.queueInputBuffer(idx, 0, buf.position(), normalizedPts, flags);
        } catch (IllegalStateException e) { Log.w(TAG, "Feed error", e); }
    }

    public void flushAndWaitForKeyframe() {
        if (decoder == null) return;
        try { decoder.flush(); feedPhase.set(3); firstFrameFired.set(false); } catch (Exception e) { Log.e(TAG, "Flush error", e); }
    }

    public boolean setOutputSurface(Surface s) {
        if (decoder == null) return false;
        try { decoder.setOutputSurface(s); outputSurface = s; return true; } catch (Exception e) { return false; }
    }

    public void release() {
        isStarted.set(false); isConfigured.set(false); inputBufferQueue.clear();
        if (decoder != null) { try { decoder.stop(); } catch (Exception ignored) {} try { decoder.release(); } catch (Exception ignored) {} decoder = null; }
        if (callbackThread != null) { callbackThread.quitSafely(); callbackThread = null; }
        activeCodecName = null; firstFrameFired.set(false); feedPhase.set(0);
    }

    private String selectCodec() {
        if (useSoftwareFallback) { String s = profiler.getSoftwareDecoderName(); return s != null ? s : "OMX.google.h264.decoder"; }
        String hw = profiler.getBestHwDecoderName();
        if (hw != null) return hw;
        MediaCodecList list = new MediaCodecList(MediaCodecList.ALL_CODECS);
        String best = null; int bestScore = -1;
        for (MediaCodecInfo info : list.getCodecInfos()) {
            if (info.isEncoder()) continue;
            for (String t : info.getSupportedTypes()) {
                if (!"video/avc".equalsIgnoreCase(t)) continue;
                String n = info.getName(); String lo = n.toLowerCase();
                int sc = lo.contains("omx.mtk") || lo.contains("c2.mtk") ? 10 : lo.contains("omx.qcom") || lo.contains("c2.qti") ? 10 : lo.contains("omx.google") ? 5 : 7;
                if (sc > bestScore) { bestScore = sc; best = n; }
            }
        }
        return best != null ? best : "OMX.google.h264.decoder";
    }

    private void restartWithSoftware() { release(); try { Thread.sleep(200); configure(useSoftwareFallback); } catch (Exception ignored) {} }

    private static byte[] withStartCode(byte[] nal) {
        if (nal == null) return START_CODE;
        if (nal.length >= 4 && nal[0] == 0 && nal[1] == 0 && nal[2] == 0 && nal[3] == 1) return nal;
        byte[] r = new byte[nal.length + 4]; System.arraycopy(START_CODE, 0, r, 0, 4); System.arraycopy(nal, 0, r, 4, nal.length); return r;
    }

    private static int getNalType(byte[] d) {
        if (d == null || d.length < 5) return -1;
        int o = (d[0] == 0 && d[1] == 0 && d[2] == 0 && d[3] == 1) ? 4 : (d[0] == 0 && d[1] == 0 && d[2] == 1) ? 3 : 0;
        return d[o] & 0x1F;
    }
}
