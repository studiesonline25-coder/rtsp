package com.rtspmaster.rtsp;

import android.util.Log;
import java.io.ByteArrayOutputStream;

/**
 * Layer 2+3 — RTP Parser + H.264 NAL Unit Assembler.
 * Parses RTP headers, handles FU-A fragmentation, STAP-A aggregation,
 * and single NAL unit packets. Outputs complete NAL units with start codes.
 */
public final class NALAssembler {
    private static final String TAG = "NALAssembler";
    private static final byte[] START_CODE = {0, 0, 0, 1};

    public interface NALCallback {
        void onNalUnit(byte[] nalWithStartCode, long timestamp, int nalType);
    }

    private final NALCallback callback;
    private ByteArrayOutputStream fuaBuffer;
    private int fuaNri, fuaType;
    private boolean fuaStarted = false;
    private int lastSeqNum = -1;

    public NALAssembler(NALCallback callback) {
        this.callback = callback;
    }

    /**
     * Process an RTP packet payload (after RTP header has been stripped).
     * @param rtpPayload raw RTP packet data
     * @param offset start of payload data (after 12-byte RTP header + any CSRC)
     * @param length length of payload
     * @param timestamp RTP timestamp
     * @param marker RTP marker bit (end of frame)
     */
    public void processRtpPayload(byte[] rtpPayload, int offset, int length, long timestamp, boolean marker) {
        if (length < 1) return;

        int fb = rtpPayload[offset] & 0xFF;
        int nalType = fb & 0x1F;
        int nri = fb & 0x60;

        if (nalType >= 1 && nalType <= 23) {
            // Single NAL unit — prepend start code and deliver
            byte[] nal = new byte[length + 4];
            System.arraycopy(START_CODE, 0, nal, 0, 4);
            System.arraycopy(rtpPayload, offset, nal, 4, length);
            deliverNal(nal, timestamp, nalType);

        } else if (nalType == 24) {
            // STAP-A — multiple NALs aggregated in one RTP packet
            processStapA(rtpPayload, offset + 1, length - 1, timestamp);

        } else if (nalType == 28) {
            // FU-A — fragmented NAL unit
            processFuA(rtpPayload, offset, length, timestamp, nri, marker);

        } else {
            Log.w(TAG, "Unsupported NAL type in RTP: " + nalType);
        }
    }

    /**
     * Convenience: process a full RTP packet including header.
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
        int seqNum = ((packet[offset + 2] & 0xFF) << 8) | (packet[offset + 3] & 0xFF);
        
        // Sequence checking to prevent corruption
        if (lastSeqNum != -1) {
            int expected = (lastSeqNum + 1) & 0xFFFF;
            if (seqNum != expected) {
                int lost = (seqNum - expected + 0x10000) & 0xFFFF;
                Log.w(TAG, "Packet loss detected! Lost=" + lost + " seq=" + seqNum);
                reset(); // Wipe the current partial NAL
            }
        }
        lastSeqNum = seqNum;

        long timestamp = ((long)(packet[offset + 4] & 0xFF) << 24) |
                          ((long)(packet[offset + 5] & 0xFF) << 16) |
                          ((long)(packet[offset + 6] & 0xFF) << 8) |
                          ((long)(packet[offset + 7] & 0xFF));

        int headerLen = 12 + csrcCount * 4;
        if (extension) {
            if (headerLen + 4 > length) return;
            int extLen = ((packet[offset + headerLen + 2] & 0xFF) << 8) |
                          (packet[offset + headerLen + 3] & 0xFF);
            headerLen += 4 + extLen * 4;
        }

        int payloadOffset = offset + headerLen;
        int payloadLength = length - headerLen;
        if (padding) {
            int padLen = packet[offset + length - 1] & 0xFF;
            payloadLength -= padLen;
        }
        if (payloadLength <= 0) return;

        processRtpPayload(packet, payloadOffset, payloadLength, timestamp, marker);
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
            deliverNal(nal, timestamp, nt);
            pos += nalSize;
        }
    }

    private void processFuA(byte[] data, int offset, int length, long timestamp, int nri, boolean marker) {
        if (length < 2) return;
        int fuIndicator = data[offset] & 0xFF;
        int fuHeader = data[offset + 1] & 0xFF;

        boolean startBit = (fuHeader & 0x80) != 0;
        boolean endBit = (fuHeader & 0x40) != 0;
        int fragNalType = fuHeader & 0x1F;

        if (startBit) {
            fuaBuffer = new ByteArrayOutputStream(length * 4);
            fuaNri = nri;
            fuaType = fragNalType;
            fuaStarted = true;
            // Write start code + reconstructed NAL header
            fuaBuffer.write(START_CODE, 0, 4);
            fuaBuffer.write((byte)(fuaNri | fuaType));
        }

        if (!fuaStarted || fuaBuffer == null) return;

        // Append fragment data (skip FU indicator + FU header = 2 bytes)
        fuaBuffer.write(data, offset + 2, length - 2);

        if (endBit) {
            byte[] nal = fuaBuffer.toByteArray();
            deliverNal(nal, timestamp, fuaType);
            fuaBuffer = null;
            fuaStarted = false;
        }
    }

    private void deliverNal(byte[] nal, long timestamp, int nalType) {
        if (callback != null) {
            if (nalType == 7 || nalType == 8 || nalType == 5) {
                Log.d(TAG, "Delivering NAL type=" + nalType + " size=" + nal.length);
            }
            callback.onNalUnit(nal, timestamp, nalType);
        }
    }

    public void reset() {
        fuaBuffer = null;
        fuaStarted = false;
    }
}
