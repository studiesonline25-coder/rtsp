package com.rtspmaster;

import android.Manifest;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.graphics.drawable.GradientDrawable;
import android.opengl.GLSurfaceView;
import android.os.Build;
import android.os.Bundle;
import android.os.IBinder;
import android.util.Log;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.textfield.TextInputEditText;
import com.rtspmaster.decoder.DecoderStrategyManager;
import com.rtspmaster.decoder.DeviceProfiler;
import com.rtspmaster.service.RTSPForegroundService;

/**
 * Main RTSP Activity — UI shell that binds to the foreground service.
 * Delegates all stream logic to DecoderStrategyManager via the service.
 */
public final class RTSPActivity extends AppCompatActivity {
    private static final String TAG = "RTSPActivity";
    private static final int PERM_REQ = 1001;

    private SurfaceView surfaceView;
    private GLSurfaceView glSurfaceView;
    private View statusDot;
    private TextInputEditText etUrl, etForwardIp, etForwardPort;
    private MaterialButton btnConnect, btnDisconnect;
    private TextView tvStatus, tvDecoder, tvFps, tvStrategy;

    private RTSPForegroundService service;
    private boolean bound = false;

    private final ServiceConnection connection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder binder) {
            RTSPForegroundService.LocalBinder lb =
                    (RTSPForegroundService.LocalBinder) binder;
            service = lb.getService();
            bound = true;
            Log.i(TAG, "Bound to service");
            setupServiceCallback();
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            bound = false;
            service = null;
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_rtsp);

        surfaceView = findViewById(R.id.surfaceView);
        glSurfaceView = findViewById(R.id.glSurfaceView);
        statusDot = findViewById(R.id.statusDot);
        etUrl = findViewById(R.id.etRtspUrl);
        etForwardIp = findViewById(R.id.etForwardIp);
        etForwardPort = findViewById(R.id.etForwardPort);
        btnConnect = findViewById(R.id.btnConnect);
        btnDisconnect = findViewById(R.id.btnDisconnect);
        tvStatus = findViewById(R.id.tvStatus);
        tvDecoder = findViewById(R.id.tvDecoder);
        tvFps = findViewById(R.id.tvFps);
        tvStrategy = findViewById(R.id.tvStrategy);

        // Surface lifecycle — Fix 6: only use surfaceChanged
        surfaceView.getHolder().addCallback(new SurfaceHolder.Callback() {
            @Override public void surfaceCreated(@NonNull SurfaceHolder h) {
                // Do NOT attach here — wait for surfaceChanged
            }

            @Override
            public void surfaceChanged(@NonNull SurfaceHolder h, int fmt, int w, int h2) {
                Log.i(TAG, "surfaceChanged: " + w + "x" + h2);
                if (bound && service != null) {
                    DecoderStrategyManager mgr = service.getStrategyManager();
                    mgr.setSurfaceView(surfaceView);
                    mgr.setGLSurfaceView(glSurfaceView);
                    mgr.onSurfaceReady(h.getSurface());
                }
            }

            @Override
            public void surfaceDestroyed(@NonNull SurfaceHolder h) {
                if (bound && service != null) {
                    service.getStrategyManager().onSurfaceDestroyed();
                }
            }
        });

        btnConnect.setOnClickListener(v -> onConnect());
        btnDisconnect.setOnClickListener(v -> onDisconnect());

        // Handle rtsp:// intent
        if (getIntent() != null && getIntent().getData() != null) {
            String uri = getIntent().getData().toString();
            etUrl.setText(uri);
        }

        // Request notification permission on Android 13+
        if (Build.VERSION.SDK_INT >= 33) {
            if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                    != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this,
                        new String[]{Manifest.permission.POST_NOTIFICATIONS}, PERM_REQ);
            }
        }

        // Show device profile
        DeviceProfiler p = new DeviceProfiler(this);
        tvStrategy.setText("Device: " + DeviceProfiler.profileToString(p.getDeviceProfile()));
    }

    @Override
    protected void onStart() {
        super.onStart();
        Intent intent = new Intent(this, RTSPForegroundService.class);
        bindService(intent, connection, Context.BIND_AUTO_CREATE);
    }

    @Override
    protected void onStop() {
        super.onStop();
        if (bound) {
            unbindService(connection);
            bound = false;
        }
    }

    private void onConnect() {
        String url = etUrl.getText() != null ? etUrl.getText().toString().trim() : "";
        if (url.isEmpty()) {
            Toast.makeText(this, "Enter RTSP URL", Toast.LENGTH_SHORT).show();
            return;
        }

        // Setup forwarding if configured
        String fwdIp = etForwardIp.getText() != null ? etForwardIp.getText().toString().trim() : "";
        String fwdPortStr = etForwardPort.getText() != null ? etForwardPort.getText().toString().trim() : "";
        if (!fwdIp.isEmpty() && !fwdPortStr.isEmpty()) {
            try {
                int fwdPort = Integer.parseInt(fwdPortStr);
                if (bound && service != null) {
                    service.getStrategyManager().getForwarder().setDestination(fwdIp, fwdPort);
                    service.getStrategyManager().getForwarder().start();
                }
            } catch (NumberFormatException e) {
                Toast.makeText(this, "Invalid port", Toast.LENGTH_SHORT).show();
            }
        }

        // Start service + stream
        Intent intent = new Intent(this, RTSPForegroundService.class);
        intent.setAction(RTSPForegroundService.ACTION_START);
        intent.putExtra(RTSPForegroundService.EXTRA_URL, url);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent);
        } else {
            startService(intent);
        }

        btnConnect.setEnabled(false);
        btnDisconnect.setEnabled(true);
        tvStatus.setText("Status: Connecting...");
    }

    private void onDisconnect() {
        Intent intent = new Intent(this, RTSPForegroundService.class);
        intent.setAction(RTSPForegroundService.ACTION_STOP);
        startService(intent);

        btnConnect.setEnabled(true);
        btnDisconnect.setEnabled(false);
        tvStatus.setText("Status: Disconnected");
        tvFps.setText("FPS: —");
        setStatusDotColor(0xFF888888);
    }

    private void setupServiceCallback() {
        if (service == null) return;
        DecoderStrategyManager mgr = service.getStrategyManager();
        mgr.setSurfaceView(surfaceView);
        mgr.setGLSurfaceView(glSurfaceView);
        mgr.setCallback(new DecoderStrategyManager.StrategyCallback() {
            @Override
            public void onStatusChanged(String status) {
                runOnUiThread(() -> tvStatus.setText("Status: " + status));
            }

            @Override
            public void onDecoderChanged(String name, int strategy) {
                runOnUiThread(() -> {
                    tvDecoder.setText("Decoder: " + name);
                    tvStrategy.setText("Strategy: " + DecoderStrategyManager.strategyName(strategy));
                });
            }

            @Override
            public void onFpsUpdate(float fps) {
                runOnUiThread(() -> {
                    tvFps.setText(String.format("FPS: %.1f", fps));
                    setStatusDotColor(fps >= 10 ? 0xFF4CAF50 : fps >= 5 ? 0xFFFF9800 : 0xFFF44336);
                });
            }

            @Override
            public void onError(String error) {
                runOnUiThread(() -> {
                    tvStatus.setText("Status: " + error);
                    setStatusDotColor(0xFFF44336);
                });
            }

            @Override
            public void onFirstFrame() {
                runOnUiThread(() -> {
                    setStatusDotColor(0xFF4CAF50);
                    btnConnect.setEnabled(false);
                    btnDisconnect.setEnabled(true);
                });
            }
        });

        // If service was already streaming (coming back from background)
        if (service.isStreaming()) {
            btnConnect.setEnabled(false);
            btnDisconnect.setEnabled(true);
            tvStatus.setText("Status: Reconnected");
        }
    }

    private void setStatusDotColor(int color) {
        GradientDrawable bg = new GradientDrawable();
        bg.setShape(GradientDrawable.OVAL);
        bg.setColor(color);
        bg.setSize(12, 12);
        statusDot.setBackground(bg);
    }
}
