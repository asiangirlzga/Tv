package com.lite.hinditv;

import android.app.Activity;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.media.MediaPlayer;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.FrameLayout;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.VideoView;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;

/**
 * Minimal Android TV player for the iptv-org Hindi playlist.
 * No libraries, no images, no XML layouts: UI is built in code to keep RAM and APK size tiny.
 *
 * Remote keys:
 *   OK / Enter / Menu  -> show channel list
 *   Up / Down (list hidden) or CH+ / CH- -> previous / next channel
 *   Back -> hide list, or exit if the list is already hidden
 */
public class MainActivity extends Activity {

    private static final String PLAYLIST_URL = "https://iptv-org.github.io/iptv/languages/hin.m3u";
    private static final String CACHE_FILE = "hin.m3u";
    private static final String PREFS = "p";
    private static final String KEY_LAST = "last";

    private final Handler ui = new Handler(Looper.getMainLooper());
    private final ArrayList<String> names = new ArrayList<>();
    private final ArrayList<String> urls = new ArrayList<>();

    private VideoView video;
    private ListView list;
    private TextView status;
    private ArrayAdapter<String> adapter;
    private int current = -1;
    private boolean playing = false;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        float d = getResources().getDisplayMetrics().density;
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);

        video = new VideoView(this);
        FrameLayout.LayoutParams vp = new FrameLayout.LayoutParams(-1, -1, Gravity.CENTER);
        root.addView(video, vp);

        list = new ListView(this);
        list.setBackgroundColor(0xDD000000);
        list.setSelector(new ColorDrawable(0x883366FF));
        list.setDivider(null);
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams((int) (380 * d), -1, Gravity.START);
        root.addView(list, lp);

        status = new TextView(this);
        status.setTextColor(Color.WHITE);
        status.setBackgroundColor(0xAA000000);
        status.setTextSize(20);
        status.setPadding((int) (16 * d), (int) (8 * d), (int) (16 * d), (int) (8 * d));
        FrameLayout.LayoutParams sp = new FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM | Gravity.END);
        sp.setMargins(0, 0, (int) (24 * d), (int) (24 * d));
        root.addView(status, sp);
        status.setText("Loading channels…");

        setContentView(root);

        adapter = new ArrayAdapter<>(this, android.R.layout.simple_list_item_1, names);
        list.setAdapter(adapter);
        list.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @Override
            public void onItemClick(AdapterView<?> p, View v, int pos, long id) {
                play(pos);
                hideList();
            }
        });

        video.setOnPreparedListener(new MediaPlayer.OnPreparedListener() {
            @Override
            public void onPrepared(MediaPlayer mp) {
                status.setVisibility(View.GONE);
                mp.start();
            }
        });
        video.setOnErrorListener(new MediaPlayer.OnErrorListener() {
            @Override
            public boolean onError(MediaPlayer mp, int what, int extra) {
                playing = false;
                status.setVisibility(View.VISIBLE);
                status.setText("Can't play this channel. Pick another.");
                showList();
                return true;
            }
        });

        loadPlaylist();
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
                if (text != null) {
                    parse(text, n, u);
                    if (!n.isEmpty()) writeCache(cache, text);
                }
                if (n.isEmpty() && cache.exists()) {
                    String cached = readCache(cache);
                    if (cached != null) parse(cached, n, u);
                }
                ui.post(new Runnable() {
                    @Override
                    public void run() {
                        onPlaylist(n, u);
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

    static void parse(String text, ArrayList<String> n, ArrayList<String> u) {
        String name = null;
        for (String raw : text.split("\n")) {
            String line = raw.trim();
            if (line.isEmpty()) continue;
            if (line.startsWith("#EXTINF")) {
                int comma = line.lastIndexOf(',');
                name = comma >= 0 && comma < line.length() - 1 ? line.substring(comma + 1).trim() : "Channel";
            } else if (!line.startsWith("#") && name != null) {
                n.add(name);
                u.add(line);
                name = null;
            }
        }
    }

    private void onPlaylist(ArrayList<String> n, ArrayList<String> u) {
        if (n.isEmpty()) {
            status.setText("No channels. Check internet and restart.");
            return;
        }
        names.clear();
        names.addAll(n);
        urls.clear();
        urls.addAll(u);
        adapter.notifyDataSetChanged();
        int last = getSharedPreferences(PREFS, MODE_PRIVATE).getInt(KEY_LAST, 0);
        if (last < 0 || last >= urls.size()) last = 0;
        play(last);
        list.setSelection(last);
        hideList();
    }

    // ---- Playback ---------------------------------------------------------

    private void play(int i) {
        if (i < 0 || i >= urls.size()) return;
        current = i;
        playing = true;
        status.setVisibility(View.VISIBLE);
        status.setText(names.get(i) + "  …");
        video.stopPlayback();
        video.setVideoURI(Uri.parse(urls.get(i)));
        video.start();
        getSharedPreferences(PREFS, MODE_PRIVATE).edit().putInt(KEY_LAST, i).apply();
    }

    private void step(int delta) {
        if (urls.isEmpty()) return;
        int next = (current + delta + urls.size()) % urls.size();
        play(next);
        list.setSelection(next);
    }

    private void showList() {
        list.setVisibility(View.VISIBLE);
        list.requestFocus();
        if (current >= 0) list.setSelection(current);
    }

    private void hideList() {
        list.setVisibility(View.GONE);
    }

    // ---- Remote control ---------------------------------------------------

    @Override
    public boolean onKeyDown(int key, KeyEvent e) {
        boolean listShown = list.getVisibility() == View.VISIBLE;
        if (key == KeyEvent.KEYCODE_BACK) {
            if (listShown && playing) {
                hideList();
                return true;
            }
            return super.onKeyDown(key, e);
        }
        if (!listShown) {
            switch (key) {
                case KeyEvent.KEYCODE_DPAD_CENTER:
                case KeyEvent.KEYCODE_ENTER:
                case KeyEvent.KEYCODE_MENU:
                    showList();
                    return true;
                case KeyEvent.KEYCODE_DPAD_UP:
                case KeyEvent.KEYCODE_CHANNEL_UP:
                case KeyEvent.KEYCODE_PAGE_UP:
                    step(-1);
                    return true;
                case KeyEvent.KEYCODE_DPAD_DOWN:
                case KeyEvent.KEYCODE_CHANNEL_DOWN:
                case KeyEvent.KEYCODE_PAGE_DOWN:
                    step(1);
                    return true;
                default:
                    break;
            }
        }
        return super.onKeyDown(key, e);
    }

    // ---- Lifecycle: release the decoder when not on screen -----------------

    @Override
    protected void onPause() {
        super.onPause();
        video.stopPlayback();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (playing && current >= 0) play(current);
    }
}
