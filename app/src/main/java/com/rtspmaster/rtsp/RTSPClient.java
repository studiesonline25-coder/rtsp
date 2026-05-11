package com.rtspmaster.rtsp;

import android.util.Base64;
import android.util.Log;
import java.io.*;
import java.net.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Layer 1+2 — Raw RTSP Client with RTP over TCP interleaved mode.
 * Performs DESCRIBE→SETUP→PLAY handshake, parses SDP for SPS/PPS,
 * and delivers raw RTP packets via callback.
 */
public final class RTSPClient {
    private static final String TAG = "RTSPClient";

    public interface RTSPCallback {
        void onSdpParsed(byte[] sps, byte[] pps, int width, int height);
        void onRtpPacket(byte[] data, int offset, int length, int channel);
        void onDisconnected(String reason);
    }

    private Socket socket;
    private OutputStream out;
    private InputStream in;
    private int cseq = 1;
    private String sessionId;
    private volatile boolean running = false;
    private final RTSPCallback callback;

    public RTSPClient(RTSPCallback callback) {
        this.callback = callback;
    }

    public void connect(String url) throws Exception {
        URI uri = new URI(url);
        String host = uri.getHost();
        int port = uri.getPort() > 0 ? uri.getPort() : 554;

        socket = new Socket();
        socket.connect(new InetSocketAddress(host, port), 10000);
        socket.setSoTimeout(15000);
        socket.setTcpNoDelay(true);
        out = socket.getOutputStream();
        in = socket.getInputStream();

        // DESCRIBE
        String descResp = sendRequest("DESCRIBE", url, "Accept: application/sdp\r\n");
        parseSDP(descResp);

        // SETUP — TCP interleaved
        String trackUrl = extractTrackUrl(descResp, url);
        String setupResp = sendRequest("SETUP", trackUrl,
                "Transport: RTP/AVP/TCP;unicast;interleaved=0-1\r\n");
        sessionId = extractSession(setupResp);

        // PLAY
        sendRequest("PLAY", url, "Session: " + sessionId + "\r\nRange: npt=0.000-\r\n");

        running = true;
        Log.i(TAG, "RTSP connected, receiving RTP over TCP");
    }

    /** Main receive loop — call on dedicated thread. */
    public void receiveLoop() {
        byte[] header = new byte[4];
        try {
            while (running) {
                // TCP interleaved: $<channel><length_hi><length_lo><data>
                int b = in.read();
                if (b < 0) break;
                if (b != '$') {
                    // Skip non-interleaved data (RTSP responses mid-stream)
                    continue;
                }
                readFully(in, header, 0, 3);
                int channel = header[0] & 0xFF;
                int length = ((header[1] & 0xFF) << 8) | (header[2] & 0xFF);
                if (length <= 0 || length > 65535) continue;

                byte[] data = new byte[length];
                readFully(in, data, 0, length);

                if (channel == 0 && callback != null) {
                    callback.onRtpPacket(data, 0, length, channel);
                }
            }
        } catch (SocketTimeoutException e) {
            Log.w(TAG, "Socket timeout");
        } catch (IOException e) {
            if (running) Log.e(TAG, "Receive error", e);
        } finally {
            running = false;
            if (callback != null) callback.onDisconnected("Stream ended");
        }
    }

    public void sendTeardown(String url) {
        try {
            if (sessionId != null) {
                sendRequest("TEARDOWN", url, "Session: " + sessionId + "\r\n");
            }
        } catch (Exception ignored) {}
    }

    public void disconnect() {
        running = false;
        try { if (socket != null) socket.close(); } catch (Exception ignored) {}
    }

    public boolean isRunning() { return running; }

    private String sendRequest(String method, String url, String extra) throws IOException {
        String req = method + " " + url + " RTSP/1.0\r\n"
                + "CSeq: " + (cseq++) + "\r\n"
                + "User-Agent: RTSPMaster/1.0\r\n"
                + (extra != null ? extra : "")
                + "\r\n";
        out.write(req.getBytes());
        out.flush();
        return readResponse();
    }

    private String readResponse() throws IOException {
        StringBuilder sb = new StringBuilder();
        int contentLength = 0;
        // Read headers
        String line;
        while ((line = readLine()) != null) {
            sb.append(line).append("\n");
            if (line.isEmpty()) break;
            if (line.toLowerCase().startsWith("content-length:")) {
                contentLength = Integer.parseInt(line.substring(15).trim());
            }
        }
        // Read body
        if (contentLength > 0) {
            byte[] body = new byte[contentLength];
            readFully(in, body, 0, contentLength);
            sb.append(new String(body));
        }
        return sb.toString();
    }

    private String readLine() throws IOException {
        StringBuilder sb = new StringBuilder();
        int c;
        while ((c = in.read()) >= 0) {
            if (c == '\r') { in.read(); break; } // skip \n
            if (c == '\n') break;
            sb.append((char) c);
        }
        return c < 0 ? null : sb.toString();
    }

    private void parseSDP(String sdp) {
        byte[] sps = null, pps = null;
        int w = 0, h = 0;

        // Extract sprop-parameter-sets from fmtp line
        Pattern fmtp = Pattern.compile("sprop-parameter-sets=([A-Za-z0-9+/=]+),([A-Za-z0-9+/=]+)");
        Matcher m = fmtp.matcher(sdp);
        if (m.find()) {
            sps = Base64.decode(m.group(1), Base64.DEFAULT);
            pps = Base64.decode(m.group(2), Base64.DEFAULT);
            Log.i(TAG, "SDP SPS=" + sps.length + "b PPS=" + pps.length + "b");
        }

        // Extract dimensions from SPS or a= lines
        Pattern dim = Pattern.compile("a=framesize:\\d+\\s+(\\d+)-(\\d+)");
        Matcher dm = dim.matcher(sdp);
        if (dm.find()) {
            w = Integer.parseInt(dm.group(1));
            h = Integer.parseInt(dm.group(2));
        }
        if (w == 0) {
            // Try cliprect
            Pattern clip = Pattern.compile("a=cliprect:\\d+,\\d+,(\\d+),(\\d+)");
            Matcher cm = clip.matcher(sdp);
            if (cm.find()) {
                h = Integer.parseInt(cm.group(1));
                w = Integer.parseInt(cm.group(2));
            }
        }
        if (w == 0 && sps != null) {
            w = 1920; h = 1080; // Fallback; real apps parse SPS NAL
        }

        if (callback != null) callback.onSdpParsed(sps, pps, w, h);
    }

    private String extractTrackUrl(String resp, String baseUrl) {
        Pattern p = Pattern.compile("a=control:(.+)");
        Matcher m = p.matcher(resp);
        String track = "trackID=0";
        while (m.find()) {
            String ctrl = m.group(1).trim();
            if (ctrl.contains("video") || ctrl.contains("track")) {
                track = ctrl; break;
            }
            track = ctrl;
        }
        if (track.startsWith("rtsp://")) return track;
        return baseUrl + "/" + track;
    }

    private String extractSession(String resp) {
        Pattern p = Pattern.compile("Session:\\s*([^;\\r\\n]+)");
        Matcher m = p.matcher(resp);
        return m.find() ? m.group(1).trim() : "0";
    }

    private static void readFully(InputStream is, byte[] buf, int off, int len) throws IOException {
        int read = 0;
        while (read < len) {
            int n = is.read(buf, off + read, len - read);
            if (n < 0) throw new IOException("EOF");
            read += n;
        }
    }
}
