package com.vivihud.companion;

import android.content.ComponentName;
import android.content.Context;
import android.media.MediaMetadata;
import android.media.session.MediaController;
import android.media.session.MediaSessionManager;
import android.media.session.PlaybackState;
import android.os.SystemClock;
import android.service.notification.NotificationListenerService;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * Reads whatever media session is active (Vivi Music, etc.) and serves it as JSON
 * on http://127.0.0.1:8765/now so the Minecraft mod can poll it.
 */
public class MusicListenerService extends NotificationListenerService {

    public static final int PORT = 8765;

    private volatile ServerSocket server;
    private volatile boolean running;

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
        String line;
        while ((line = r.readLine()) != null && !line.isEmpty()) {
            // discard request headers
        }
        byte[] body = buildJson().getBytes(StandardCharsets.UTF_8);
        OutputStream o = s.getOutputStream();
        String head = "HTTP/1.1 200 OK\r\n"
                + "Content-Type: application/json; charset=utf-8\r\n"
                + "Content-Length: " + body.length + "\r\n"
                + "Connection: close\r\n\r\n";
        o.write(head.getBytes(StandardCharsets.US_ASCII));
        o.write(body);
        o.flush();
    }

    private String buildJson() {
        try {
            MediaSessionManager msm =
                    (MediaSessionManager) getSystemService(Context.MEDIA_SESSION_SERVICE);
            List<MediaController> list = msm.getActiveSessions(
                    new ComponentName(this, MusicListenerService.class));

            MediaController pick = null;
            for (MediaController c : list) {
                PlaybackState st = c.getPlaybackState();
                if (st != null && st.getState() == PlaybackState.STATE_PLAYING) {
                    pick = c;
                    break;
                }
            }
            if (pick == null && !list.isEmpty()) pick = list.get(0);
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

            return "{\"ok\":true"
                    + ",\"title\":\"" + esc(title) + "\""
                    + ",\"artist\":\"" + esc(artist) + "\""
                    + ",\"playing\":" + playing
                    + ",\"position\":" + pos
                    + ",\"duration\":" + Math.max(duration, 0)
                    + ",\"pkg\":\"" + esc(pick.getPackageName()) + "\"}";
        } catch (SecurityException e) {
            return "{\"ok\":false,\"error\":\"no notification access\"}";
        } catch (Exception e) {
            return "{\"ok\":false,\"error\":\"internal\"}";
        }
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
