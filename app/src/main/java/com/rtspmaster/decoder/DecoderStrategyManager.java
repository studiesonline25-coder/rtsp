package com.rtspmaster.decoder;

import android.content.Context;
import android.opengl.GLSurfaceView;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.util.Log;
import android.view.Surface;
import android.view.SurfaceView;

import com.rtspmaster.forward.StreamForwarder;
import com.rtspmaster.rtsp.NALAssembler;
import com.rtspmaster.rtsp.RTSPClient;

/**
 * Master Orchestrator — Decoder Strategy Manager.
 * 
 * Wires RTSPClient → NALAssembler → MTKDecoderFix pipeline.
 * 
 * The NALAssembler now handles the "Golden Rule" (combining SPS+PPS+IDR),
 * so this class is simplified — it just passes data through.
 */
public final class DecoderStrategyManager {
    private static final String TAG = "StrategyMgr";

    public static final int STRATEGY_VLC = 0;
    public static final int STRATEGY_EXOPLAYER = 1;
    public static final int STRATEGY_MEDIACODEC_GL = 2;
    public static final int STRATEGY_MEDIACODEC_RAW = 3;

    public interface StrategyCallback {
        void onStatusChanged(String status);
        void onDecoderChanged(String decoderName, int strategy);
        void onFpsUpdate(float fps);
        void onError(String error);
        void onFirstFrame();
    }

    private final Context context;
    private final DeviceProfiler profiler;
    private final GreenFrameDetector greenDetector;
    private final StreamHealthMonitor healthMonitor;
    private final StreamForwarder forwarder;
    private StrategyCallback callback;

    private int currentStrategy = -1;
    private String rtspUrl;
    private boolean useSoftwareFallback = false;
    private int lowFpsCount = 0;

    // Pipeline components
    private MTKDecoderFix mtkDecoder;
    private RTSPClient rtspClient;
    private NALAssembler nalAssembler;
    private RTSPGLRenderer glRenderer;

    private SurfaceView surfaceView;
    private GLSurfaceView glSurfaceView;
    private Surface currentSurface;

    private HandlerThread rtspThread;
    private Handler mainHandler = new Handler(Looper.getMainLooper());

    public DecoderStrategyManager(Context context) {
        this.context = context;
        this.profiler = new DeviceProfiler(context);
        this.greenDetector = new GreenFrameDetector();
        this.forwarder = new StreamForwarder();
        this.healthMonitor = new StreamHealthMonitor(new StreamHealthMonitor.HealthCallback() {
            @Override public void onHealthUpdate(float fps, boolean healthy) {
                if (callback != null) callback.onFpsUpdate(fps);
            }
            @Override public void onLowFps() {
                Log.w(TAG, "Low FPS detected. Count: " + (++lowFpsCount));
                if (lowFpsCount >= 3 && !useSoftwareFallback) {
                    Log.e(TAG, "Hardware decoder stalling. Falling back to SOFTWARE.");
                    useSoftwareFallback = true;
                    lowFpsCount = 0;
                }
                mainHandler.post(() -> reconnect());
            }
            @Override public void onTooManyErrors() {
                Log.w(TAG, "Too many errors — restarting stream");
                mainHandler.post(() -> reconnect());
            }
        });
    }

    public void setCallback(StrategyCallback cb) { this.callback = cb; }
    public void setSurfaceView(SurfaceView sv) { this.surfaceView = sv; }
    public void setGLSurfaceView(GLSurfaceView glsv) { this.glSurfaceView = glsv; }
    public DeviceProfiler getProfiler() { return profiler; }
    public StreamForwarder getForwarder() { return forwarder; }
    public int getCurrentStrategy() { return currentStrategy; }

    public int selectInitialStrategy() {
        return STRATEGY_MEDIACODEC_GL;
    }

    public void start(String url, int strategy) {
        this.rtspUrl = url;
        stopCurrent();

        currentStrategy = strategy;
        String name = strategyName(strategy);
        Log.i(TAG, "Starting strategy: " + name + " (Software: " + useSoftwareFallback + ")");
        if (callback != null) {
            callback.onStatusChanged("Connecting...");
            callback.onDecoderChanged(name, strategy);
        }

        healthMonitor.start();

        if (strategy == STRATEGY_MEDIACODEC_GL) {
            startMediaCodecGL(url);
        } else {
            startMediaCodecRaw(url);
        }
    }

    public void stop() {
        stopCurrent();
        healthMonitor.stop();
        greenDetector.stop();
        forwarder.stop();
    }

    public void reconnect() {
        if (rtspUrl != null) {
            Log.i(TAG, "Reconnecting...");
            start(rtspUrl, currentStrategy);
        }
    }

    // ===================== Strategy Implementations =====================

    private boolean isRendererSet = false;

    private void startMediaCodecGL(String url) {
        if (glSurfaceView != null) {
            mainHandler.post(() -> {
                if (surfaceView != null) surfaceView.setVisibility(android.view.View.GONE);
                glSurfaceView.setVisibility(android.view.View.VISIBLE);
            });
        }

        if (glRenderer == null) {
            glRenderer = new RTSPGLRenderer();
        }

        glRenderer.setOnSurfaceReadyListener(decoderSurface -> {
            startPipeline(url, decoderSurface);
        });

        if (glSurfaceView != null && !isRendererSet) {
            glSurfaceView.setEGLContextClientVersion(2);
            glSurfaceView.setRenderer(glRenderer);
            glSurfaceView.setRenderMode(GLSurfaceView.RENDERMODE_WHEN_DIRTY);
            isRendererSet = true;
        }
    }

    private void startMediaCodecRaw(String url) {
        if (glSurfaceView != null) {
            mainHandler.post(() -> {
                glSurfaceView.setVisibility(android.view.View.GONE);
                if (surfaceView != null) surfaceView.setVisibility(android.view.View.VISIBLE);
            });
        }
        startPipeline(url, currentSurface);
    }

    /**
     * The simplified pipeline:
     *   RTSPClient (TCP) → NALAssembler (Golden Rule) → MTKDecoderFix → Surface
     */
    private void startPipeline(String url, Surface decoderSurface) {
        // 1. Create decoder
        mtkDecoder = new MTKDecoderFix(profiler, new MTKDecoderFix.DecoderCallback() {
            @Override public void onFrameRendered() {
                healthMonitor.onFrameRendered();
                if (glSurfaceView != null && currentStrategy == STRATEGY_MEDIACODEC_GL) {
                    glSurfaceView.requestRender();
                }
            }
            @Override public void onDecoderError(Exception e) {
                healthMonitor.onError();
            }
            @Override public void onFormatChanged(int w, int h) {
                Log.i(TAG, "Video format: " + w + "x" + h);
                if (glRenderer != null) glRenderer.updateBufferSize(w, h);
            }
            @Override public void onFirstFrame() {
                if (callback != null) {
                    callback.onStatusChanged("Playing");
                    callback.onFirstFrame();
                }
            }
        });

        // 2. Create NAL assembler with "Golden Rule" combining
        nalAssembler = new NALAssembler((nalWithStartCode, timestamp, nalType) -> {
            // Forward raw NAL data if forwarder is active
            if (forwarder.isRunning()) {
                forwarder.forwardPacket(nalWithStartCode);
            }

            // Feed directly to decoder — NALAssembler already combined SPS+PPS+IDR
            if (mtkDecoder.isReady()) {
                mtkDecoder.feedNalUnit(nalWithStartCode, timestamp);
            }
        });

        // 3. Create RTSP client (TCP Interleaved)
        rtspClient = new RTSPClient(new RTSPClient.RTSPCallback() {
            @Override
            public void onSdpParsed(byte[] sps, byte[] pps, int width, int height) {
                Log.i(TAG, "SDP: SPS=" + (sps != null ? sps.length : 0)
                        + "b PPS=" + (pps != null ? pps.length : 0)
                        + "b  " + width + "x" + height);
                if (sps != null && pps != null) {
                    mtkDecoder.setSpsAndPps(sps, pps);
                }
                if (width > 0 && height > 0) {
                    mtkDecoder.setVideoDimensions(width, height);
                }
                mtkDecoder.setSurface(decoderSurface);
                mtkDecoder.configure(useSoftwareFallback);
            }

            @Override
            public void onVideoNalUnit(byte[] data, int offset, int length, long timestamp) {
                // Raw RTP packet from TCP — pass to NALAssembler for parsing
                nalAssembler.processRtpPacket(data, offset, length);
            }

            @Override
            public void onDisconnected(String reason) {
                Log.w(TAG, "Disconnected: " + reason);
                mainHandler.post(() -> reconnect());
            }
        });

        // 4. Launch RTSP thread
        rtspThread = new HandlerThread("RTSP-Client");
        rtspThread.start();
        new Handler(rtspThread.getLooper()).post(() -> {
            try {
                rtspClient.connect(url);
                rtspClient.receiveLoop();
            } catch (Exception e) {
                Log.e(TAG, "RTSP failed", e);
                mainHandler.post(() -> {
                    if (callback != null) callback.onError("RTSP Error: " + e.getMessage());
                    mainHandler.postDelayed(() -> reconnect(), 5000);
                });
            }
        });
    }

    private void stopCurrent() {
        if (mtkDecoder != null) { mtkDecoder.release(); mtkDecoder = null; }
        if (rtspClient != null) { rtspClient.disconnect(); rtspClient = null; }
        if (nalAssembler != null) { nalAssembler.reset(); nalAssembler = null; }
        if (rtspThread != null) { rtspThread.quitSafely(); rtspThread = null; }
    }

    public void onSurfaceReady(Surface surface) {
        this.currentSurface = surface;
        Log.i(TAG, "Surface ready");
    }

    public void onSurfaceDestroyed() {
        this.currentSurface = null;
    }

    public static String strategyName(int s) {
        switch (s) {
            case STRATEGY_MEDIACODEC_GL: return "MediaCodec+GL";
            case STRATEGY_MEDIACODEC_RAW: return "MediaCodec Raw";
            default: return "Lightweight";
        }
    }
}
