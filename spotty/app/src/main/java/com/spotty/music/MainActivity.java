package com.spotty.music;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.DownloadManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.media.AudioAttributes;
import android.media.MediaPlayer;
import android.os.Bundle;
import android.os.Handler;
import android.os.Environment;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.Intent;
import android.net.Uri;
import android.widget.Button;
import android.widget.EditText;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletionService;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorCompletionService;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

public final class MainActivity extends Activity {
    private int BG, PANEL, PANEL_ALT, TEXT, MUTED, ACCENT;
    private static final int REQUEST_PLAYLIST_FILE = 7234;
    private static final String PREFS = "spotty_preferences";
    private static final String[] THEME_NAMES = {
            "Frutiger Aero", "Midwest Emo", "Synthwave", "Vaporwave",
            "Cyberpunk", "Aurora Forest", "AMOLED", "Y2K Glass",
            "Candy Pop", "Retro CRT", "Sunset Coast", "Dark Academia"
    };
    private static final String[] QUALITY_NAMES = {
            "Best available", "Prefer lossless", "Balanced (prefer high bitrate)", "Data saver"
    };

    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Executors.newFixedThreadPool(6);
    private String source = "youtube";
    private LinearLayout sourceRow;
    private EditText searchField;
    private TextView statusText;
    private TextView resultHeading;
    private TextView nowPlayingText;
    private TextView playPauseButton;
    private LinearLayout resultsList;
    private MediaPlayer player;
    private boolean paused;
    private boolean preparing;
    private Track currentTrack;
    private final List<Track> visibleTracks = new ArrayList<>();
    private int youtubeSearchVersion = 0;
    private final List<Track> playlistTracks = new ArrayList<>();
    private final List<Track> favoriteTracks = new ArrayList<>();
    private List<Track> playbackQueue = new ArrayList<>();
    private int playbackQueueIndex = -1;
    private TextView previousTrackButton;
    private TextView nextTrackButton;
    private String importedPlaylistName = "Imported playlist";
    private String activeTheme = "Frutiger Aero";
    private String activeQuality = "Best available";

    @Override
    public void onCreate(Bundle state) {
        super.onCreate(state);
        applyThemeFromPreferences();
        SharedPreferences savedPrefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        String savedPlaylist = savedPrefs.getString("imported_playlist_text", "");
        importedPlaylistName = savedPrefs.getString("imported_playlist_name", "Imported playlist");
        if (!savedPlaylist.trim().isEmpty()) playlistTracks.addAll(parsePlaylistTracks(savedPlaylist));
        favoriteTracks.addAll(readFavoriteTracks(savedPrefs.getString("favorite_tracks_json", "[]")));
        getWindow().setStatusBarColor(BG);
        getWindow().setNavigationBarColor(BG);
        getWindow().getDecorView().setSystemUiVisibility(0);
        buildUi();
        loadCurrentSource("");
        checkForUpdatesIfDue(false);
    }

    private void buildUi() {
        LinearLayout screen = new LinearLayout(this);
        screen.setOrientation(LinearLayout.VERTICAL);
        screen.setBackgroundColor(BG);
        screen.setPadding(dp(18), dp(10), dp(18), dp(8));

        LinearLayout header = new LinearLayout(this);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setOrientation(LinearLayout.HORIZONTAL);

        ImageView mark = new ImageView(this);
        mark.setImageResource(R.drawable.spotty_app_icon);
        mark.setScaleType(ImageView.ScaleType.FIT_CENTER);
        header.addView(mark, new LinearLayout.LayoutParams(dp(46), dp(46)));

        LinearLayout titleBlock = new LinearLayout(this);
        titleBlock.setOrientation(LinearLayout.VERTICAL);
        TextView title = text("spotty", 29, TEXT, true);
        title.setLetterSpacing(-0.03f);
        TextView subtitle = text("YOUR MUSIC, WITHOUT PREMIUM", 10, MUTED, true);
        subtitle.setLetterSpacing(0.09f);
        titleBlock.addView(title);
        titleBlock.addView(subtitle);
        header.addView(titleBlock, new LinearLayout.LayoutParams(0, -2, 1));

        TextView freePill = text("FREE", 11, ACCENT, true);
        freePill.setPadding(dp(12), dp(7), dp(12), dp(7));
        freePill.setBackground(roundRect(PANEL_ALT, 50));
        header.addView(freePill);
        screen.addView(header, new LinearLayout.LayoutParams(-1, -2));

        TextView intro = text("Discover popular sounds and underground finds.", 14, MUTED, false);
        LinearLayout.LayoutParams introLp = new LinearLayout.LayoutParams(-1, -2);
        introLp.topMargin = dp(8);
        introLp.bottomMargin = dp(15);
        screen.addView(intro, introLp);

        HorizontalScrollView sourceScroller = new HorizontalScrollView(this);
        sourceScroller.setHorizontalScrollBarEnabled(false);
        sourceRow = new LinearLayout(this);
        sourceRow.setOrientation(LinearLayout.HORIZONTAL);
        sourceRow.addView(sourceButton("YouTube • Morphe", "youtube"));
        sourceRow.addView(sourceButton("Morphe Music", "ytmusic"));
        sourceRow.addView(sourceButton("Audius", "audius"));
        sourceRow.addView(sourceButton("Open Archive", "archive"));
        sourceRow.addView(sourceButton("Radio", "radio"));
        sourceRow.addView(sourceButton("Playlists", "playlist"));
        sourceRow.addView(sourceButton("Liked", "liked"));
        sourceScroller.addView(sourceRow);
        screen.addView(sourceScroller, new LinearLayout.LayoutParams(-1, dp(44)));

        LinearLayout searchRow = new LinearLayout(this);
        searchRow.setGravity(Gravity.CENTER_VERTICAL);
        searchRow.setOrientation(LinearLayout.HORIZONTAL);
        searchField = new EditText(this);
        searchField.setSingleLine(true);
        searchField.setTextColor(TEXT);
        searchField.setHintTextColor(MUTED);
        searchField.setHint("Artists, tracks, genres…");
        searchField.setTextSize(15);
        searchField.setImeOptions(EditorInfo.IME_ACTION_SEARCH);
        searchField.setPadding(dp(14), 0, dp(10), 0);
        searchField.setBackground(roundRect(PANEL, 16));
        LinearLayout.LayoutParams inputLp = new LinearLayout.LayoutParams(0, dp(49), 1);
        inputLp.rightMargin = dp(8);
        searchRow.addView(searchField, inputLp);

        Button search = button("Search", ACCENT, BG);
        search.setOnClickListener(v -> submitSearch());
        searchRow.addView(search, new LinearLayout.LayoutParams(dp(88), dp(49)));
        searchField.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                submitSearch();
                return true;
            }
            return false;
        });
        LinearLayout.LayoutParams searchLp = new LinearLayout.LayoutParams(-1, -2);
        searchLp.topMargin = dp(12);
        screen.addView(searchRow, searchLp);

        LinearLayout utilityRow = new LinearLayout(this);
        utilityRow.setOrientation(LinearLayout.HORIZONTAL);
        utilityRow.setGravity(Gravity.CENTER_VERTICAL);
        String[] utilityLabels = {"Import playlist", "Themes", "Settings"};
        View.OnClickListener[] utilityActions = {
                v -> showPlaylistImportDialog(),
                v -> showThemeDialog(),
                v -> showSettingsDialog()
        };
        for (int i = 0; i < utilityLabels.length; i++) {
            Button utility = button(utilityLabels[i], PANEL_ALT, TEXT);
            utility.setTextSize(11);
            utility.setOnClickListener(utilityActions[i]);
            LinearLayout.LayoutParams utilityLp = new LinearLayout.LayoutParams(0, dp(40), 1);
            utilityLp.rightMargin = i < utilityLabels.length - 1 ? dp(7) : 0;
            utilityRow.addView(utility, utilityLp);
        }
        LinearLayout.LayoutParams utilityLp = new LinearLayout.LayoutParams(-1, -2);
        utilityLp.topMargin = dp(8);
        screen.addView(utilityRow, utilityLp);

        HorizontalScrollView genreScroll = new HorizontalScrollView(this);
        genreScroll.setHorizontalScrollBarEnabled(false);
        LinearLayout genres = new LinearLayout(this);
        genres.setOrientation(LinearLayout.HORIZONTAL);
        for (String genre : new String[]{"shoegaze", "dream pop", "ambient", "post-rock", "indie", "electronic", "hip-hop"}) {
            TextView chip = text(genre, 12, TEXT, false);
            chip.setPadding(dp(12), dp(8), dp(12), dp(8));
            chip.setBackground(roundRect(PANEL_ALT, 18));
            LinearLayout.LayoutParams chipLp = new LinearLayout.LayoutParams(-2, -2);
            chipLp.rightMargin = dp(7);
            chip.setOnClickListener(v -> {
                searchField.setText(genre);
                source = "youtube";
                refreshSourceButtons(sourceRow);
                loadYoutube(genre);
            });
            genres.addView(chip, chipLp);
        }
        genreScroll.addView(genres);
        LinearLayout.LayoutParams genreLp = new LinearLayout.LayoutParams(-1, -2);
        genreLp.topMargin = dp(10);
        genreLp.bottomMargin = dp(13);
        screen.addView(genreScroll, genreLp);

        LinearLayout statusRow = new LinearLayout(this);
        statusRow.setGravity(Gravity.CENTER_VERTICAL);
        statusRow.setOrientation(LinearLayout.HORIZONTAL);
        resultHeading = text("DISCOVER", 11, ACCENT, true);
        resultHeading.setLetterSpacing(0.13f);
        statusRow.addView(resultHeading, new LinearLayout.LayoutParams(-2, -2));
        statusText = text("Connecting…", 12, MUTED, false);
        statusText.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
        statusRow.addView(statusText, new LinearLayout.LayoutParams(0, -2, 1));
        screen.addView(statusRow, new LinearLayout.LayoutParams(-1, dp(29)));

        ScrollView resultScroller = new ScrollView(this);
        resultScroller.setFillViewport(false);
        resultScroller.setVerticalScrollBarEnabled(false);
        resultsList = new LinearLayout(this);
        resultsList.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams resultsLp = new LinearLayout.LayoutParams(-1, 0, 1);
        resultsLp.topMargin = dp(4);
        screen.addView(resultScroller, resultsLp);
        resultScroller.addView(resultsList);

        LinearLayout mini = new LinearLayout(this);
        mini.setGravity(Gravity.CENTER_VERTICAL);
        mini.setOrientation(LinearLayout.HORIZONTAL);
        mini.setPadding(dp(13), dp(9), dp(10), dp(9));
        mini.setBackground(roundRect(PANEL, 17));
        LinearLayout miniText = new LinearLayout(this);
        miniText.setOrientation(LinearLayout.VERTICAL);
        TextView miniLabel = text("NOW PLAYING", 9, ACCENT, true);
        miniLabel.setLetterSpacing(0.1f);
        nowPlayingText = text("Pick a track to start", 13, TEXT, true);
        nowPlayingText.setMaxLines(1);
        miniText.addView(miniLabel);
        LinearLayout.LayoutParams nowLp = new LinearLayout.LayoutParams(-1, -2);
        nowLp.topMargin = dp(3);
        miniText.addView(nowPlayingText, nowLp);
        mini.addView(miniText, new LinearLayout.LayoutParams(0, -2, 1));

        previousTrackButton = text("⏮", 16, TEXT, true);
        previousTrackButton.setGravity(Gravity.CENTER);
        previousTrackButton.setBackground(roundRect(PANEL_ALT, 50));
        previousTrackButton.setOnClickListener(v -> playPreviousTrack());
        LinearLayout.LayoutParams previousLp = new LinearLayout.LayoutParams(dp(38), dp(38));
        previousLp.rightMargin = dp(5);
        mini.addView(previousTrackButton, previousLp);

        playPauseButton = text("▶", 22, ACCENT, true);
        playPauseButton.setGravity(Gravity.CENTER);
        playPauseButton.setBackground(roundRect(PANEL_ALT, 50));
        playPauseButton.setOnClickListener(v -> togglePlayback());
        mini.addView(playPauseButton, new LinearLayout.LayoutParams(dp(42), dp(42)));

        nextTrackButton = text("⏭", 16, TEXT, true);
        nextTrackButton.setGravity(Gravity.CENTER);
        nextTrackButton.setBackground(roundRect(PANEL_ALT, 50));
        nextTrackButton.setOnClickListener(v -> playNextTrack());
        LinearLayout.LayoutParams nextLp = new LinearLayout.LayoutParams(dp(38), dp(38));
        nextLp.leftMargin = dp(5);
        mini.addView(nextTrackButton, nextLp);

        LinearLayout.LayoutParams miniLp = new LinearLayout.LayoutParams(-1, -2);
        miniLp.topMargin = dp(8);
        screen.addView(mini, miniLp);

        setContentView(screen);
    }

    private View sourceButton(String label, String value) {
        TextView b = text(label, 13, value.equals(source) ? BG : TEXT, true);
        b.setGravity(Gravity.CENTER);
        b.setPadding(dp(15), 0, dp(15), 0);
        b.setBackground(roundRect(value.equals(source) ? ACCENT : PANEL_ALT, 18));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, dp(38));
        lp.rightMargin = dp(8);
        b.setTag(value);
        b.setOnClickListener(v -> {
            source = value;
            LinearLayout row = (LinearLayout) ((HorizontalScrollView) b.getParent().getParent()).getChildAt(0);
            refreshSourceButtons(row);
            loadCurrentSource(searchField.getText().toString().trim());
        });
        b.setLayoutParams(lp);
        return b;
    }

    private void refreshSourceButtons(LinearLayout row) {
        for (int i = 0; i < row.getChildCount(); i++) {
            View child = row.getChildAt(i);
            if (!(child instanceof TextView)) continue;
            String value = String.valueOf(child.getTag());
            boolean selected = value.equals(source);
            ((TextView) child).setTextColor(selected ? BG : TEXT);
            child.setBackground(roundRect(selected ? ACCENT : PANEL_ALT, 18));
        }
    }

    private void applyThemeFromPreferences() {
        SharedPreferences prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        activeTheme = prefs.getString("theme", "Frutiger Aero");
        activeQuality = prefs.getString("quality", "Best available");
        switch (activeTheme) {
            case "Midwest Emo":
                BG = Color.rgb(23, 20, 30); PANEL = Color.rgb(43, 36, 49);
                PANEL_ALT = Color.rgb(66, 52, 71); TEXT = Color.rgb(248, 237, 235);
                MUTED = Color.rgb(190, 169, 183); ACCENT = Color.rgb(237, 164, 202); break;
            case "Synthwave":
                BG = Color.rgb(20, 10, 40); PANEL = Color.rgb(39, 21, 67);
                PANEL_ALT = Color.rgb(59, 32, 92); TEXT = Color.rgb(255, 242, 255);
                MUTED = Color.rgb(200, 169, 224); ACCENT = Color.rgb(255, 82, 211); break;
            case "Vaporwave":
                BG = Color.rgb(25, 23, 54); PANEL = Color.rgb(43, 40, 83);
                PANEL_ALT = Color.rgb(64, 57, 112); TEXT = Color.rgb(248, 243, 255);
                MUTED = Color.rgb(193, 184, 234); ACCENT = Color.rgb(95, 239, 236); break;
            case "Cyberpunk":
                BG = Color.rgb(7, 15, 18); PANEL = Color.rgb(17, 31, 38);
                PANEL_ALT = Color.rgb(31, 51, 59); TEXT = Color.rgb(244, 252, 255);
                MUTED = Color.rgb(160, 185, 191); ACCENT = Color.rgb(236, 255, 0); break;
            case "Aurora Forest":
                BG = Color.rgb(8, 29, 27); PANEL = Color.rgb(17, 54, 49);
                PANEL_ALT = Color.rgb(30, 79, 68); TEXT = Color.rgb(238, 255, 247);
                MUTED = Color.rgb(164, 208, 190); ACCENT = Color.rgb(120, 255, 180); break;
            case "AMOLED":
                BG = Color.BLACK; PANEL = Color.rgb(17, 17, 21);
                PANEL_ALT = Color.rgb(35, 35, 42); TEXT = Color.WHITE;
                MUTED = Color.rgb(170, 170, 180); ACCENT = Color.rgb(133, 235, 158); break;
            case "Y2K Glass":
                BG = Color.rgb(24, 35, 75); PANEL = Color.rgb(50, 72, 125);
                PANEL_ALT = Color.rgb(76, 111, 174); TEXT = Color.rgb(248, 253, 255);
                MUTED = Color.rgb(192, 220, 245); ACCENT = Color.rgb(255, 224, 145); break;
            case "Candy Pop":
                BG = Color.rgb(54, 25, 57); PANEL = Color.rgb(95, 43, 91);
                PANEL_ALT = Color.rgb(132, 59, 118); TEXT = Color.rgb(255, 245, 252);
                MUTED = Color.rgb(238, 194, 224); ACCENT = Color.rgb(255, 172, 211); break;
            case "Retro CRT":
                BG = Color.rgb(8, 24, 18); PANEL = Color.rgb(15, 43, 30);
                PANEL_ALT = Color.rgb(25, 67, 43); TEXT = Color.rgb(218, 255, 209);
                MUTED = Color.rgb(139, 202, 137); ACCENT = Color.rgb(127, 255, 91); break;
            case "Sunset Coast":
                BG = Color.rgb(54, 28, 61); PANEL = Color.rgb(100, 49, 86);
                PANEL_ALT = Color.rgb(143, 67, 98); TEXT = Color.rgb(255, 247, 235);
                MUTED = Color.rgb(242, 194, 180); ACCENT = Color.rgb(255, 198, 119); break;
            case "Dark Academia":
                BG = Color.rgb(29, 26, 22); PANEL = Color.rgb(54, 45, 35);
                PANEL_ALT = Color.rgb(78, 62, 45); TEXT = Color.rgb(247, 237, 213);
                MUTED = Color.rgb(197, 179, 145); ACCENT = Color.rgb(214, 175, 91); break;
            case "Frutiger Aero":
            default:
                activeTheme = "Frutiger Aero";
                BG = Color.rgb(8, 31, 47); PANEL = Color.rgb(19, 68, 83);
                PANEL_ALT = Color.rgb(28, 104, 121); TEXT = Color.rgb(244, 255, 255);
                MUTED = Color.rgb(166, 220, 229); ACCENT = Color.rgb(104, 246, 204); break;
        }
    }

    private void showThemeDialog() {
        int selected = 0;
        for (int i = 0; i < THEME_NAMES.length; i++) if (THEME_NAMES[i].equals(activeTheme)) selected = i;
        new AlertDialog.Builder(this)
                .setTitle("Choose your Spotty universe")
                .setSingleChoiceItems(THEME_NAMES, selected, (dialog, which) -> {
                    getSharedPreferences(PREFS, MODE_PRIVATE).edit().putString("theme", THEME_NAMES[which]).apply();
                    dialog.dismiss();
                    recreate();
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void showSettingsDialog() {
        new AlertDialog.Builder(this)
                .setTitle("Spotty settings")
                .setItems(new String[]{"Audio quality preference", "Check for updates now", "About Spotty"}, (dialog, which) -> {
                    if (which == 0) showQualityDialog();
                    else if (which == 1) checkForUpdatesIfDue(true);
                    else new AlertDialog.Builder(this).setTitle("Spotty")
                            .setMessage("A source-first music and video discovery app. YouTube playback is handed to Morphe/YouTube. Spotty never claims to unlock Spotify Premium or upsample audio beyond the source quality.")
                            .setPositiveButton("Done", null).show();
                }).show();
    }

    private void showQualityDialog() {
        int selected = 0;
        for (int i = 0; i < QUALITY_NAMES.length; i++) if (QUALITY_NAMES[i].equals(activeQuality)) selected = i;
        new AlertDialog.Builder(this)
                .setTitle("Preferred source quality")
                .setSingleChoiceItems(QUALITY_NAMES, selected, (dialog, which) -> {
                    activeQuality = QUALITY_NAMES[which];
                    getSharedPreferences(PREFS, MODE_PRIVATE).edit().putString("quality", activeQuality).apply();
                    dialog.dismiss();
                    statusText.setText("Quality preference saved • source quality is never artificially upsampled");
                })
                .setMessage("Spotty selects the best matching file that a licensed source actually offers. 320 kbps or lossless is not guaranteed for every track.")
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void checkForUpdatesIfDue(boolean force) {
        SharedPreferences prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        long last = prefs.getLong("last_update_check", 0L);
        if (!force && System.currentTimeMillis() - last < TimeUnit.DAYS.toMillis(30)) return;
        statusText.setText("Checking Spotty updates…");
        io.execute(() -> {
            try {
                checkMorpheManagerUpdates(prefs);
                JSONArray releases = requestJsonArray("https://api.github.com/repos/jazplaysmcpe2-cloud/spotatui-android-build/releases?per_page=100");
                JSONObject release = null;
                for (int i = 0; i < releases.length(); i++) {
                    JSONObject candidate = releases.optJSONObject(i);
                    if (candidate != null && candidate.optString("tag_name", "").startsWith("spotty-android-")) {
                        release = candidate;
                        break;
                    }
                }
                long now = System.currentTimeMillis();
                prefs.edit().putLong("last_update_check", now).apply();
                if (release == null) {
                    main.post(() -> statusText.setText("Update check complete • no Spotty release published yet"));
                    return;
                }
                String tag = release.optString("tag_name", "");
                String name = release.optString("name", "Spotty update");
                String url = release.optString("html_url", "https://github.com/jazplaysmcpe2-cloud/spotatui-android-build/releases");
                String body = release.optString("body", "");
                int remoteBuild = 0;
                try {
                    String[] tagParts = tag.substring("spotty-android-".length()).split("-");
                    remoteBuild = Integer.parseInt(tagParts[0]);
                } catch (Exception ignored) {}
                int localBuild = 1;
                try {
                    localBuild = getPackageManager().getPackageInfo(getPackageName(), 0).versionCode;
                } catch (Exception ignored) {}
                final int remoteBuildNumber = remoteBuild;
                final int localBuildNumber = localBuild;
                prefs.edit().putString("last_update_tag", tag).apply();
                String notified = prefs.getString("notified_release_tag", "");
                main.post(() -> {
                    statusText.setText("Update check complete • " + (tag.isEmpty() ? "no release tag" : tag));
                    if (!tag.isEmpty() && remoteBuildNumber > localBuildNumber && !tag.equals(notified)) {
                        prefs.edit().putString("notified_release_tag", tag).apply();
                        new AlertDialog.Builder(this).setTitle(name)
                                .setMessage((body.isEmpty() ? "A newer Spotty build is available." : body) + "\n\nAndroid requires you to confirm installation; Spotty cannot silently install APK updates.")
                                .setPositiveButton("View release", (d, w) -> {
                                    try { startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url))); }
                                    catch (Exception e) { statusText.setText("Could not open release page."); }
                                }).setNegativeButton("Later", null).show();
                    }
                });
            } catch (Exception e) {
                main.post(() -> statusText.setText("Update check unavailable • try again in Settings"));
            }
        });
    }

    private void checkMorpheManagerUpdates(SharedPreferences prefs) {
        try {
            JSONObject release = requestJsonObject("https://api.github.com/repos/MorpheApp/morphe-manager/releases/latest");
            String tag = release.optString("tag_name", "");
            String url = release.optString("html_url", "https://github.com/MorpheApp/morphe-manager/releases");
            if (tag.isEmpty()) return;
            String previous = prefs.getString("morphe_last_seen_tag", "");
            prefs.edit().putString("morphe_last_seen_tag", tag).apply();
            if (!previous.isEmpty() && !previous.equals(tag)) {
                main.post(() -> new AlertDialog.Builder(this)
                        .setTitle("Morphe update available")
                        .setMessage("A new Morphe manager release (" + tag + ") is available. Spotty will not silently patch or replace other apps; review the official release and apply patches yourself.")
                        .setPositiveButton("Open official release", (dialog, which) -> {
                            try { startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url))); }
                            catch (Exception e) { statusText.setText("Could not open the Morphe release page."); }
                        })
                        .setNegativeButton("Later", null)
                        .show());
            }
        } catch (Exception ignored) {
            // Morphe's release endpoint is optional; an outage must not block Spotty startup or its own update check.
        }
    }

    private void showPlaylistImportDialog() {
        EditText input = new EditText(this);
        input.setHint("Paste exported track rows (Title, Artist) or M3U text");
        input.setTextSize(13);
        input.setMinLines(5);
        input.setGravity(Gravity.TOP | Gravity.START);
        input.setPadding(dp(12), dp(10), dp(12), dp(10));
        input.setBackground(roundRect(PANEL_ALT, 12));
        new AlertDialog.Builder(this)
                .setTitle("Import a playlist")
                .setMessage("Premium-free import: paste a title/artist list or choose Spotify Playlist*.json / YourLibrary.json, CSV, M3U, or text. To get Spotify JSON, request Download your data → Account data → Playlist, unzip it, then choose the Playlist*.json file. Spotty imports metadata only and searches sources you can play.")
                .setView(input)
                .setPositiveButton("Import text", (d, w) -> importPlaylistText(input.getText().toString()))
                .setNeutralButton("Choose file", (d, w) -> choosePlaylistFile())
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void choosePlaylistFile() {
        try {
            Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
            intent.addCategory(Intent.CATEGORY_OPENABLE);
            intent.setType("*/*");
            intent.putExtra(Intent.EXTRA_MIME_TYPES, new String[]{"application/json", "text/json", "text/csv", "text/plain", "application/vnd.ms-excel", "audio/x-mpegurl", "application/x-mpegURL"});
            startActivityForResult(intent, REQUEST_PLAYLIST_FILE);
        } catch (Exception e) {
            statusText.setText("Could not open the file picker.");
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQUEST_PLAYLIST_FILE || resultCode != RESULT_OK || data == null || data.getData() == null) return;
        try (InputStream stream = getContentResolver().openInputStream(data.getData());
             BufferedReader reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
            StringBuilder contents = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                contents.append(line).append('\n');
                if (contents.length() > 15000000) throw new Exception("Playlist file is too large. Choose a single Playlist*.json file or CSV.");
            }
            importPlaylistText(contents.toString());
        } catch (Exception e) {
            showError("Playlist import failed: " + shortError(e));
        }
    }

    private void importPlaylistText(String raw) {
        if (raw == null || raw.trim().isEmpty()) {
            showError("The playlist text or file is empty.");
            return;
        }
        String trimmed = raw.trim();
        if (trimmed.matches("(?is)^https?://(open\\.)?spotify\\.com/(playlist|embed/playlist)/[^\\s]+/?([?].*)?$")) {
            new AlertDialog.Builder(this).setTitle("Spotify link detected")
                    .setMessage("A Spotify share link identifies the playlist but doesn't expose its complete track list to an unauthenticated third-party app. Export its tracks as CSV or paste the track names here; Spotty will map them to sources you can play.")
                    .setPositiveButton("Open Spotify", (d, w) -> {
                        try { startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(trimmed))); } catch (Exception ignored) {}
                    }).setNeutralButton("Choose CSV", (d, w) -> choosePlaylistFile()).setNegativeButton("Cancel", null).show();
            return;
        }
        if (trimmed.startsWith("{") && importSpotifyExportJson(trimmed)) return;
        List<Track> imported = parsePlaylistTracks(raw);
        if (imported.isEmpty()) {
            showError("No tracks found. Choose Spotify Playlist*.json / YourLibrary.json, a CSV with Track Name and Artist columns, an M3U/text file, or paste one track per line.");
            return;
        }
        applyImportedPlaylist(imported, "Imported playlist");
    }

    private boolean importSpotifyExportJson(String raw) {
        try {
            JSONObject root = new JSONObject(raw);
            JSONArray playlists = root.optJSONArray("playlists");
            if (playlists != null) {
                List<String> names = new ArrayList<>();
                List<List<Track>> importedPlaylists = new ArrayList<>();
                for (int i = 0; i < playlists.length(); i++) {
                    JSONObject playlist = playlists.optJSONObject(i);
                    if (playlist == null) continue;
                    String name = jsonString(playlist, "name", "playlistName", "title");
                    if (name.isEmpty()) name = "Spotify playlist " + (i + 1);
                    JSONArray items = playlist.optJSONArray("items");
                    if (items == null) items = playlist.optJSONArray("tracks");
                    List<Track> tracks = parseSpotifyExportItems(items);
                    if (!tracks.isEmpty()) {
                        names.add(name);
                        importedPlaylists.add(tracks);
                    }
                }
                if (importedPlaylists.isEmpty()) {
                    showError("This Spotify JSON file contains no track rows. Choose a Playlist*.json export with items.");
                } else if (importedPlaylists.size() == 1) {
                    applyImportedPlaylist(importedPlaylists.get(0), names.get(0));
                } else {
                    String[] choices = new String[names.size()];
                    for (int i = 0; i < names.size(); i++) {
                        choices[i] = names.get(i) + "  •  " + importedPlaylists.get(i).size() + " tracks";
                    }
                    new AlertDialog.Builder(this)
                            .setTitle("Choose a Spotify playlist")
                            .setItems(choices, (dialog, which) -> {
                                if (which >= 0 && which < importedPlaylists.size()) {
                                    applyImportedPlaylist(importedPlaylists.get(which), names.get(which));
                                }
                            })
                            .setNegativeButton("Cancel", null)
                            .show();
                }
                return true;
            }

            JSONArray tracks = root.optJSONArray("tracks");
            if (tracks == null) tracks = root.optJSONArray("items");
            if (tracks != null) {
                List<Track> imported = parseSpotifyExportItems(tracks);
                if (imported.isEmpty()) {
                    showError("No track rows were found in this Spotify JSON file.");
                } else {
                    String name = jsonString(root, "name", "playlistName", "title");
                    if (name.isEmpty()) name = "Spotify export";
                    applyImportedPlaylist(imported, name);
                }
                return true;
            }
        } catch (Exception ignored) {
            // Not a supported Spotify JSON export; continue with CSV / plain-text parsing.
        }
        return false;
    }

    private List<Track> parseSpotifyExportItems(JSONArray items) {
        List<Track> tracks = new ArrayList<>();
        if (items == null) return tracks;
        for (int i = 0; i < items.length() && tracks.size() < 1000; i++) {
            JSONObject entry = items.optJSONObject(i);
            if (entry == null) continue;
            JSONObject track = entry.optJSONObject("track");
            if (track == null) track = entry.optJSONObject("item");
            if (track == null) track = entry;
            String title = jsonString(track, "trackName", "name", "title", "track_name", "track");
            if (title.isEmpty() && track != entry) {
                title = jsonString(entry, "trackName", "name", "title", "track_name", "track");
            }
            String artist = jsonString(track, "artistName", "artist_name", "artist", "creator", "artists");
            if (artist.isEmpty() && track != entry) {
                artist = jsonString(entry, "artistName", "artist_name", "artist", "creator", "artists");
            }
            addImportedTrack(tracks, title, artist);
        }
        return tracks;
    }

    private String jsonString(JSONObject object, String... keys) {
        if (object == null) return "";
        for (String key : keys) {
            Object value = object.opt(key);
            if (value instanceof String) {
                String result = ((String) value).trim();
                if (!result.isEmpty() && !"null".equalsIgnoreCase(result)) return result;
            } else if (value instanceof JSONObject) {
                String name = ((JSONObject) value).optString("name", "").trim();
                if (!name.isEmpty() && !"null".equalsIgnoreCase(name)) return name;
            } else if (value instanceof JSONArray) {
                JSONArray values = (JSONArray) value;
                StringBuilder joined = new StringBuilder();
                for (int i = 0; i < values.length(); i++) {
                    Object item = values.opt(i);
                    String name = "";
                    if (item instanceof JSONObject) name = ((JSONObject) item).optString("name", "").trim();
                    else if (item instanceof String) name = ((String) item).trim();
                    if (!name.isEmpty() && !"null".equalsIgnoreCase(name)) {
                        if (joined.length() > 0) joined.append(", ");
                        joined.append(name);
                    }
                }
                if (joined.length() > 0) return joined.toString();
            }
        }
        return "";
    }

    private void applyImportedPlaylist(List<Track> imported, String playlistName) {
        if (imported == null || imported.isEmpty()) {
            showError("This playlist contains no importable tracks.");
            return;
        }
        playlistTracks.clear();
        playlistTracks.addAll(imported);
        importedPlaylistName = playlistName == null || playlistName.trim().isEmpty()
                ? "Imported playlist" : playlistName.trim();
        String normalized = serializePlaylistTracks(playlistTracks);
        getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                .putString("imported_playlist_text", normalized)
                .putString("imported_playlist_name", importedPlaylistName)
                .apply();
        source = "playlist";
        refreshSourceButtons(sourceRow);
        showTracks(playlistTracks, playlistTracks.size() + " imported tracks • tap one to find it on YouTube/Morphe");
        resultHeading.setText("PLAYLIST • " + importedPlaylistName.toUpperCase(Locale.ROOT));
        statusText.setText("Playlist imported and saved on this device");
    }

    private String serializePlaylistTracks(List<Track> tracks) {
        try {
            JSONObject root = new JSONObject();
            JSONArray items = new JSONArray();
            for (Track track : tracks) {
                JSONObject item = new JSONObject();
                item.put("title", track.title);
                item.put("artist", track.artist);
                items.put(item);
            }
            root.put("tracks", items);
            return root.toString();
        } catch (Exception e) {
            StringBuilder fallback = new StringBuilder();
            for (Track track : tracks) fallback.append(track.title).append(" — ").append(track.artist).append('\n');
            return fallback.toString();
        }
    }

    private List<Track> parsePlaylistTracks(String raw) {
        List<Track> found = new ArrayList<>();
        if (raw == null) return found;
        String trimmed = raw.trim();
        if (trimmed.startsWith("{")) {
            try {
                JSONObject root = new JSONObject(trimmed);
                JSONArray items = root.optJSONArray("tracks");
                if (items == null) items = root.optJSONArray("items");
                if (items != null) return parseSpotifyExportItems(items);
            } catch (Exception ignored) {}
        }
        String[] lines = raw.replace("\r", "").split("\n");
        int titleIndex = -1, artistIndex = -1;
        if (lines.length > 0) {
            List<String> header = parseCsvLine(lines[0]);
            for (int i = 0; i < header.size(); i++) {
                String h = header.get(i).trim().toLowerCase(Locale.ROOT);
                if (h.contains("track name") || h.equals("title") || h.equals("song") || h.equals("track")) titleIndex = i;
                if (h.contains("artist")) artistIndex = i;
            }
            if (titleIndex >= 0) {
                for (String line : lines) {
                    if (line.trim().isEmpty() || line.trim().startsWith("#")) continue;
                    List<String> cells = parseCsvLine(line);
                    if (cells.size() <= titleIndex || line.equals(lines[0])) continue;
                    String title = cells.get(titleIndex).trim();
                    String artist = artistIndex >= 0 && cells.size() > artistIndex ? cells.get(artistIndex).trim() : "Unknown artist";
                    addImportedTrack(found, title, artist);
                }
                return found;
            }
        }
        for (String line : lines) {
            String value = line.trim();
            if (value.startsWith("#EXTINF:")) {
                int comma = value.indexOf(',');
                if (comma >= 0 && comma + 1 < value.length()) {
                    String extInfo = value.substring(comma + 1).trim();
                    String[] extPair = extInfo.split("\\s+[—–-]\\s+", 2);
                    addImportedTrack(found, extPair[0].trim(), extPair.length > 1 ? extPair[1].trim() : "Unknown artist");
                }
                continue;
            }
            if (value.isEmpty() || value.startsWith("#") || value.toLowerCase(Locale.ROOT).startsWith("http")) continue;
            List<String> cells = parseCsvLine(value);
            String title = "", artist = "Unknown artist";
            if (cells.size() >= 2) {
                title = cells.get(0).trim();
                artist = cells.get(1).trim();
            } else {
                String[] pair = value.split("\\s+[—–-]\\s+", 2);
                if (pair.length == 2) { title = pair[0].trim(); artist = pair[1].trim(); }
                else if (value.contains("\t")) {
                    String[] tab = value.split("\\t", 2); title = tab[0].trim(); artist = tab.length > 1 ? tab[1].trim() : artist;
                } else title = value;
            }
            addImportedTrack(found, title, artist);
            if (found.size() >= 1000) break;
        }
        return found;
    }

    private List<String> parseCsvLine(String line) {
        List<String> cells = new ArrayList<>();
        StringBuilder cell = new StringBuilder();
        boolean quoted = false;
        for (int i = 0; i < line.length(); i++) {
            char ch = line.charAt(i);
            if (ch == '"') {
                if (quoted && i + 1 < line.length() && line.charAt(i + 1) == '"') { cell.append('"'); i++; }
                else quoted = !quoted;
            } else if (ch == ',' && !quoted) {
                cells.add(cell.toString()); cell.setLength(0);
            } else cell.append(ch);
        }
        cells.add(cell.toString());
        return cells;
    }

    private void addImportedTrack(List<Track> target, String title, String artist) {
        if (title == null || title.trim().isEmpty()) return;
        String cleanTitle = title.trim();
        String cleanArtist = artist == null || artist.trim().isEmpty() ? "Unknown artist" : artist.trim();
        if (cleanTitle.toLowerCase(Locale.ROOT).matches(".*(spotify:track:|open\\.spotify\\.com/track/).*")) return;
        target.add(new Track("imported-" + target.size(), cleanTitle, cleanArtist,
                "Imported playlist • tap to find playable version", "playlist-entry", "", "", ""));
    }

    private void submitSearch() {
        String query = searchField.getText().toString().trim();
        ((InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE))
                .hideSoftInputFromWindow(searchField.getWindowToken(), 0);
        loadCurrentSource(query);
    }

    private void loadCurrentSource(String query) {
        if ("liked".equals(source)) {
            resultHeading.setText("YOUR LIKED MUSIC");
            showTracks(new ArrayList<>(favoriteTracks), favoriteTracks.isEmpty()
                    ? "Your collection is empty. Tap the heart on a result to save it here."
                    : favoriteTracks.size() + " saved tracks • stored on this device");
        } else if ("playlist".equals(source)) {
            resultHeading.setText("PLAYLIST • " + importedPlaylistName.toUpperCase(Locale.ROOT));
            showTracks(playlistTracks, playlistTracks.isEmpty()
                    ? "No imported tracks yet. Import a CSV, M3U, or pasted Spotify track list."
                    : playlistTracks.size() + " imported tracks • tap a track to find it on YouTube/Morphe");
        } else if ("youtube".equals(source)) loadYoutube(query);
        else if ("ytmusic".equals(source)) openYoutubeMusicSearch(query);
        else if ("archive".equals(source)) loadArchive(query);
        else if ("radio".equals(source)) loadRadio(query);
        else loadAudius(query);
    }

    /**
     * Search the public Invidious catalog, then delegate playback to the user's
     * Morphe YouTube installation via an ordinary YouTube URL. Spotty does not
     * extract or redistribute YouTube media streams.
     */
    private void loadYoutube(String query) {
        final int requestVersion = ++youtubeSearchVersion;
        resultHeading.setText(query.isEmpty() ? "POPULAR MUSIC ON YOUTUBE" : "YOUTUBE SEARCH");
        setLoading(query.isEmpty() ? "Finding popular music…" : "Searching YouTube videos and audio…");
        String q = query.isEmpty() ? "music sort:views" : query;
        String[] instances = new String[] {
                "https://inv.nadeko.net",
                "https://invidious.nerdvpn.de",
                "https://invidious.f5.si",
                "https://invidious.tiekoetter.com"
        };

        // Offer the direct Morphe/YouTube route while mirrors search in the background.
        // This prevents an offline third-party catalog from trapping the user on a spinner.
        main.postDelayed(() -> {
            if (requestVersion == youtubeSearchVersion && "youtube".equals(source)
                    && statusText.getText().toString().contains("…")) {
                showYoutubeFallback(query, null);
            }
        }, 8500L);

        io.execute(() -> {
            CompletionService<List<Track>> completedSearches = new ExecutorCompletionService<>(io);
            List<Future<List<Track>>> requests = new ArrayList<>();
            for (String base : instances) {
                requests.add(completedSearches.submit(() -> searchYoutubeInstance(base, q)));
            }

            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(18);
            Exception lastError = null;
            int finished = 0;
            try {
                while (finished < requests.size()) {
                    long remaining = deadline - System.nanoTime();
                    if (remaining <= 0) break;
                    Future<List<Track>> completed = completedSearches.poll(remaining, TimeUnit.NANOSECONDS);
                    if (completed == null) break;
                    finished++;
                    try {
                        List<Track> found = completed.get();
                        if (!found.isEmpty()) {
                            for (Future<List<Track>> pending : requests) {
                                if (!pending.isDone()) pending.cancel(true);
                            }
                            main.post(() -> {
                                if (requestVersion == youtubeSearchVersion && "youtube".equals(source)) {
                                    showTracks(found, found.size() + " results • playback via Morphe YouTube");
                                }
                            });
                            return;
                        }
                        lastError = new Exception("No matching videos were returned.");
                    } catch (ExecutionException e) {
                        Throwable cause = e.getCause();
                        lastError = cause instanceof Exception ? (Exception) cause : new Exception("Search provider failed.");
                    }
                }
                Exception failure = lastError == null
                        ? new Exception("Public search providers timed out or are unavailable.")
                        : lastError;
                main.post(() -> {
                    if (requestVersion == youtubeSearchVersion && "youtube".equals(source)) {
                        showYoutubeFallback(query, failure);
                    }
                });
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                main.post(() -> {
                    if (requestVersion == youtubeSearchVersion && "youtube".equals(source)) {
                        showYoutubeFallback(query, e);
                    }
                });
            }
        });
    }

    private List<Track> searchYoutubeInstance(String base, String query) throws Exception {
        JSONArray data = requestJsonArray(base + "/api/v1/search?q=" + enc(query) + "&type=video");
        List<Track> found = new ArrayList<>();
        for (int i = 0; i < data.length(); i++) {
            JSONObject item = data.optJSONObject(i);
            if (item == null) continue;
            String id = item.optString("videoId", "");
            String type = item.optString("type", "video");
            if (id.isEmpty() || (!"video".equals(type) && !"shortVideo".equals(type))) continue;
            String title = item.optString("title", "Untitled video");
            String artist = item.optString("author", "YouTube creator");
            String subtitle = "YouTube • opens in Morphe";
            int length = item.optInt("lengthSeconds", 0);
            if (length > 0) subtitle += " • " + formatDuration(length);
            String watchUrl = "https://www.youtube.com/watch?v=" + Uri.encode(id);
            found.add(new Track(id, title, artist, subtitle, "youtube", watchUrl, "", ""));
            if (found.size() >= 30) break;
        }
        return found;
    }

    private String formatDuration(int totalSeconds) {
        if (totalSeconds < 1) return "";
        int hours = totalSeconds / 3600;
        int minutes = (totalSeconds % 3600) / 60;
        int seconds = totalSeconds % 60;
        if (hours > 0) return String.format(Locale.ROOT, "%d:%02d:%02d", hours, minutes, seconds);
        return String.format(Locale.ROOT, "%d:%02d", minutes, seconds);
    }

    private void showYoutubeFallback(String query, Exception e) {
        resultsList.removeAllViews();
        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(dp(18), dp(18), dp(18), dp(18));
        panel.setBackground(panelCardDrawable());

        TextView symbol = text("♫  DISCOVER WITHOUT LIMITS", 11, ACCENT, true);
        symbol.setLetterSpacing(0.08f);
        panel.addView(symbol);

        TextView headline = text(e == null ? "Search directly in Morphe" : "YouTube search mirrors are offline", 19, TEXT, true);
        headline.setMaxLines(2);
        LinearLayout.LayoutParams headlineLp = new LinearLayout.LayoutParams(-1, -2);
        headlineLp.topMargin = dp(10);
        panel.addView(headline, headlineLp);

        TextView detail = text(e == null
                ? "You can keep searching while Spotty checks its community catalog providers."
                : "Community catalog servers can go offline. Your search can still open directly in Morphe YouTube or the installed YouTube handler.",
                13, MUTED, false);
        LinearLayout.LayoutParams detailLp = new LinearLayout.LayoutParams(-1, -2);
        detailLp.topMargin = dp(7);
        panel.addView(detail, detailLp);

        Button direct = button("Open Morphe / YouTube search  ↗", ACCENT, BG);
        direct.setOnClickListener(v -> openYoutubeSearch(query));
        LinearLayout.LayoutParams directLp = new LinearLayout.LayoutParams(-1, dp(48));
        directLp.topMargin = dp(16);
        panel.addView(direct, directLp);

        TextView retry = text("Retry catalog search", 13, TEXT, true);
        retry.setGravity(Gravity.CENTER);
        retry.setPadding(dp(10), dp(13), dp(10), dp(5));
        retry.setOnClickListener(v -> loadYoutube(query));
        panel.addView(retry);

        resultsList.addView(panel, new LinearLayout.LayoutParams(-1, -2));
        statusText.setText(e == null ? "Direct search ready" : "Mirror search unavailable • direct search ready");
    }

    private void openYoutubeSearch(String query) {
        try {
            String q = query == null || query.trim().isEmpty() ? "popular music" : query.trim();
            String url = "https://www.youtube.com/results?search_query=" + enc(q);
            openYoutubeUrl(url, "Search: " + q);
        } catch (Exception e) {
            statusText.setText("Could not open the YouTube search.");
        }
    }

    private void openYoutubeMusicSearch(String query) {
        io.execute(() -> {
            try {
                String q = query == null || query.trim().isEmpty() ? "popular music" : query.trim();
                String url = "https://music.youtube.com/search?q=" + enc(q);
                main.post(() -> {
                    releasePlayer();
                    if (playPauseButton != null) playPauseButton.setText("▶");
                    nowPlayingText.setText("Morphe Music • " + q);
                    try {
                        Intent musicIntent = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
                        musicIntent.setPackage("app.morphe.android.apps.youtube.music");
                        startActivity(musicIntent);
                        statusText.setText("Opened Morphe Music • playback handled by YouTube Music");
                    } catch (Exception musicUnavailable) {
                        try {
                            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
                            statusText.setText("Morphe Music not detected; opened the default music/web handler");
                        } catch (Exception noHandler) {
                            statusText.setText("No app can open YouTube Music links. Install Morphe Music or a browser.");
                        }
                    }
                });
            } catch (Exception e) {
                main.post(() -> statusText.setText("Could not prepare YouTube Music search."));
            }
        });
    }

    private void openYoutubeUrl(String url, String title) {
        releasePlayer();
        if (playPauseButton != null) playPauseButton.setText("▶");
        nowPlayingText.setText(title == null || title.isEmpty() ? "Opening YouTube…" : title);
        try {
            Intent morpheIntent = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
            morpheIntent.setPackage("app.morphe.android.youtube");
            startActivity(morpheIntent);
            statusText.setText("Opened Morphe YouTube • no Spotify Premium needed");
        } catch (Exception morpheUnavailable) {
            try {
                Intent fallbackIntent = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
                startActivity(fallbackIntent);
                statusText.setText("Morphe not detected; opened the default YouTube app/browser");
            } catch (Exception noHandler) {
                statusText.setText("No app can open YouTube links. Install Morphe YouTube or a browser.");
            }
        }
    }

    private void loadAudius(String query) {
        source = "audius";
        resultHeading.setText(query.isEmpty() ? "TRENDING ON AUDIUS" : "AUDIUS SEARCH");
        setLoading(query.isEmpty() ? "Loading trending tracks…" : "Searching free tracks…");
        io.execute(() -> {
            try {
                String endpoint;
                if (query.isEmpty()) {
                    endpoint = "https://api.audius.co/v1/tracks/trending?limit=30&filter_tracks=public&gate_condition=ungated";
                } else {
                    endpoint = "https://api.audius.co/v1/tracks/search?query=" + enc(query)
                            + "&limit=30&filter_tracks=public&gate_condition=ungated";
                }
                JSONObject response = requestJsonObject(endpoint);
                JSONArray data = response.optJSONArray("data");
                if (data == null) throw new Exception("Audius returned an unexpected response.");
                List<Track> found = new ArrayList<>();
                for (int i = 0; i < data.length(); i++) {
                    JSONObject item = data.optJSONObject(i);
                    if (item == null || !item.optBoolean("is_streamable", true)) continue;
                    String id = item.optString("id", "");
                    String title = item.optString("title", "Untitled track");
                    JSONObject user = item.optJSONObject("user");
                    String artist = user == null ? "Audius artist" : user.optString("name", "Audius artist");
                    if (id.isEmpty()) continue;
                    String image = "";
                    JSONObject artwork = item.optJSONObject("artwork");
                    if (artwork != null) image = artwork.optString("150x150", "");
                    String url = "https://api.audius.co/v1/tracks/" + Uri.encode(id) + "/stream";
                    found.add(new Track(id, title, artist, "Audius • free streaming", "audius", url, image, ""));
                }
                main.post(() -> showTracks(found, found.isEmpty()
                        ? "No playable tracks found. Try another search or source." : found.size() + " tracks"));
            } catch (Exception e) {
                main.post(() -> showError("Audius could not be reached: " + shortError(e)
                        + "  •  Try Open Archive or Radio."));
            }
        });
    }

    private void loadArchive(String query) {
        resultHeading.setText(query.isEmpty() ? "OPEN-LICENSE AUDIO" : "OPEN ARCHIVE SEARCH");
        setLoading("Searching public-domain / Creative Commons audio…");
        io.execute(() -> {
            try {
                String expression = "mediatype:audio AND (licenseurl:*creativecommons.org* OR licenseurl:*publicdomain*)";
                String clean = query.replaceAll("[^\\p{L}\\p{N}\\s_-]", " ").trim();
                if (!clean.isEmpty()) expression += " AND title:(" + clean + ")";
                String endpoint = "https://archive.org/advancedsearch.php?q=" + enc(expression)
                        + "&fl%5B%5D=identifier&fl%5B%5D=title&fl%5B%5D=creator&fl%5B%5D=licenseurl"
                        + "&rows=25&page=1&output=json&sort%5B%5D=downloads+desc";
                JSONObject response = requestJsonObject(endpoint);
                JSONObject responseBlock = response.optJSONObject("response");
                JSONArray docs = responseBlock == null ? null : responseBlock.optJSONArray("docs");
                if (docs == null) throw new Exception("Internet Archive returned an unexpected response.");
                List<Track> found = new ArrayList<>();
                for (int i = 0; i < docs.length(); i++) {
                    JSONObject item = docs.optJSONObject(i);
                    if (item == null) continue;
                    String id = item.optString("identifier", "");
                    if (id.isEmpty()) continue;
                    String title = item.optString("title", id);
                    Object creatorValue = item.opt("creator");
                    String artist = creatorValue instanceof JSONArray
                            ? ((JSONArray) creatorValue).optString(0, "Archive contributor")
                            : (creatorValue == null || creatorValue == JSONObject.NULL
                                ? "Archive contributor" : String.valueOf(creatorValue));
                    String license = item.optString("licenseurl", "");
                    found.add(new Track(id, title, artist, "Internet Archive • license checked before playback",
                            "archive", "", "", license));
                }
                main.post(() -> showTracks(found, found.isEmpty()
                        ? "No matching open audio found. Try a broader title or another source."
                        : found.size() + " archive items • only CC/public-domain audio can play"));
            } catch (Exception e) {
                main.post(() -> showError("Open Archive search failed: " + shortError(e)));
            }
        });
    }

    private void loadRadio(String query) {
        resultHeading.setText(query.isEmpty() ? "POPULAR RADIO" : "RADIO STATIONS");
        setLoading(query.isEmpty() ? "Loading popular free stations…" : "Searching stations and genres…");
        io.execute(() -> {
            try {
                String endpoint = "https://de1.api.radio-browser.info/json/stations/search?hidebroken=true&limit=30&order=votes&reverse=true";
                if (!query.isEmpty()) endpoint += "&name=" + enc(query);
                JSONArray data = requestJsonArray(endpoint);
                if (!query.isEmpty() && data.length() == 0) {
                    endpoint = "https://de1.api.radio-browser.info/json/stations/search?hidebroken=true&limit=30&order=votes&reverse=true&tag=" + enc(query);
                    data = requestJsonArray(endpoint);
                }
                List<Track> found = new ArrayList<>();
                for (int i = 0; i < data.length(); i++) {
                    JSONObject item = data.optJSONObject(i);
                    if (item == null) continue;
                    String url = item.optString("url_resolved", "");
                    if (url.isEmpty()) url = item.optString("url", "");
                    String name = item.optString("name", "Internet radio");
                    if (url.isEmpty() || !url.startsWith("https://")) continue;
                    String tags = item.optString("tags", "");
                    String country = item.optString("country", "");
                    String extra = tags.isEmpty() ? country : tags;
                    if (!country.isEmpty() && !extra.contains(country)) extra += " • " + country;
                    found.add(new Track(item.optString("stationuuid", name), name,
                            extra.isEmpty() ? "Free internet radio" : extra,
                            "Radio Browser • live station", "radio", url,
                            item.optString("favicon", ""), ""));
                }
                main.post(() -> showTracks(found, found.isEmpty()
                        ? "No stations found. Try a genre such as ambient, jazz, or shoegaze."
                        : found.size() + " free live stations"));
            } catch (Exception e) {
                main.post(() -> showError("Radio directory is unavailable: " + shortError(e)));
            }
        });
    }

    private void showTracks(List<Track> tracks, String status) {
        visibleTracks.clear();
        visibleTracks.addAll(tracks);
        resultsList.removeAllViews();
        if (tracks.isEmpty()) {
            TextView empty = text(status, 14, MUTED, false);
            empty.setPadding(dp(8), dp(18), dp(8), dp(18));
            resultsList.addView(empty);
        } else {
            for (Track track : tracks) {
                LinearLayout card = new LinearLayout(this);
                card.setOrientation(LinearLayout.VERTICAL);
                card.setPadding(dp(14), dp(12), dp(14), dp(12));
                card.setBackground(panelCardDrawable());
                TextView name = text(track.title, 15, TEXT, true);
                name.setMaxLines(2);
                TextView artist = text(track.artist, 13, MUTED, false);
                artist.setMaxLines(1);
                TextView provider = text(track.subtitle, 10, ACCENT, false);
                provider.setMaxLines(1);
                LinearLayout titleRow = new LinearLayout(this);
                titleRow.setOrientation(LinearLayout.HORIZONTAL);
                titleRow.setGravity(Gravity.CENTER_VERTICAL);
                name.setLayoutParams(new LinearLayout.LayoutParams(0, -2, 1));
                titleRow.addView(name);
                TextView like = text(isFavorite(track) ? "♥" : "♡", 23,
                        isFavorite(track) ? ACCENT : MUTED, true);
                like.setGravity(Gravity.CENTER);
                like.setContentDescription(isFavorite(track) ? "Remove from liked tracks" : "Add to liked tracks");
                like.setPadding(dp(8), 0, dp(4), 0);
                like.setOnClickListener(v -> {
                    boolean nowLiked = toggleFavorite(track);
                    like.setText(nowLiked ? "♥" : "♡");
                    like.setTextColor(nowLiked ? ACCENT : MUTED);
                    like.setContentDescription(nowLiked ? "Remove from liked tracks" : "Add to liked tracks");
                    if ("liked".equals(source) && !nowLiked) {
                        showTracks(new ArrayList<>(favoriteTracks), favoriteTracks.size() + " saved tracks • stored on this device");
                    }
                });
                titleRow.addView(like, new LinearLayout.LayoutParams(dp(42), dp(42)));
                card.addView(titleRow);
                LinearLayout.LayoutParams artistLp = new LinearLayout.LayoutParams(-1, -2);
                artistLp.topMargin = dp(4);
                card.addView(artist, artistLp);
                LinearLayout.LayoutParams providerLp = new LinearLayout.LayoutParams(-1, -2);
                providerLp.topMargin = dp(7);
                card.addView(provider, providerLp);
                if ("archive".equals(track.provider)) {
                    TextView download = text("↓  Download licensed audio", 12, ACCENT, true);
                    download.setPadding(0, dp(11), 0, 0);
                    download.setOnClickListener(v -> downloadArchive(track));
                    card.addView(download);
                }
                card.setOnClickListener(v -> playTrack(track));
                LinearLayout.LayoutParams cardLp = new LinearLayout.LayoutParams(-1, -2);
                cardLp.bottomMargin = dp(8);
                card.setAlpha(0f);
                card.setTranslationY(dp(8));
                resultsList.addView(card, cardLp);
                card.animate().alpha(1f).translationY(0f).setDuration(180).start();
            }
        }
        statusText.setText(status);
    }

    private void setLoading(String message) {
        statusText.setText(message);
        resultsList.removeAllViews();
        TextView progress = text(message, 14, MUTED, false);
        progress.setPadding(dp(8), dp(18), dp(8), dp(18));
        resultsList.addView(progress);
    }

    private void showError(String message) {
        resultsList.removeAllViews();
        TextView error = text(message, 14, MUTED, false);
        error.setPadding(dp(8), dp(16), dp(8), dp(16));
        resultsList.addView(error);
        TextView retry = text("Tap here to retry", 13, ACCENT, true);
        retry.setPadding(dp(8), dp(10), dp(8), dp(14));
        retry.setOnClickListener(v -> loadCurrentSource(searchField.getText().toString().trim()));
        resultsList.addView(retry);
        statusText.setText("Connection issue");
    }

    private GradientDrawable panelCardDrawable() {
        if ("Frutiger Aero".equals(activeTheme)) {
            GradientDrawable d = new GradientDrawable(GradientDrawable.Orientation.TL_BR,
                    new int[]{Color.rgb(25, 94, 116), Color.rgb(20, 59, 83)});
            d.setCornerRadius(dp(15));
            d.setStroke(dp(1), Color.argb(105, 132, 255, 230));
            return d;
        }
        if ("Cyberpunk".equals(activeTheme) || "Retro CRT".equals(activeTheme)) {
            GradientDrawable d = roundRect(PANEL, 15);
            d.setStroke(dp(1), ACCENT);
            return d;
        }
        if ("Y2K Glass".equals(activeTheme)) {
            GradientDrawable d = new GradientDrawable(GradientDrawable.Orientation.TL_BR,
                    new int[]{Color.rgb(79, 129, 188), Color.rgb(48, 62, 123)});
            d.setCornerRadius(dp(18));
            d.setStroke(dp(1), Color.argb(150, 210, 239, 255));
            return d;
        }
        if ("Candy Pop".equals(activeTheme) || "Sunset Coast".equals(activeTheme)) {
            GradientDrawable d = new GradientDrawable(GradientDrawable.Orientation.TL_BR,
                    new int[]{PANEL_ALT, PANEL});
            d.setCornerRadius(dp(17));
            d.setStroke(dp(1), Color.argb(100, 255, 220, 235));
            return d;
        }
        if ("Synthwave".equals(activeTheme) || "Vaporwave".equals(activeTheme)) {
            GradientDrawable d = new GradientDrawable(GradientDrawable.Orientation.TL_BR,
                    new int[]{PANEL_ALT, PANEL});
            d.setCornerRadius(dp(16));
            d.setStroke(dp(1), Color.argb(100, ACCENT));
            return d;
        }
        if ("Dark Academia".equals(activeTheme)) {
            GradientDrawable d = new GradientDrawable(GradientDrawable.Orientation.TL_BR,
                    new int[]{Color.rgb(76, 62, 43), Color.rgb(43, 37, 31)});
            d.setCornerRadius(dp(15));
            d.setStroke(dp(1), Color.argb(110, ACCENT));
            return d;
        }
        return roundRect(PANEL, 15);
    }

    private int audioFileRank(String name) {
        String n = name.toLowerCase(Locale.ROOT);
        boolean lossless = n.endsWith(".flac") || n.endsWith(".wav") || n.endsWith(".aiff") || n.endsWith(".alac");
        if ("Prefer lossless".equals(activeQuality)) {
            if (n.endsWith(".flac")) return 100;
            if (n.endsWith(".wav") || n.endsWith(".aiff") || n.endsWith(".alac")) return 90;
            if (n.endsWith(".opus")) return 55;
            if (n.endsWith(".ogg")) return 50;
            if (n.endsWith(".m4a") || n.endsWith(".aac")) return 45;
            if (n.endsWith(".mp3")) return 40;
        } else if ("Balanced (prefer high bitrate)".equals(activeQuality)) {
            if (n.endsWith(".mp3")) return 100;
            if (n.endsWith(".opus")) return 95;
            if (n.endsWith(".flac")) return 90;
            if (n.endsWith(".m4a") || n.endsWith(".aac")) return 85;
            if (n.endsWith(".ogg")) return 80;
        } else if ("Data saver".equals(activeQuality)) {
            if (n.endsWith(".opus")) return 100;
            if (n.endsWith(".ogg")) return 95;
            if (n.endsWith(".mp3")) return 90;
            if (n.endsWith(".aac") || n.endsWith(".m4a")) return 85;
        } else {
            if (n.endsWith(".flac")) return 100;
            if (n.endsWith(".wav") || n.endsWith(".aiff") || n.endsWith(".alac")) return 95;
            if (n.endsWith(".opus")) return 85;
            if (n.endsWith(".ogg")) return 80;
            if (n.endsWith(".m4a") || n.endsWith(".aac")) return 75;
            if (n.endsWith(".mp3")) return 70;
        }
        if ("Data saver".equals(activeQuality)) return lossless ? 10 : -1;
        if ("Balanced (prefer high bitrate)".equals(activeQuality)) return lossless ? 60 : -1;
        return lossless ? 90 : -1;
    }

    private JSONObject chooseArchiveAudioFile(JSONArray files, boolean forPlayback) {
        JSONObject best = null;
        int bestRank = -1;
        long bestSize = -1;
        for (int i = 0; i < files.length(); i++) {
            JSONObject file = files.optJSONObject(i);
            if (file == null || file.optBoolean("private", false)) continue;
            String name = file.optString("name", "");
            int rank = audioFileRank(name);
            String lowerName = name.toLowerCase(Locale.ROOT);
            boolean lossless = lowerName.endsWith(".flac") || lowerName.endsWith(".wav")
                    || lowerName.endsWith(".aiff") || lowerName.endsWith(".alac");
            if ("Balanced (prefer high bitrate)".equals(activeQuality)) {
                // Prefer a lossless original; when none exists, rank a verified 320+ kbps file above
                // lower-bitrate lossy encodes instead of treating every MP3 as high quality.
                if (lossless) {
                    rank = 200;
                } else {
                    int bitrate = file.optInt("bitrate", 0);
                    if (bitrate <= 0) {
                        try { bitrate = Integer.parseInt(file.optString("bitrate", "0").replaceAll("[^0-9]", "")); }
                        catch (Exception ignored) { bitrate = 0; }
                    }
                    if (bitrate >= 320) rank = 160 + Math.min(10, bitrate / 100);
                    else if (bitrate > 0) rank = 50 + Math.min(60, bitrate / 10);
                }
            }
            if (forPlayback && (lowerName.endsWith(".aiff") || lowerName.endsWith(".alac"))) rank = -1;
            long size = file.optLong("size", 0L);
            if (rank < 0) continue;
            if (rank > bestRank || (rank == bestRank && size > bestSize)) {
                best = file; bestRank = rank; bestSize = size;
            }
        }
        return best;
    }

    private String describeAudioFile(JSONObject file) {
        String name = file.optString("name", "").toLowerCase(Locale.ROOT);
        if (name.endsWith(".flac") || name.endsWith(".wav") || name.endsWith(".aiff") || name.endsWith(".alac")) {
            return "Lossless-format source • " + name.substring(name.lastIndexOf('.') + 1).toUpperCase(Locale.ROOT);
        }
        String bitrate = file.optString("bitrate", "");
        if (!bitrate.isEmpty() && !bitrate.equals("0")) return "Source bitrate reported: " + bitrate + " kbps";
        if (name.endsWith(".mp3")) return "MP3 source • bitrate varies by file";
        if (name.endsWith(".opus")) return "Opus source • bitrate varies by file";
        return "Original source file • bitrate not verified";
    }

    private boolean isAllowedArchiveLicense(String license) {
        String lower = license == null ? "" : license.toLowerCase(Locale.ROOT);
        return lower.contains("creativecommons.org/licenses/")
                || lower.contains("creativecommons.org/publicdomain/")
                || lower.contains("creativecommons.org/zero/");
    }

    private String chooseArchiveStreamUrl(String itemId, String fileName) {
        return "https://archive.org/download/" + Uri.encode(itemId) + "/" + Uri.encode(fileName);
    }

    private void downloadArchive(Track track) {
        statusText.setText("Verifying license before download…");
        io.execute(() -> {
            try {
                JSONObject response = requestJsonObject("https://archive.org/metadata/" + enc(track.id));
                JSONObject metadata = response.optJSONObject("metadata");
                String license = metadata == null ? "" : metadata.optString("licenseurl", "");
                if (license.isEmpty()) license = response.optString("licenseurl", "");
                if (!isAllowedArchiveLicense(license))
                    throw new Exception("This item has no verifiable Creative Commons/public-domain license. Download blocked.");
                JSONArray files = response.optJSONArray("files");
                if (files == null) throw new Exception("No downloadable files were listed.");
                JSONObject chosen = chooseArchiveAudioFile(files, false);
                if (chosen == null) throw new Exception("No supported audio file was found.");
                String name = chosen.optString("name", "");
                String url = chooseArchiveStreamUrl(track.id, name);
                String quality = describeAudioFile(chosen);
                main.post(() -> {
                    try {
                        DownloadManager manager = (DownloadManager) getSystemService(DOWNLOAD_SERVICE);
                        DownloadManager.Request request = new DownloadManager.Request(Uri.parse(url));
                        request.setTitle(track.title);
                        request.setDescription("Spotty • licensed audio • " + quality);
                        request.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
                        request.setAllowedOverMetered(true);
                        request.setAllowedOverRoaming(false);
                        String safeName = track.title.replaceAll("[^a-zA-Z0-9._ -]", "_").trim();
                        if (safeName.isEmpty()) safeName = "Spotty_audio";
                        int dot = name.lastIndexOf('.');
                        String ext = dot >= 0 ? name.substring(dot) : ".audio";
                        request.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, safeName + ext);
                        request.allowScanningByMediaScanner();
                        manager.enqueue(request);
                        statusText.setText("Download queued • " + quality);
                    } catch (Exception e) {
                        statusText.setText("Could not queue download: " + shortError(e));
                    }
                });
            } catch (Exception e) {
                main.post(() -> statusText.setText("Download blocked/failed • " + shortError(e)));
            }
        });
    }

    private void playTrack(Track track) {
        int visibleIndex = visibleTracks.indexOf(track);
        if (visibleIndex >= 0) {
            playbackQueue = new ArrayList<>(visibleTracks);
            playbackQueueIndex = visibleIndex;
        }
        if ("playlist-entry".equals(track.provider)) {
            openYoutubeSearch(track.title + " " + (track.artist.equals("Unknown artist") ? "" : track.artist));
            return;
        }
        if ("youtube".equals(track.provider)) {
            openYoutubeUrl(track.playUrl, track.title);
            return;
        }
        if ("archive".equals(track.provider)) {
            statusText.setText("Checking the audio license…");
            nowPlayingText.setText(track.title);
            io.execute(() -> {
                try {
                    JSONObject response = requestJsonObject("https://archive.org/metadata/" + enc(track.id));
                    JSONObject metadata = response.optJSONObject("metadata");
                    String license = metadata == null ? "" : metadata.optString("licenseurl", "");
                    if (license.isEmpty()) license = response.optString("licenseurl", "");
                    String lower = license.toLowerCase(Locale.ROOT);
                    boolean allowed = lower.contains("creativecommons.org/licenses/")
                            || lower.contains("creativecommons.org/publicdomain/")
                            || lower.contains("creativecommons.org/zero/");
                    if (!allowed) throw new Exception("No verifiable Creative Commons/public-domain license was found. This item will not be played.");
                    JSONArray files = response.optJSONArray("files");
                    if (files == null) throw new Exception("This archive item has no accessible files.");
                    JSONObject chosen = chooseArchiveAudioFile(files, true);
                    if (chosen == null) throw new Exception("No supported public audio file was found for this item.");
                    String selected = chosen.optString("name", "");
                    String stream = chooseArchiveStreamUrl(track.id, selected);
                    String quality = describeAudioFile(chosen);
                    Track playable = new Track(track.id, track.title, track.artist,
                            "Internet Archive • " + quality, "archive-playable", stream, "", license);
                    main.post(() -> beginPlayback(playable));
                } catch (Exception e) {
                    main.post(() -> {
                        statusText.setText("Cannot play this archive item");
                        nowPlayingText.setText(shortError(e));
                    });
                }
            });
        } else {
            beginPlayback(track);
        }
    }

    private void beginPlayback(Track track) {
        releasePlayer();
        currentTrack = track;
        paused = false;
        preparing = true;
        nowPlayingText.setText(track.title + " — " + track.artist);
        playPauseButton.setText("…");
        statusText.setText("Connecting to audio stream…");
        try {
            MediaPlayer next = new MediaPlayer();
            player = next;
            next.setAudioAttributes(new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build());
            next.setOnPreparedListener(mp -> {
                if (player != mp) return;
                preparing = false;
                mp.start();
                playPauseButton.setText("Ⅱ");
                statusText.setText("Playing • " + track.subtitle);
            });
            next.setOnCompletionListener(mp -> {
                if (player == mp) {
                    preparing = false;
                    paused = false;
                    playPauseButton.setText("▶");
                    statusText.setText("Track finished");
                    if (playbackQueue.size() > 1) main.postDelayed(() -> playNextTrack(), 350L);
                }
            });
            next.setOnErrorListener((mp, what, extra) -> {
                if (player == mp) {
                    statusText.setText("Stream failed — try another track");
                    playPauseButton.setText("▶");
                    preparing = false;
                    releasePlayer();
                }
                return true;
            });
            next.setDataSource(track.playUrl);
            next.prepareAsync();
        } catch (Exception e) {
            statusText.setText("Could not start stream: " + shortError(e));
            playPauseButton.setText("▶");
            preparing = false;
            releasePlayer();
        }
    }

    private boolean sameTrack(Track a, Track b) {
        if (a == null || b == null) return false;
        if (!a.provider.equals(b.provider)) return false;
        if (!a.playUrl.isEmpty() || !b.playUrl.isEmpty()) return a.playUrl.equals(b.playUrl);
        return a.id.equals(b.id) && a.title.equals(b.title) && a.artist.equals(b.artist);
    }

    private boolean isFavorite(Track track) {
        for (Track saved : favoriteTracks) if (sameTrack(saved, track)) return true;
        return false;
    }

    private boolean toggleFavorite(Track track) {
        for (int i = 0; i < favoriteTracks.size(); i++) {
            if (sameTrack(favoriteTracks.get(i), track)) {
                favoriteTracks.remove(i);
                persistFavoriteTracks();
                statusText.setText("Removed from liked tracks");
                return false;
            }
        }
        favoriteTracks.add(track);
        persistFavoriteTracks();
        statusText.setText("Added to liked tracks");
        return true;
    }

    private void persistFavoriteTracks() {
        getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                .putString("favorite_tracks_json", serializeTrackList(favoriteTracks))
                .apply();
    }

    private String serializeTrackList(List<Track> tracks) {
        JSONArray array = new JSONArray();
        for (Track track : tracks) {
            JSONObject item = new JSONObject();
            try {
                item.put("id", track.id);
                item.put("title", track.title);
                item.put("artist", track.artist);
                item.put("subtitle", track.subtitle);
                item.put("provider", track.provider);
                item.put("playUrl", track.playUrl);
                item.put("artworkUrl", track.artworkUrl);
                item.put("license", track.license);
                array.put(item);
            } catch (Exception ignored) {}
        }
        return array.toString();
    }

    private List<Track> readFavoriteTracks(String raw) {
        List<Track> tracks = new ArrayList<>();
        try {
            JSONArray array = new JSONArray(raw);
            for (int i = 0; i < array.length() && tracks.size() < 2000; i++) {
                JSONObject item = array.optJSONObject(i);
                if (item == null) continue;
                String provider = item.optString("provider", "");
                String playUrl = item.optString("playUrl", "");
                // Keep only HTTP(S) links in persisted favourites; don't restore arbitrary URI schemes.
                if (!playUrl.isEmpty() && !(playUrl.startsWith("https://") || playUrl.startsWith("http://"))) continue;
                tracks.add(new Track(item.optString("id", ""), item.optString("title", ""),
                        item.optString("artist", ""), item.optString("subtitle", ""),
                        provider, playUrl, item.optString("artworkUrl", ""), item.optString("license", "")));
            }
        } catch (Exception ignored) {}
        return tracks;
    }

    private void playNextTrack() {
        if (playbackQueue.isEmpty()) {
            statusText.setText("Play a result first to start a queue.");
            return;
        }
        playbackQueueIndex = (playbackQueueIndex + 1) % playbackQueue.size();
        Track track = playbackQueue.get(playbackQueueIndex);
        statusText.setText("Next • " + track.title);
        playTrack(track);
    }

    private void playPreviousTrack() {
        if (playbackQueue.isEmpty()) {
            statusText.setText("Play a result first to start a queue.");
            return;
        }
        if (player != null && !preparing) {
            try {
                if (player.getCurrentPosition() > 3000) {
                    player.seekTo(0);
                    statusText.setText("Restarted track");
                    return;
                }
            } catch (Exception ignored) {}
        }
        playbackQueueIndex = (playbackQueueIndex - 1 + playbackQueue.size()) % playbackQueue.size();
        Track track = playbackQueue.get(playbackQueueIndex);
        statusText.setText("Previous • " + track.title);
        playTrack(track);
    }

    private void togglePlayback() {
        if (player == null) {
            statusText.setText("Choose a track first.");
            return;
        }
        if (preparing) {
            statusText.setText("Still connecting to the stream…");
            return;
        }
        try {
            if (player.isPlaying()) {
                player.pause();
                paused = true;
                playPauseButton.setText("▶");
                statusText.setText("Paused");
            } else if (paused) {
                player.start();
                paused = false;
                playPauseButton.setText("Ⅱ");
                statusText.setText("Playing");
            } else {
                statusText.setText("Wait for the stream to connect.");
            }
        } catch (IllegalStateException e) {
            statusText.setText("Player reset. Tap a track to try again.");
        }
    }

    private JSONObject requestJsonObject(String address) throws Exception {
        String body = requestText(address);
        return new JSONObject(body);
    }

    private JSONArray requestJsonArray(String address) throws Exception {
        String body = requestText(address);
        return new JSONArray(body);
    }

    private String requestText(String address) throws Exception {
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(address).openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(7000);
            conn.setReadTimeout(10000);
            conn.setRequestProperty("Accept", "application/json");
            conn.setRequestProperty("User-Agent", "SpottyAndroid/0.1 (Android music discovery app)");
            conn.setInstanceFollowRedirects(true);
            int code = conn.getResponseCode();
            InputStream stream = (code >= 200 && code < 400) ? conn.getInputStream() : conn.getErrorStream();
            if (stream == null) throw new Exception("HTTP " + code);
            StringBuilder body = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) body.append(line);
            }
            if (code < 200 || code >= 400) throw new Exception("HTTP " + code + (body.length() > 0 ? " from provider" : ""));
            return body.toString();
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    private String enc(String value) throws Exception {
        return URLEncoder.encode(value, StandardCharsets.UTF_8.name());
    }

    private String shortError(Exception e) {
        String message = e.getMessage();
        if (message == null || message.trim().isEmpty()) return "network error";
        if (message.length() > 110) return message.substring(0, 107) + "…";
        return message;
    }

    private TextView text(String value, int size, int color, boolean bold) {
        TextView v = new TextView(this);
        v.setText(value);
        v.setTextSize(size);
        v.setTextColor(color);
        if (bold) v.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        return v;
    }

    private Button button(String label, int color, int textColor) {
        Button b = new Button(this);
        b.setText(label);
        b.setTextSize(13);
        b.setTextColor(textColor);
        b.setAllCaps(false);
        b.setPadding(dp(7), 0, dp(7), 0);
        b.setBackground(roundRect(color, 15));
        return b;
    }

    private GradientDrawable roundRect(int color, int radius) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(color);
        d.setCornerRadius(dp(radius));
        return d;
    }

    private int dp(float value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private void releasePlayer() {
        if (player != null) {
            try { player.setOnPreparedListener(null); } catch (Exception ignored) {}
            try { player.setOnCompletionListener(null); } catch (Exception ignored) {}
            try { player.setOnErrorListener(null); } catch (Exception ignored) {}
            try { player.stop(); } catch (Exception ignored) {}
            try { player.reset(); } catch (Exception ignored) {}
            try { player.release(); } catch (Exception ignored) {}
        }
        player = null;
        paused = false;
        preparing = false;
    }

    @Override
    protected void onDestroy() {
        releasePlayer();
        io.shutdownNow();
        main.removeCallbacksAndMessages(null);
        super.onDestroy();
    }

    private static final class Track {
        final String id;
        final String title;
        final String artist;
        final String subtitle;
        final String provider;
        final String playUrl;
        final String artworkUrl;
        final String license;

        Track(String id, String title, String artist, String subtitle, String provider,
              String playUrl, String artworkUrl, String license) {
            this.id = id == null ? "" : id;
            this.title = title == null || title.isEmpty() ? "Untitled" : title;
            this.artist = artist == null || artist.isEmpty() ? "Unknown artist" : artist;
            this.subtitle = subtitle == null ? "" : subtitle;
            this.provider = provider == null ? "" : provider;
            this.playUrl = playUrl == null ? "" : playUrl;
            this.artworkUrl = artworkUrl == null ? "" : artworkUrl;
            this.license = license == null ? "" : license;
        }
    }
}
