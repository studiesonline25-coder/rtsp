package com.rtspmaster.rtsp;

import android.util.Base64;
import android.util.Log;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Production-grade RTSP Client — TCP Interleaved Mode.
 * 
 * Architecture modeled after alexvas/rtsp-client-android:
 *   1. OPTIONS → DESCRIBE → SETUP (TCP interleaved) → PLAY
 *   2. RTP data arrives as $ framed packets on the TCP socket
 *   3. Keep-alive via GET_PARAMETER every sessionTimeout/2
 *   4. Complete NAL units delivered via callback (SPS+PPS+IDR combined)
 */
public final class RTSPClient {
    private static final String TAG = "RTSPClient";
    private static final String CRLF = "\r\n";
    private static final String USER_AGENT = "Lavf58.29.100";

    public interface RTSPCallback {
        /** Called after DESCRIBE with SDP-extracted SPS/PPS */
        void onSdpParsed(byte[] sps, byte[] pps, int width, int height);
        /** Called for each complete, ready-to-decode NAL unit (with start code) */
        void onVideoNalUnit(byte[] data, int offset, int length, long timestamp);
        void onDisconnected(String reason);
    }

    private Socket socket;
    private OutputStream out;
    private InputStream in;
    private int cseq = 0;
    private String sessionId;
    private int sessionTimeout = 60;
    private volatile boolean running = false;
    private final RTSPCallback callback;

    private String username, password;
    private String digestRealm, digestNonce;
    private boolean useDigestAuth = false;

    public RTSPClient(RTSPCallback callback) {
        this.callback = callback;
    }

    public void connect(String url) throws Exception {
        Log.i(TAG, "Connecting to: " + url);

        // Parse URL: rtsp://[user:pass@]host[:port][/path]
        Pattern urlPattern = Pattern.compile("rtsp://(?:([^:]+):([^@]+)@)?([^:/]+)(?::(\\d+))?(.*)");
        Matcher urlMatcher = urlPattern.matcher(url);
        if (!urlMatcher.find()) {
            throw new IllegalArgumentException("Invalid RTSP URL: " + url);
        }

        username = urlMatcher.group(1);
        password = urlMatcher.group(2);
        String host = urlMatcher.group(3);
        String portStr = urlMatcher.group(4);
        int port = (portStr != null) ? Integer.parseInt(portStr) : 554;

        Log.i(TAG, "Parsed: host=" + host + " port=" + port);

        socket = new Socket();
        socket.setReceiveBufferSize(2 * 1024 * 1024);
        socket.setSendBufferSize(512 * 1024);
        socket.connect(new InetSocketAddress(host, port), 10000);
        socket.setSoTimeout(15000);
        socket.setTcpNoDelay(true);
        out = new BufferedOutputStream(socket.getOutputStream());
        in = socket.getInputStream();

        String authToken = null;

        // =============== OPTIONS ===============
        sendCommand("OPTIONS", url, null, authToken);
        int status = readResponseStatusCode();
        String[] headers = readResponseHeaders();

        if (status == 401) {
            authToken = handleAuth(headers, "OPTIONS", url);
            sendCommand("OPTIONS", url, null, authToken);
            status = readResponseStatusCode();
            headers = readResponseHeaders();
        }
        checkStatus(status);

        // =============== DESCRIBE ===============
        if (useDigestAuth) {
            authToken = makeDigestAuth("DESCRIBE", url);
        }
        sendDescribe(url, authToken);
        status = readResponseStatusCode();
        headers = readResponseHeaders();

        if (status == 401) {
            authToken = handleAuth(headers, "DESCRIBE", url);
            sendDescribe(url, authToken);
            status = readResponseStatusCode();
            headers = readResponseHeaders();
        }
        checkStatus(status);

        int contentLength = getContentLength(headers);
        String sdpContent = "";
        if (contentLength > 0) {
            byte[] body = new byte[contentLength];
            readFully(in, body, 0, contentLength);
            sdpContent = new String(body);
            Log.d(TAG, "SDP:\n" + sdpContent);
        }

        // Check for Content-Base redirect
        String contentBase = getHeader(headers, "Content-Base");
        if (contentBase != null && !contentBase.isEmpty()) {
            url = contentBase.trim();
            Log.i(TAG, "Content-Base redirect: " + url);
        }

        parseSDP(sdpContent, url);

        // =============== SETUP (TCP Interleaved) ===============
        String trackUrl = extractTrackUrl(sdpContent, url);
        if (useDigestAuth) {
            authToken = makeDigestAuth("SETUP", trackUrl);
        }
        sendSetup(trackUrl, authToken);
        status = readResponseStatusCode();
        headers = readResponseHeaders();
        checkStatus(status);

        String sessionHeader = getHeader(headers, "Session");
        if (sessionHeader != null) {
            String[] parts = sessionHeader.split(";");
            sessionId = parts[0].trim();
            for (int i = 1; i < parts.length; i++) {
                String[] kv = parts[i].trim().split("=");
                if (kv.length == 2 && kv[0].trim().equalsIgnoreCase("timeout")) {
                    try { sessionTimeout = Integer.parseInt(kv[1].trim()); } catch (Exception ignored) {}
                }
            }
        }
        Log.i(TAG, "Session: " + sessionId + ", timeout: " + sessionTimeout + "s");

        // =============== PLAY ===============
        if (useDigestAuth) {
            authToken = makeDigestAuth("PLAY", url);
        }
        sendPlay(url, authToken);
        status = readResponseStatusCode();
        headers = readResponseHeaders();
        checkStatus(status);

        running = true;
        Log.i(TAG, "RTSP connected via TCP Interleaved");
    }

    /**
     * Main receive loop — reads $ framed RTP packets from TCP stream.
     * 
     * TCP Interleaved format:
     *   $ (0x24) | channel (1 byte) | length (2 bytes big-endian) | RTP data
     */
    public void receiveLoop() {
        android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_URGENT_DISPLAY);

        byte[] header = new byte[4];
        byte[] rtpBuf = new byte[65536];
        long keepAliveSent = System.currentTimeMillis();
        int keepAliveInterval = (sessionTimeout / 2) * 1000;
        long packetCount = 0;

        try {
            while (running) {
                // Send keep-alive
                long now = System.currentTimeMillis();
                if (keepAliveInterval > 0 && now - keepAliveSent > keepAliveInterval) {
                    keepAliveSent = now;
                    sendKeepAlive();
                }

                // Read the 4-byte interleaved header
                int b = in.read();
                if (b < 0) break;

                if (b == '$') {
                    // Standard $ framed RTP
                    readFully(in, header, 0, 3);
                    int channel = header[0] & 0xFF;
                    int length = ((header[1] & 0xFF) << 8) | (header[2] & 0xFF);

                    if (length <= 0 || length > rtpBuf.length) {
                        Log.w(TAG, "Invalid RTP frame length: " + length);
                        continue;
                    }

                    readFully(in, rtpBuf, 0, length);
                    packetCount++;

                    if (channel == 0 && callback != null) {
                        // Video RTP — channel 0
                        callback.onVideoNalUnit(rtpBuf, 0, length, System.currentTimeMillis());
                    }
                    // channel 1 = video RTCP, channel 2 = audio RTP, channel 3 = audio RTCP

                } else if (b == 'R') {
                    // Could be an RTSP response (e.g., keep-alive response "RTSP/1.0 200 OK")
                    // Skip it by reading until we find an empty line
                    skipInterleavedResponse();
                }
                // else: stray byte, ignore
            }
        } catch (SocketTimeoutException e) {
            Log.w(TAG, "Socket timeout in receive loop");
        } catch (IOException e) {
            if (running) Log.e(TAG, "Receive error", e);
        } finally {
            running = false;
            Log.i(TAG, "Receive loop ended. Total packets: " + packetCount);
            if (callback != null) callback.onDisconnected("Stream ended");
        }
    }

    private void skipInterleavedResponse() {
        // Read until double CRLF (end of RTSP response headers)
        try {
            int consecutive = 0;
            while (consecutive < 2) {
                int b = in.read();
                if (b < 0) return;
                if (b == '\n') consecutive++;
                else if (b != '\r') consecutive = 0;
            }
        } catch (IOException ignored) {}
    }

    private void sendKeepAlive() {
        try {
            String authToken = useDigestAuth ? makeDigestAuth("GET_PARAMETER", "") : null;
            // Use the host address instead of '*' to avoid 'invalid path' errors on some servers
            String req = "GET_PARAMETER rtsp://" + socket.getInetAddress().getHostAddress() + "/ RTSP/1.0" + CRLF
                    + "CSeq: " + (++cseq) + CRLF
                    + "User-Agent: " + USER_AGENT + CRLF
                    + "Session: " + sessionId + CRLF;
            if (authToken != null) req += "Authorization: " + authToken + CRLF;
            req += CRLF;
            out.write(req.getBytes());
            out.flush();
            Log.d(TAG, "Keep-alive sent");
        } catch (IOException e) {
            Log.w(TAG, "Keep-alive failed", e);
        }
    }

    public void disconnect() {
        running = false;
        try {
            if (sessionId != null && out != null) {
                String req = "TEARDOWN * RTSP/1.0" + CRLF
                        + "CSeq: " + (++cseq) + CRLF
                        + "Session: " + sessionId + CRLF + CRLF;
                out.write(req.getBytes());
                out.flush();
            }
        } catch (Exception ignored) {}
        try { if (socket != null) socket.close(); } catch (Exception ignored) {}
    }

    public boolean isRunning() { return running; }

    // ==================== RTSP Commands ====================

    private void sendCommand(String method, String url, String extraHeaders, String authToken) throws IOException {
        StringBuilder sb = new StringBuilder();
        sb.append(method).append(' ').append(url).append(" RTSP/1.0").append(CRLF);
        sb.append("CSeq: ").append(++cseq).append(CRLF);
        sb.append("User-Agent: ").append(USER_AGENT).append(CRLF);
        if (authToken != null) sb.append("Authorization: ").append(authToken).append(CRLF);
        if (extraHeaders != null) sb.append(extraHeaders);
        sb.append(CRLF);
        out.write(sb.toString().getBytes());
        out.flush();
    }

    private void sendDescribe(String url, String authToken) throws IOException {
        StringBuilder sb = new StringBuilder();
        sb.append("DESCRIBE ").append(url).append(" RTSP/1.0").append(CRLF);
        sb.append("Accept: application/sdp").append(CRLF);
        sb.append("CSeq: ").append(++cseq).append(CRLF);
        sb.append("User-Agent: ").append(USER_AGENT).append(CRLF);
        if (authToken != null) sb.append("Authorization: ").append(authToken).append(CRLF);
        sb.append(CRLF);
        out.write(sb.toString().getBytes());
        out.flush();
    }

    private void sendSetup(String trackUrl, String authToken) throws IOException {
        StringBuilder sb = new StringBuilder();
        sb.append("SETUP ").append(trackUrl).append(" RTSP/1.0").append(CRLF);
        sb.append("Transport: RTP/AVP/TCP;unicast;interleaved=0-1").append(CRLF);
        sb.append("CSeq: ").append(++cseq).append(CRLF);
        sb.append("User-Agent: ").append(USER_AGENT).append(CRLF);
        if (authToken != null) sb.append("Authorization: ").append(authToken).append(CRLF);
        if (sessionId != null) sb.append("Session: ").append(sessionId).append(CRLF);
        sb.append(CRLF);
        out.write(sb.toString().getBytes());
        out.flush();
    }

    private void sendPlay(String url, String authToken) throws IOException {
        StringBuilder sb = new StringBuilder();
        sb.append("PLAY ").append(url).append(" RTSP/1.0").append(CRLF);
        sb.append("Range: npt=0.000-").append(CRLF);
        sb.append("CSeq: ").append(++cseq).append(CRLF);
        sb.append("User-Agent: ").append(USER_AGENT).append(CRLF);
        if (authToken != null) sb.append("Authorization: ").append(authToken).append(CRLF);
        sb.append("Session: ").append(sessionId).append(CRLF);
        sb.append(CRLF);
        out.write(sb.toString().getBytes());
        out.flush();
    }

    // ==================== Response Parsing ====================

    private int readResponseStatusCode() throws IOException {
        String line = readLine();
        if (line == null) return -1;
        // RTSP/1.0 200 OK
        int idx = line.indexOf(' ');
        if (idx < 0) return -1;
        int idx2 = line.indexOf(' ', idx + 1);
        if (idx2 < 0) idx2 = line.length();
        try {
            return Integer.parseInt(line.substring(idx + 1, idx2));
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    private String[] readResponseHeaders() throws IOException {
        java.util.ArrayList<String> headerList = new java.util.ArrayList<>();
        String line;
        while ((line = readLine()) != null && !line.isEmpty()) {
            headerList.add(line);
        }
        return headerList.toArray(new String[0]);
    }

    private String readLine() throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        int c;
        while ((c = in.read()) >= 0) {
            if (c == '\n') break;
            if (c != '\r') baos.write(c);
        }
        if (c < 0 && baos.size() == 0) return null;
        return baos.toString();
    }

    private String getHeader(String[] headers, String name) {
        for (String h : headers) {
            int idx = h.indexOf(':');
            if (idx > 0 && h.substring(0, idx).trim().equalsIgnoreCase(name)) {
                return h.substring(idx + 1).trim();
            }
        }
        return null;
    }

    private int getContentLength(String[] headers) {
        String val = getHeader(headers, "Content-Length");
        if (val != null) {
            try { return Integer.parseInt(val.trim()); } catch (Exception ignored) {}
        }
        return 0;
    }

    private void checkStatus(int code) throws IOException {
        if (code == 401) throw new IOException("Unauthorized");
        if (code != 200) throw new IOException("RTSP error: " + code);
    }

    // ==================== Authentication ====================

    private String handleAuth(String[] headers, String method, String url) throws IOException {
        for (String h : headers) {
            if (h.toLowerCase().startsWith("www-authenticate:")) {
                String val = h.substring(17).trim();
                if (val.toLowerCase().startsWith("digest")) {
                    parseDigestChallenge(val);
                    useDigestAuth = true;
                    return makeDigestAuth(method, url);
                } else if (val.toLowerCase().startsWith("basic")) {
                    return makeBasicAuth();
                }
            }
        }
        throw new IOException("Unknown authentication type");
    }

    private void parseDigestChallenge(String header) {
        Pattern realmPattern = Pattern.compile("realm=\"([^\"]+)\"");
        Pattern noncePattern = Pattern.compile("nonce=\"([^\"]+)\"");
        Matcher m = realmPattern.matcher(header);
        if (m.find()) digestRealm = m.group(1);
        m = noncePattern.matcher(header);
        if (m.find()) digestNonce = m.group(1);
    }

    private String makeDigestAuth(String method, String uri) {
        try {
            String user = username != null ? username : "";
            String pass = password != null ? password : "";

            MessageDigest md = MessageDigest.getInstance("MD5");
            md.update((user + ":" + digestRealm + ":" + pass).getBytes(StandardCharsets.ISO_8859_1));
            String ha1 = bytesToHex(md.digest());

            md.reset();
            md.update((method + ":" + uri).getBytes(StandardCharsets.ISO_8859_1));
            String ha2 = bytesToHex(md.digest());

            md.reset();
            md.update((ha1 + ":" + digestNonce + ":" + ha2).getBytes(StandardCharsets.ISO_8859_1));
            String response = bytesToHex(md.digest());

            return "Digest username=\"" + user + "\", realm=\"" + digestRealm
                    + "\", nonce=\"" + digestNonce + "\", uri=\"" + uri
                    + "\", response=\"" + response + "\"";
        } catch (Exception e) {
            Log.e(TAG, "Digest auth failed", e);
            return null;
        }
    }

    private String makeBasicAuth() {
        String auth = (username != null ? username : "") + ":" + (password != null ? password : "");
        return "Basic " + Base64.encodeToString(auth.getBytes(StandardCharsets.ISO_8859_1), Base64.NO_WRAP);
    }

    private static String bytesToHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder();
        for (byte b : bytes) sb.append(String.format("%02x", b));
        return sb.toString();
    }

    // ==================== SDP Parsing ====================

    private void parseSDP(String sdp, String url) {
        byte[] sps = null, pps = null;
        int w = 0, h = 0;

        // Extract sprop-parameter-sets
        Pattern fmtp = Pattern.compile("sprop-parameter-sets=([A-Za-z0-9+/=]+),([A-Za-z0-9+/=]+)");
        Matcher m = fmtp.matcher(sdp);
        if (m.find()) {
            sps = Base64.decode(m.group(1), Base64.DEFAULT);
            pps = Base64.decode(m.group(2), Base64.DEFAULT);
            Log.i(TAG, "SDP: SPS=" + sps.length + "b PPS=" + pps.length + "b");
        }

        // Extract dimensions
        Pattern dim = Pattern.compile("a=framesize:\\d+\\s+(\\d+)-(\\d+)");
        Matcher dm = dim.matcher(sdp);
        if (dm.find()) {
            w = Integer.parseInt(dm.group(1));
            h = Integer.parseInt(dm.group(2));
        }
        if (w == 0) {
            Pattern clip = Pattern.compile("a=cliprect:\\d+,\\d+,(\\d+),(\\d+)");
            Matcher cm = clip.matcher(sdp);
            if (cm.find()) {
                h = Integer.parseInt(cm.group(1));
                w = Integer.parseInt(cm.group(2));
            }
        }

        Log.i(TAG, "SDP dimensions: " + w + "x" + h);
        if (callback != null) callback.onSdpParsed(sps, pps, w, h);
    }

    private String extractTrackUrl(String sdp, String baseUrl) {
        Pattern p = Pattern.compile("a=control:(.+)");
        Matcher m = p.matcher(sdp);
        String track = null;
        while (m.find()) {
            String ctrl = m.group(1).trim();
            if (ctrl.contains("video") || ctrl.contains("track")) {
                track = ctrl;
                break;
            }
            if (track == null) track = ctrl;
        }
        if (track == null) track = "trackID=0";
        if (track.startsWith("rtsp://")) return track;
        if (!baseUrl.endsWith("/") && !track.startsWith("/")) return baseUrl + "/" + track;
        return baseUrl + track;
    }

    private static void readFully(InputStream is, byte[] buf, int off, int len) throws IOException {
        int read = 0;
        while (read < len) {
            int n = is.read(buf, off + read, len - read);
            if (n < 0) throw new IOException("EOF while reading " + len + " bytes");
            read += n;
        }
    }
}
