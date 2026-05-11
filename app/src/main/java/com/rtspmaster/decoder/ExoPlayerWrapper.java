package com.rtspmaster.decoder;

import android.content.Context;
import android.util.Log;
import android.view.Surface;
import androidx.annotation.OptIn;
import androidx.media3.common.MediaItem;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.rtsp.RtspMediaSource;

/**
 * Tier 2 — ExoPlayer Media3 RTSP Wrapper.
 * Uses media3-exoplayer-rtsp for transport with TCP forced.
 * Exponential backoff reconnection.
 */
@OptIn(markerClass = UnstableApi.class)
public final class ExoPlayerWrapper {
    private static final String TAG = "ExoPlayerWrap";

    public interface ExoCallback {
        void onPlaying();
        void onError(String message);
        void onStopped();
    }

    private ExoPlayer player;
    private final Context context;
    private ExoCallback callback;
    private Surface outputSurface;
    private String currentUrl;
    private int reconnectAttempts = 0;
    private long backoffMs = 1000;
    private static final long MAX_BACKOFF = 16000;
    private long playStartTime = 0;

    public ExoPlayerWrapper(Context context) {
        this.context = context;
    }

    public void setCallback(ExoCallback cb) { this.callback = cb; }

    public void setSurface(Surface surface) {
        this.outputSurface = surface;
        if (player != null) {
            player.setVideoSurface(surface);
        }
    }

    public void play(String rtspUrl) {
        this.currentUrl = rtspUrl;
        reconnectAttempts = 0;
        backoffMs = 1000;

        release();

        player = new ExoPlayer.Builder(context).build();

        if (outputSurface != null) {
            player.setVideoSurface(outputSurface);
        }

        player.addListener(new Player.Listener() {
            @Override
            public void onPlaybackStateChanged(int state) {
                if (state == Player.STATE_READY) {
                    Log.i(TAG, "ExoPlayer READY");
                    playStartTime = System.currentTimeMillis();
                    reconnectAttempts = 0;
                    backoffMs = 1000;
                    if (callback != null) callback.onPlaying();
                } else if (state == Player.STATE_ENDED) {
                    Log.w(TAG, "ExoPlayer ENDED");
                    attemptReconnect();
                }
            }

            @Override
            public void onPlayerError(PlaybackException error) {
                Log.e(TAG, "ExoPlayer error: " + error.getMessage());
                if (callback != null) callback.onError(error.getMessage());
                attemptReconnect();
            }
        });

        RtspMediaSource.Factory factory = new RtspMediaSource.Factory();
        factory.setForceUseRtpTcp(true);
        factory.setTimeoutMs(10000);

        RtspMediaSource source = factory.createMediaSource(
                MediaItem.fromUri(rtspUrl));

        player.setMediaSource(source);
        player.prepare();
        player.setPlayWhenReady(true);
        Log.i(TAG, "ExoPlayer starting: " + rtspUrl);
    }

    public void stop() {
        reconnectAttempts = 100; // prevent reconnect
        if (player != null) {
            player.stop();
            if (callback != null) callback.onStopped();
        }
    }

    public void release() {
        if (player != null) {
            player.stop();
            player.release();
            player = null;
        }
    }

    public boolean isPlaying() {
        return player != null && player.isPlaying();
    }

    private void attemptReconnect() {
        if (currentUrl == null || reconnectAttempts >= 5) {
            if (callback != null) callback.onError("Max reconnect reached");
            return;
        }
        reconnectAttempts++;
        long delay = backoffMs;
        backoffMs = Math.min(backoffMs * 2, MAX_BACKOFF);
        Log.i(TAG, "Reconnect in " + delay + "ms (attempt " + reconnectAttempts + ")");
        new Thread(() -> {
            try { Thread.sleep(delay); } catch (InterruptedException ignored) {}
            if (reconnectAttempts <= 5) play(currentUrl);
        }).start();
    }
}
