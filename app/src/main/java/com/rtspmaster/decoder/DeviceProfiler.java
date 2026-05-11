package com.rtspmaster.decoder;

import android.content.Context;
import android.content.SharedPreferences;
import android.media.MediaCodecInfo;
import android.media.MediaCodecList;
import android.os.Build;
import android.util.Log;

import java.util.ArrayList;
import java.util.List;

/**
 * Stage 1 — Device Profiler
 * Runs once on first launch. Detects chipset, enumerates available H.264 decoders,
 * and classifies the device into a profile that drives decoder strategy selection.
 */
public final class DeviceProfiler {

    private static final String TAG = "DeviceProfiler";
    private static final String PREFS_NAME = "rtsp_device_profile";
    private static final String KEY_PROFILE = "device_profile";
    private static final String KEY_BEST_DECODER = "best_decoder_name";
    private static final String KEY_SW_DECODER = "sw_decoder_name";
    private static final String KEY_SUCCESSFUL_STRATEGY = "successful_strategy";

    // Device profile constants
    public static final int PROFILE_UNKNOWN = 0;
    public static final int PROFILE_MEDIATEK = 1;
    public static final int PROFILE_QUALCOMM = 2;
    public static final int PROFILE_EXYNOS = 3;
    public static final int PROFILE_GOOGLE_SW = 4;
    public static final int PROFILE_KIRIN = 5;

    private final SharedPreferences prefs;
    private int deviceProfile = PROFILE_UNKNOWN;
    private String bestHwDecoderName = null;
    private String softwareDecoderName = null;
    private final List<DecoderInfo> availableDecoders = new ArrayList<>();

    public DeviceProfiler(Context context) {
        this.prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        loadOrDetect();
    }

    private void loadOrDetect() {
        int saved = prefs.getInt(KEY_PROFILE, -1);
        if (saved >= 0) {
            deviceProfile = saved;
            bestHwDecoderName = prefs.getString(KEY_BEST_DECODER, null);
            softwareDecoderName = prefs.getString(KEY_SW_DECODER, null);
            Log.i(TAG, "Loaded cached profile: " + profileToString(deviceProfile)
                    + " HW=" + bestHwDecoderName + " SW=" + softwareDecoderName);
        } else {
            detect();
        }
    }

    private void detect() {
        Log.i(TAG, "=== Device Profiling Start ===");
        Log.i(TAG, "Build.HARDWARE: " + Build.HARDWARE);
        Log.i(TAG, "Build.BOARD: " + Build.BOARD);
        Log.i(TAG, "Build.MANUFACTURER: " + Build.MANUFACTURER);
        Log.i(TAG, "Build.MODEL: " + Build.MODEL);
        if (Build.VERSION.SDK_INT >= 31) {
            Log.i(TAG, "Build.SOC_MODEL: " + Build.SOC_MODEL);
            Log.i(TAG, "Build.SOC_MANUFACTURER: " + Build.SOC_MANUFACTURER);
        }

        // Enumerate all H.264 decoders
        MediaCodecList codecList = new MediaCodecList(MediaCodecList.ALL_CODECS);
        int mtkScore = 0;
        int qcomScore = 0;
        int exynosScore = 0;
        int kirinScore = 0;

        for (MediaCodecInfo info : codecList.getCodecInfos()) {
            if (info.isEncoder()) continue;
            String[] types = info.getSupportedTypes();
            for (String type : types) {
                if ("video/avc".equalsIgnoreCase(type)) {
                    String name = info.getName();
                    DecoderInfo di = new DecoderInfo();
                    di.name = name;
                    di.isHardware = !name.startsWith("OMX.google")
                            && !name.contains("sw")
                            && !name.contains("c2.android");
                    availableDecoders.add(di);

                    Log.i(TAG, "Found H264 decoder: " + name
                            + " (HW=" + di.isHardware + ")");

                    String lower = name.toLowerCase();
                    if (lower.contains("omx.mtk") || lower.contains("mtkomx")
                            || lower.contains("c2.mtk")) {
                        mtkScore += 10;
                        di.score = 10;
                    } else if (lower.contains("omx.qcom") || lower.contains("c2.qti")) {
                        qcomScore += 10;
                        di.score = 10;
                    } else if (lower.contains("omx.exynos") || lower.contains("c2.exynos")) {
                        exynosScore += 10;
                        di.score = 10;
                    } else if (lower.contains("kirin") || lower.contains("hisi")) {
                        kirinScore += 10;
                        di.score = 8;
                    } else if (lower.contains("omx.google") || lower.contains("c2.android")) {
                        di.score = 5;
                        softwareDecoderName = name;
                    } else {
                        di.score = 7;
                    }
                }
            }
        }

        // Classify profile
        if (mtkScore >= 10) {
            deviceProfile = PROFILE_MEDIATEK;
        } else if (qcomScore >= 10) {
            deviceProfile = PROFILE_QUALCOMM;
        } else if (exynosScore >= 10) {
            deviceProfile = PROFILE_EXYNOS;
        } else if (kirinScore >= 10) {
            deviceProfile = PROFILE_KIRIN;
        } else if (availableDecoders.size() > 0) {
            // Check Build strings as fallback
            String hw = Build.HARDWARE.toLowerCase();
            String board = Build.BOARD.toLowerCase();
            if (hw.contains("mt") || board.contains("mt")) {
                deviceProfile = PROFILE_MEDIATEK;
            } else if (hw.contains("qcom") || hw.contains("msm") || hw.contains("sdm")) {
                deviceProfile = PROFILE_QUALCOMM;
            } else if (hw.contains("exynos") || hw.contains("universal")) {
                deviceProfile = PROFILE_EXYNOS;
            } else if (hw.contains("kirin") || hw.contains("hi")) {
                deviceProfile = PROFILE_KIRIN;
            } else {
                // Unknown → treat as MediaTek for safety (worst-case workarounds won't hurt others)
                deviceProfile = PROFILE_MEDIATEK;
            }
        } else {
            deviceProfile = PROFILE_GOOGLE_SW;
        }

        // Pick best hardware decoder by score
        DecoderInfo best = null;
        for (DecoderInfo di : availableDecoders) {
            if (di.isHardware && (best == null || di.score > best.score)) {
                best = di;
            }
        }
        if (best != null) {
            bestHwDecoderName = best.name;
        }

        // Ensure we have software fallback
        if (softwareDecoderName == null) {
            softwareDecoderName = "OMX.google.h264.decoder";
        }

        // Persist
        prefs.edit()
                .putInt(KEY_PROFILE, deviceProfile)
                .putString(KEY_BEST_DECODER, bestHwDecoderName)
                .putString(KEY_SW_DECODER, softwareDecoderName)
                .apply();

        Log.i(TAG, "=== Profile Result: " + profileToString(deviceProfile)
                + " HW=" + bestHwDecoderName + " SW=" + softwareDecoderName + " ===");
    }

    // --- Public API ---

    public int getDeviceProfile() {
        return deviceProfile;
    }

    public boolean isMediaTek() {
        return deviceProfile == PROFILE_MEDIATEK;
    }

    public String getBestHwDecoderName() {
        return bestHwDecoderName;
    }

    public String getSoftwareDecoderName() {
        return softwareDecoderName;
    }

    public List<DecoderInfo> getAvailableDecoders() {
        return availableDecoders;
    }

    /** Save which strategy ultimately worked so we skip straight to it next time. */
    public void saveSuccessfulStrategy(int strategyId) {
        prefs.edit().putInt(KEY_SUCCESSFUL_STRATEGY, strategyId).apply();
        Log.i(TAG, "Saved successful strategy: " + strategyId);
    }

    /** Returns -1 if no successful strategy was previously saved. */
    public int getSavedSuccessfulStrategy() {
        return prefs.getInt(KEY_SUCCESSFUL_STRATEGY, -1);
    }

    /** Force re-profile (clears cache). */
    public void reprofile() {
        prefs.edit().clear().apply();
        availableDecoders.clear();
        detect();
    }

    public static String profileToString(int profile) {
        switch (profile) {
            case PROFILE_MEDIATEK: return "MEDIATEK";
            case PROFILE_QUALCOMM: return "QUALCOMM";
            case PROFILE_EXYNOS:   return "EXYNOS";
            case PROFILE_KIRIN:    return "KIRIN";
            case PROFILE_GOOGLE_SW: return "GOOGLE_SW";
            default: return "UNKNOWN";
        }
    }

    public static class DecoderInfo {
        public String name;
        public boolean isHardware;
        public int score;

        @Override
        public String toString() {
            return name + "(hw=" + isHardware + ",score=" + score + ")";
        }
    }
}
