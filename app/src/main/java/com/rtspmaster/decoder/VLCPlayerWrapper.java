package com.rtspmaster.decoder;

import android.content.Context;
import android.util.Log;
import android.view.SurfaceView;
import org.videolan.libvlc.LibVLC;
import org.videolan.libvlc.Media;
import org.videolan.libvlc.MediaPlayer;
import org.videolan.libvlc.interfaces.IVLCVout;
import java.util.ArrayList;

/**
 * Tier 1 — LibVLC Player Wrapper (Most Compatible).
 * Uses software decoding via --avcodec-hw=none to avoid all hardware color issues.
 * Safest option, works on every device including MediaTek.
 */
public final class VLCPlayerWrapper {
    private static final String TAG = "VLCPlayer";

    public interface VLCCallback {
        void onPlaying();
        void onError(String message);
        void onStopped();
        void onBuffering(float percent);
    }

    private LibVLC libVLC;
    private MediaPlayer mediaPlayer;
    private final Context context;
    private VLCCallback callback;
    private SurfaceView surfaceView;
    private boolean isAttached = false;
    private int reconnectAttempts = 0;
    private static final int MAX_RECONNECT = 5;
    private String currentUrl;

    public VLCPlayerWrapper(Context context) {
        this.context = context;
    }

    public void setCallback(VLCCallback cb) { this.callback = cb; }

    public void initialize() {
        ArrayList<String> options = new ArrayList<>();
        options.add("--avcodec-hw=none");       // Software decode — no green frames
        options.add("--rtsp-tcp");               // TCP transport
        options.add("--network-caching=300");    // 300ms cache
        options.add("--clock-jitter=0");
        options.add("--clock-synchro=0");
        options.add("--avcodec-fast");
        options.add("--no-drop-late-frames");
        options.add("--no-skip-frames");
        options.add("--no-audio");               // Video only
        options.add("--verbose=0");

        libVLC = new LibVLC(context, options);
        mediaPlayer = new MediaPlayer(libVLC);

        mediaPlayer.setEventListener(event -> {
            switch (event.type) {
                case MediaPlayer.Event.Playing:
                    Log.i(TAG, "VLC Playing");
                    reconnectAttempts = 0;
                    if (callback != null) callback.onPlaying();
                    break;
                case MediaPlayer.Event.Stopped:
                    Log.i(TAG, "VLC Stopped");
                    if (callback != null) callback.onStopped();
                    break;
                case MediaPlayer.Event.EncounteredError:
                    Log.e(TAG, "VLC Error");
                    if (callback != null) callback.onError("VLC playback error");
                    attemptReconnect();
                    break;
                case MediaPlayer.Event.EndReached:
                    Log.w(TAG, "VLC EndReached — stream ended");
                    attemptReconnect();
                    break;
                case MediaPlayer.Event.Buffering:
                    if (callback != null) callback.onBuffering(event.getBuffering());
                    break;
            }
        });
    }

    /**
     * Attach to SurfaceView — MUST be called from surfaceChanged callback only.
     */
    public void attachSurface(SurfaceView sv) {
        this.surfaceView = sv;
        if (mediaPlayer == null) return;
        IVLCVout vout = mediaPlayer.getVLCVout();
        if (!isAttached) {
            vout.setVideoView(sv);
            vout.attachViews();
            isAttached = true;
            Log.i(TAG, "Surface attached (from surfaceChanged)");
        }
    }

    public void detachSurface() {
        if (mediaPlayer == null) return;
        try {
            IVLCVout vout = mediaPlayer.getVLCVout();
            if (isAttached) {
                vout.detachViews();
                isAttached = false;
                Log.i(TAG, "Surface detached");
            }
        } catch (Exception e) {
            Log.w(TAG, "Detach error", e);
        }
    }

    public void play(String rtspUrl) {
        if (mediaPlayer == null) {
            Log.e(TAG, "Not initialized");
            return;
        }
        this.currentUrl = rtspUrl;
        reconnectAttempts = 0;

        Media media = new Media(libVLC, android.net.Uri.parse(rtspUrl));
        media.setHWDecoderEnabled(false, false); // Force software
        media.addOption(":rtsp-tcp");
        media.addOption(":network-caching=300");
        mediaPlayer.setMedia(media);
        media.release();
        mediaPlayer.play();
        Log.i(TAG, "Playing: " + rtspUrl);
    }

    public void stop() {
        reconnectAttempts = MAX_RECONNECT; // Prevent auto-reconnect
        if (mediaPlayer != null) {
            mediaPlayer.stop();
        }
    }

    public void release() {
        stop();
        detachSurface();
        if (mediaPlayer != null) { mediaPlayer.release(); mediaPlayer = null; }
        if (libVLC != null) { libVLC.release(); libVLC = null; }
    }

    public boolean isPlaying() {
        return mediaPlayer != null && mediaPlayer.isPlaying();
    }

    private void attemptReconnect() {
        if (currentUrl == null || reconnectAttempts >= MAX_RECONNECT) {
            if (callback != null) callback.onError("Max reconnection attempts reached");
            return;
        }
        reconnectAttempts++;
        Log.i(TAG, "Reconnecting attempt " + reconnectAttempts + "/" + MAX_RECONNECT);
        new Thread(() -> {
            try { Thread.sleep(2000); } catch (InterruptedException ignored) {}
            if (reconnectAttempts < MAX_RECONNECT) {
                play(currentUrl);
            }
        }).start();
    }
}
