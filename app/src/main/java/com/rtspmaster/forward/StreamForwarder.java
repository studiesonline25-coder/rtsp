package com.rtspmaster.forward;

import android.util.Log;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * RTP Stream Forwarder — mirrors raw RTP packets to a configurable destination.
 * Forwards BEFORE decoding for zero quality loss and minimal CPU overhead.
 * Runs on dedicated thread with its own socket.
 */
public final class StreamForwarder {
    private static final String TAG = "StreamForwarder";

    private DatagramSocket socket;
    private InetAddress destAddress;
    private int destPort;
    private Thread forwardThread;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicLong packetCount = new AtomicLong(0);
    private final AtomicLong byteCount = new AtomicLong(0);
    private final AtomicLong errorCount = new AtomicLong(0);
    private long startTimeMs;

    // Queue for packets to forward
    private final java.util.concurrent.LinkedBlockingQueue<byte[]> packetQueue =
            new java.util.concurrent.LinkedBlockingQueue<>(500);

    /**
     * Configure forwarding destination.
     * @param ip destination IP (e.g. "127.0.0.1" for local, or remote IP)
     * @param port destination UDP port (e.g. 5004)
     */
    public boolean setDestination(String ip, int port) {
        try {
            destAddress = InetAddress.getByName(ip);
            destPort = port;
            Log.i(TAG, "Forward destination: " + ip + ":" + port);
            return true;
        } catch (Exception e) {
            Log.e(TAG, "Invalid destination", e);
            return false;
        }
    }

    public void start() {
        if (running.get()) return;
        if (destAddress == null) {
            Log.e(TAG, "No destination configured");
            return;
        }
        running.set(true);
        startTimeMs = System.currentTimeMillis();
        packetCount.set(0);
        byteCount.set(0);
        errorCount.set(0);

        forwardThread = new Thread(() -> {
            try {
                socket = new DatagramSocket();
                socket.setSendBufferSize(1048576); // 1MB send buffer
                Log.i(TAG, "Forwarder started → " + destAddress + ":" + destPort);

                while (running.get()) {
                    byte[] data = packetQueue.poll(500, java.util.concurrent.TimeUnit.MILLISECONDS);
                    if (data == null) continue;
                    try {
                        DatagramPacket pkt = new DatagramPacket(data, data.length, destAddress, destPort);
                        socket.send(pkt);
                        packetCount.incrementAndGet();
                        byteCount.addAndGet(data.length);
                    } catch (Exception e) {
                        errorCount.incrementAndGet();
                        if (errorCount.get() % 100 == 1) {
                            Log.w(TAG, "Forward send error #" + errorCount.get(), e);
                        }
                    }
                }
            } catch (Exception e) {
                Log.e(TAG, "Forwarder thread error", e);
            } finally {
                if (socket != null && !socket.isClosed()) socket.close();
                Log.i(TAG, "Forwarder stopped. Packets=" + packetCount.get()
                        + " Bytes=" + byteCount.get() + " Errors=" + errorCount.get());
            }
        }, "StreamForwarder");
        forwardThread.setDaemon(true);
        forwardThread.start();
    }

    /**
     * Queue a raw RTP packet for forwarding.
     * Non-blocking — drops if queue is full (backpressure).
     */
    public void forwardPacket(byte[] rtpData) {
        if (!running.get() || rtpData == null) return;
        if (!packetQueue.offer(rtpData)) {
            // Queue full — drop oldest
            packetQueue.poll();
            packetQueue.offer(rtpData);
        }
    }

    /** Fire-and-forget single packet (no queue, direct send). */
    public void forwardPacketDirect(byte[] rtpData, int offset, int length) {
        if (!running.get() || socket == null || destAddress == null) return;
        try {
            DatagramPacket pkt = new DatagramPacket(rtpData, offset, length, destAddress, destPort);
            socket.send(pkt);
            packetCount.incrementAndGet();
            byteCount.addAndGet(length);
        } catch (Exception e) {
            errorCount.incrementAndGet();
        }
    }

    public void stop() {
        running.set(false);
        if (forwardThread != null) {
            forwardThread.interrupt();
            forwardThread = null;
        }
        packetQueue.clear();
    }

    public boolean isRunning() { return running.get(); }
    public long getPacketCount() { return packetCount.get(); }
    public long getByteCount() { return byteCount.get(); }
    public long getErrorCount() { return errorCount.get(); }

    /** Get approximate bitrate in kbps. */
    public float getBitrateKbps() {
        long elapsed = System.currentTimeMillis() - startTimeMs;
        if (elapsed <= 0) return 0;
        return (byteCount.get() * 8f) / elapsed; // bits per ms = kbps
    }
}
