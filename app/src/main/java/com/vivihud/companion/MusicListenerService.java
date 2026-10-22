package com.vivihud.companion;

import android.content.ComponentName;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.media.MediaMetadata;
import android.media.session.MediaController;
import android.media.session.MediaSessionManager;
import android.media.session.PlaybackState;
import android.os.SystemClock;
import android.service.notification.NotificationListenerService;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * Serves on 127.0.0.1:8765
 *   /now        -> JSON (title, artist, position, color, art hash)
 *   /art        -> PNG album art (64x64)
 *   /cmd?c=...  -> pause | next | prev
 */
public class MusicListenerService extends NotificationListenerService {

    public static final int PORT = 8765;

    private volatile ServerSocket server;
    private volatile boolean running;

    // album art cache
    private String artKey = "";
    private byte[] artPng;
    private int artColor = 0x55FF88;

    @Override
    public void onCreate() {
        super.onCreate();
        startServer();
    }

    @Override
    public void onListenerConnected() {
        startServer();
    }

    @Override
    public void onListenerDisconnected() {
        requestRebind(new ComponentName(this, MusicListenerService.class));
    }

    @Override
    public void onDestroy() {
        stopServer();
        super.onDestroy();
    }

    private synchronized void startServer() {
        if (running) return;
        running = true;
        Thread t = new Thread(this::serve, "vivihud-http");
        t.setDaemon(true);
        t.start();
    }

    private synchronized void stopServer() {
        running = false;
        try {
            if (server != null) server.close();
        } catch (IOException ignored) {
        }
        server = null;
    }

    private void serve() {
        try (ServerSocket ss = new ServerSocket()) {
            ss.setReuseAddress(true);
            ss.bind(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), PORT));
            server = ss;
            while (running) {
                try (Socket s = ss.accept()) {
                    handle(s);
                } catch (IOException e) {
                    if (!running) break;
                }
            }
        } catch (IOException ignored) {
        } finally {
            running = false;
        }
    }

    private void handle(Socket s) throws IOException {
        s.setSoTimeout(2000);
        BufferedReader r = new BufferedReader(
                new InputStreamReader(s.getInputStream(), StandardCharsets.US_ASCII));
        String first = r.readLine();
        String line;
        while ((line = r.readLine()) != null && !line.isEmpty()) {
            // discard headers
        }
        String path = "/now";
        if (first != null) {
            String[] p = first.split(" ");
            if (p.length > 1) path = p[1];
        }

        OutputStream o = s.getOutputStream();
        if (path.startsWith("/art")) {
            byte[] png;
            synchronized (this) {
                png = artPng;
            }
            if (png == null) respond(o, "404 Not Found", "text/plain", new byte[0]);
            else respond(o, "200 OK", "image/png", png);
        } else if (path.startsWith("/cmd")) {
            String c = "";
            int i = path.indexOf("c=");
            if (i >= 0) c = path.substring(i + 2);
            boolean ok = doCommand(c);
            respond(o, "200 OK", "application/json", ("{\"ok\":" + ok + "}").getBytes(StandardCharsets.UTF_8));
        } else {
            respond(o, "200 OK", "application/json; charset=utf-8",
                    buildJson().getBytes(StandardCharsets.UTF_8));
        }
    }

    private static void respond(OutputStream o, String status, String type, byte[] body) throws IOException {
        String head = "HTTP/1.1 " + status + "\r\n"
                + "Content-Type: " + type + "\r\n"
                + "Content-Length: " + body.length + "\r\n"
                + "Connection: close\r\n\r\n";
        o.write(head.getBytes(StandardCharsets.US_ASCII));
        o.write(body);
        o.flush();
    }

    private MediaController pickController() {
        MediaSessionManager msm =
                (MediaSessionManager) getSystemService(Context.MEDIA_SESSION_SERVICE);
        List<MediaController> list = msm.getActiveSessions(
                new ComponentName(this, MusicListenerService.class));
        for (MediaController c : list) {
            PlaybackState st = c.getPlaybackState();
            if (st != null && st.getState() == PlaybackState.STATE_PLAYING) return c;
        }
        return list.isEmpty() ? null : list.get(0);
    }

    private boolean doCommand(String c) {
        try {
            MediaController mc = pickController();
            if (mc == null) return false;
            MediaController.TransportControls tc = mc.getTransportControls();
            switch (c) {
                case "pause":
                    PlaybackState st = mc.getPlaybackState();
                    if (st != null && st.getState() == PlaybackState.STATE_PLAYING) tc.pause();
                    else tc.play();
                    return true;
                case "next":
                    tc.skipToNext();
                    return true;
                case "prev":
                    tc.skipToPrevious();
                    return true;
                default:
                    return false;
            }
        } catch (Exception e) {
            return false;
        }
    }

    private String buildJson() {
        try {
            MediaController pick = pickController();
            if (pick == null) return "{\"ok\":false,\"error\":\"no session\"}";

            MediaMetadata md = pick.getMetadata();
            PlaybackState st = pick.getPlaybackState();
            if (md == null) return "{\"ok\":false,\"error\":\"no metadata\"}";

            String title = str(md.getString(MediaMetadata.METADATA_KEY_TITLE));
            String artist = str(md.getString(MediaMetadata.METADATA_KEY_ARTIST));
            if (artist.isEmpty()) artist = str(md.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST));
            long duration = md.getLong(MediaMetadata.METADATA_KEY_DURATION);

            boolean playing = st != null && st.getState() == PlaybackState.STATE_PLAYING;
            long pos = 0;
            if (st != null) {
                pos = st.getPosition();
                if (playing) {
                    long elapsed = SystemClock.elapsedRealtime() - st.getLastPositionUpdateTime();
                    pos += (long) (elapsed * st.getPlaybackSpeed());
                }
            }
            if (duration > 0 && pos > duration) pos = duration;
            if (pos < 0) pos = 0;

            updateArt(md, title, artist);
            int artHash;
            int color;
            synchronized (this) {
                artHash = artPng != null ? artKey.hashCode() | 1 : 0;
                color = artColor;
            }

            return "{\"ok\":true"
                    + ",\"title\":\"" + esc(title) + "\""
                    + ",\"artist\":\"" + esc(artist) + "\""
                    + ",\"playing\":" + playing
                    + ",\"position\":" + pos
                    + ",\"duration\":" + Math.max(duration, 0)
                    + ",\"color\":" + color
                    + ",\"art\":" + artHash
                    + ",\"pkg\":\"" + esc(pick.getPackageName()) + "\"}";
        } catch (SecurityException e) {
            return "{\"ok\":false,\"error\":\"no notification access\"}";
        } catch (Exception e) {
            return "{\"ok\":false,\"error\":\"internal\"}";
        }
    }

    // ---------- album art ----------

    private void updateArt(MediaMetadata md, String title, String artist) {
        Bitmap b = md.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART);
        if (b == null) b = md.getBitmap(MediaMetadata.METADATA_KEY_ART);
        if (b == null) b = md.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON);

        String uri = md.getString(MediaMetadata.METADATA_KEY_ALBUM_ART_URI);
        if (uri == null) uri = md.getString(MediaMetadata.METADATA_KEY_ART_URI);
        if (uri == null) uri = md.getString(MediaMetadata.METADATA_KEY_DISPLAY_ICON_URI);

        final String key = title + "|" + artist + "|" + (b != null ? "b" : "") + (uri != null ? uri : "");
        synchronized (this) {
            if (key.equals(artKey)) return;
            artKey = key;
            artPng = null;
        }

        if (b != null) {
            process(b, key);
        } else if (uri != null && uri.startsWith("http")) {
            final String u = uri;
            Thread t = new Thread(() -> {
                try {
                    HttpURLConnection c = (HttpURLConnection) new URL(u).openConnection();
                    c.setConnectTimeout(4000);
                    c.setReadTimeout(4000);
                    try (InputStream in = c.getInputStream()) {
                        Bitmap bm = BitmapFactory.decodeStream(in);
                        if (bm != null) process(bm, key);
                    } finally {
                        c.disconnect();
                    }
                } catch (Exception ignored) {
                }
            }, "vivihud-art");
            t.setDaemon(true);
            t.start();
        }
    }

    private void process(Bitmap src, String key) {
        try {
            Bitmap b = src;
            if (b.getConfig() == Bitmap.Config.HARDWARE) b = b.copy(Bitmap.Config.ARGB_8888, false);
            int size = Math.min(b.getWidth(), b.getHeight());
            Bitmap sq = Bitmap.createBitmap(b, (b.getWidth() - size) / 2, (b.getHeight() - size) / 2, size, size);
            Bitmap small = Bitmap.createScaledBitmap(sq, 64, 64, true);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            small.compress(Bitmap.CompressFormat.PNG, 100, out);
            int color = dominant(small);
            synchronized (this) {
                if (!key.equals(artKey)) return;
                artPng = out.toByteArray();
                artColor = color;
            }
        } catch (Exception ignored) {
        }
    }

    private static int dominant(Bitmap b) {
        Bitmap s = Bitmap.createScaledBitmap(b, 16, 16, true);
        float[] hsv = new float[3];
        double sx = 0, sy = 0, sw = 0, sat = 0;
        for (int yy = 0; yy < 16; yy++) {
            for (int xx = 0; xx < 16; xx++) {
                Color.colorToHSV(s.getPixel(xx, yy), hsv);
                double w = hsv[1] * hsv[2] + 0.01;
                double rad = Math.toRadians(hsv[0]);
                sx += Math.cos(rad) * w;
                sy += Math.sin(rad) * w;
                sat += hsv[1] * w;
                sw += w;
            }
        }
        double avgSat = sat / sw;
        if (avgSat < 0.12) return 0xDDDDDD;
        float hue = (float) Math.toDegrees(Math.atan2(sy, sx));
        if (hue < 0) hue += 360f;
        float sv = (float) Math.max(0.45, Math.min(1.0, avgSat));
        return Color.HSVToColor(new float[]{hue, sv, 0.9f}) & 0xFFFFFF;
    }

    private static String str(String s) {
        return s == null ? "" : s;
    }

    private static String esc(String s) {
        StringBuilder b = new StringBuilder(s.length() + 8);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"': b.append("\\\""); break;
                case '\\': b.append("\\\\"); break;
                case '\n': b.append("\\n"); break;
                case '\r': b.append("\\r"); break;
                case '\t': b.append("\\t"); break;
                default:
                    if (c < 0x20) b.append(String.format("\\u%04x", (int) c));
                    else b.append(c);
            }
        }
        return b.toString();
    }
}
