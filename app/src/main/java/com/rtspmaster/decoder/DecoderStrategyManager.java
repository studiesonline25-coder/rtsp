package com.rtspmaster.decoder;

import android.content.Context;
import android.opengl.GLSurfaceView;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.util.Log;
import android.view.Surface;
import android.view.SurfaceHolder;
import android.view.SurfaceView;

import com.rtspmaster.forward.StreamForwarder;
import com.rtspmaster.rtsp.NALAssembler;
import com.rtspmaster.rtsp.RTSPClient;

/**
 * Master Orchestrator — Intelligent Decoder Strategy Manager.
 * 
 * Selects the best decoder strategy based on device profile:
 *   STRATEGY_VLC (Tier 1) — LibVLC software decode, safest
 *   STRATEGY_EXOPLAYER (Tier 2) — ExoPlayer Media3 RTSP
 *   STRATEGY_MEDIACODEC_GL (Tier 3a) — Raw MediaCodec + OES shader
 *   STRATEGY_MEDIACODEC_RAW (Tier 3b) — Raw MediaCodec + SurfaceView
 *
 * Auto-detects green frames and falls through strategies.
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

    // Tier 3
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
                Log.w(TAG, "Low FPS detected — triggering reconnect");
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

    /**
     * Select initial strategy. Since we removed heavy libs, 
     * we always use the surgical MediaCodec + GL strategy.
     */
    public int selectInitialStrategy() {
        return STRATEGY_MEDIACODEC_GL;
    }

    /**
     * Start playing with the given strategy.
     */
    public void start(String url, int strategy) {
        this.rtspUrl = url;
        stopCurrent();

        currentStrategy = strategy;
        String name = strategyName(strategy);
        Log.i(TAG, "Starting Lightweight strategy: " + name);
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

        // Always use green detector for hardware
        greenDetector.start((sampleIdx, pct) -> {
            Log.w(TAG, "Green frames detected! Attempting Raw switch...");
            mainHandler.post(() -> start(rtspUrl, STRATEGY_MEDIACODEC_RAW));
        });
    }

    public void stop() {
        stopCurrent();
        healthMonitor.stop();
        greenDetector.stop();
        forwarder.stop();
    }

    public void reconnect() {
        if (rtspUrl != null) {
            start(rtspUrl, currentStrategy);
        }
    }

    // ===================== Strategy Implementations =====================

    private void startMediaCodecGL(String url) {
        if (glSurfaceView != null) {
            mainHandler.post(() -> {
                if (surfaceView != null) surfaceView.setVisibility(android.view.View.GONE);
                glSurfaceView.setVisibility(android.view.View.VISIBLE);
            });
        }

        glRenderer = new RTSPGLRenderer();
        glRenderer.setOnSurfaceReadyListener(decoderSurface -> {
            startRawMediaCodec(url, decoderSurface);
        });

        if (glSurfaceView != null) {
            glSurfaceView.setEGLContextClientVersion(2);
            glSurfaceView.setRenderer(glRenderer);
            glSurfaceView.setRenderMode(GLSurfaceView.RENDERMODE_WHEN_DIRTY);
        }
    }

    private void startMediaCodecRaw(String url) {
        if (glSurfaceView != null) {
            mainHandler.post(() -> {
                glSurfaceView.setVisibility(android.view.View.GONE);
                if (surfaceView != null) surfaceView.setVisibility(android.view.View.VISIBLE);
            });
        }
        startRawMediaCodec(url, currentSurface);
    }

    private void startRawMediaCodec(String url, Surface decoderSurface) {
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
                Log.i(TAG, "Video: " + w + "x" + h);
            }
            @Override public void onFirstFrame() {
                if (callback != null) {
                    callback.onStatusChanged("Playing (Lightweight)");
                    callback.onFirstFrame();
                }
            }
        });

        nalAssembler = new NALAssembler((nalWithStartCode, timestamp, nalType) -> {
            if (forwarder.isRunning()) {
                forwarder.forwardPacket(nalWithStartCode);
            }

            if (nalType == 7 || nalType == 8) {
                byte[] raw = new byte[nalWithStartCode.length - 4];
                System.arraycopy(nalWithStartCode, 4, raw, 0, raw.length);
                if (nalType == 7) mtkDecoder.setSpsAndPps(raw, null);
            }
            if (mtkDecoder.isReady()) {
                mtkDecoder.feedNalUnit(nalWithStartCode, timestamp);
            }
        });

        rtspClient = new RTSPClient(new RTSPClient.RTSPCallback() {
            @Override
            public void onSdpParsed(byte[] sps, byte[] pps, int width, int height) {
                mtkDecoder.setSpsAndPps(sps, pps);
                mtkDecoder.setVideoDimensions(width, height);
                mtkDecoder.setSurface(decoderSurface);
                mtkDecoder.configure();
            }

            @Override
            public void onRtpPacket(byte[] data, int offset, int length, int channel) {
                nalAssembler.processRtpPacket(data, offset, length);
            }

            @Override
            public void onDisconnected(String reason) {
                mainHandler.post(() -> reconnect());
            }
        });

        rtspThread = new HandlerThread("RTSP-Client");
        rtspThread.start();
        new Handler(rtspThread.getLooper()).post(() -> {
            try {
                rtspClient.connect(url);
                rtspClient.receiveLoop();
            } catch (Exception e) {
                Log.e(TAG, "RTSP failed", e);
                mainHandler.post(() -> reconnect());
            }
        });
    }

    private void stopCurrent() {
        if (mtkDecoder != null) { mtkDecoder.release(); mtkDecoder = null; }
        if (rtspClient != null) { rtspClient.disconnect(); rtspClient = null; }
        if (nalAssembler != null) { nalAssembler.reset(); nalAssembler = null; }
        if (glRenderer != null) { glRenderer.release(); glRenderer = null; }
        if (rtspThread != null) { rtspThread.quitSafely(); rtspThread = null; }
    }

    /** Called from SurfaceHolder.Callback.surfaceChanged */
    public void onSurfaceReady(Surface surface) {
        this.currentSurface = surface;
        Log.i(TAG, "Surface ready");
    }

    public void onSurfaceDestroyed() {
        this.currentSurface = null;
    }

    public static String strategyName(int s) {
        switch (s) {
            case STRATEGY_MEDIACODEC_GL: return "MediaCodec+GL (Light)";
            case STRATEGY_MEDIACODEC_RAW: return "MediaCodec Raw";
            default: return "Lightweight";
        }
    }
}
