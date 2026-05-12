package com.rtspmaster.service;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.os.Binder;
import android.os.Build;
import android.os.IBinder;
import android.util.Log;
import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;
import com.rtspmaster.RTSPActivity;
import com.rtspmaster.R;
import com.rtspmaster.decoder.DecoderStrategyManager;

/**
 * Foreground Service — keeps RTSP stream alive when app is backgrounded.
 * Handles stream reception, forwarding, and decoder lifecycle.
 * Activity binds to this service for display.
 */
public final class RTSPForegroundService extends Service {
    private static final String TAG = "RTSPService";
    private static final String CHANNEL_ID = "rtsp_stream_channel";
    private static final int NOTIFICATION_ID = 1001;

    public static final String ACTION_START = "com.rtspmaster.START";
    public static final String ACTION_STOP = "com.rtspmaster.STOP";
    public static final String EXTRA_URL = "rtsp_url";
    public static final String EXTRA_STRATEGY = "strategy";

    private final IBinder binder = new LocalBinder();
    private DecoderStrategyManager strategyManager;
    private String currentUrl;
    private boolean isStreaming = false;
    private android.os.PowerManager.WakeLock wakeLock;

    public class LocalBinder extends Binder {
        public RTSPForegroundService getService() {
            return RTSPForegroundService.this;
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        createNotificationChannel();
        strategyManager = new DecoderStrategyManager(this);
        
        android.os.PowerManager pm = (android.os.PowerManager) getSystemService(android.content.Context.POWER_SERVICE);
        if (pm != null) {
            wakeLock = pm.newWakeLock(android.os.PowerManager.PARTIAL_WAKE_LOCK, "RTSPMaster:WakeLock");
        }
        
        Log.i(TAG, "Service created");
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null) return START_STICKY;

        String action = intent.getAction();
        if (ACTION_START.equals(action)) {
            String url = intent.getStringExtra(EXTRA_URL);
            int strategy = intent.getIntExtra(EXTRA_STRATEGY, -1);
            if (url != null) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    startForeground(NOTIFICATION_ID, buildNotification("Connecting..."), 
                            android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK);
                } else {
                    startForeground(NOTIFICATION_ID, buildNotification("Connecting..."));
                }
                startStream(url, strategy);
            }
        } else if (ACTION_STOP.equals(action)) {
            stopStream();
            stopForeground(true);
            stopSelf();
        }

        return START_STICKY;
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return binder;
    }

    @Override
    public void onDestroy() {
        stopStream();
        if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
        super.onDestroy();
        Log.i(TAG, "Service destroyed");
    }

    public DecoderStrategyManager getStrategyManager() {
        return strategyManager;
    }

    public boolean isStreaming() { return isStreaming; }
    public String getCurrentUrl() { return currentUrl; }

    public void startStream(String url, int strategy) {
        this.currentUrl = url;
        this.isStreaming = true;
        
        if (wakeLock != null && !wakeLock.isHeld()) {
            wakeLock.acquire(3600000); // 1 hour max
        }

        if (strategy < 0) {
            strategy = strategyManager.selectInitialStrategy();
        }

        strategyManager.setCallback(new DecoderStrategyManager.StrategyCallback() {
            @Override public void onStatusChanged(String status) {
                updateNotification(status);
            }
            @Override public void onDecoderChanged(String name, int strat) {
                Log.i(TAG, "Decoder: " + name);
            }
            @Override public void onFpsUpdate(float fps) {}
            @Override public void onError(String error) {
                updateNotification("Error: " + error);
            }
            @Override public void onFirstFrame() {
                updateNotification("Stream Active");
            }
        });

        strategyManager.start(url, strategy);
        updateNotification("Connecting...");
        Log.i(TAG, "Stream started: " + url);
    }

    public void stopStream() {
        isStreaming = false;
        if (wakeLock != null && wakeLock.isHeld()) {
            wakeLock.release();
        }
        if (strategyManager != null) {
            strategyManager.stop();
        }
        Log.i(TAG, "Stream stopped");
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID,
                    getString(R.string.notification_channel_name),
                    NotificationManager.IMPORTANCE_LOW);
            channel.setDescription(getString(R.string.notification_channel_desc));
            NotificationManager nm = getSystemService(NotificationManager.class);
            if (nm != null) nm.createNotificationChannel(channel);
        }
    }

    private Notification buildNotification(String status) {
        Intent tapIntent = new Intent(this, RTSPActivity.class);
        tapIntent.setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent pi = PendingIntent.getActivity(this, 0, tapIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        Intent stopIntent = new Intent(this, RTSPForegroundService.class);
        stopIntent.setAction(ACTION_STOP);
        PendingIntent stopPi = PendingIntent.getService(this, 1, stopIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("RTSP Master")
                .setContentText(status)
                .setSmallIcon(android.R.drawable.ic_menu_camera)
                .setContentIntent(pi)
                .addAction(android.R.drawable.ic_media_pause, "Stop", stopPi)
                .setOngoing(true)
                .build();
    }

    private void updateNotification(String status) {
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (nm != null) {
            nm.notify(NOTIFICATION_ID, buildNotification(status));
        }
    }
}
