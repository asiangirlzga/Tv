package com.lite.hinditv;

import android.app.Activity;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
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
 * Video is always full screen. The channel bar (now playing + channel list) is a
 * transparent overlay at the bottom that auto-hides after a few seconds and comes
 * back on any remote key. Video quality is capped at 480p.
 *
 * Remote keys:
 *   Any of Up / Down / OK / Menu (bar hidden) -> show the channel bar
 *   Up / Down (bar visible)                   -> move through the channel list
 *   OK / Enter (bar visible)                  -> play the highlighted channel
 *   CH+ / CH-                                 -> previous / next channel directly
 *   Menu (bar visible)                        -> hide the bar now
 *   Back                                      -> hide the bar, or exit if it is already hidden
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
    private static final long STUCK_MS = 15000;     // buffering this long = treat as stuck
    private static final long AUTO_HIDE_MS = 5000;  // channel bar hides after this much idle time

    private final Handler ui = new Handler(Looper.getMainLooper());
    private final ArrayList<String> names = new ArrayList<>();
    private final ArrayList<String> urls = new ArrayList<>();
    private final ArrayList<String> referrers = new ArrayList<>();
    private final ArrayList<String> agents = new ArrayList<>();

    private FrameLayout root;
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
    private boolean panelShown = true;
    private int retries = 0;

    private final Runnable stuckCheck = new Runnable() {
        @Override
        public void run() {
            retry("Stream stalled");
        }
    };

    private final Runnable autoHide = new Runnable() {
        @Override
        public void run() {
            hidePanel();
        }
    };

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        float d = getResources().getDisplayMetrics().density;

        root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);

        // --- Full-screen video ---
        surface = new SurfaceView(this);
        root.addView(surface, new FrameLayout.LayoutParams(-1, -1, Gravity.CENTER));

        status = new TextView(this);
        status.setTextColor(Color.WHITE);
        status.setBackgroundColor(0xAA000000);
        status.setTextSize(18);
        status.setPadding((int) (12 * d), (int) (6 * d), (int) (12 * d), (int) (6 * d));
        FrameLayout.LayoutParams sp = new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.END);
        sp.setMargins(0, (int) (16 * d), (int) (16 * d), 0);
        root.addView(status, sp);
        status.setText("Loading channels…");

        // --- Transparent overlay at the bottom: now playing + channel list ---
        panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        // Fades from semi-dark at the bottom to fully transparent at the top.
        panel.setBackground(new GradientDrawable(GradientDrawable.Orientation.BOTTOM_TOP,
                new int[]{0xCC000000, 0x00000000}));
        panel.setPadding(0, (int) (24 * d), 0, 0);

        nowPlaying = new TextView(this);
        nowPlaying.setTextColor(0xFF66CCFF);
        nowPlaying.setTextSize(20);
        nowPlaying.setSingleLine(true);
        nowPlaying.setShadowLayer(4, 0, 0, Color.BLACK);
        nowPlaying.setPadding((int) (16 * d), (int) (8 * d), (int) (16 * d), (int) (8 * d));
        nowPlaying.setText("Hindi TV");
        panel.addView(nowPlaying, new LinearLayout.LayoutParams(-1, -2));

        list = new ListView(this);
        list.setSelector(new ColorDrawable(0x663366FF));
        list.setDivider(null);
        panel.addView(list, new LinearLayout.LayoutParams(-1, (int) (150 * d)));

        root.addView(panel, new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM));
        setContentView(root);

        adapter = new ArrayAdapter<String>(this, android.R.layout.simple_list_item_1, names) {
            @Override
            public View getView(int pos, View cv, ViewGroup parent) {
                TextView t = (TextView) super.getView(pos, cv, parent);
                boolean on = pos == current;
                t.setTextSize(18);
                t.setSingleLine(true);
                t.setShadowLayer(4, 0, 0, Color.BLACK);
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

        root.addOnLayoutChangeListener(new View.OnLayoutChangeListener() {
            @Override
            public void onLayoutChange(View v, int l, int t, int r, int bt,
                                       int ol, int ot, int or, int ob) {
                if (lastSize != null && (r - l != or - ol || bt - t != ob - ot)) fitVideo(lastSize);
            }
        });

        list.requestFocus();
        loadPlaylist();
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) {
            // True full screen: hide system bars.
            getWindow().getDecorView().setSystemUiVisibility(
                    View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                            | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                            | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                            | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                            | View.SYSTEM_UI_FLAG_FULLSCREEN
                            | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
        }
    }

    // ---- Channel bar show / auto-hide ---------------------------------------

    /** Show the bar. If hideLater is true it fades away after AUTO_HIDE_MS of no key presses. */
    private void showPanel(boolean hideLater) {
        ui.removeCallbacks(autoHide);
        panelShown = true;
        panel.animate().cancel();
        panel.setVisibility(View.VISIBLE);
        panel.animate().alpha(1f).setDuration(150).start();
        if (!list.hasFocus()) list.requestFocus();
        if (hideLater) ui.postDelayed(autoHide, AUTO_HIDE_MS);
    }

    private void hidePanel() {
        ui.removeCallbacks(autoHide);
        panelShown = false;
        panel.animate().cancel();
        panel.animate().alpha(0f).setDuration(300).withEndAction(new Runnable() {
            @Override
            public void run() {
                if (!panelShown) panel.setVisibility(View.GONE);
            }
        }).start();
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
        // Quality: best variant up to 480p. If a stream only offers higher
        // qualities, ExoPlayer falls back to the lowest one available.
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

    /** Fit the video to the full screen, keeping its aspect ratio (SurfaceView would otherwise stretch). */
    private void fitVideo(VideoSize vs) {
        int W = root.getWidth(), H = root.getHeight();
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
            showPanel(false);   // keep the list up so a new channel can be picked
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
        showPanel(true);
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
    public boolean dispatchKeyEvent(KeyEvent e) {
        if (e.getAction() != KeyEvent.ACTION_DOWN) return super.dispatchKeyEvent(e);
        int key = e.getKeyCode();

        if (key == KeyEvent.KEYCODE_BACK) {
            if (panelShown && playing) {
                hidePanel();
                return true;
            }
            return super.dispatchKeyEvent(e);   // bar already hidden: exit
        }

        if (key == KeyEvent.KEYCODE_CHANNEL_UP || key == KeyEvent.KEYCODE_PAGE_UP) {
            step(-1);
            showPanel(true);
            return true;
        }
        if (key == KeyEvent.KEYCODE_CHANNEL_DOWN || key == KeyEvent.KEYCODE_PAGE_DOWN) {
            step(1);
            showPanel(true);
            return true;
        }

        if (key == KeyEvent.KEYCODE_MENU) {
            if (panelShown) hidePanel(); else showPanel(true);
            return true;
        }

        if (!panelShown) {
            // First press only wakes the bar, so a stray key never changes the channel.
            switch (key) {
                case KeyEvent.KEYCODE_DPAD_UP:
                case KeyEvent.KEYCODE_DPAD_DOWN:
                case KeyEvent.KEYCODE_DPAD_CENTER:
                case KeyEvent.KEYCODE_ENTER:
                case KeyEvent.KEYCODE_DPAD_LEFT:
                case KeyEvent.KEYCODE_DPAD_RIGHT:
                    showPanel(true);
                    return true;
                default:
                    break;
            }
        } else {
            // Any key while the bar is up restarts the hide timer.
            ui.removeCallbacks(autoHide);
            ui.postDelayed(autoHide, AUTO_HIDE_MS);
        }
        return super.dispatchKeyEvent(e);
    }

    // ---- Lifecycle: free the decoder and buffers when not on screen --------

    @Override
    protected void onStop() {
        super.onStop();
        ui.removeCallbacks(autoHide);
        releasePlayer();
    }

    @Override
    protected void onStart() {
        super.onStart();
        if (playing && current >= 0 && player == null) startStream(current);
        if (playing) showPanel(true);
    }
}
