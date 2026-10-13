package com.lite.hinditv;

import android.app.Activity;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.SurfaceView;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;

import androidx.media3.common.MediaItem;
import androidx.media3.common.MimeTypes;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.common.VideoSize;
import androidx.media3.datasource.DefaultHttpDataSource;
import androidx.media3.exoplayer.DefaultLoadControl;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Minimal Android TV player for the iptv-org Hindi playlist.
 *
 * Layout: video on top, "now playing" bar and channel list always visible at the bottom.
 * Video quality is capped at 480p to save bandwidth, RAM and decoder load.
 *
 * Remote keys:
 *   Up / Down        -> move through the channel list
 *   OK / Enter       -> play the highlighted channel
 *   CH+ / CH-        -> previous / next channel directly
 *   Menu             -> hide / show the bottom panel (full-screen video)
 *   Back             -> exit
 */
public class MainActivity extends Activity {

    private static final String PLAYLIST_URL = "https://iptv-org.github.io/iptv/languages/hin.m3u";
    private static final String CACHE_FILE = "hin.m3u";
    private static final String PREFS = "p";
    private static final String KEY_LAST = "last";
    private static final String DEFAULT_UA =
            "Mozilla/5.0 (Linux; Android 11; TV) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0 Safari/537.36";

    // Quality cap: 480p (854x480).
    private static final int MAX_W = 854;
    private static final int MAX_H = 480;

    private static final int MAX_RETRIES = 4;
    private static final long STUCK_MS = 15000;   // buffering this long = treat as stuck

    private final Handler ui = new Handler(Looper.getMainLooper());
    private final ArrayList<String> names = new ArrayList<>();
    private final ArrayList<String> urls = new ArrayList<>();
    private final ArrayList<String> referrers = new ArrayList<>();
    private final ArrayList<String> agents = new ArrayList<>();

    private FrameLayout videoFrame;
    private SurfaceView surface;
    private LinearLayout panel;
    private TextView nowPlaying;
    private ExoPlayer player;
    private ListView list;
    private TextView status;
    private ArrayAdapter<String> adapter;
    private VideoSize lastSize;
    private int current = -1;
    private boolean playing = false;
    private int retries = 0;

    private final Runnable stuckCheck = new Runnable() {
        @Override
        public void run() {
            retry("Stream stalled");
        }
    };

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        float d = getResources().getDisplayMetrics().density;

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.BLACK);

        // --- Video area (fills everything above the bottom panel) ---
        videoFrame = new FrameLayout(this);
        surface = new SurfaceView(this);
        videoFrame.addView(surface, new FrameLayout.LayoutParams(-1, -1, Gravity.CENTER));

        status = new TextView(this);
        status.setTextColor(Color.WHITE);
        status.setBackgroundColor(0xAA000000);
        status.setTextSize(18);
        status.setPadding((int) (12 * d), (int) (6 * d), (int) (12 * d), (int) (6 * d));
        FrameLayout.LayoutParams sp = new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.END);
        sp.setMargins(0, (int) (16 * d), (int) (16 * d), 0);
        videoFrame.addView(status, sp);
        status.setText("Loading channels…");

        root.addView(videoFrame, new LinearLayout.LayoutParams(-1, 0, 1f));

        // --- Bottom panel: now playing + channel list, always visible ---
        panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setBackgroundColor(0xFF101010);

        nowPlaying = new TextView(this);
        nowPlaying.setTextColor(0xFF66CCFF);
        nowPlaying.setTextSize(20);
        nowPlaying.setSingleLine(true);
        nowPlaying.setBackgroundColor(0xFF000000);
        nowPlaying.setPadding((int) (16 * d), (int) (8 * d), (int) (16 * d), (int) (8 * d));
        nowPlaying.setText("Hindi TV");
        panel.addView(nowPlaying, new LinearLayout.LayoutParams(-1, -2));

        list = new ListView(this);
        list.setSelector(new ColorDrawable(0x883366FF));
        list.setDivider(null);
        panel.addView(list, new LinearLayout.LayoutParams(-1, (int) (150 * d)));

        root.addView(panel, new LinearLayout.LayoutParams(-1, -2));
        setContentView(root);

        adapter = new ArrayAdapter<String>(this, android.R.layout.simple_list_item_1, names) {
            @Override
            public View getView(int pos, View cv, ViewGroup parent) {
                TextView t = (TextView) super.getView(pos, cv, parent);
                boolean on = pos == current;
                t.setTextSize(18);
                t.setSingleLine(true);
                t.setTextColor(on ? 0xFF66CCFF : Color.WHITE);
                t.setText((on ? "▶  " : "     ") + getItem(pos));
                return t;
            }
        };
        list.setAdapter(adapter);
        list.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @Override
            public void onItemClick(AdapterView<?> p, View v, int pos, long id) {
                play(pos);
            }
        });

        // Re-fit the video whenever the video area changes size (panel shown/hidden).
        videoFrame.addOnLayoutChangeListener(new View.OnLayoutChangeListener() {
            @Override
            public void onLayoutChange(View v, int l, int t, int r, int bt,
                                       int ol, int ot, int or, int ob) {
                if (lastSize != null && (r - l != or - ol || bt - t != ob - ot)) fitVideo(lastSize);
            }
        });

        list.requestFocus();
        loadPlaylist();
    }

    // ---- Player -----------------------------------------------------------

    private void createPlayer() {
        if (player != null) return;
        // Small, bounded buffer: smooth on weak TV boxes without hogging RAM.
        DefaultLoadControl lc = new DefaultLoadControl.Builder()
                .setBufferDurationsMs(10000, 30000, 2000, 5000)
                .setTargetBufferBytes(8 * 1024 * 1024)
                .build();
        player = new ExoPlayer.Builder(this).setLoadControl(lc).build();
        // Quality: pick the best variant up to 480p. If a stream only offers
        // higher qualities, ExoPlayer falls back to the lowest one available.
        player.setTrackSelectionParameters(
                player.getTrackSelectionParameters().buildUpon().setMaxVideoSize(MAX_W, MAX_H).build());
        player.setVideoSurfaceView(surface);
        player.addListener(new Player.Listener() {
            @Override
            public void onPlaybackStateChanged(int state) {
                ui.removeCallbacks(stuckCheck);
                if (state == Player.STATE_BUFFERING) {
                    status.setVisibility(View.VISIBLE);
                    status.setText("Buffering…");
                    ui.postDelayed(stuckCheck, STUCK_MS);
                } else if (state == Player.STATE_READY) {
                    retries = 0;
                    status.setVisibility(View.GONE);
                }
            }

            @Override
            public void onPlayerError(PlaybackException e) {
                if (e.errorCode == PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW) {
                    player.seekToDefaultPosition();
                    player.prepare();
                } else {
                    retry("Playback error");
                }
            }

            @Override
            public void onVideoSizeChanged(VideoSize vs) {
                lastSize = vs;
                fitVideo(vs);
            }
        });
    }

    private void releasePlayer() {
        ui.removeCallbacks(stuckCheck);
        if (player != null) {
            player.release();
            player = null;
        }
    }

    /** Keep the video's aspect ratio inside the video area (SurfaceView would otherwise stretch). */
    private void fitVideo(VideoSize vs) {
        int W = videoFrame.getWidth(), H = videoFrame.getHeight();
        if (vs.width == 0 || vs.height == 0 || W == 0 || H == 0) return;
        float ratio = vs.width * vs.pixelWidthHeightRatio / vs.height;
        int w = W, h = (int) (W / ratio);
        if (h > H) {
            h = H;
            w = (int) (H * ratio);
        }
        surface.setLayoutParams(new FrameLayout.LayoutParams(w, h, Gravity.CENTER));
    }

    private void retry(String why) {
        if (player == null || current < 0) return;
        ui.removeCallbacks(stuckCheck);
        if (retries >= MAX_RETRIES) {
            status.setVisibility(View.VISIBLE);
            status.setText("Can't play this channel. Pick another.");
            return;
        }
        retries++;
        status.setVisibility(View.VISIBLE);
        status.setText(why + " – reconnecting (" + retries + "/" + MAX_RETRIES + ")…");
        final int idx = current;
        ui.postDelayed(new Runnable() {
            @Override
            public void run() {
                if (player != null && idx == current) startStream(idx);
            }
        }, 1500);
    }

    // ---- Playlist ---------------------------------------------------------

    private void loadPlaylist() {
        new Thread(new Runnable() {
            @Override
            public void run() {
                String text = download();
                File cache = new File(getFilesDir(), CACHE_FILE);
                final ArrayList<String> n = new ArrayList<>();
                final ArrayList<String> u = new ArrayList<>();
                final ArrayList<String> r = new ArrayList<>();
                final ArrayList<String> a = new ArrayList<>();
                if (text != null) {
                    parse(text, n, u, r, a);
                    if (!n.isEmpty()) writeCache(cache, text);
                }
                if (n.isEmpty() && cache.exists()) {
                    String cached = readCache(cache);
                    if (cached != null) parse(cached, n, u, r, a);
                }
                ui.post(new Runnable() {
                    @Override
                    public void run() {
                        onPlaylist(n, u, r, a);
                    }
                });
            }
        }).start();
    }

    private String download() {
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) new URL(PLAYLIST_URL).openConnection();
            c.setConnectTimeout(10000);
            c.setReadTimeout(15000);
            if (c.getResponseCode() != 200) return null;
            return slurp(c.getInputStream());
        } catch (Exception e) {
            return null;
        } finally {
            if (c != null) c.disconnect();
        }
    }

    private static String slurp(InputStream in) throws Exception {
        BufferedReader r = new BufferedReader(new InputStreamReader(in, "UTF-8"), 8192);
        StringBuilder sb = new StringBuilder();
        char[] buf = new char[8192];
        int k;
        while ((k = r.read(buf)) > 0) sb.append(buf, 0, k);
        r.close();
        return sb.toString();
    }

    private static void writeCache(File f, String text) {
        try {
            FileOutputStream o = new FileOutputStream(f);
            o.write(text.getBytes("UTF-8"));
            o.close();
        } catch (Exception ignored) {
        }
    }

    private static String readCache(File f) {
        try {
            return slurp(new FileInputStream(f));
        } catch (Exception e) {
            return null;
        }
    }

    /** Parses #EXTINF names, plus #EXTVLCOPT referrer / user-agent hints that some streams require. */
    static void parse(String text, ArrayList<String> n, ArrayList<String> u,
                      ArrayList<String> refs, ArrayList<String> uas) {
        String name = null, ref = null, ua = null;
        for (String raw : text.split("\n")) {
            String line = raw.trim();
            if (line.isEmpty()) continue;
            if (line.startsWith("#EXTINF")) {
                int comma = line.lastIndexOf(',');
                name = comma >= 0 && comma < line.length() - 1 ? line.substring(comma + 1).trim() : "Channel";
                ref = null;
                ua = null;
            } else if (line.startsWith("#EXTVLCOPT:")) {
                String o = line.substring(11);
                String lo = o.toLowerCase(Locale.ROOT);
                if (lo.startsWith("http-referrer=")) ref = o.substring(14).trim();
                else if (lo.startsWith("http-user-agent=")) ua = o.substring(16).trim();
            } else if (!line.startsWith("#") && name != null) {
                n.add(name);
                u.add(line);
                refs.add(ref);
                uas.add(ua);
                name = null;
            }
        }
    }

    private void onPlaylist(ArrayList<String> n, ArrayList<String> u,
                            ArrayList<String> r, ArrayList<String> a) {
        if (n.isEmpty()) {
            status.setText("No channels. Check internet and restart.");
            return;
        }
        names.clear();
        names.addAll(n);
        urls.clear();
        urls.addAll(u);
        referrers.clear();
        referrers.addAll(r);
        agents.clear();
        agents.addAll(a);
        int last = getSharedPreferences(PREFS, MODE_PRIVATE).getInt(KEY_LAST, 0);
        if (last < 0 || last >= urls.size()) last = 0;
        play(last);
        list.requestFocus();
    }

    // ---- Playback ---------------------------------------------------------

    private void play(int i) {
        if (i < 0 || i >= urls.size()) return;
        current = i;
        playing = true;
        retries = 0;
        nowPlaying.setText("▶  " + names.get(i) + "   (" + (i + 1) + "/" + names.size() + ")  · 480p");
        adapter.notifyDataSetChanged();
        list.setSelection(i);
        getSharedPreferences(PREFS, MODE_PRIVATE).edit().putInt(KEY_LAST, i).apply();
        startStream(i);
    }

    private void startStream(int i) {
        createPlayer();
        status.setVisibility(View.VISIBLE);
        status.setText("Loading…");

        String ua = agents.get(i) != null ? agents.get(i) : DEFAULT_UA;
        DefaultHttpDataSource.Factory http = new DefaultHttpDataSource.Factory()
                .setUserAgent(ua)
                .setAllowCrossProtocolRedirects(true)
                .setConnectTimeoutMs(10000)
                .setReadTimeoutMs(10000);
        if (referrers.get(i) != null) {
            Map<String, String> h = new HashMap<>();
            h.put("Referer", referrers.get(i));
            http.setDefaultRequestProperties(h);
        }

        String url = urls.get(i);
        MediaItem.Builder item = new MediaItem.Builder().setUri(url);
        if (url.toLowerCase(Locale.ROOT).contains("m3u8")) item.setMimeType(MimeTypes.APPLICATION_M3U8);

        player.setMediaSource(new DefaultMediaSourceFactory(http).createMediaSource(item.build()));
        player.prepare();
        player.setPlayWhenReady(true);
    }

    private void step(int delta) {
        if (urls.isEmpty()) return;
        play((current + delta + urls.size()) % urls.size());
    }

    // ---- Remote control ---------------------------------------------------

    @Override
    public boolean onKeyDown(int key, KeyEvent e) {
        switch (key) {
            case KeyEvent.KEYCODE_CHANNEL_UP:
            case KeyEvent.KEYCODE_PAGE_UP:
                step(-1);
                return true;
            case KeyEvent.KEYCODE_CHANNEL_DOWN:
            case KeyEvent.KEYCODE_PAGE_DOWN:
                step(1);
                return true;
            case KeyEvent.KEYCODE_MENU:
                // Optional full-screen toggle; the panel is visible by default.
                panel.setVisibility(panel.getVisibility() == View.VISIBLE ? View.GONE : View.VISIBLE);
                list.requestFocus();
                return true;
            default:
                // Up/Down/OK are handled by the focused list.
                return super.onKeyDown(key, e);
        }
    }

    // ---- Lifecycle: free the decoder and buffers when not on screen --------

    @Override
    protected void onStop() {
        super.onStop();
        releasePlayer();
    }

    @Override
    protected void onStart() {
        super.onStart();
        if (playing && current >= 0 && player == null) startStream(current);
    }
}
