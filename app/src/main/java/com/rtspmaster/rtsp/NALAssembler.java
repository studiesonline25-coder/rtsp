package com.rtspmaster.rtsp;

import android.util.Log;
import java.io.ByteArrayOutputStream;

/**
 * Production-grade RTP→H.264 NAL Assembler.
 * 
 * Modeled after alexvas/rtsp-client-android's readRtpData():
 *   - Parses RTP headers from TCP interleaved frames
 *   - Handles Single NAL, STAP-A, and FU-A packet types
 *   - Implements the "Golden Rule": SPS+PPS+IDR are combined into
 *     a SINGLE byte array before delivery to the decoder
 *   - SPS/PPS are sent only ONCE (prepended to first IDR), then nulled
 *   - All NAL units include 00 00 00 01 start codes
 */
public final class NALAssembler {
    private static final String TAG = "NALAssembler";
    private static final byte[] START_CODE = {0, 0, 0, 1};

    // NAL unit types
    private static final int NAL_SLICE     = 1;
    private static final int NAL_IDR       = 5;
    private static final int NAL_SEI       = 6;
    private static final int NAL_SPS       = 7;
    private static final int NAL_PPS       = 8;
    private static final int NAL_AUD       = 9;
    private static final int NAL_STAP_A    = 24;
    private static final int NAL_FU_A      = 28;

    public interface NALCallback {
        /** Delivers a complete, ready-to-decode NAL unit (with start code). */
        void onNalUnit(byte[] nalWithStartCode, long timestamp, int nalType);
    }

    private final NALCallback callback;

    // FU-A reassembly state
    private ByteArrayOutputStream fuaBuffer;
    private int fuaNri, fuaType;
    private boolean fuaStarted = false;

    // "Golden Rule" state: accumulate SPS/PPS/AUD/SEI, combine with first IDR
    private byte[] savedSps = null;
    private byte[] savedPps = null;
    private byte[] savedAud = new byte[0];
    private byte[] savedSei = new byte[0];

    public NALAssembler(NALCallback callback) {
        this.callback = callback;
    }

    /**
     * Process a raw RTP packet (with RTP header) from TCP interleaved frame.
     */
    public void processRtpPacket(byte[] packet, int offset, int length) {
        if (length < 12) return;

        // Parse RTP header
        int version = (packet[offset] >> 6) & 0x3;
        if (version != 2) return;

        boolean padding = ((packet[offset] >> 5) & 1) == 1;
        boolean extension = ((packet[offset] >> 4) & 1) == 1;
        int csrcCount = packet[offset] & 0x0F;
        boolean marker = ((packet[offset + 1] >> 7) & 1) == 1;

        long timestamp = ((long)(packet[offset + 4] & 0xFF) << 24) |
                          ((long)(packet[offset + 5] & 0xFF) << 16) |
                          ((long)(packet[offset + 6] & 0xFF) << 8)  |
                          ((long)(packet[offset + 7] & 0xFF));

        int headerLen = 12 + csrcCount * 4;

        // Skip RTP header extension if present
        if (extension) {
            if (headerLen + 4 > length) return;
            int extLen = ((packet[offset + headerLen + 2] & 0xFF) << 8) |
                          (packet[offset + headerLen + 3] & 0xFF);
            headerLen += 4 + extLen * 4;
        }

        int payloadOffset = offset + headerLen;
        int payloadLength = length - headerLen;

        // Handle padding
        if (padding && payloadLength > 0) {
            int padLen = packet[offset + length - 1] & 0xFF;
            payloadLength -= padLen;
        }
        if (payloadLength <= 0) return;

        processPayload(packet, payloadOffset, payloadLength, timestamp, marker);
    }

    private void processPayload(byte[] data, int offset, int length, long timestamp, boolean marker) {
        if (length < 1) return;

        int fb = data[offset] & 0xFF;
        int nalType = fb & 0x1F;
        int nri = fb & 0x60;

        if (nalType >= 1 && nalType <= 23) {
            // Single NAL unit
            byte[] nal = new byte[length + 4];
            System.arraycopy(START_CODE, 0, nal, 0, 4);
            System.arraycopy(data, offset, nal, 4, length);
            handleCompleteNal(nal, timestamp, nalType);

        } else if (nalType == NAL_STAP_A) {
            // STAP-A: multiple NALs in one RTP packet
            processStapA(data, offset + 1, length - 1, timestamp);

        } else if (nalType == NAL_FU_A) {
            // FU-A: fragmented NAL unit
            processFuA(data, offset, length, timestamp, nri, marker);

        } else {
            Log.w(TAG, "Unsupported NAL type: " + nalType);
        }
    }

    private void processStapA(byte[] data, int offset, int length, long timestamp) {
        int pos = offset;
        int end = offset + length;
        while (pos + 2 <= end) {
            int nalSize = ((data[pos] & 0xFF) << 8) | (data[pos + 1] & 0xFF);
            pos += 2;
            if (pos + nalSize > end) break;

            byte[] nal = new byte[nalSize + 4];
            System.arraycopy(START_CODE, 0, nal, 0, 4);
            System.arraycopy(data, pos, nal, 4, nalSize);
            int nt = data[pos] & 0x1F;
            handleCompleteNal(nal, timestamp, nt);
            pos += nalSize;
        }
    }

    private void processFuA(byte[] data, int offset, int length, long timestamp, int nri, boolean marker) {
        if (length < 2) return;
        int fuHeader = data[offset + 1] & 0xFF;

        boolean startBit = (fuHeader & 0x80) != 0;
        boolean endBit   = (fuHeader & 0x40) != 0;
        int fragNalType   = fuHeader & 0x1F;

        if (startBit) {
            fuaBuffer = new ByteArrayOutputStream(length * 4);
            fuaNri = nri;
            fuaType = fragNalType;
            fuaStarted = true;
            // Write start code + reconstructed NAL header byte
            fuaBuffer.write(START_CODE, 0, 4);
            fuaBuffer.write((byte)(fuaNri | fuaType));
        }

        if (!fuaStarted || fuaBuffer == null) return;

        // Append fragment payload (skip FU indicator + FU header = 2 bytes)
        fuaBuffer.write(data, offset + 2, length - 2);

        if (endBit) {
            byte[] nal = fuaBuffer.toByteArray();
            handleCompleteNal(nal, timestamp, fuaType);
            fuaBuffer = null;
            fuaStarted = false;
        }
    }

    /**
     * THE GOLDEN RULE — This is the critical method that fixes green screens.
     * 
     * Instead of delivering SPS, PPS, and IDR as separate calls:
     *   - SPS and PPS are SAVED (not delivered immediately)
     *   - When an IDR arrives, SPS+PPS+IDR are combined into ONE byte array
     *   - This combined array is delivered as a SINGLE call to the decoder
     *   - After the first IDR, SPS/PPS are nulled so they're not re-sent
     * 
     * This matches exactly how alexvas/rtsp-client-android handles it (lines 723-744).
     */
    private void handleCompleteNal(byte[] nalWithStartCode, long timestamp, int nalType) {
        switch (nalType) {
            case NAL_SPS:
                savedSps = nalWithStartCode;
                Log.d(TAG, "Saved SPS: " + nalWithStartCode.length + " bytes");
                // If SPS is unusually large, it might contain IDR data too — deliver it
                if (nalWithStartCode.length > 128) {
                    deliverNal(nalWithStartCode, timestamp, nalType);
                }
                break;

            case NAL_PPS:
                savedPps = nalWithStartCode;
                Log.d(TAG, "Saved PPS: " + nalWithStartCode.length + " bytes");
                if (nalWithStartCode.length > 128) {
                    deliverNal(nalWithStartCode, timestamp, nalType);
                }
                break;

            case NAL_AUD:
                savedAud = nalWithStartCode;
                break;

            case NAL_SEI:
                savedSei = nalWithStartCode;
                break;

            case NAL_IDR:
                // ★ THE GOLDEN RULE ★
                // Combine SPS + PPS + AUD + SEI + IDR into ONE buffer
                if (savedSps != null && savedPps != null) {
                    int totalLen = savedSps.length + savedPps.length 
                                 + savedAud.length + savedSei.length 
                                 + nalWithStartCode.length;
                    byte[] combined = new byte[totalLen];
                    int pos = 0;

                    System.arraycopy(savedSps, 0, combined, pos, savedSps.length);
                    pos += savedSps.length;
                    System.arraycopy(savedPps, 0, combined, pos, savedPps.length);
                    pos += savedPps.length;
                    System.arraycopy(savedAud, 0, combined, pos, savedAud.length);
                    pos += savedAud.length;
                    System.arraycopy(savedSei, 0, combined, pos, savedSei.length);
                    pos += savedSei.length;
                    System.arraycopy(nalWithStartCode, 0, combined, pos, nalWithStartCode.length);

                    Log.i(TAG, "★ GOLDEN RULE: Combined SPS+PPS+IDR = " + totalLen + " bytes");
                    deliverNal(combined, timestamp, NAL_IDR);

                    // Send SPS/PPS only once, then null them out (exactly like the reference)
                    savedSps = null;
                    savedPps = null;
                    savedSei = new byte[0];
                    savedAud = new byte[0];
                } else {
                    // No saved SPS/PPS — deliver IDR alone (subsequent keyframes)
                    if (savedAud.length == 0 && savedSei.length == 0) {
                        deliverNal(nalWithStartCode, timestamp, NAL_IDR);
                    } else {
                        // Prepend AUD + SEI
                        int totalLen = savedAud.length + savedSei.length + nalWithStartCode.length;
                        byte[] combined = new byte[totalLen];
                        int pos = 0;
                        System.arraycopy(savedAud, 0, combined, pos, savedAud.length);
                        pos += savedAud.length;
                        System.arraycopy(savedSei, 0, combined, pos, savedSei.length);
                        pos += savedSei.length;
                        System.arraycopy(nalWithStartCode, 0, combined, pos, nalWithStartCode.length);
                        deliverNal(combined, timestamp, NAL_IDR);
                        savedSei = new byte[0];
                        savedAud = new byte[0];
                    }
                }
                break;

            default:
                // Regular P/B slices — deliver with AUD/SEI if present
                if (savedAud.length == 0 && savedSei.length == 0) {
                    deliverNal(nalWithStartCode, timestamp, nalType);
                } else {
                    int totalLen = savedAud.length + savedSei.length + nalWithStartCode.length;
                    byte[] combined = new byte[totalLen];
                    int pos = 0;
                    System.arraycopy(savedAud, 0, combined, pos, savedAud.length);
                    pos += savedAud.length;
                    System.arraycopy(savedSei, 0, combined, pos, savedSei.length);
                    pos += savedSei.length;
                    System.arraycopy(nalWithStartCode, 0, combined, pos, nalWithStartCode.length);
                    deliverNal(combined, timestamp, nalType);
                    savedSei = new byte[0];
                    savedAud = new byte[0];
                }
                break;
        }
    }

    private long deliveryCount = 0;

    private void deliverNal(byte[] nal, long timestamp, int nalType) {
        if (callback != null) {
            deliveryCount++;
            if (deliveryCount <= 5 || deliveryCount % 100 == 0) {
                Log.d(TAG, "Deliver #" + deliveryCount + " type=" + nalType + " size=" + nal.length);
            }
            callback.onNalUnit(nal, timestamp, nalType);
        }
    }

    public void reset() {
        fuaBuffer = null;
        fuaStarted = false;
        savedSps = null;
        savedPps = null;
        savedAud = new byte[0];
        savedSei = new byte[0];
    }
}
