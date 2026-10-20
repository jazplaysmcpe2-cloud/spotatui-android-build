package com.spotty.music;

import android.app.Activity;
import android.Manifest;
import android.content.BroadcastReceiver;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.content.pm.ApplicationInfo;
import android.database.Cursor;
import android.app.AlertDialog;
import android.app.Dialog;
import android.app.DownloadManager;
import android.graphics.Color;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.RadialGradient;
import android.graphics.Rect;
import android.graphics.Shader;
import android.graphics.drawable.Drawable;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.drawable.ColorDrawable;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.media.AudioAttributes;
import android.media.MediaPlayer;
import android.media.MediaMetadataRetriever;
import android.os.Bundle;
import android.os.Build;
import android.os.Handler;
import android.os.Environment;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.view.WindowInsets;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.Intent;
import android.net.Uri;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.SeekBar;
import android.widget.Toast;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.provider.OpenableColumns;
import android.provider.DocumentsContract;
import android.provider.Settings;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
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
    private static final int REQUEST_LOCAL_AUDIO = 7235;
    private static final int REQUEST_LOCAL_FOLDER = 7236;
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
    // Activity can be relaunched on configuration changes while provider jobs are in flight.
    // Keep the bounded worker pool process-scoped so a stale Activity cannot shut it down mid-search.
    private static final ExecutorService io = Executors.newFixedThreadPool(6);
    private String source = "all";
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
    private final List<UserPlaylist> userPlaylists = new ArrayList<>();
    private final List<Track> favoriteTracks = new ArrayList<>();
    private final List<Track> localTracks = new ArrayList<>();
    private BroadcastReceiver playbackStateReceiver;
    private List<Track> playbackQueue = new ArrayList<>();
    private int playbackQueueIndex = -1;
    private int activeBackgroundQueueIndex = 0;
    private String activeUserPlaylist = "";
    private String repeatMode = "off";
    private String shuffleMode = "off";
    private boolean preserveQueueOnNextPlay;
    private TextView playerShuffleButton;
    private TextView playerSmartShuffleButton;
    private TextView playerRepeatButton;
    private TextView previousTrackButton;
    private TextView nextTrackButton;
    private String importedPlaylistName = "Imported playlist";
    private String activeTheme = "Frutiger Aero";
    private String activeQuality = "Best available";
    private Dialog playerDialog;
    private Dialog settingsDialog;
    private Dialog themeDialog;
    private Track activePlayerSelection;
    private ImageView miniArtwork;
    private ImageView playerArtwork;
    private TextView playerTitleView;
    private TextView playerArtistView;
    private TextView playerSourceView;
    private TextView playerElapsedView;
    private TextView playerDurationView;
    private TextView playerToggleView;
    private SeekBar playerSeekBar;
    private boolean playerSeeking;
    private boolean checkingArchiveLicense;
    private int allSearchVersion;
    private final Runnable playerProgressTicker = new Runnable() {
        @Override public void run() {
            if (playerDialog == null || !playerDialog.isShowing()) return;
            int duration = MusicPlaybackService.currentDuration();
            int position = MusicPlaybackService.currentPosition();
            if (playerSeekBar != null && !playerSeeking) {
                playerSeekBar.setMax(Math.max(1, duration));
                playerSeekBar.setProgress(Math.min(Math.max(0, position), Math.max(1, duration)));
            }
            if (playerElapsedView != null) playerElapsedView.setText(formatDuration(Math.max(0, position) / 1000));
            if (playerDurationView != null) playerDurationView.setText(duration > 0 ? formatDuration(duration / 1000) : "—:—");
            if (playerToggleView != null) {
                playerToggleView.setText(checkingArchiveLicense ? "…" :
                        (needsExternalPlayback(activePlayerSelection) ? "↗" : (MusicPlaybackService.isPlaying() ? "Ⅱ" : "▶")));
            }
            main.postDelayed(this, 450L);
        }
    };

    @Override
    public void onCreate(Bundle state) {
        super.onCreate(state);
        applyThemeFromPreferences();
        SharedPreferences savedPrefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        String savedPlaylist = savedPrefs.getString("imported_playlist_text", "");
        importedPlaylistName = savedPrefs.getString("imported_playlist_name", "Imported playlist");
        if (!savedPlaylist.trim().isEmpty()) playlistTracks.addAll(parsePlaylistTracks(savedPlaylist));
        favoriteTracks.addAll(readFavoriteTracks(savedPrefs.getString("favorite_tracks_json", "[]")));
        localTracks.addAll(readFavoriteTracks(savedPrefs.getString("local_tracks_json", "[]")));
        userPlaylists.addAll(readUserPlaylists(savedPrefs.getString("user_playlists_json", "[]")));
        String savedRepeatMode = savedPrefs.getString("repeat_mode", "off");
        repeatMode = ("playlist".equals(savedRepeatMode) || "track".equals(savedRepeatMode)) ? savedRepeatMode : "off";
        String savedShuffleMode = savedPrefs.getString("shuffle_mode", "off");
        shuffleMode = ("shuffle".equals(savedShuffleMode) || "smart".equals(savedShuffleMode)) ? savedShuffleMode : "off";
        playbackQueue.addAll(readFavoriteTracks(savedPrefs.getString("ui_queue_json", "[]")));
        playbackQueueIndex = savedPrefs.getInt("ui_queue_index", -1);
        if (playbackQueueIndex < 0 || playbackQueueIndex >= playbackQueue.size()) playbackQueueIndex = -1;
        getWindow().setStatusBarColor(BG);
        getWindow().setNavigationBarColor(BG);
        getWindow().getDecorView().setSystemUiVisibility(0);
        buildUi();
        registerPlaybackStateReceiver();
        if (MusicPlaybackService.hasTrack()) {
            currentTrack = new Track("background-playback", MusicPlaybackService.currentTitle(),
                    MusicPlaybackService.currentArtist(), "Background audio", "background",
                    MusicPlaybackService.currentUrl(), "", "");
            int queueMatch = indexOfQueueUrl(currentTrack.playUrl);
            if (queueMatch >= 0) {
                playbackQueueIndex = queueMatch;
                currentTrack = playbackQueue.get(queueMatch);
            } else {
                playbackQueue.add(0, currentTrack);
                playbackQueueIndex = 0;
            }
            nowPlayingText.setText(currentTrack.title + " — " + currentTrack.artist);
            playPauseButton.setText(MusicPlaybackService.isPlaying() ? "Ⅱ" : "▶");
        }
        // Avoid a real provider request racing the deterministic fixtures in debug-only
        // emulator tests. Production launches still load the selected catalogue normally.
        boolean debugSmokeSession = (getApplicationInfo().flags & ApplicationInfo.FLAG_DEBUGGABLE) != 0
                && getIntent() != null
                && (getIntent().getBooleanExtra("spotty_ci_smoke_playback", false)
                || getIntent().getBooleanExtra("spotty_ci_smoke_stats", false)
                || getIntent().getBooleanExtra("spotty_ci_smoke_connections", false));
        if (!debugSmokeSession) loadCurrentSource("");
        startDebugOnlyPlaybackSmokeIfRequested();
        startDebugOnlyStatsSmokeIfRequested();
        checkForUpdatesIfDue(false);
    }

    private void buildUi() {
        LinearLayout screen = new LinearLayout(this);
        screen.setOrientation(LinearLayout.VERTICAL);
        screen.setBackground(createThemeBackdrop());
        screen.setPadding(dp(18), dp(8), dp(18), dp(7));
        screen.setOnApplyWindowInsetsListener((view, insets) -> {
            view.setPadding(dp(18), insets.getSystemWindowInsetTop() + dp(8),
                    dp(18), insets.getSystemWindowInsetBottom() + dp(7));
            return insets;
        });

        LinearLayout header = new LinearLayout(this);
        header.setGravity(Gravity.CENTER_VERTICAL);
        ImageView mark = new ImageView(this);
        mark.setImageResource(R.drawable.spotty_app_icon);
        mark.setScaleType(ImageView.ScaleType.FIT_CENTER);
        header.addView(mark, new LinearLayout.LayoutParams(dp(48), dp(48)));

        LinearLayout titleBlock = new LinearLayout(this);
        titleBlock.setOrientation(LinearLayout.VERTICAL);
        TextView title = text("spotty", 29, TEXT, true);
        title.setLetterSpacing(-0.055f);
        TextView subtitle = text("SOUND FOR THE SIDE QUESTS", 9, MUTED, true);
        subtitle.setLetterSpacing(0.11f);
        titleBlock.addView(title);
        LinearLayout subLp = new LinearLayout(this);
        subLp.setOrientation(LinearLayout.HORIZONTAL);
        subLp.addView(subtitle);
        titleBlock.addView(subLp);
        LinearLayout.LayoutParams titleLp = new LinearLayout.LayoutParams(0, -2, 1);
        titleLp.leftMargin = dp(8);
        header.addView(titleBlock, titleLp);

        TextView settings = text("☷", 24, TEXT, true);
        settings.setGravity(Gravity.CENTER);
        settings.setBackground(roundRect(PANEL, 18));
        settings.setContentDescription("Settings");
        header.addView(settings, new LinearLayout.LayoutParams(dp(46), dp(46)));
        settings.setOnClickListener(v -> showSettingsDialog());
        screen.addView(header, new LinearLayout.LayoutParams(-1, -2));

        LinearLayout hero = new LinearLayout(this);
        hero.setOrientation(LinearLayout.VERTICAL);
        hero.setPadding(dp(20), dp(17), dp(20), dp(18));
        hero.setBackground(heroDrawable());
        TextView heroEyebrow = text("A MIXTAPE OUTSIDE THE MAINSTREAM", 9, TEXT, true);
        heroEyebrow.setLetterSpacing(0.12f);
        hero.addView(heroEyebrow);
        TextView headline = text(themeHeadline(), 25, TEXT, true);
        headline.setLetterSpacing(-0.025f);
        headline.setMaxLines(2);
        LinearLayout.LayoutParams headlineLp = new LinearLayout.LayoutParams(-1, -2);
        headlineLp.topMargin = dp(10);
        hero.addView(headline, headlineLp);
        TextView direction = text(themeNarrative(), 12, MUTED, false);
        LinearLayout.LayoutParams directionLp = new LinearLayout.LayoutParams(-1, -2);
        directionLp.topMargin = dp(8);
        hero.addView(direction, directionLp);
        LinearLayout.LayoutParams heroLp = new LinearLayout.LayoutParams(-1, -2);
        heroLp.topMargin = dp(13);
        screen.addView(hero, heroLp);

        LinearLayout searchRow = new LinearLayout(this);
        searchRow.setGravity(Gravity.CENTER_VERTICAL);
        searchField = new EditText(this);
        searchField.setSingleLine(true);
        searchField.setTextColor(TEXT);
        searchField.setHintTextColor(MUTED);
        searchField.setHint("Track, artist, mood, or a lyric…");
        searchField.setTextSize(14);
        searchField.setImeOptions(EditorInfo.IME_ACTION_SEARCH);
        searchField.setPadding(dp(15), 0, dp(8), 0);
        searchField.setBackground(roundRect(PANEL, 17));
        LinearLayout.LayoutParams inputLp = new LinearLayout.LayoutParams(0, dp(51), 1);
        inputLp.rightMargin = dp(8);
        searchRow.addView(searchField, inputLp);

        TextView search = text("↗", 23, BG, true);
        search.setGravity(Gravity.CENTER);
        search.setBackground(roundRect(ACCENT, 17));
        search.setContentDescription("Search all sources");
        searchRow.addView(search, new LinearLayout.LayoutParams(dp(54), dp(51)));
        search.setOnClickListener(v -> submitSearch());
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

        HorizontalScrollView genreScroll = new HorizontalScrollView(this);
        genreScroll.setHorizontalScrollBarEnabled(false);
        genreScroll.setClipToPadding(false);
        LinearLayout genres = new LinearLayout(this);
        genres.setOrientation(LinearLayout.HORIZONTAL);
        String[][] mixtapes = {
                {"01 / AFTER DARK", "Shoegaze · slowcore", "shoegaze slowcore", "☾"},
                {"02 / SOFT FOCUS", "Dream pop · ambient", "dream pop ambient", "◌"},
                {"03 / GUITAR WEATHER", "Post-rock · emo", "post-rock emo", "✳"},
                {"04 / STATIC BLOOM", "Indie · alternative", "indie alternative rock", "⌁"},
                {"05 / SLOW MOTION", "Bedroom pop · slowcore", "bedroom pop slowcore", "✦"}
        };
        for (int i = 0; i < mixtapes.length; i++) {
            final String searchMix = mixtapes[i][2];
            int shift = i % 3;
            int mixStart = blendColor(BG, ACCENT, 0.19f + shift * 0.09f);
            int mixEnd = blendColor(PANEL_ALT, ACCENT, 0.10f + ((i + 1) % 3) * 0.08f);
            FrameLayout tile = new FrameLayout(this);
            GradientDrawable tileSurface = new GradientDrawable(GradientDrawable.Orientation.TL_BR,
                    new int[]{mixStart, mixEnd});
            tileSurface.setCornerRadius(dp(20));
            tileSurface.setStroke(dp(1), blendColor(TEXT, ACCENT, 0.28f));
            tile.setBackground(tileSurface);
            tile.setClipToOutline(true);
            tile.setElevation(dp(2));

            LinearLayout tileCopy = new LinearLayout(this);
            tileCopy.setOrientation(LinearLayout.VERTICAL);
            tileCopy.setPadding(dp(13), dp(12), dp(13), dp(10));
            TextView tileNumber = text(mixtapes[i][0], 8, ACCENT, true);
            tileNumber.setLetterSpacing(0.07f);
            TextView tileTitle = text(mixtapes[i][1], 13, TEXT, true);
            tileTitle.setMaxLines(2);
            LinearLayout.LayoutParams tileTitleLp = new LinearLayout.LayoutParams(-1, -2);
            tileTitleLp.topMargin = dp(7);
            tileCopy.addView(tileNumber);
            tileCopy.addView(tileTitle, tileTitleLp);
            tile.addView(tileCopy, new FrameLayout.LayoutParams(-1, -1));

            TextView motif = text(mixtapes[i][3], 43, blendColor(TEXT, ACCENT, 0.35f), true);
            motif.setAlpha(0.32f);
            motif.setGravity(Gravity.CENTER);
            FrameLayout.LayoutParams motifLp = new FrameLayout.LayoutParams(dp(62), dp(62), Gravity.END | Gravity.BOTTOM);
            motifLp.rightMargin = dp(1);
            motifLp.bottomMargin = dp(-2);
            tile.addView(motif, motifLp);

            LinearLayout.LayoutParams tileLp = new LinearLayout.LayoutParams(dp(184), dp(96));
            tileLp.rightMargin = dp(9);
            tile.setOnClickListener(v -> {
                searchField.setText(searchMix);
                source = "all";
                refreshSourceButtons(sourceRow);
                loadAllSources(searchMix);
            });
            genres.addView(tile, tileLp);
        }
        genreScroll.addView(genres);
        LinearLayout.LayoutParams genreLp = new LinearLayout.LayoutParams(-1, dp(104));
        genreLp.topMargin = dp(10);
        screen.addView(genreScroll, genreLp);

        // "All sources" merges the supported public catalogues; these chips are real filters.
        HorizontalScrollView sourceScroll = new HorizontalScrollView(this);
        sourceScroll.setHorizontalScrollBarEnabled(false);
        sourceRow = new LinearLayout(this);
        sourceRow.setOrientation(LinearLayout.HORIZONTAL);
        String[][] providers = {
                {"AUDIUS", "audius"},
                {"OPEN ARCHIVE", "archive"}, {"DEEZER PREVIEWS", "deezer"},
                {"YOUTUBE", "youtube"}, {"SOUNDCLOUD", "soundcloud"},
                {"SPOTIFY / SPOTUI", "spotui"}, {"SOULSEEK", "soulseek"},
                {"LOCAL / FLAC", "local"}, {"RADIO", "radio"}
        };
        for (String[] provider : providers) sourceRow.addView(sourceButton(provider[0], provider[1]));
        sourceScroll.addView(sourceRow);
        LinearLayout.LayoutParams sourceLp = new LinearLayout.LayoutParams(-1, -2);
        sourceLp.topMargin = dp(7);
        screen.addView(sourceScroll, sourceLp);

        LinearLayout statusRow = new LinearLayout(this);
        statusRow.setGravity(Gravity.CENTER_VERTICAL);
        TextView sectionMark = text("✳", 14, ACCENT, true);
        sectionMark.setPadding(0, 0, dp(7), 0);
        statusRow.addView(sectionMark);
        resultHeading = text("FRESH FINDS", 11, TEXT, true);
        resultHeading.setLetterSpacing(0.09f);
        statusRow.addView(resultHeading, new LinearLayout.LayoutParams(0, -2, 1));
        statusText = text("Finding the good stuff…", 10, MUTED, false);
        statusText.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
        statusText.setMaxLines(1);
        statusRow.addView(statusText, new LinearLayout.LayoutParams(-2, -2));
        LinearLayout.LayoutParams statusLp = new LinearLayout.LayoutParams(-1, dp(31));
        statusLp.topMargin = dp(5);
        screen.addView(statusRow, statusLp);

        ScrollView resultScroller = new ScrollView(this);
        resultScroller.setFillViewport(false);
        resultScroller.setVerticalScrollBarEnabled(false);
        resultsList = new LinearLayout(this);
        resultsList.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams resultsLp = new LinearLayout.LayoutParams(-1, 0, 1);
        resultsLp.topMargin = dp(2);
        screen.addView(resultScroller, resultsLp);
        resultScroller.addView(resultsList);

        LinearLayout mini = new LinearLayout(this);
        mini.setGravity(Gravity.CENTER_VERTICAL);
        mini.setOrientation(LinearLayout.HORIZONTAL);
        mini.setPadding(dp(10), dp(9), dp(10), dp(9));
        mini.setBackground(panelCardDrawable());
        FrameLayout miniCover = new FrameLayout(this);
        miniCover.setBackground(coverBackdrop(null));
        miniCover.setClipToOutline(true);
        TextView miniGlyph = text("◉", 27, TEXT, true);
        miniGlyph.setGravity(Gravity.CENTER);
        miniCover.addView(miniGlyph, new FrameLayout.LayoutParams(-1, -1));
        miniArtwork = new ImageView(this);
        miniArtwork.setScaleType(ImageView.ScaleType.CENTER_CROP);
        miniArtwork.setBackground(roundRect(PANEL_ALT, 12));
        miniArtwork.setClipToOutline(true);
        miniCover.addView(miniArtwork, new FrameLayout.LayoutParams(-1, -1));
        miniArtwork.setImageResource(R.drawable.spotty_app_icon);
        mini.addView(miniCover, new LinearLayout.LayoutParams(dp(51), dp(51)));

        LinearLayout miniTextBlock = new LinearLayout(this);
        miniTextBlock.setOrientation(LinearLayout.VERTICAL);
        miniTextBlock.setPadding(dp(10), 0, dp(5), 0);
        TextView miniLabel = text("ON THE TURNTABLE", 8, ACCENT, true);
        miniLabel.setLetterSpacing(0.11f);
        miniTextBlock.addView(miniLabel);
        nowPlayingText = text("Pick a sound to begin", 12, TEXT, true);
        nowPlayingText.setMaxLines(1);
        TextView miniHint = text("Tap to open the player", 10, MUTED, false);
        LinearLayout.LayoutParams hintLp = new LinearLayout.LayoutParams(-1, -2);
        hintLp.topMargin = dp(3);
        miniTextBlock.addView(nowPlayingText, new LinearLayout.LayoutParams(-1, -2));
        miniTextBlock.addView(miniHint, hintLp);
        mini.addView(miniTextBlock, new LinearLayout.LayoutParams(0, -2, 1));
        miniTextBlock.setOnClickListener(v -> openMiniPlayer());
        miniCover.setOnClickListener(v -> openMiniPlayer());

        playPauseButton = text("▶", 21, BG, true);
        playPauseButton.setGravity(Gravity.CENTER);
        playPauseButton.setBackground(roundRect(ACCENT, 50));
        playPauseButton.setContentDescription("Play or pause");
        playPauseButton.setContentDescription("Play or pause, or start the first result");
        playPauseButton.setOnClickListener(v -> {
            if (currentTrack != null && needsExternalPlayback(currentTrack)) {
                showPlayerDialog(currentTrack);
            } else if (MusicPlaybackService.hasTrack()) {
                togglePlayback();
            } else if (currentTrack != null) {
                playTrack(currentTrack);
            } else if (!visibleTracks.isEmpty()) {
                playTrack(visibleTracks.get(0));
            } else {
                openMiniPlayer();
            }
        });
        mini.addView(playPauseButton, new LinearLayout.LayoutParams(dp(43), dp(43)));
        mini.setOnClickListener(v -> openMiniPlayer());
        LinearLayout.LayoutParams miniLp = new LinearLayout.LayoutParams(-1, -2);
        miniLp.topMargin = dp(8);
        screen.addView(mini, miniLp);

        LinearLayout nav = new LinearLayout(this);
        nav.setGravity(Gravity.CENTER);
        nav.setPadding(0, dp(6), 0, 0);
        TextView discoverTab = text("⌂  DISCOVER", 10, ACCENT, true);
        TextView libraryTab = text("♡  LIBRARY", 10, MUTED, true);
        TextView queueTab = text("≡  QUEUE", 10, MUTED, true);
        TextView settingsTab = text("☷  SETTINGS", 10, MUTED, true);
        for (TextView tab : new TextView[]{discoverTab, libraryTab, queueTab, settingsTab}) {
            tab.setGravity(Gravity.CENTER);
            tab.setPadding(dp(9), dp(9), dp(9), dp(9));
            nav.addView(tab, new LinearLayout.LayoutParams(0, dp(40), 1));
        }
        discoverTab.setOnClickListener(v -> {
            source = "all";
            discoverTab.setTextColor(ACCENT);
            libraryTab.setTextColor(MUTED);
            searchField.setHint("Track, artist, mood, or a lyric…");
            loadAllSources(searchField.getText().toString().trim());
        });
        libraryTab.setOnClickListener(v -> showLibraryDialog());
        queueTab.setOnClickListener(v -> showQueueDialog());
        settingsTab.setOnClickListener(v -> showSettingsDialog());
        screen.addView(nav, new LinearLayout.LayoutParams(-1, -2));

        setContentView(screen);
    }

    private void openMiniPlayer() {
        Track selected = currentTrack;
        if (selected == null && !visibleTracks.isEmpty()) selected = visibleTracks.get(0);
        if (selected != null) {
            showPlayerDialog(selected);
            return;
        }
        showEmptyPlayerDialog();
    }

    private void showEmptyPlayerDialog() {
        Dialog dialog = new Dialog(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER_HORIZONTAL);
        root.setPadding(dp(24), dp(20), dp(24), dp(24));
        root.setBackground(createThemeBackdrop());

        TextView close = text("⌄", 30, TEXT, true);
        close.setGravity(Gravity.CENTER);
        close.setBackground(roundRect(PANEL, 50));
        LinearLayout.LayoutParams closeLp = new LinearLayout.LayoutParams(dp(46), dp(46));
        closeLp.gravity = Gravity.START;
        root.addView(close, closeLp);
        close.setOnClickListener(v -> dialog.dismiss());

        TextView glyph = text(themeSymbolFor(activeTheme), 62, ACCENT, true);
        glyph.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams glyphLp = new LinearLayout.LayoutParams(-1, dp(135));
        glyphLp.topMargin = dp(36);
        root.addView(glyph, glyphLp);
        TextView title = text("Your next listen starts here.", 25, TEXT, true);
        title.setGravity(Gravity.CENTER);
        TextView detail = text("Choose a result, import a local audio file, or search every connected public catalogue from one place.", 13, MUTED, false);
        detail.setGravity(Gravity.CENTER);
        detail.setLineSpacing(dp(3), 1.05f);
        LinearLayout.LayoutParams detailLp = new LinearLayout.LayoutParams(-1, -2);
        detailLp.topMargin = dp(10);
        root.addView(title, new LinearLayout.LayoutParams(-1, -2));
        root.addView(detail, detailLp);

        TextView search = text("SEARCH MUSIC  ↗", 12, Color.WHITE, true);
        search.setGravity(Gravity.CENTER);
        search.setBackground(roundRect(ACCENT, 18));
        LinearLayout.LayoutParams searchLp = new LinearLayout.LayoutParams(-1, dp(50));
        searchLp.topMargin = dp(24);
        root.addView(search, searchLp);
        search.setOnClickListener(v -> { dialog.dismiss(); submitSearch(); });

        TextView local = text("＋  CHOOSE LOCAL AUDIO / FLAC", 11, TEXT, true);
        local.setGravity(Gravity.CENTER);
        local.setBackground(roundRect(PANEL, 16));
        LinearLayout.LayoutParams localLp = new LinearLayout.LayoutParams(-1, dp(48));
        localLp.topMargin = dp(9);
        root.addView(local, localLp);
        local.setOnClickListener(v -> { dialog.dismiss(); chooseLocalAudio(); });

        dialog.setContentView(root);
        dialog.setCancelable(true);
        dialog.show();
        Window window = dialog.getWindow();
        if (window != null) {
            window.setBackgroundDrawable(new ColorDrawable(BG));
            window.setLayout(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT);
            window.setStatusBarColor(BG);
            window.setNavigationBarColor(BG);
            window.addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS);
        }
    }

    private View sourceButton(String label, String value) {
        TextView b = text(label, 13, value.equals(source) ? Color.WHITE : TEXT, true);
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
        if (row == null) return;
        for (int i = 0; i < row.getChildCount(); i++) {
            View child = row.getChildAt(i);
            if (!(child instanceof TextView)) continue;
            String value = String.valueOf(child.getTag());
            boolean selected = value.equals(source);
            ((TextView) child).setTextColor(selected ? Color.WHITE : TEXT);
            child.setBackground(roundRect(selected ? ACCENT : PANEL_ALT, 18));
        }
    }

    private void applyThemeFromPreferences() {
        SharedPreferences prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        String savedTheme = prefs.getString("theme", "Frutiger Aero");
        boolean knownTheme = false;
        for (String available : THEME_NAMES) if (available.equals(savedTheme)) knownTheme = true;
        activeTheme = knownTheme ? savedTheme : "Frutiger Aero";
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
                // Bright aquatic sky, translucent glass, white highlights and deep-blue type.
                BG = Color.rgb(204, 241, 249); PANEL = Color.rgb(239, 252, 255);
                PANEL_ALT = Color.rgb(155, 220, 237); TEXT = Color.rgb(13, 62, 91);
                MUTED = Color.rgb(42, 111, 139); ACCENT = Color.rgb(0, 133, 194); break;
        }
    }

    private void showThemeDialog() {
        Dialog dialog = new Dialog(this);
        themeDialog = dialog;
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackground(createThemeBackdrop());
        root.setPadding(dp(20), dp(16), dp(20), dp(18));
        LinearLayout heading = new LinearLayout(this);
        heading.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout copy = new LinearLayout(this);
        copy.setOrientation(LinearLayout.VERTICAL);
        TextView title = text("Choose your world", 24, TEXT, true);
        TextView subtitle = text("Distinct art direction, not just a different accent colour.", 11, MUTED, false);
        LinearLayout.LayoutParams subtitleLp = new LinearLayout.LayoutParams(-1, -2);
        subtitleLp.topMargin = dp(5);
        copy.addView(title);
        copy.addView(subtitle, subtitleLp);
        heading.addView(copy, new LinearLayout.LayoutParams(0, -2, 1));
        TextView close = text("×", 27, TEXT, true);
        close.setGravity(Gravity.CENTER);
        close.setBackground(roundRect(PANEL, 50));
        heading.addView(close, new LinearLayout.LayoutParams(dp(42), dp(42)));
        close.setOnClickListener(v -> dialog.dismiss());
        root.addView(heading, new LinearLayout.LayoutParams(-1, -2));

        ScrollView scroller = new ScrollView(this);
        scroller.setVerticalScrollBarEnabled(false);
        LinearLayout list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams listLp = new LinearLayout.LayoutParams(-1, -2);
        listLp.topMargin = dp(15);
        root.addView(scroller, new LinearLayout.LayoutParams(-1, 0, 1));
        scroller.addView(list);
        for (String name : THEME_NAMES) {
            LinearLayout row = new LinearLayout(this);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setPadding(dp(11), dp(11), dp(11), dp(11));
            GradientDrawable previewBg = new GradientDrawable(GradientDrawable.Orientation.TL_BR,
                    themePreviewColors(name));
            previewBg.setCornerRadius(dp("Retro CRT".equals(name) ? 5 : 17));
            if (name.equals(activeTheme)) previewBg.setStroke(dp(2), TEXT);
            row.setBackground(panelCardDrawable());
            TextView icon = text(themeSymbolFor(name), 24, Color.WHITE, true);
            icon.setGravity(Gravity.CENTER);
            icon.setBackground(previewBg);
            row.addView(icon, new LinearLayout.LayoutParams(dp(48), dp(48)));
            LinearLayout labelBlock = new LinearLayout(this);
            labelBlock.setOrientation(LinearLayout.VERTICAL);
            TextView label = text(name, 13, TEXT, true);
            TextView blurb = text(themeNarrativeFor(name), 10, MUTED, false);
            blurb.setMaxLines(2);
            labelBlock.addView(label);
            LinearLayout.LayoutParams blurbLp = new LinearLayout.LayoutParams(-1, -2);
            blurbLp.topMargin = dp(4);
            labelBlock.addView(blurb, blurbLp);
            LinearLayout.LayoutParams labelLp = new LinearLayout.LayoutParams(0, -2, 1);
            labelLp.leftMargin = dp(12);
            row.addView(labelBlock, labelLp);
            TextView selected = text(name.equals(activeTheme) ? "✓" : "›", 18,
                    name.equals(activeTheme) ? ACCENT : MUTED, true);
            row.addView(selected);
            LinearLayout.LayoutParams rowLp = new LinearLayout.LayoutParams(-1, -2);
            rowLp.bottomMargin = dp(8);
            list.addView(row, rowLp);
            row.setOnClickListener(v -> {
                // Dismiss both stacked dialogs before recreating the Activity; otherwise the
                // old Settings window can outlive the Activity window token.
                getSharedPreferences(PREFS, MODE_PRIVATE).edit().putString("theme", name).apply();
                dialog.dismiss();
                if (settingsDialog != null && settingsDialog.isShowing()) settingsDialog.dismiss();
                settingsDialog = null;
                themeDialog = null;
                recreate();
            });
        }
        dialog.setContentView(root);
        dialog.setCancelable(true);
        dialog.setOnDismissListener(ignored -> { if (themeDialog == dialog) themeDialog = null; });
        dialog.show();
        Window window = dialog.getWindow();
        if (window != null) {
            window.setBackgroundDrawable(new ColorDrawable(BG));
            window.setLayout(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT);
        }
    }

    private String themeNarrativeFor(String name) {
        switch (name) {
            case "Frutiger Aero": return "Aqua glass · blue skies · bubbles and bright early-web optimism";
            case "Midwest Emo": return "Bedroom shows, diary pages, worn guitar strings";
            case "Synthwave": return "Neon grids · chrome drums · after-hours energy";
            case "Vaporwave": return "Lost malls · soft pixels · yesterday's future";
            case "Cyberpunk": return "Glitched transmissions · hard edges · underground frequencies";
            case "Aurora Forest": return "Mist, moss and music from the edge of the map";
            case "AMOLED": return "True black · quiet controls · no visual noise";
            case "Y2K Glass": return "Iridescent surfaces, soft chrome and digital nostalgia";
            case "Candy Pop": return "Playful shapes, bubblegum colour and a little chaos";
            case "Retro CRT": return "Phosphor green, scanlines and late-night broadcasts";
            case "Sunset Coast": return "Warm haze, peach skies and the last light";
            case "Dark Academia": return "Ink, paper, old rooms and records worth keeping";
            default: return "An independent mix of open catalogues, local files and strange little finds.";
        }
    }

    private void showSettingsDialog() {
        Dialog dialog = new Dialog(this);
        settingsDialog = dialog;
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackground(createThemeBackdrop());
        root.setPadding(dp(20), dp(16), dp(20), dp(18));
        LinearLayout heading = new LinearLayout(this);
        heading.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout titleBlock = new LinearLayout(this);
        titleBlock.setOrientation(LinearLayout.VERTICAL);
        TextView kicker = text("YOUR LISTENING SPACE", 9, ACCENT, true);
        kicker.setLetterSpacing(0.12f);
        TextView title = text("Settings", 28, TEXT, true);
        titleBlock.addView(kicker);
        LinearLayout.LayoutParams titleLp = new LinearLayout.LayoutParams(-1, -2);
        titleLp.topMargin = dp(4);
        titleBlock.addView(title, titleLp);
        heading.addView(titleBlock, new LinearLayout.LayoutParams(0, -2, 1));
        TextView close = text("×", 27, TEXT, true);
        close.setGravity(Gravity.CENTER);
        close.setBackground(roundRect(PANEL, 50));
        heading.addView(close, new LinearLayout.LayoutParams(dp(42), dp(42)));
        close.setOnClickListener(v -> dialog.dismiss());
        root.addView(heading);

        ScrollView scroller = new ScrollView(this);
        scroller.setVerticalScrollBarEnabled(false);
        LinearLayout rows = new LinearLayout(this);
        rows.setOrientation(LinearLayout.VERTICAL);
        scroller.addView(rows);
        LinearLayout.LayoutParams scrollLp = new LinearLayout.LayoutParams(-1, 0, 1);
        scrollLp.topMargin = dp(17);
        root.addView(scroller, scrollLp);

        addSettingsRow(rows, "✧", "Appearance & themes", activeTheme + " · art direction, contrast and surfaces", this::showThemeDialog);
        addSettingsRow(rows, "♫", "Playback quality", activeQuality + " · uses what each source actually provides", this::showQualityDialog);
        addSettingsRow(rows, "◷", "Listening stats", "Time listened, track starts and top artists on this device", this::showMusicStatsDialog);
        addSettingsRow(rows, "⇄", "Account connections", "Provider sign-in status and requirements", this::showConnectionsDialog);
        addSettingsRow(rows, "◉", "Background reliability", "Battery and background-running settings for long listening sessions", this::showBackgroundReliabilityDialog);
        addSettingsRow(rows, "▤", "Playlist import", "CSV, M3U or pasted track lists", this::showPlaylistImportDialog);
        addSettingsRow(rows, "↻", "App updates", "Check the latest Spotty Android release", () -> checkForUpdatesIfDue(true));
        addSettingsRow(rows, "ⓘ", "About Spotty", "What plays here, what opens elsewhere, and why", () ->
                new AlertDialog.Builder(this).setTitle("About Spotty")
                        .setMessage("Spotty searches several public catalogues together. Audius streams full public tracks, Internet Archive playback is checked for an open licence, Deezer supplies official previews, and local files play directly. YouTube listings need an explicit hand-off because this app has no supported YouTube audio-stream API. Spotify and SoundCloud credentials are not fabricated or silently marked as connected.")
                        .setPositiveButton("Done", null).show());
        TextView foot = text("No pretend logins. No invented quality. No automatic source hand-offs.", 10, MUTED, false);
        foot.setGravity(Gravity.CENTER);
        foot.setPadding(dp(6), dp(12), dp(6), dp(8));
        root.addView(foot, new LinearLayout.LayoutParams(-1, -2));

        dialog.setContentView(root);
        dialog.setCancelable(true);
        dialog.setOnDismissListener(ignored -> { if (settingsDialog == dialog) settingsDialog = null; });
        dialog.show();
        Window window = dialog.getWindow();
        if (window != null) {
            window.setBackgroundDrawable(new ColorDrawable(BG));
            window.setLayout(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT);
            window.setStatusBarColor(BG);
            window.setNavigationBarColor(BG);
            window.addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS);
        }
    }

    private void showBackgroundReliabilityDialog() {
        String guidance = "Spotty keeps playback in a foreground media service and retries remote streams, but Android and phone manufacturers still control battery and background limits. For longer listening sessions:\n\n"
                + "• Open App info → Battery and choose Unrestricted / No restrictions if your phone offers it.\n"
                + "• On Xiaomi / POCO HyperOS, enable Autostart and allow background activity where those options are available.\n"
                + "• Allow Spotty notifications and background data.\n"
                + "• Do not force-stop Spotty; force-stop deliberately terminates playback.\n\n"
                + "These settings improve resilience but cannot prevent interruptions from shutdown/reboot, lost connectivity, inaccessible tracks, provider outages, or system-enforced restrictions.";
        new AlertDialog.Builder(this)
                .setTitle("Background playback reliability")
                .setMessage(guidance)
                .setPositiveButton("Open App info", (dialog, which) -> openSpottyAppSettings())
                .setNeutralButton("Battery optimization", (dialog, which) -> openBatteryOptimizationSettings())
                .setNegativeButton("Done", null)
                .show();
    }

    private void openSpottyAppSettings() {
        try {
            Intent intent = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.fromParts("package", getPackageName(), null));
            startActivity(intent);
        } catch (Exception e) {
            Toast.makeText(this, "Could not open Spotty's App info settings.", Toast.LENGTH_LONG).show();
        }
    }

    private void openBatteryOptimizationSettings() {
        try {
            startActivity(new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS));
        } catch (Exception e) {
            openSpottyAppSettings();
        }
    }

    private void showMusicStatsDialog() {
        TextView body = text(MusicStats.buildSummaryText(this), 14, TEXT, false);
        body.setPadding(dp(2), dp(5), dp(2), dp(8));
        body.setTextIsSelectable(true);
        body.setLineSpacing(dp(3), 1.05f);
        ScrollView scroller = new ScrollView(this);
        scroller.setFillViewport(false);
        scroller.setVerticalScrollBarEnabled(true);
        scroller.addView(body, new ScrollView.LayoutParams(-1, -2));
        int availableHeight = Math.max(dp(260), getResources().getDisplayMetrics().heightPixels - dp(220));
        scroller.setLayoutParams(new LinearLayout.LayoutParams(-1, Math.min(dp(540), availableHeight)));

        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("Music stats")
                .setView(scroller)
                .setPositiveButton("Done", null)
                .setNeutralButton("Refresh now", null)
                .create();
        Runnable refresh = new Runnable() {
            @Override public void run() {
                if (!dialog.isShowing()) return;
                body.setText(MusicStats.buildSummaryText(MainActivity.this));
                main.postDelayed(this, 1000L);
            }
        };
        dialog.setOnShowListener(ignored -> {
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener(v ->
                    body.setText(MusicStats.buildSummaryText(MainActivity.this)));
            main.post(refresh);
        });
        dialog.setOnDismissListener(ignored -> main.removeCallbacks(refresh));
        dialog.show();
    }

    private void showConnectionsDialog() {
        new AlertDialog.Builder(this)
                .setTitle("Connect music accounts")
                .setItems(new String[]{"YouTube account", "YouTube Music", "Spotify sign-in",
                        "Open Spotui player", ".fmbot / Last.fm setup", "Connection status & requirements"}, (dialog, which) -> {
                    if (which == 0) openServiceLink("https://www.youtube.com/account", new String[]{"com.google.android.youtube"});
                    else if (which == 1) openServiceLink("https://music.youtube.com/", new String[]{"com.google.android.apps.youtube.music", "app.morphe.android.apps.youtube.music"});
                    else if (which == 2) openServiceLink("https://accounts.spotify.com/login", new String[]{"com.spotify.music"});
                    else if (which == 3) openSpotui();
                    else if (which == 4) openServiceLink("https://fm.bot/", new String[0]);
                    else showConnectionStatusDialog();
                })
                .setNegativeButton("Close", null)
                .show();
    }

    private void showConnectionStatusDialog() {
        String explanation = "Account access status\n"
                + "• YouTube: Spotty currently searches public catalog results; it does not hold a Google/YouTube account token.\n"
                + "• YouTube Music: playback/search is handed to the official app or website; sign-in remains there.\n"
                + "• Spotify: this Spotty app has no Spotify OAuth/API session. The legacy Spotatui build is separate.\n"
                + "• .fmbot: its stats are based on Last.fm and Discord; it has no direct Spotty account API.\n\n"
                + "The listed destinations open official services; they do not mark an account as connected or silently import private data. True in-app sync needs registered provider credentials and supported OAuth/API access.";
        new AlertDialog.Builder(this)
                .setTitle("Connection status")
                .setMessage(explanation)
                .setPositiveButton("Done", null)
                .show();
    }

    private void openSpotui() {
        try {
            Intent launch = getPackageManager().getLaunchIntentForPackage("com.music.spotui");
            if (launch != null) {
                startActivity(launch);
                if (statusText != null) statusText.setText("Handed off to Spotui. Spotify playback is handled by that separate app.");
                return;
            }
        } catch (Exception ignored) {}
        new AlertDialog.Builder(this)
                .setTitle("Spotui is not installed")
                .setMessage("Spotui is a separate open-source Android player for Spotify-account features. Spotty cannot embed its private player session. Open the official project page to install it.")
                .setPositiveButton("Official project", (d, w) -> openServiceLink("https://github.com/Spotui/Spotui/releases/latest", new String[0]))
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void openServiceLink(String url, String[] packageNames) {
        for (String packageName : packageNames) {
            try {
                Intent appIntent = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
                appIntent.setPackage(packageName);
                startActivity(appIntent);
                return;
            } catch (Exception ignored) {}
        }
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
        } catch (Exception e) {
            statusText.setText("No installed app or browser can open this service.");
        }
    }

    private Track enrichLocalTrackMetadata(Track track) {
        if (track == null || !track.playUrl.startsWith("content://")) return track;
        MediaMetadataRetriever retriever = new MediaMetadataRetriever();
        try {
            retriever.setDataSource(this, Uri.parse(track.playUrl));
            String title = metadataValue(retriever, MediaMetadataRetriever.METADATA_KEY_TITLE, track.title);
            String artist = metadataValue(retriever, MediaMetadataRetriever.METADATA_KEY_ARTIST, track.artist);
            String album = metadataValue(retriever, MediaMetadataRetriever.METADATA_KEY_ALBUM, track.album);
            String genre = track.genre;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                genre = metadataValue(retriever, MediaMetadataRetriever.METADATA_KEY_GENRE, genre);
            }
            return new Track(track.id, title, artist, track.subtitle, track.provider, track.playUrl,
                    track.artworkUrl, track.license, album, genre, track.playlist);
        } catch (Exception ignored) {
            return track;
        } finally {
            try { retriever.release(); } catch (Exception ignored) {}
        }
    }

    private String metadataValue(MediaMetadataRetriever retriever, int key, String fallback) {
        try {
            String value = retriever.extractMetadata(key);
            if (value != null && !value.trim().isEmpty()) return value.trim();
        } catch (Exception ignored) {}
        return fallback == null ? "" : fallback;
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
        if (requestCode == REQUEST_LOCAL_AUDIO) {
            if (resultCode == RESULT_OK && data != null) handleLocalAudioSelection(data);
            return;
        }
        if (requestCode == REQUEST_LOCAL_FOLDER) {
            if (resultCode == RESULT_OK && data != null) handleLocalFolderSelection(data);
            return;
        }
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
            root.put("tracks", new JSONArray(serializeTrackList(tracks)));
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
                if (items != null) {
                    JSONObject first = items.optJSONObject(0);
                    if (first != null && (first.has("playUrl") || first.has("provider"))) return readFavoriteTracks(items.toString());
                    return parseSpotifyExportItems(items);
                }
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
        source = "all";
        refreshSourceButtons(sourceRow);
        loadAllSources(query);
    }

    private void loadAllSources(String query) {
        loadAllSources(query, false);
    }

    private void loadAllSources(String query, boolean retryAttempt) {
        final String q = query == null ? "" : query.trim();
        source = "all";
        refreshSourceButtons(sourceRow);
        final int version = ++allSearchVersion;
        youtubeSearchVersion++;
        resultHeading.setText(q.isEmpty() ? "FRESH FINDS" : "SEARCH RESULTS");
        setLoading(q.isEmpty() ? "Tuning into four catalogues…" : "Searching across sources…");
        resultsList.removeAllViews();

        CompletionService<List<Track>> completed = new ExecutorCompletionService<>(io);
        List<Future<List<Track>>> requests = new ArrayList<>();
        requests.add(completed.submit(() -> searchAudiusCatalogue(q)));
        requests.add(completed.submit(() -> searchArchiveCatalogue(q)));
        requests.add(completed.submit(() -> searchDeezerCatalogue(q)));
        requests.add(completed.submit(() -> searchYoutubeCatalogue(q)));

        // Snapshot local results on the UI thread, then merge network catalogues off-thread.
        List<Track> localSnapshot = new ArrayList<>();
        for (Track t : localTracks) {
            String haystack = (t.title + " " + t.artist + " " + t.album).toLowerCase(Locale.ROOT);
            if (q.isEmpty() || haystack.contains(q.toLowerCase(Locale.ROOT))) localSnapshot.add(t);
        }
        io.execute(() -> {
            List<Track> merged = new ArrayList<>(localSnapshot);
            int responsive = 0;
            int finished = 0;
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(24);
            while (finished < requests.size()) {
                long remaining = deadline - System.nanoTime();
                if (remaining <= 0L) break;
                try {
                    Future<List<Track>> task = completed.poll(remaining, TimeUnit.NANOSECONDS);
                    if (task == null) break;
                    finished++;
                    try {
                        List<Track> found = task.get();
                        if (found != null) {
                            responsive++;
                            merged.addAll(found);
                        }
                    } catch (Exception ignored) {
                        // One provider failing must not hide results from the other catalogues.
                    }
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
            for (Future<List<Track>> task : requests) if (!task.isDone()) task.cancel(true);
            // Put direct, in-app streams first. Non-playable YouTube listings remain accessible,
            // but only through an explicit action in the player sheet.
            merged.sort((a, b) -> Integer.compare(playbackRank(a), playbackRank(b)));
            if (merged.size() > 120) merged = new ArrayList<>(merged.subList(0, 120));
            final List<Track> finalTracks = merged;
            final int sourcesOk = responsive;
            main.post(() -> {
                if (version != allSearchVersion || !"all".equals(source)) return;
                if (sourcesOk == 0 && !retryAttempt) {
                    String retrying = "No catalogue responded • retrying once…";
                    showTracks(finalTracks, retrying);
                    appendExternalSearchShortcuts(q);
                    statusText.setText(retrying);
                    // Mobile/emulator networks can finish connecting just after launch. Retry once
                    // rather than leaving a fresh install stuck on an empty catalogue screen.
                    main.postDelayed(() -> {
                        if (version == allSearchVersion && "all".equals(source)) {
                            loadAllSources(q, true);
                        }
                    }, 1500L);
                    return;
                }
                String status = finalTracks.size() + " finds • " + sourcesOk + "/4 catalogues online";
                if (sourcesOk == 0) status = "Catalogues unavailable • check connection and retry";
                showTracks(finalTracks, status);
                appendExternalSearchShortcuts(q);
                if (sourcesOk == 0 && statusText != null) statusText.setText(status);
            });
        });
    }

    private void appendExternalSearchShortcuts(String query) {
        if (resultsList == null) return;
        final String q = query == null ? "" : query.trim();
        TextView section = text("MORE PLACES TO LISTEN", 10, ACCENT, true);
        section.setLetterSpacing(0.11f);
        section.setPadding(dp(2), dp(18), dp(2), dp(8));
        resultsList.addView(section, new LinearLayout.LayoutParams(-1, -2));
        addHandoffCard(resultsList, "Spotify · Spotui",
                "Spotify's catalogue and account playback stay inside Spotui; Spotty will not fake a connected Spotify session.",
                "Open Spotui player ↗", this::openSpotui);
        addHandoffCard(resultsList, "SoundCloud",
                "Use SoundCloud's official app or web search for tracks that are not in the public catalogues above.",
                "Search SoundCloud ↗", () -> openSoundCloudSearch(q.isEmpty() ? "music" : q));
        addHandoffCard(resultsList, "YouTube Music · Morphe",
                "Search and listen in your installed YouTube Music/Morphe app. Playback controls belong to that app.",
                "Search Morphe Music ↗", () -> openYoutubeMusicSearch(q.isEmpty() ? "music" : q));
    }

    private void addHandoffCard(LinearLayout parent, String title, String detailText,
            String actionLabel, Runnable action) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(14), dp(13), dp(14), dp(13));
        card.setBackground(panelCardDrawable());
        TextView heading = text(title, 14, TEXT, true);
        card.addView(heading);
        TextView detail = text(detailText, 11, MUTED, false);
        detail.setLineSpacing(dp(2), 1.04f);
        LinearLayout.LayoutParams detailLp = new LinearLayout.LayoutParams(-1, -2);
        detailLp.topMargin = dp(5);
        card.addView(detail, detailLp);
        Button open = button(actionLabel, ACCENT, BG);
        open.setOnClickListener(v -> action.run());
        LinearLayout.LayoutParams openLp = new LinearLayout.LayoutParams(-1, dp(42));
        openLp.topMargin = dp(10);
        card.addView(open, openLp);
        LinearLayout.LayoutParams cardLp = new LinearLayout.LayoutParams(-1, -2);
        cardLp.bottomMargin = dp(8);
        parent.addView(card, cardLp);
    }

    private int playbackRank(Track track) {
        if (track == null) return 5;
        if ("audius".equals(track.provider)) return 0;
        if ("deezer-preview".equals(track.provider)) return 1;
        if ("archive".equals(track.provider) || "archive-playable".equals(track.provider)) return 2;
        if ("local".equals(track.provider)) return 0;
        if ("radio".equals(track.provider)) return 2;
        return 4;
    }

    private List<Track> searchAudiusCatalogue(String query) throws Exception {
        String endpoint = query.isEmpty()
                ? "https://api.audius.co/v1/tracks/trending?limit=40&filter_tracks=public&gate_condition=ungated"
                : "https://api.audius.co/v1/tracks/search?query=" + enc(query)
                        + "&limit=40&filter_tracks=public&gate_condition=ungated";
        JSONObject response = requestJsonObject(endpoint);
        JSONArray data = response.optJSONArray("data");
        if (data == null) throw new Exception("Audius returned no track list");
        List<Track> found = new ArrayList<>();
        for (int i = 0; i < data.length(); i++) {
            JSONObject item = data.optJSONObject(i);
            if (item == null || !item.optBoolean("is_streamable", true)) continue;
            String id = item.optString("id", "");
            if (id.isEmpty()) continue;
            String title = item.optString("title", "Untitled track");
            JSONObject user = item.optJSONObject("user");
            String artist = user == null ? "Audius artist" : user.optString("name", "Audius artist");
            JSONObject artwork = item.optJSONObject("artwork");
            String art = artwork == null ? "" : artwork.optString("150x150", "");
            String genre = item.optString("genre", "");
            found.add(new Track(id, title, artist, "Audius", "audius",
                    "https://api.audius.co/v1/tracks/" + Uri.encode(id) + "/stream", art, "", "", genre, ""));
        }
        return found;
    }

    private List<Track> searchDeezerCatalogue(String query) throws Exception {
        String endpoint = query.isEmpty()
                ? "https://api.deezer.com/chart/0/tracks?limit=40"
                : "https://api.deezer.com/search?q=" + enc(query) + "&limit=40";
        JSONObject response = requestJsonObject(endpoint);
        JSONArray data = response.optJSONArray("data");
        if (data == null) throw new Exception("Deezer returned no track list");
        List<Track> found = new ArrayList<>();
        for (int i = 0; i < data.length(); i++) {
            JSONObject item = data.optJSONObject(i);
            if (item == null) continue;
            String preview = item.optString("preview", "");
            if (preview.isEmpty()) continue;
            String id = item.optString("id", "");
            JSONObject artistObject = item.optJSONObject("artist");
            String artist = artistObject == null ? "Deezer artist" : artistObject.optString("name", "Deezer artist");
            JSONObject album = item.optJSONObject("album");
            String art = album == null ? "" : album.optString("cover_medium", "");
            String albumTitle = album == null ? "" : album.optString("title", "");
            String title = item.optString("title", "Untitled track");
            String link = item.optString("link", id.isEmpty() ? "https://www.deezer.com/" :
                    "https://www.deezer.com/track/" + Uri.encode(id));
            found.add(new Track(id, title, artist, "Deezer preview · about 30 sec", "deezer-preview",
                    preview, art, link, albumTitle, "", ""));
        }
        return found;
    }

    private List<Track> searchArchiveCatalogue(String query) throws Exception {
        String expression = "mediatype:audio AND (licenseurl:*creativecommons.org* OR licenseurl:*publicdomain*)";
        String clean = query.replaceAll("[^\\p{L}\\p{N}\\s_-]", " ").trim();
        if (!clean.isEmpty()) expression += " AND title:(" + clean + ")";
        String endpoint = "https://archive.org/advancedsearch.php?q=" + enc(expression)
                + "&fl%5B%5D=identifier&fl%5B%5D=title&fl%5B%5D=creator&fl%5B%5D=licenseurl"
                + "&rows=35&page=1&output=json&sort%5B%5D=downloads+desc";
        JSONObject response = requestJsonObject(endpoint);
        JSONObject responseBlock = response.optJSONObject("response");
        JSONArray docs = responseBlock == null ? null : responseBlock.optJSONArray("docs");
        if (docs == null) throw new Exception("Internet Archive returned no results");
        List<Track> found = new ArrayList<>();
        for (int i = 0; i < docs.length(); i++) {
            JSONObject item = docs.optJSONObject(i);
            if (item == null) continue;
            String id = item.optString("identifier", "");
            if (id.isEmpty()) continue;
            String title = item.optString("title", id);
            Object creator = item.opt("creator");
            String artist = creator instanceof JSONArray ? ((JSONArray) creator).optString(0, "Archive contributor")
                    : (creator == null || creator == JSONObject.NULL ? "Archive contributor" : String.valueOf(creator));
            String license = item.optString("licenseurl", "");
            found.add(new Track(id, title, artist, "Internet Archive · open licence check", "archive",
                    "", "", license));
        }
        return found;
    }

    private List<Track> searchYoutubeCatalogue(String query) throws Exception {
        String q = query.isEmpty() ? "music sort:views" : query;
        String[] instances = {
                "https://inv.nadeko.net",
                "https://invidious.nerdvpn.de",
                "https://invidious.f5.si",
                "https://invidious.tiekoetter.com"
        };
        Exception last = null;
        for (String instance : instances) {
            try {
                List<Track> found = searchYoutubeInstance(instance, q);
                if (!found.isEmpty()) return found;
                last = new Exception("No public YouTube listings from this instance");
            } catch (Exception e) {
                last = e;
            }
        }
        throw last == null ? new Exception("No YouTube catalog instance responded") : last;
    }

    private void loadCurrentSource(String query) {
        if ("all".equals(source)) {
            loadAllSources(query);
        } else if ("liked".equals(source)) {
            resultHeading.setText("YOUR LIKED MUSIC");
            showTracks(new ArrayList<>(favoriteTracks), favoriteTracks.isEmpty()
                    ? "Your collection is empty. Tap the heart on a result to save it here."
                    : favoriteTracks.size() + " saved tracks • stored on this device");
        } else if ("playlist".equals(source)) {
            resultHeading.setText("PLAYLIST • " + importedPlaylistName.toUpperCase(Locale.ROOT));
            showTracks(new ArrayList<>(playlistTracks), playlistTracks.isEmpty()
                    ? "No imported tracks yet. Import a CSV, M3U, or pasted track list."
                    : playlistTracks.size() + " imported tracks • tap a track to open its options");
        } else if ("user_playlist".equals(source)) {
            UserPlaylist selected = findUserPlaylist(activeUserPlaylist);
            resultHeading.setText("YOUR MIX • " + (selected == null ? "PLAYLIST" : selected.name.toUpperCase(Locale.ROOT)));
            showTracks(selected == null ? new ArrayList<>() : new ArrayList<>(selected.tracks),
                    selected == null ? "This playlist is no longer available." : selected.tracks.size() + " tracks • saved on this device");
        } else if ("youtube".equals(source)) loadYoutube(query);
        else if ("ytmusic".equals(source)) openYoutubeMusicSearch(query);
        else if ("archive".equals(source)) loadArchive(query);
        else if ("deezer".equals(source)) loadDeezer(query);
        else if ("soundcloud".equals(source)) loadSoundCloud(query);
        else if ("spotui".equals(source)) loadSpotuiSource(query);
        else if ("soulseek".equals(source)) loadSoulseekSource();
        else if ("local".equals(source)) loadLocalLibrary();
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
            String subtitle = "YouTube · external player";
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
        try {
            Intent morpheIntent = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
            morpheIntent.setPackage("app.morphe.android.youtube");
            startActivity(morpheIntent);
            statusText.setText("Opened in Morphe YouTube; Spotty remains available in the background.");
        } catch (Exception morpheUnavailable) {
            try {
                startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
                statusText.setText("Morphe not detected; opened the video in the available YouTube app or browser.");
            } catch (Exception noHandler) {
                statusText.setText("No installed app or browser can open this YouTube link.");
            }
        }
    }


    /**
     * CI-only integration probe. It is inert in release APKs and accepts no caller-supplied
     * URL: the only test source is a WAV fixture copied into this app's private files dir.
     */
    private void startDebugOnlyStatsSmokeIfRequested() {
        if ((getApplicationInfo().flags & ApplicationInfo.FLAG_DEBUGGABLE) == 0) return;
        if (getIntent().getBooleanExtra("spotty_ci_smoke_stats", false)) {
            main.postDelayed(this::showMusicStatsDialog, 1200L);
        } else if (getIntent().getBooleanExtra("spotty_ci_smoke_connections", false)) {
            main.postDelayed(this::showConnectionsDialog, 1200L);
        }
    }

    private void startDebugOnlyPlaybackSmokeIfRequested() {
        boolean debuggable = (getApplicationInfo().flags & ApplicationInfo.FLAG_DEBUGGABLE) != 0;
        boolean requested = getIntent() != null
                && getIntent().getBooleanExtra("spotty_ci_smoke_playback", false);
        android.util.Log.i("SpottySmoke", "playback-hook gate debuggable=" + debuggable
                + " requested=" + requested + " intent=" + getIntent());
        if (!debuggable || !requested) return;
        File fixture = new File(getFilesDir(), "spotty-ci-smoke.wav");
        File shortTwo = new File(getFilesDir(), "spotty-ci-short-two.wav");
        File shortThree = new File(getFilesDir(), "spotty-ci-short-three.wav");
        File smartFixture = new File(getFilesDir(), "spotty-ci-short-smart.wav");
        if (!fixture.isFile() || !fixture.canRead()
                || !shortTwo.isFile() || !shortTwo.canRead()
                || !shortThree.isFile() || !shortThree.canRead()
                || !smartFixture.isFile() || !smartFixture.canRead()) {
            statusText.setText("CI playback fixtures are missing");
            android.util.Log.e("SpottySmoke", "missing playback fixture(s): long=" + fixture.isFile()
                    + " second=" + shortTwo.isFile() + " third=" + shortThree.isFile()
                    + " smart=" + smartFixture.isFile() + " directory=" + getFilesDir());
            return;
        }
        android.util.Log.i("SpottySmoke", "playback-hook accepted fixtures directory=" + getFilesDir());
        main.postDelayed(() -> {
            Track smokeTrack = new Track("ci-smoke", "Spotty playback test", "Anchor Artist",
                    "Local WAV • CI smoke test", "local",
                    fixture.getAbsolutePath(), "", "generated test tone", "Anchor Album", "Shoegaze", "CI Mix");
            Track shortTrackTwo = new Track("ci-smoke-2", "Spotty queue second", "Neighbor Artist",
                    "Short local WAV • CI repeat test", "local",
                    shortTwo.getAbsolutePath(), "", "generated test tone", "Neighbor Album", "Shoegaze", "CI Mix");
            Track shortTrackThree = new Track("ci-smoke-3", "Spotty queue third", "Different Artist",
                    "Short local WAV • CI repeat test", "local",
                    shortThree.getAbsolutePath(), "", "generated test tone", "Different Album", "Ambient", "CI Mix");
            Track smartSuggestion = new Track("ci-smart", "Spotty smart suggestion", "Neighbor Artist",
                    "Short local WAV • CI smart-shuffle test", "local",
                    smartFixture.getAbsolutePath(), "", "generated test tone", "Neighbor Album", "Shoegaze", "CI Mix");
            Track duplicateSmartSuggestion = new Track("ci-smart-duplicate", "Spotty smart suggestion duplicate",
                    "Neighbor Artist", "Duplicate URL • CI smart-shuffle test", "local",
                    smartFixture.getAbsolutePath(), "", "generated test tone", "Neighbor Album", "Shoegaze", "CI Mix");
            visibleTracks.clear();
            visibleTracks.add(smartSuggestion);
            visibleTracks.add(duplicateSmartSuggestion);
            playbackQueue = new ArrayList<>();
            playbackQueue.add(smokeTrack);
            playbackQueue.add(shortTrackTwo);
            playbackQueue.add(shortTrackThree);
            playbackQueueIndex = 0;
            android.util.Log.i("SpottySmoke", "test-hook restored-favorites=" + favoriteTracks.size()
                    + " saved-current=" + isFavorite(smokeTrack)
                    + " files-dir=" + getFilesDir().getAbsolutePath());
            beginPlayback(smokeTrack);
            android.util.Log.i("SpottySmoke", "playback-hook started service=" + MusicPlaybackService.hasTrack()
                    + " playing=" + MusicPlaybackService.isPlaying()
                    + " current-title=" + MusicPlaybackService.currentTitle());
            // The emulator gate also exercises the actual player sheet, not just the service.
            showPlayerDialog(smokeTrack);
            android.util.Log.i("SpottySmoke", "playback-hook opened player sheet title=" + smokeTrack.title);
        }, 500L);
    }

    private void registerPlaybackStateReceiver() {
        playbackStateReceiver = new BroadcastReceiver() {
            @Override public void onReceive(Context context, Intent intent) {
                String state = intent.getStringExtra("state");
                String message = intent.getStringExtra("message");
                String title = intent.getStringExtra("title");
                String artist = intent.getStringExtra("artist");
                if (title != null && !title.isEmpty() && MusicPlaybackService.hasTrack()) {
                    String playingUrl = MusicPlaybackService.currentUrl();
                    int matchedIndex = indexOfQueueUrl(playingUrl);
                    if (matchedIndex >= 0) {
                        playbackQueueIndex = matchedIndex;
                        currentTrack = playbackQueue.get(matchedIndex);
                    } else if (currentTrack == null || !playingUrl.equals(currentTrack.playUrl)) {
                        currentTrack = new Track("background-playback", title,
                                artist == null ? "" : artist, "Background audio", "background", playingUrl, "", "");
                    }
                    persistUiQueue();
                }
                if (title != null && !title.isEmpty() && nowPlayingText != null) {
                    nowPlayingText.setText(title + (artist == null || artist.isEmpty() ? "" : " — " + artist));
                }
                if (title != null && !title.isEmpty() && playerTitleView != null) playerTitleView.setText(title);
                if (artist != null && playerArtistView != null) playerArtistView.setText(artist);
                if (playerToggleView != null) {
                    playerToggleView.setText("connecting".equals(state) ? "…" : (needsExternalPlayback(activePlayerSelection) ? "↗" : ("playing".equals(state) ? "Ⅱ" : "▶")));
                }
                if ("playing".equals(state)) {
                    // The service may finish preparing after the player sheet first appears.
                    // Seed the timeline on the first real playing broadcast as well as on open,
                    // so a paused UI ticker never leaves the seek bar with a placeholder max.
                    if (playerSeekBar != null) {
                        int durationMs = MusicPlaybackService.currentDuration();
                        if (durationMs > 0) {
                            playerSeekBar.setMax(durationMs);
                            playerSeekBar.setProgress(Math.min(Math.max(0, MusicPlaybackService.currentPosition()), durationMs));
                        }
                    }
                    preparing = false; paused = false;
                    if (playPauseButton != null) playPauseButton.setText("Ⅱ");
                    if (statusText != null) statusText.setText("Playing in background • " + (message == null ? "" : message));
                } else if ("paused".equals(state)) {
                    preparing = false; paused = true;
                    if (playPauseButton != null) playPauseButton.setText("▶");
                    if (statusText != null) statusText.setText(message == null ? "Paused" : message);
                } else if ("connecting".equals(state)) {
                    preparing = true;
                    if (playPauseButton != null) playPauseButton.setText("…");
                    if (statusText != null) statusText.setText(message == null ? "Connecting…" : message);
                } else if ("error".equals(state)) {
                    preparing = false; paused = false;
                    if (playPauseButton != null) playPauseButton.setText("▶");
                    if (statusText != null) statusText.setText("Playback issue • " + (message == null ? "try again" : message));
                } else if ("completed".equals(state)) {
                    preparing = false; paused = false;
                    if (playPauseButton != null) playPauseButton.setText("▶");
                    if (statusText != null) statusText.setText("Track finished");
                    // The foreground playback service advances the persisted queue itself.
                } else if ("stopped".equals(state)) {
                    preparing = false; paused = false;
                    if (playPauseButton != null) playPauseButton.setText("▶");
                }
            }
        };
        IntentFilter filter = new IntentFilter(MusicPlaybackService.ACTION_STATE);
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(playbackStateReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
        else registerReceiver(playbackStateReceiver, filter);
    }

    private void requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT < 33) return;
        if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) return;
        SharedPreferences prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        if (prefs.getBoolean("notification_permission_asked", false)) return;
        prefs.edit().putBoolean("notification_permission_asked", true).apply();
        requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 7404);
    }

    private void chooseLocalAudio() {
        new AlertDialog.Builder(this)
                .setTitle("Add local music")
                .setItems(new String[]{"Choose audio files", "Import a folder (e.g. Soulseek downloads)"}, (dialog, which) -> {
                    if (which == 0) chooseLocalAudioFiles();
                    else chooseLocalAudioFolder();
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void chooseLocalAudioFiles() {
        try {
            Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
            intent.addCategory(Intent.CATEGORY_OPENABLE);
            intent.setType("audio/*");
            intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
            startActivityForResult(intent, REQUEST_LOCAL_AUDIO);
        } catch (Exception e) {
            statusText.setText("Could not open the audio file picker.");
        }
    }

    private void chooseLocalAudioFolder() {
        try {
            Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
            startActivityForResult(intent, REQUEST_LOCAL_FOLDER);
        } catch (Exception e) {
            statusText.setText("Folder access is not supported by this file provider. Choose audio files instead.");
        }
    }

    private void loadLocalLibrary() {
        resultHeading.setText("LOCAL AUDIO • FLAC / LOSSLESS");
        showTracks(new ArrayList<>(localTracks), localTracks.isEmpty()
                ? "Choose Add local audio / FLAC to play music already on your phone or in a Soulseek downloads folder."
                : localTracks.size() + " local files • phone storage only");
    }

    private void handleLocalAudioSelection(Intent data) {
        List<Uri> selected = new ArrayList<>();
        if (data.getClipData() != null) {
            for (int i = 0; i < data.getClipData().getItemCount(); i++) {
                Uri uri = data.getClipData().getItemAt(i).getUri();
                if (uri != null) selected.add(uri);
            }
        } else if (data.getData() != null) {
            selected.add(data.getData());
        }
        int added = 0;
        for (Uri uri : selected) {
            try {
                getContentResolver().takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
            } catch (SecurityException | IllegalArgumentException ignored) {
                // Some document providers grant access only for the current session.
            }
            String name = getDocumentDisplayName(uri);
            if (!isSupportedAudioName(name)) {
                statusText.setText("Skipped unsupported-looking file: " + name);
                continue;
            }
            if (addLocalTrack(uri, name)) added++;
        }
        persistLocalTracks();
        source = "local";
        refreshSourceButtons(sourceRow);
        showTracks(new ArrayList<>(localTracks), added + " files added • " + localTracks.size() + " local files saved");
        resultHeading.setText("LOCAL AUDIO • FLAC / LOSSLESS");
    }

    private void handleLocalFolderSelection(Intent data) {
        Uri treeUri = data.getData();
        if (treeUri == null) return;
        final String rootDocumentId;
        try {
            try {
                getContentResolver().takePersistableUriPermission(treeUri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
            } catch (SecurityException | IllegalArgumentException ignored) {
                // Some file providers give temporary but usable access instead.
            }
            rootDocumentId = DocumentsContract.getTreeDocumentId(treeUri);
        } catch (Exception e) {
            showError("Could not open this folder: " + shortError(e));
            return;
        }

        statusText.setText("Scanning audio files and subfolders…");
        final List<Track> existingTracks = new ArrayList<>(localTracks);
        io.execute(() -> {
            List<Track> discovered = new ArrayList<>();
            List<String> folders = new ArrayList<>();
            folders.add(rootDocumentId);
            int foldersScanned = 0;
            int entriesScanned = 0;
            String scanWarning = "";
            String[] columns = new String[]{DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                    DocumentsContract.Document.COLUMN_DISPLAY_NAME, DocumentsContract.Document.COLUMN_MIME_TYPE};

            while (!folders.isEmpty() && foldersScanned < 500 && entriesScanned < 12000
                    && existingTracks.size() + discovered.size() < 2500) {
                String folderId = folders.remove(folders.size() - 1);
                foldersScanned++;
                Uri childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, folderId);
                try (Cursor cursor = getContentResolver().query(childrenUri, columns, null, null, null)) {
                    if (cursor == null) continue;
                    int idCol = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DOCUMENT_ID);
                    int nameCol = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DISPLAY_NAME);
                    int mimeCol = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_MIME_TYPE);
                    if (idCol < 0 || nameCol < 0) continue;

                    while (cursor.moveToNext() && entriesScanned < 12000
                            && existingTracks.size() + discovered.size() < 2500) {
                        entriesScanned++;
                        String documentId = cursor.getString(idCol);
                        String name = cursor.getString(nameCol);
                        String mime = mimeCol >= 0 ? cursor.getString(mimeCol) : "";
                        if (documentId == null || documentId.isEmpty()) continue;
                        if (DocumentsContract.Document.MIME_TYPE_DIR.equals(mime)) {
                            if (foldersScanned + folders.size() < 500) folders.add(documentId);
                            continue;
                        }
                        // Trust a recognized audio extension even if a document provider
                        // reports a non-standard MIME type for FLAC or other audio files.
                        if (!isSupportedAudioName(name)) continue;

                        Uri documentUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, documentId);
                        String uri = documentUri.toString();
                        if (containsLocalUri(existingTracks, uri) || containsLocalUri(discovered, uri)) continue;
                        String lower = name.toLowerCase(Locale.ROOT);
                        String format = lower.contains(".")
                                ? lower.substring(lower.lastIndexOf('.') + 1).toUpperCase(Locale.ROOT) : "AUDIO";
                        discovered.add(new Track(uri, name, "Local library",
                                "Local file • " + format, "local", uri, "", "user-selected local file"));
                    }
                } catch (Exception e) {
                    if (scanWarning.isEmpty()) scanWarning = "Some folders could not be read.";
                }
            }

            final int folderCount = foldersScanned;
            final int entryCount = entriesScanned;
            final String warning = scanWarning;
            main.post(() -> {
                localTracks.addAll(discovered);
                persistLocalTracks();
                source = "local";
                refreshSourceButtons(sourceRow);
                showTracks(new ArrayList<>(localTracks), discovered.size() + " new audio files imported from "
                        + folderCount + " folders • " + localTracks.size() + " total");
                resultHeading.setText("LOCAL AUDIO • FLAC / LOSSLESS");
                if (!warning.isEmpty()) statusText.setText(warning + " Imported " + discovered.size() + " audio files.");
                else if (entryCount >= 12000 || folderCount >= 500 || localTracks.size() >= 2500) {
                    statusText.setText("Scan reached its safety limit. Narrow the selected download folder to import more.");
                } else statusText.setText("Folder scan complete.");
            });
        });
    }

    private boolean containsLocalUri(List<Track> tracks, String uri) {
        for (Track track : tracks) if (uri.equals(track.playUrl)) return true;
        return false;
    }

    private String getDocumentDisplayName(Uri uri) {
        String name = uri.getLastPathSegment();
        try (Cursor cursor = getContentResolver().query(uri, null, null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) {
                int index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if (index >= 0) name = cursor.getString(index);
            }
        } catch (Exception ignored) {}
        return name == null || name.trim().isEmpty() ? "Local audio" : name.trim();
    }

    private boolean isSupportedAudioName(String name) {
        if (name == null) return false;
        String lower = name.toLowerCase(Locale.ROOT);
        return lower.endsWith(".flac") || lower.endsWith(".wav") || lower.endsWith(".aiff")
                || lower.endsWith(".alac") || lower.endsWith(".mp3") || lower.endsWith(".m4a")
                || lower.endsWith(".aac") || lower.endsWith(".ogg") || lower.endsWith(".opus")
                || lower.endsWith(".wma");
    }

    private boolean addLocalTrack(Uri uri, String name) {
        for (Track existing : localTracks) if (existing.playUrl.equals(uri.toString())) return false;
        String lower = name.toLowerCase(Locale.ROOT);
        String format = lower.contains(".") ? lower.substring(lower.lastIndexOf('.') + 1).toUpperCase(Locale.ROOT) : "AUDIO";
        localTracks.add(new Track(uri.toString(), name, "Local library",
                "Local file • " + format, "local", uri.toString(), "", "user-selected local file"));
        return true;
    }

    private void persistLocalTracks() {
        getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                .putString("local_tracks_json", serializeTrackList(localTracks)).apply();
    }

    private void loadSoulseekSource() {
        resultHeading.setText("SOULSEEK • FLAC LIBRARY");
        resultsList.removeAllViews();
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(18), dp(18), dp(18), dp(18));
        card.setBackground(panelCardDrawable());
        card.addView(text("Search with Soulseek, play in Spotty", 18, TEXT, true));
        TextView detail = text("Soulseek is a peer-to-peer file-sharing network, not a streaming FLAC server. Open the Seeker client to search/download audio you have rights to, then import its download folder. Spotty will play your selected FLAC files locally at their original decoded quality.", 13, MUTED, false);
        LinearLayout.LayoutParams detailLp = new LinearLayout.LayoutParams(-1, -2);
        detailLp.topMargin = dp(10);
        card.addView(detail, detailLp);

        Button open = button("Open Seeker (Soulseek) ↗", ACCENT, BG);
        open.setOnClickListener(v -> openSeeker());
        LinearLayout.LayoutParams openLp = new LinearLayout.LayoutParams(-1, dp(46));
        openLp.topMargin = dp(15);
        card.addView(open, openLp);

        Button importFolder = button("Import Soulseek download folder", PANEL_ALT, TEXT);
        importFolder.setOnClickListener(v -> chooseLocalAudio());
        LinearLayout.LayoutParams importLp = new LinearLayout.LayoutParams(-1, dp(46));
        importLp.topMargin = dp(8);
        card.addView(importFolder, importLp);

        resultsList.addView(card, new LinearLayout.LayoutParams(-1, -2));
        statusText.setText("Ready to open Seeker or import local FLAC files");
    }

    private void openSeeker() {
        final String packageName = "com.companyname.andriodapp1";
        try {
            Intent launch = getPackageManager().getLaunchIntentForPackage(packageName);
            if (launch != null) {
                startActivity(launch);
                statusText.setText("Opened Seeker • import downloaded files here when ready");
                return;
            }
        } catch (Exception ignored) {}
        new AlertDialog.Builder(this)
                .setTitle("Seeker is not installed")
                .setMessage("Seeker is a separate Soulseek client. Install it from its official project, then return to Spotty and import the folder where it saves downloads.")
                .setPositiveButton("Open official project", (dialog, which) -> {
                    try { startActivity(new Intent(Intent.ACTION_VIEW,
                            Uri.parse("https://github.com/jackBonadies/SeekerAndroid"))); }
                    catch (Exception ignored) { statusText.setText("Could not open the Seeker project link."); }
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void loadSpotuiSource(String query) {
        resultHeading.setText("SPOTIFY • SPOTUI HANDOFF");
        resultsList.removeAllViews();
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(18), dp(18), dp(18), dp(18));
        card.setBackground(panelCardDrawable());
        card.addView(text("Spotify belongs in Spotui.", 20, TEXT, true));
        TextView detail = text(query == null || query.trim().isEmpty()
                ? "Open the separate Spotui app for Spotify account sign-in and its own supported playback."
                : "Your search: “" + query.trim() + "”. Spotify's private catalogue session is handled by Spotui; Spotty does not fabricate a logged-in Spotify result list.",
                13, MUTED, false);
        detail.setLineSpacing(dp(3), 1.05f);
        LinearLayout.LayoutParams detailLp = new LinearLayout.LayoutParams(-1, -2);
        detailLp.topMargin = dp(10);
        card.addView(detail, detailLp);
        Button open = button("Open Spotui player ↗", ACCENT, Color.WHITE);
        open.setOnClickListener(v -> openSpotui());
        LinearLayout.LayoutParams openLp = new LinearLayout.LayoutParams(-1, dp(48));
        openLp.topMargin = dp(16);
        card.addView(open, openLp);
        TextView note = text("If Spotui is not installed, the button offers its official project page. Playback stays in Spotui rather than being falsely shown as playing in this player.",
                11, MUTED, false);
        note.setLineSpacing(dp(2), 1.05f);
        LinearLayout.LayoutParams noteLp = new LinearLayout.LayoutParams(-1, -2);
        noteLp.topMargin = dp(12);
        card.addView(note, noteLp);
        resultsList.addView(card, new LinearLayout.LayoutParams(-1, -2));
        statusText.setText("Spotify playback is an explicit handoff to Spotui.");
    }

    private void loadSoundCloud(String query) {
        resultHeading.setText("SOUNDCLOUD • OFFICIAL CATALOG");
        resultsList.removeAllViews();
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(18), dp(18), dp(18), dp(18));
        card.setBackground(panelCardDrawable());
        card.addView(text("Find tracks on SoundCloud", 18, TEXT, true));
        TextView detail = text("Search and stream in SoundCloud's official app or website. Spotty won't scrape private streams or bypass platform access controls.", 13, MUTED, false);
        LinearLayout.LayoutParams detailLp = new LinearLayout.LayoutParams(-1, -2);
        detailLp.topMargin = dp(10);
        card.addView(detail, detailLp);
        Button open = button("Open SoundCloud search ↗", ACCENT, BG);
        open.setOnClickListener(v -> openSoundCloudSearch(query));
        LinearLayout.LayoutParams openLp = new LinearLayout.LayoutParams(-1, dp(46));
        openLp.topMargin = dp(15);
        card.addView(open, openLp);
        resultsList.addView(card, new LinearLayout.LayoutParams(-1, -2));
        statusText.setText("Official SoundCloud handoff ready");
    }

    private void openSoundCloudSearch(String query) {
        String q = query == null ? "" : query.trim();
        final String address;
        try { address = "https://soundcloud.com/search/sounds?q=" + enc(q.isEmpty() ? "music" : q); }
        catch (Exception e) {
            statusText.setText("Could not prepare SoundCloud search.");
            return;
        }
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(address)).setPackage("com.soundcloud.android"));
            statusText.setText("Opened SoundCloud app");
        } catch (Exception appUnavailable) {
            try {
                startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(address)));
                statusText.setText("Opened official SoundCloud search in the browser");
            } catch (Exception noHandler) {
                statusText.setText("No installed app or browser can open SoundCloud.");
            }
        }
    }

    private void loadDeezer(String query) {
        resultHeading.setText(query == null || query.trim().isEmpty() ? "DEEZER PREVIEWS" : "DEEZER SEARCH");
        String q = query == null ? "" : query.trim();
        setLoading(q.isEmpty() ? "Loading Deezer previews…" : "Searching Deezer previews…");
        io.execute(() -> {
            try {
                String endpoint = q.isEmpty()
                        ? "https://api.deezer.com/chart/0/tracks?limit=30"
                        : "https://api.deezer.com/search?q=" + enc(q) + "&limit=30";
                JSONObject response = requestJsonObject(endpoint);
                JSONArray data = response.optJSONArray("data");
                if (data == null) throw new Exception("Deezer returned no track list.");
                List<Track> found = new ArrayList<>();
                for (int i = 0; i < data.length(); i++) {
                    JSONObject item = data.optJSONObject(i);
                    if (item == null) continue;
                    String preview = item.optString("preview", "");
                    if (preview.isEmpty()) continue;
                    String id = item.optString("id", "");
                    JSONObject artistObject = item.optJSONObject("artist");
                    String artist = artistObject == null ? "Deezer artist" : artistObject.optString("name", "Deezer artist");
                    JSONObject album = item.optJSONObject("album");
                    String art = album == null ? "" : album.optString("cover_medium", "");
                    String title = item.optString("title", "Untitled track");
                    String link = item.optString("link", id.isEmpty() ? "https://www.deezer.com/" : "https://www.deezer.com/track/" + Uri.encode(id));
                    String albumTitle = album == null ? "" : album.optString("title", "");
                    found.add(new Track(id, title, artist, "Deezer • official preview (typically 30 seconds)",
                            "deezer-preview", preview, art, link, albumTitle, "", ""));
                    if (found.size() >= 30) break;
                }
                main.post(() -> showTracks(found, found.isEmpty()
                        ? "No preview-enabled results. Try another search or open Deezer directly."
                        : found.size() + " official previews • full tracks open in Deezer"));
            } catch (Exception e) {
                main.post(() -> showError("Deezer API is unavailable: " + shortError(e) + " • You can still open Deezer directly."));
            }
        });
    }

    private void openDeezerTrack(Track track) {
        String address = track.license.startsWith("https://") ? track.license
                : "https://www.deezer.com/track/" + Uri.encode(track.id);
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(address)).setPackage("deezer.android.app"));
        } catch (Exception appUnavailable) {
            try { startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(address))); }
            catch (Exception noHandler) { statusText.setText("No app or browser can open Deezer."); }
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
                    String genre = item.optString("genre", "");
                    found.add(new Track(id, title, artist, "Audius • free streaming", "audius",
                            url, image, "", "", genre, ""));
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

    private int blendColor(int first, int second, float ratio) {
        float r = Math.max(0f, Math.min(1f, ratio));
        return Color.rgb(
                Math.round(Color.red(first) * (1f - r) + Color.red(second) * r),
                Math.round(Color.green(first) * (1f - r) + Color.green(second) * r),
                Math.round(Color.blue(first) * (1f - r) + Color.blue(second) * r));
    }

    private GradientDrawable heroDrawable() {
        int radius = "Retro CRT".equals(activeTheme) ? 8 :
                ("Dark Academia".equals(activeTheme) ? 13 : ("Midwest Emo".equals(activeTheme) ? 12 : 25));
        int[] colors;
        switch (activeTheme) {
            case "Frutiger Aero": colors = new int[]{Color.rgb(106, 215, 241), Color.rgb(188, 242, 250), Color.rgb(250, 255, 255)}; break;
            case "Midwest Emo": colors = new int[]{Color.rgb(80, 46, 78), Color.rgb(42, 34, 49), Color.rgb(27, 24, 31)}; break;
            case "Synthwave": colors = new int[]{Color.rgb(107, 31, 125), Color.rgb(42, 20, 71), BG}; break;
            case "Vaporwave": colors = new int[]{Color.rgb(93, 76, 151), Color.rgb(54, 53, 111), Color.rgb(27, 45, 75)}; break;
            case "Cyberpunk": colors = new int[]{Color.rgb(41, 58, 51), Color.rgb(19, 33, 37), Color.BLACK}; break;
            case "Aurora Forest": colors = new int[]{Color.rgb(48, 120, 91), Color.rgb(17, 63, 55), BG}; break;
            case "AMOLED": colors = new int[]{Color.rgb(28, 28, 35), Color.rgb(8, 8, 10), Color.BLACK}; break;
            case "Y2K Glass": colors = new int[]{Color.rgb(163, 213, 249), Color.rgb(76, 122, 186), Color.rgb(37, 54, 103)}; break;
            case "Candy Pop": colors = new int[]{Color.rgb(226, 89, 170), Color.rgb(135, 47, 130), BG}; break;
            case "Retro CRT": colors = new int[]{Color.rgb(28, 79, 46), Color.rgb(12, 41, 28), Color.rgb(4, 20, 14)}; break;
            case "Sunset Coast": colors = new int[]{Color.rgb(245, 145, 126), Color.rgb(155, 73, 104), BG}; break;
            case "Dark Academia": colors = new int[]{Color.rgb(96, 71, 45), Color.rgb(53, 44, 34), BG}; break;
            default: colors = new int[]{PANEL_ALT, PANEL, BG}; break;
        }
        GradientDrawable d = new GradientDrawable(GradientDrawable.Orientation.TL_BR, colors);
        d.setCornerRadius(dp(radius));
        d.setStroke(dp(1), Color.argb(90, Color.red(ACCENT), Color.green(ACCENT), Color.blue(ACCENT)));
        return d;
    }

    private String themeHeadline() {
        switch (activeTheme) {
            case "Frutiger Aero": return "The internet felt like sunshine.";
            case "Midwest Emo": return "Songs for the long way home.";
            case "Synthwave": return "Midnight looks good on you.";
            case "Vaporwave": return "A soft glitch in the ordinary.";
            case "Cyberpunk": return "Find signal in the static.";
            case "Aurora Forest": return "Get lost. Come back with a song.";
            case "AMOLED": return "Nothing extra. Just the feeling.";
            case "Y2K Glass": return "Tomorrow, in full colour.";
            case "Candy Pop": return "Make your ears feel something.";
            case "Retro CRT": return "Broadcast from another planet.";
            case "Sunset Coast": return "Golden-hour soundtracks.";
            case "Dark Academia": return "For the beautifully overthought.";
            default: return "A little less algorithm. A little more feeling.";
        }
    }

    private String themeNarrative() {
        switch (activeTheme) {
            case "Frutiger Aero": return "Glossy aqua · glass bubbles · blue skies and clean air";
            case "Midwest Emo": return "Fuzzy guitars · diary lyrics · 2 a.m. bus rides";
            case "Synthwave": return "Neon horizons · chrome drums · after-hours energy";
            case "Vaporwave": return "Lost malls · soft pixels · yesterday's future";
            case "Cyberpunk": return "Glitched transmissions · underground frequencies";
            case "Aurora Forest": return "Mist, moss and music from the edge of the map";
            case "AMOLED": return "A clean listening room, with the lights turned down";
            case "Y2K Glass": return "Iridescent surfaces, soft chrome and digital nostalgia";
            case "Candy Pop": return "Playful shapes, vivid covers and a little chaos";
            case "Retro CRT": return "Scanlines, bootlegs and late-night broadcasts";
            case "Sunset Coast": return "Warm haze, dusty drums and the last light";
            case "Dark Academia": return "Ink, paper, old rooms and records worth keeping";
            default: return "An independent mix of open catalogues, local files and strange little finds.";
        }
    }

    private GradientDrawable coverBackdrop(Track track) {
        int second = "Dark Academia".equals(activeTheme) ? Color.rgb(127, 83, 46)
                : ("Midwest Emo".equals(activeTheme) ? Color.rgb(105, 65, 95) : ACCENT);
        if (track != null && "deezer-preview".equals(track.provider)) second = Color.rgb(225, 125, 69);
        if (track != null && "audius".equals(track.provider)) second = Color.rgb(92, 192, 180);
        if (track != null && ("archive".equals(track.provider) || "archive-playable".equals(track.provider))) second = Color.rgb(190, 163, 111);
        int[] colors = "Frutiger Aero".equals(activeTheme)
                ? new int[]{Color.rgb(246, 255, 255), Color.rgb(137, 224, 243), Color.rgb(22, 141, 199)}
                : new int[]{blendColor(second, TEXT, 0.12f), PANEL_ALT, BG};
        GradientDrawable d = new GradientDrawable(GradientDrawable.Orientation.TL_BR, colors);
        d.setCornerRadius(dp("Retro CRT".equals(activeTheme) ? 7 : 18));
        d.setStroke(dp(1), Color.argb(65, Color.red(ACCENT), Color.green(ACCENT), Color.blue(ACCENT)));
        return d;
    }

    private FrameLayout createCoverFrame(Track track, int size) {
        FrameLayout frame = new FrameLayout(this);
        frame.setBackground(coverBackdrop(track));
        frame.setClipToOutline(true);
        TextView glyph = text(track == null ? "◉" :
                ("audius".equals(track.provider) ? "∿" :
                ("deezer-preview".equals(track.provider) ? "◒" :
                (("archive".equals(track.provider) || "archive-playable".equals(track.provider)) ? "✳" :
                ("local".equals(track.provider) ? "▤" : "♫")))),
                size >= dp(150) ? 53 : 28, TEXT, true);
        glyph.setGravity(Gravity.CENTER);
        frame.addView(glyph, new FrameLayout.LayoutParams(-1, -1));
        ImageView art = new ImageView(this);
        art.setScaleType(ImageView.ScaleType.CENTER_CROP);
        art.setBackground(roundRect(PANEL_ALT, 16));
        art.setClipToOutline(true);
        art.setVisibility(View.GONE);
        frame.addView(art, new FrameLayout.LayoutParams(-1, -1));
        if (track != null && !track.artworkUrl.isEmpty()) loadTrackArtwork(art, track.artworkUrl);
        else art.setVisibility(View.GONE);
        return frame;
    }

    private void loadTrackArtwork(ImageView view, String address) {
        if (view == null || address == null || !address.startsWith("https://")) return;
        view.setTag(address);
        io.execute(() -> {
            HttpURLConnection connection = null;
            Bitmap bitmap = null;
            try {
                connection = (HttpURLConnection) new URL(address).openConnection();
                connection.setConnectTimeout(4500);
                connection.setReadTimeout(5000);
                connection.setInstanceFollowRedirects(true);
                connection.setRequestProperty("User-Agent", "SpottyAndroid/0.2");
                try (InputStream stream = connection.getInputStream()) {
                    bitmap = BitmapFactory.decodeStream(stream);
                }
            } catch (Exception ignored) {
                // Artwork is decorative; never allow an image host failure to stop playback/search.
            } finally {
                if (connection != null) connection.disconnect();
            }
            final Bitmap finalBitmap = bitmap;
            if (finalBitmap != null) main.post(() -> {
                if (address.equals(view.getTag())) {
                    view.setVisibility(View.VISIBLE);
                    view.setImageBitmap(finalBitmap);
                }
            });
        });
    }

    private String formatPlayerTime(int milliseconds) {
        return formatDuration(Math.max(0, milliseconds) / 1000);
    }

    private void showLibraryDialog() {
        Dialog dialog = new Dialog(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(BG);
        root.setPadding(dp(20), dp(16), dp(20), dp(18));

        LinearLayout heading = new LinearLayout(this);
        heading.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout titleBlock = new LinearLayout(this);
        titleBlock.setOrientation(LinearLayout.VERTICAL);
        TextView kicker = text("SAVED, LOCAL & SIDE CHANNELS", 9, ACCENT, true);
        kicker.setLetterSpacing(0.12f);
        TextView title = text("Your library", 25, TEXT, true);
        titleBlock.addView(kicker);
        LinearLayout.LayoutParams titleLp = new LinearLayout.LayoutParams(-1, -2);
        titleLp.topMargin = dp(4);
        titleBlock.addView(title, titleLp);
        heading.addView(titleBlock, new LinearLayout.LayoutParams(0, -2, 1));
        TextView close = text("×", 27, TEXT, true);
        close.setGravity(Gravity.CENTER);
        close.setBackground(roundRect(PANEL, 50));
        heading.addView(close, new LinearLayout.LayoutParams(dp(42), dp(42)));
        close.setOnClickListener(v -> dialog.dismiss());
        root.addView(heading);

        ScrollView scroller = new ScrollView(this);
        scroller.setVerticalScrollBarEnabled(false);
        LinearLayout rows = new LinearLayout(this);
        rows.setOrientation(LinearLayout.VERTICAL);
        scroller.addView(rows);
        LinearLayout.LayoutParams scrollLp = new LinearLayout.LayoutParams(-1, 0, 1);
        scrollLp.topMargin = dp(16);
        root.addView(scroller, scrollLp);

        addSettingsRow(rows, "♡", "Liked tracks", favoriteTracks.size() + " saved on this device", () -> {
            dialog.dismiss();
            source = "liked";
            loadCurrentSource("");
        });
        addSettingsRow(rows, "▤", "Local audio & FLAC", localTracks.size() + " local files · original file quality", () -> {
            dialog.dismiss();
            source = "local";
            loadLocalLibrary();
        });
        addSettingsRow(rows, "♫", "Imported playlist", importedPlaylistName + " · " + playlistTracks.size() + " tracks", () -> {
            dialog.dismiss();
            source = "playlist";
            loadCurrentSource("");
        });
        addSettingsRow(rows, "♫", "Your playlists", userPlaylists.size() + " saved mixes · add any playable result", () -> {
            dialog.dismiss();
            showUserPlaylistsDialog();
        });
        addSettingsRow(rows, "＋", "Create playlist", "Build a named mix from tracks in Discover or your library", () -> {
            dialog.dismiss();
            createUserPlaylist(null);
        });
        addSettingsRow(rows, "＋", "Import playlist", "CSV, M3U or exported track lists", () -> {
            dialog.dismiss();
            showPlaylistImportDialog();
        });
        addSettingsRow(rows, "◌", "Internet radio", "Stations are separate from song search", () -> {
            dialog.dismiss();
            source = "radio";
            loadRadio(searchField == null ? "" : searchField.getText().toString().trim());
        });
        addSettingsRow(rows, "⇣", "Soulseek / FLAC folder", "Open Seeker or import files you have rights to", () -> {
            dialog.dismiss();
            loadSoulseekSource();
        });
        addSettingsRow(rows, "↗", "SoundCloud", "Open the official app/site; in-app stream access is not configured", () -> {
            dialog.dismiss();
            openSoundCloudSearch(searchField == null ? "" : searchField.getText().toString().trim());
        });

        TextView foot = text("Your saved tracks stay on this phone. Public catalogues are discovered separately.", 10, MUTED, false);
        foot.setGravity(Gravity.CENTER);
        foot.setPadding(dp(4), dp(10), dp(4), dp(4));
        root.addView(foot);
        dialog.setContentView(root);
        dialog.setCancelable(true);
        dialog.show();
        Window window = dialog.getWindow();
        if (window != null) {
            window.setBackgroundDrawable(new ColorDrawable(BG));
            window.setLayout(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT);
            window.setStatusBarColor(BG);
            window.setNavigationBarColor(BG);
            window.addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS);
        }
    }

    private void showUserPlaylistsDialog() {
        if (userPlaylists.isEmpty()) {
            new AlertDialog.Builder(this).setTitle("Your playlists")
                    .setMessage("Create your first playlist, then add tracks from the ⋮ menu or the player.")
                    .setPositiveButton("Create playlist", (d, w) -> createUserPlaylist(null))
                    .setNegativeButton("Close", null).show();
            return;
        }
        String[] names = new String[userPlaylists.size() + 1];
        for (int i = 0; i < userPlaylists.size(); i++) {
            UserPlaylist p = userPlaylists.get(i);
            names[i] = p.name + "  ·  " + p.tracks.size() + " tracks";
        }
        names[names.length - 1] = "＋  Create a new playlist";
        new AlertDialog.Builder(this).setTitle("Your playlists").setItems(names, (d, which) -> {
            if (which >= userPlaylists.size()) { createUserPlaylist(null); return; }
            activeUserPlaylist = userPlaylists.get(which).name;
            source = "user_playlist";
            loadCurrentSource("");
        }).setNegativeButton("Close", null).show();
    }

    private UserPlaylist findUserPlaylist(String name) {
        if (name == null) return null;
        for (UserPlaylist playlist : userPlaylists) if (playlist.name.equals(name)) return playlist;
        return null;
    }

    private void createUserPlaylist(Track initialTrack) {
        EditText nameInput = new EditText(this);
        nameInput.setSingleLine(true);
        nameInput.setTextColor(TEXT);
        nameInput.setHintTextColor(MUTED);
        nameInput.setHint("e.g. Late-night shoegaze");
        nameInput.setText("My playlist");
        nameInput.setPadding(dp(14), dp(10), dp(14), dp(10));
        nameInput.setBackground(roundRect(PANEL_ALT, 14));
        LinearLayout inputWrap = new LinearLayout(this);
        inputWrap.setPadding(dp(20), dp(4), dp(20), dp(4));
        inputWrap.addView(nameInput, new LinearLayout.LayoutParams(-1, dp(50)));
        new AlertDialog.Builder(this).setTitle("Name your playlist").setView(inputWrap)
                .setPositiveButton("Create", (d, w) -> {
                    String name = nameInput.getText().toString().trim();
                    if (name.isEmpty()) name = "My playlist";
                    String base = name;
                    int suffix = 2;
                    while (findUserPlaylist(name) != null) name = base + " (" + suffix++ + ")";
                    UserPlaylist created = new UserPlaylist(name);
                    if (initialTrack != null) created.tracks.add(initialTrack);
                    userPlaylists.add(created);
                    activeUserPlaylist = created.name;
                    persistUserPlaylists();
                    source = "user_playlist";
                    loadCurrentSource("");
                    Toast.makeText(this, initialTrack == null ? "Playlist created" : "Playlist created and track added", Toast.LENGTH_SHORT).show();
                }).setNegativeButton("Cancel", null).show();
    }

    private void choosePlaylistForTrack(Track track) {
        if (track == null) return;
        if (userPlaylists.isEmpty()) { createUserPlaylist(track); return; }
        String[] names = new String[userPlaylists.size() + 1];
        for (int i = 0; i < userPlaylists.size(); i++) {
            UserPlaylist p = userPlaylists.get(i);
            names[i] = p.name + "  ·  " + p.tracks.size() + " tracks";
        }
        names[names.length - 1] = "＋  New playlist…";
        new AlertDialog.Builder(this).setTitle("Add to playlist").setItems(names, (d, which) -> {
            if (which >= userPlaylists.size()) createUserPlaylist(track);
            else addTrackToUserPlaylist(userPlaylists.get(which), track);
        }).setNegativeButton("Cancel", null).show();
    }

    private void addTrackToUserPlaylist(UserPlaylist playlist, Track track) {
        if (playlist == null || track == null) return;
        playlist.tracks.add(track);
        persistUserPlaylists();
        Toast.makeText(this, "Added to " + playlist.name, Toast.LENGTH_SHORT).show();
        if ("user_playlist".equals(source) && playlist.name.equals(activeUserPlaylist))
            showTracks(new ArrayList<>(playlist.tracks), playlist.tracks.size() + " tracks • saved on this device");
    }

    private void persistUserPlaylists() {
        JSONArray root = new JSONArray();
        for (UserPlaylist playlist : userPlaylists) {
            JSONObject item = new JSONObject();
            try {
                item.put("name", playlist.name);
                item.put("tracks", new JSONArray(serializeTrackList(playlist.tracks)));
                root.put(item);
            } catch (Exception ignored) {}
        }
        getSharedPreferences(PREFS, MODE_PRIVATE).edit().putString("user_playlists_json", root.toString()).apply();
    }

    private List<UserPlaylist> readUserPlaylists(String raw) {
        List<UserPlaylist> result = new ArrayList<>();
        try {
            JSONArray root = new JSONArray(raw);
            for (int i = 0; i < root.length() && result.size() < 100; i++) {
                JSONObject item = root.optJSONObject(i);
                if (item == null) continue;
                String name = item.optString("name", "").trim();
                if (name.isEmpty()) continue;
                UserPlaylist playlist = new UserPlaylist(name);
                JSONArray tracks = item.optJSONArray("tracks");
                if (tracks != null) playlist.tracks.addAll(readFavoriteTracks(tracks.toString()));
                result.add(playlist);
            }
        } catch (Exception ignored) {}
        return result;
    }

    private void addSettingsRow(LinearLayout parent, String symbol, String titleText, String detailText, Runnable action) {
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setPadding(dp(13), dp(13), dp(12), dp(13));
        row.setBackground(panelCardDrawable());
        TextView icon = text(symbol, 20, ACCENT, true);
        icon.setGravity(Gravity.CENTER);
        row.addView(icon, new LinearLayout.LayoutParams(dp(34), dp(40)));
        LinearLayout copy = new LinearLayout(this);
        copy.setOrientation(LinearLayout.VERTICAL);
        TextView title = text(titleText, 13, TEXT, true);
        TextView detail = text(detailText, 10, MUTED, false);
        detail.setMaxLines(2);
        copy.addView(title);
        LinearLayout.LayoutParams detailLp = new LinearLayout.LayoutParams(-1, -2);
        detailLp.topMargin = dp(4);
        copy.addView(detail, detailLp);
        LinearLayout.LayoutParams copyLp = new LinearLayout.LayoutParams(0, -2, 1);
        copyLp.leftMargin = dp(10);
        row.addView(copy, copyLp);
        TextView arrow = text("›", 25, MUTED, false);
        row.addView(arrow);
        row.setOnClickListener(v -> action.run());
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.bottomMargin = dp(8);
        parent.addView(row, lp);
    }

    private Drawable createThemeBackdrop() {
        return new Drawable() {
            private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);

            @Override public void draw(Canvas canvas) {
                Rect bounds = getBounds();
                float w = bounds.width(), h = bounds.height();
                if (w <= 0f || h <= 0f) return;
                int top = BG, bottom = BG;
                switch (activeTheme) {
                    case "Frutiger Aero": top = Color.rgb(119, 215, 244); bottom = Color.rgb(232, 252, 255); break;
                    case "Y2K Glass": top = Color.rgb(33, 87, 154); bottom = Color.rgb(120, 175, 220); break;
                    case "Synthwave": top = Color.rgb(41, 12, 70); bottom = Color.rgb(16, 8, 31); break;
                    case "Vaporwave": top = Color.rgb(73, 59, 133); bottom = Color.rgb(25, 30, 62); break;
                    case "Sunset Coast": top = Color.rgb(150, 70, 100); bottom = Color.rgb(45, 24, 52); break;
                    case "Candy Pop": top = Color.rgb(105, 34, 94); bottom = Color.rgb(36, 17, 41); break;
                    case "Aurora Forest": top = Color.rgb(17, 78, 65); bottom = Color.rgb(4, 22, 23); break;
                    case "Cyberpunk": top = Color.rgb(19, 36, 38); bottom = Color.rgb(4, 10, 14); break;
                    case "Midwest Emo": top = Color.rgb(39, 30, 46); bottom = Color.rgb(18, 17, 24); break;
                    case "Retro CRT": top = Color.rgb(10, 42, 28); bottom = Color.rgb(3, 15, 10); break;
                    case "Dark Academia": top = Color.rgb(53, 45, 35); bottom = Color.rgb(24, 22, 19); break;
                    default: top = BG; bottom = Color.BLACK; break;
                }
                paint.setStyle(Paint.Style.FILL);
                paint.setShader(new LinearGradient(0, 0, w, h, top, bottom, Shader.TileMode.CLAMP));
                canvas.drawRect(bounds, paint);
                paint.setShader(null);

                if ("Frutiger Aero".equals(activeTheme) || "Y2K Glass".equals(activeTheme)) {
                    drawBubble(canvas, w * 0.82f, h * 0.15f, w * 0.16f);
                    drawBubble(canvas, w * 0.11f, h * 0.42f, w * 0.09f);
                    drawBubble(canvas, w * 0.74f, h * 0.74f, w * 0.12f);
                    paint.setStyle(Paint.Style.STROKE);
                    paint.setStrokeWidth(Math.max(1f, dp(1)));
                    paint.setColor(Color.argb(80, 255, 255, 255));
                    canvas.drawLine(0, h * 0.27f, w * 0.68f, 0, paint);
                    canvas.drawLine(w * 0.34f, h, w, h * 0.54f, paint);
                } else if ("Synthwave".equals(activeTheme) || "Vaporwave".equals(activeTheme)) {
                    float horizon = h * 0.65f;
                    paint.setStyle(Paint.Style.STROKE);
                    paint.setStrokeWidth(Math.max(1f, dp(1)));
                    paint.setColor(Color.argb(42, Color.red(ACCENT), Color.green(ACCENT), Color.blue(ACCENT)));
                    for (int i = 0; i < 9; i++) {
                        float t = i / 8f;
                        float y = horizon + (h - horizon) * t * t;
                        canvas.drawLine(0, y, w, y, paint);
                    }
                    for (int i = -5; i <= 5; i++) canvas.drawLine(w / 2f, horizon, w / 2f + i * w * 0.24f, h, paint);
                } else if ("Retro CRT".equals(activeTheme)) {
                    paint.setStyle(Paint.Style.STROKE);
                    paint.setStrokeWidth(1f);
                    paint.setColor(Color.argb(23, 127, 255, 91));
                    for (float y = 0; y < h; y += dp(4)) canvas.drawLine(0, y, w, y, paint);
                } else if ("Aurora Forest".equals(activeTheme)) {
                    paint.setStyle(Paint.Style.FILL);
                    paint.setShader(new RadialGradient(w * 0.75f, h * 0.23f, w * 0.55f,
                            new int[]{Color.argb(60, 64, 255, 177), Color.TRANSPARENT},
                            null, Shader.TileMode.CLAMP));
                    canvas.drawRect(bounds, paint);
                    paint.setShader(null);
                } else if ("Sunset Coast".equals(activeTheme)) {
                    paint.setStyle(Paint.Style.FILL);
                    paint.setShader(new RadialGradient(w * 0.82f, h * 0.15f, w * 0.23f,
                            new int[]{Color.argb(90, 255, 201, 139), Color.argb(0, 255, 201, 139)},
                            null, Shader.TileMode.CLAMP));
                    canvas.drawCircle(w * 0.82f, h * 0.15f, w * 0.23f, paint);
                    paint.setShader(null);
                } else if ("Dark Academia".equals(activeTheme)) {
                    paint.setStyle(Paint.Style.STROKE);
                    paint.setStrokeWidth(1f);
                    paint.setColor(Color.argb(20, 214, 175, 91));
                    for (float y = dp(60); y < h; y += dp(34)) canvas.drawLine(0, y, w, y, paint);
                } else if ("Candy Pop".equals(activeTheme)) {
                    paint.setStyle(Paint.Style.STROKE);
                    paint.setStrokeWidth(dp(1));
                    paint.setColor(Color.argb(24, 255, 172, 211));
                    for (int i = 0; i < 6; i++) canvas.drawCircle((i % 3) * w / 2f + w * 0.12f,
                            h * (0.12f + (i / 3) * 0.72f), w * 0.12f, paint);
                }
                paint.setStyle(Paint.Style.FILL);
                paint.setShader(null);
            }

            private void drawBubble(Canvas canvas, float cx, float cy, float radius) {
                paint.setStyle(Paint.Style.FILL);
                paint.setShader(new RadialGradient(cx - radius * 0.25f, cy - radius * 0.3f, radius,
                        new int[]{Color.argb(110, 255, 255, 255), Color.argb(24, 255, 255, 255),
                                Color.argb(2, 255, 255, 255)}, new float[]{0f, 0.6f, 1f}, Shader.TileMode.CLAMP));
                canvas.drawCircle(cx, cy, radius, paint);
                paint.setShader(null);
                paint.setStyle(Paint.Style.STROKE);
                paint.setStrokeWidth(Math.max(1f, dp(1)));
                paint.setColor(Color.argb(110, 255, 255, 255));
                canvas.drawCircle(cx, cy, radius * 0.94f, paint);
                paint.setStyle(Paint.Style.FILL);
            }

            @Override public void setAlpha(int alpha) {}
            @Override public void setColorFilter(ColorFilter colorFilter) {}
            @Override public int getOpacity() { return PixelFormat.OPAQUE; }
        };
    }

    private int themeAccentFor(String theme) {
        switch (theme) {
            case "Midwest Emo": return Color.rgb(237, 164, 202);
            case "Synthwave": return Color.rgb(255, 82, 211);
            case "Vaporwave": return Color.rgb(95, 239, 236);
            case "Cyberpunk": return Color.rgb(236, 255, 0);
            case "Aurora Forest": return Color.rgb(120, 255, 180);
            case "AMOLED": return Color.rgb(133, 235, 158);
            case "Y2K Glass": return Color.rgb(255, 224, 145);
            case "Candy Pop": return Color.rgb(255, 172, 211);
            case "Retro CRT": return Color.rgb(127, 255, 91);
            case "Sunset Coast": return Color.rgb(255, 198, 119);
            case "Dark Academia": return Color.rgb(214, 175, 91);
            default: return Color.rgb(0, 133, 194);
        }
    }

    private String themeSymbolFor(String theme) {
        switch (theme) {
            case "Frutiger Aero": return "◉";
            case "Midwest Emo": return "✳";
            case "Synthwave": return "▥";
            case "Vaporwave": return "▦";
            case "Cyberpunk": return "⌁";
            case "Aurora Forest": return "✦";
            case "AMOLED": return "◐";
            case "Y2K Glass": return "◇";
            case "Candy Pop": return "✿";
            case "Retro CRT": return "▧";
            case "Sunset Coast": return "☼";
            case "Dark Academia": return "✒";
            default: return "♪";
        }
    }

    private int[] themePreviewColors(String theme) {
        switch (theme) {
            case "Frutiger Aero": return new int[]{Color.rgb(20, 152, 209), Color.rgb(111, 226, 234), Color.rgb(244, 255, 255)};
            case "Midwest Emo": return new int[]{Color.rgb(103, 61, 94), Color.rgb(45, 37, 52), Color.rgb(24, 22, 30)};
            case "Synthwave": return new int[]{Color.rgb(255, 69, 204), Color.rgb(90, 37, 136), Color.rgb(23, 11, 47)};
            case "Vaporwave": return new int[]{Color.rgb(104, 91, 194), Color.rgb(75, 63, 142), Color.rgb(52, 51, 91)};
            case "Cyberpunk": return new int[]{Color.rgb(235, 255, 0), Color.rgb(36, 55, 58), Color.rgb(6, 15, 19)};
            case "Aurora Forest": return new int[]{Color.rgb(91, 214, 160), Color.rgb(22, 88, 69), Color.rgb(6, 27, 27)};
            case "AMOLED": return new int[]{Color.rgb(133, 235, 158), Color.rgb(20, 20, 25), Color.BLACK};
            case "Y2K Glass": return new int[]{Color.rgb(244, 255, 255), Color.rgb(127, 184, 226), Color.rgb(40, 57, 112)};
            case "Candy Pop": return new int[]{Color.rgb(255, 173, 213), Color.rgb(174, 64, 148), Color.rgb(50, 22, 52)};
            case "Retro CRT": return new int[]{Color.rgb(127, 255, 91), Color.rgb(19, 73, 43), Color.rgb(4, 18, 13)};
            case "Sunset Coast": return new int[]{Color.rgb(255, 208, 152), Color.rgb(191, 89, 109), Color.rgb(62, 29, 62)};
            case "Dark Academia": return new int[]{Color.rgb(214, 175, 91), Color.rgb(86, 68, 49), Color.rgb(27, 24, 20)};
            default: return new int[]{Color.rgb(0, 133, 194), Color.rgb(155, 220, 237), Color.rgb(204, 241, 249)};
        }
    }

    private void showPlayerDialog(Track track) {
        if (track == null) {
            Toast.makeText(this, "Choose a track first.", Toast.LENGTH_SHORT).show();
            return;
        }
        if (playerDialog != null && playerDialog.isShowing()) playerDialog.dismiss();
        activePlayerSelection = track;
        Dialog dialog = new Dialog(this);
        playerDialog = dialog;

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(22), dp(12), dp(22), dp(18));
        root.setBackground(createThemeBackdrop());

        LinearLayout top = new LinearLayout(this);
        top.setGravity(Gravity.CENTER_VERTICAL);
        TextView close = text("⌄", 30, TEXT, true);
        close.setGravity(Gravity.CENTER);
        close.setBackground(roundRect(PANEL, 50));
        top.addView(close, new LinearLayout.LayoutParams(dp(44), dp(44)));
        close.setOnClickListener(v -> dialog.dismiss());
        LinearLayout topTitle = new LinearLayout(this);
        topTitle.setOrientation(LinearLayout.VERTICAL);
        TextView overline = text("NOW PLAYING", 10, ACCENT, true);
        overline.setLetterSpacing(0.13f);
        TextView themeLine = text(activeTheme.toUpperCase(Locale.ROOT), 8, MUTED, false);
        themeLine.setLetterSpacing(0.09f);
        topTitle.addView(overline);
        topTitle.addView(themeLine);
        LinearLayout.LayoutParams topTitleLp = new LinearLayout.LayoutParams(0, -2, 1);
        topTitleLp.leftMargin = dp(12);
        top.addView(topTitle, topTitleLp);
        TextView queue = text("≡", 23, TEXT, true);
        queue.setGravity(Gravity.CENTER);
        queue.setBackground(roundRect(PANEL, 50));
        queue.setContentDescription("Queue");
        top.addView(queue, new LinearLayout.LayoutParams(dp(44), dp(44)));
        queue.setOnClickListener(v -> showQueueDialog());
        root.addView(top, new LinearLayout.LayoutParams(-1, -2));

        ScrollView scroller = new ScrollView(this);
        scroller.setFillViewport(false);
        scroller.setVerticalScrollBarEnabled(false);
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setGravity(Gravity.CENTER_HORIZONTAL);

        int artSize = Math.max(dp(190), Math.min(dp(300), getResources().getDisplayMetrics().widthPixels - dp(76)));
        FrameLayout art = createCoverFrame(track, artSize);
        LinearLayout.LayoutParams artLp = new LinearLayout.LayoutParams(artSize, artSize);
        artLp.topMargin = dp(16);
        artLp.bottomMargin = dp(22);
        content.addView(art, artLp);

        playerTitleView = text(track.title, 23, TEXT, true);
        playerTitleView.setGravity(Gravity.CENTER);
        playerTitleView.setMaxLines(2);
        playerArtistView = text(track.artist, 14, MUTED, false);
        playerArtistView.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams playerArtistLp = new LinearLayout.LayoutParams(-1, -2);
        playerArtistLp.topMargin = dp(6);
        content.addView(playerTitleView, new LinearLayout.LayoutParams(-1, -2));
        content.addView(playerArtistView, playerArtistLp);

        LinearLayout favoriteRow = new LinearLayout(this);
        favoriteRow.setGravity(Gravity.CENTER);
        TextView fav = text(isFavorite(track) ? "♥  SAVED TO YOUR LIBRARY" : "♡  ADD TO YOUR LIBRARY", 10,
                isFavorite(track) ? ACCENT : MUTED, true);
        fav.setLetterSpacing(0.04f);
        fav.setPadding(dp(10), dp(10), dp(10), dp(5));
        favoriteRow.addView(fav);
        fav.setOnClickListener(v -> {
            boolean liked = toggleFavorite(activePlayerSelection == null ? track : activePlayerSelection);
            fav.setText(liked ? "♥  SAVED TO YOUR LIBRARY" : "♡  ADD TO YOUR LIBRARY");
            fav.setTextColor(liked ? ACCENT : MUTED);
        });
        LinearLayout.LayoutParams favLp = new LinearLayout.LayoutParams(-1, -2);
        favLp.topMargin = dp(2);
        content.addView(favoriteRow, favLp);

        playerSeekBar = new SeekBar(this);
        // Seed the real timeline as soon as the player opens. Do not rely exclusively
        // on the periodic UI ticker: smoke tests intentionally pause it to let
        // UIAutomator settle, and the seek control must still target real milliseconds.
        int initialSeekDurationMs = Math.max(1, MusicPlaybackService.currentDuration());
        playerSeekBar.setMax(initialSeekDurationMs);
        playerSeekBar.setProgress(Math.min(Math.max(0, MusicPlaybackService.currentPosition()), initialSeekDurationMs));
        LinearLayout.LayoutParams seekLp = new LinearLayout.LayoutParams(-1, dp(32));
        seekLp.topMargin = dp(12);
        content.addView(playerSeekBar, seekLp);
        playerSeekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar bar, int progress, boolean fromUser) {}
            @Override public void onStartTrackingTouch(SeekBar bar) { playerSeeking = true; }
            @Override public void onStopTrackingTouch(SeekBar bar) {
                playerSeeking = false;
                MusicPlaybackService.seekToPosition(bar.getProgress());
            }
        });

        LinearLayout timeRow = new LinearLayout(this);
        timeRow.setGravity(Gravity.CENTER_VERTICAL);
        playerElapsedView = text("0:00", 10, MUTED, false);
        playerDurationView = text("—:—", 10, MUTED, false);
        timeRow.addView(playerElapsedView, new LinearLayout.LayoutParams(0, -2, 1));
        playerDurationView.setGravity(Gravity.END);
        timeRow.addView(playerDurationView, new LinearLayout.LayoutParams(0, -2, 1));
        content.addView(timeRow, new LinearLayout.LayoutParams(-1, -2));

        LinearLayout controls = new LinearLayout(this);
        controls.setGravity(Gravity.CENTER);
        controls.setPadding(0, dp(12), 0, dp(10));
        TextView previous = text("⏮", 21, TEXT, true);
        previous.setGravity(Gravity.CENTER);
        controls.addView(previous, new LinearLayout.LayoutParams(dp(56), dp(56)));
        previous.setOnClickListener(v -> playPreviousTrack());
        playerToggleView = text(needsExternalPlayback(track) ? "↗" : "▶", 27, BG, true);
        playerToggleView.setGravity(Gravity.CENTER);
        playerToggleView.setBackground(roundRect(ACCENT, 50));
        LinearLayout.LayoutParams toggleLp = new LinearLayout.LayoutParams(dp(72), dp(72));
        toggleLp.leftMargin = dp(20);
        toggleLp.rightMargin = dp(20);
        controls.addView(playerToggleView, toggleLp);
        playerToggleView.setEnabled(!checkingArchiveLicense);
        playerToggleView.setOnClickListener(v -> {
            Track selected = activePlayerSelection;
            if (selected == null) return;
            if ("youtube".equals(selected.provider)) {
                openYoutubeUrl(selected.playUrl, selected.title);
            } else if ("playlist-entry".equals(selected.provider)) {
                openYoutubeMusicSearch(selected.title + " " + selected.artist);
            } else if (checkingArchiveLicense) {
                statusText.setText("Still verifying the open-audio licence…");
            } else if (selectionIsCurrent(selected) && MusicPlaybackService.hasTrack()) {
                togglePlayback();
            } else {
                playTrack(selected);
            }
        });
        TextView next = text("⏭", 21, TEXT, true);
        next.setGravity(Gravity.CENTER);
        controls.addView(next, new LinearLayout.LayoutParams(dp(56), dp(56)));
        next.setOnClickListener(v -> playNextTrack());
        content.addView(controls, new LinearLayout.LayoutParams(-1, -2));

        LinearLayout modeRow = new LinearLayout(this);
        modeRow.setOrientation(LinearLayout.HORIZONTAL);
        modeRow.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams modeRowLp = new LinearLayout.LayoutParams(-1, -2);
        modeRowLp.topMargin = dp(3);
        modeRowLp.bottomMargin = dp(8);
        playerShuffleButton = modeButton("SHUFFLE", "shuffle".equals(shuffleMode));
        playerSmartShuffleButton = modeButton("SMART SHUFFLE", "smart".equals(shuffleMode));
        playerRepeatButton = modeButton(repeatLabel(), !"off".equals(repeatMode));
        for (TextView control : new TextView[]{playerShuffleButton, playerSmartShuffleButton, playerRepeatButton}) {
            LinearLayout.LayoutParams modeLp = new LinearLayout.LayoutParams(0, dp(42), 1);
            modeLp.leftMargin = dp(3);
            modeLp.rightMargin = dp(3);
            modeRow.addView(control, modeLp);
        }
        playerShuffleButton.setOnClickListener(v -> activateShuffleMode("shuffle"));
        playerSmartShuffleButton.setOnClickListener(v -> activateShuffleMode("smart"));
        playerRepeatButton.setOnClickListener(v -> cycleRepeatMode());
        content.addView(modeRow, modeRowLp);
        updatePlaybackModeButtons();

        LinearLayout playlistActions = new LinearLayout(this);
        playlistActions.setOrientation(LinearLayout.HORIZONTAL);
        TextView playNextAction = text("＋  PLAY NEXT", 10, TEXT, true);
        TextView savePlaylistAction = text("♫  ADD TO PLAYLIST", 10, TEXT, true);
        for (TextView action : new TextView[]{playNextAction, savePlaylistAction}) {
            action.setGravity(Gravity.CENTER);
            action.setPadding(dp(5), dp(12), dp(5), dp(12));
            action.setBackground(roundRect(PANEL_ALT, 14));
        }
        LinearLayout.LayoutParams nextActionLp = new LinearLayout.LayoutParams(0, -2, 1);
        nextActionLp.rightMargin = dp(4);
        LinearLayout.LayoutParams saveActionLp = new LinearLayout.LayoutParams(0, -2, 1);
        saveActionLp.leftMargin = dp(4);
        playlistActions.addView(playNextAction, nextActionLp);
        playlistActions.addView(savePlaylistAction, saveActionLp);
        playNextAction.setOnClickListener(v -> addTrackToQueue(activePlayerSelection == null ? track : activePlayerSelection, true));
        savePlaylistAction.setOnClickListener(v -> choosePlaylistForTrack(activePlayerSelection == null ? track : activePlayerSelection));
        LinearLayout.LayoutParams playlistActionsLp = new LinearLayout.LayoutParams(-1, -2);
        playlistActionsLp.bottomMargin = dp(10);
        content.addView(playlistActions, playlistActionsLp);

        if (needsExternalPlayback(track)) {
            TextView note = text("This catalogue entry does not provide a stream Spotty can play. Nothing leaves the app until you choose the destination below.", 11, MUTED, false);
            note.setGravity(Gravity.CENTER);
            note.setLineSpacing(dp(2), 1.05f);
            LinearLayout.LayoutParams noteLp = new LinearLayout.LayoutParams(-1, -2);
            noteLp.topMargin = dp(2);
            content.addView(note, noteLp);
            TextView open = text("Open in " + ("youtube".equals(track.provider) ? "YouTube" : "YouTube Music") + "  ↗", 12, ACCENT, true);
            open.setGravity(Gravity.CENTER);
            open.setPadding(dp(12), dp(14), dp(12), dp(14));
            open.setBackground(roundRect(PANEL_ALT, 15));
            LinearLayout.LayoutParams openLp = new LinearLayout.LayoutParams(-1, -2);
            openLp.topMargin = dp(12);
            content.addView(open, openLp);
            open.setOnClickListener(v -> {
                if ("youtube".equals(track.provider)) openYoutubeUrl(track.playUrl, track.title);
                else openYoutubeMusicSearch(track.title + " " + track.artist);
            });
            playerSeekBar.setVisibility(View.GONE);
            timeRow.setVisibility(View.GONE);
            controls.getChildAt(0).setVisibility(View.INVISIBLE);
            next.setVisibility(View.INVISIBLE);
        } else if ("deezer-preview".equals(track.provider)) {
            TextView open = text("Find the full track on Deezer  ↗", 11, MUTED, true);
            open.setGravity(Gravity.CENTER);
            open.setPadding(dp(8), dp(10), dp(8), dp(10));
            content.addView(open, new LinearLayout.LayoutParams(-1, -2));
            open.setOnClickListener(v -> openDeezerTrack(track));
        }

        playerSourceView = text("SOURCE  ·  " + displayProvider(track), 9, MUTED, true);
        playerSourceView.setGravity(Gravity.CENTER);
        playerSourceView.setLetterSpacing(0.1f);
        LinearLayout.LayoutParams sourceLp = new LinearLayout.LayoutParams(-1, -2);
        sourceLp.topMargin = dp(19);
        content.addView(playerSourceView, sourceLp);
        scroller.addView(content);
        LinearLayout.LayoutParams scrollerLp = new LinearLayout.LayoutParams(-1, 0, 1);
        scrollerLp.topMargin = dp(2);
        root.addView(scroller, scrollerLp);

        dialog.setContentView(root);
        dialog.setCancelable(true);
        dialog.setOnDismissListener(ignored -> {
            main.removeCallbacks(playerProgressTicker);
            playerSeekBar = null;
            playerElapsedView = null;
            playerDurationView = null;
            playerToggleView = null;
            playerShuffleButton = null;
            playerSmartShuffleButton = null;
            playerRepeatButton = null;
            playerTitleView = null;
            playerArtistView = null;
            playerSourceView = null;
            playerArtwork = null;
            if (playerDialog == dialog) playerDialog = null;
        });
        dialog.show();
        Window window = dialog.getWindow();
        if (window != null) {
            window.setBackgroundDrawable(new ColorDrawable(BG));
            window.setLayout(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT);
            window.setStatusBarColor(BG);
            window.setNavigationBarColor(BG);
            window.addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS);
        }
        // UIAutomator's wait-for-idle check cannot settle while a debug smoke test
        // continuously mutates SeekBar progress. Keep production refresh unchanged,
        // but leave the test player sheet static long enough to inspect its controls.
        boolean playbackSmokeTest = (getApplicationInfo().flags & ApplicationInfo.FLAG_DEBUGGABLE) != 0
                && getIntent().getBooleanExtra("spotty_ci_smoke_playback", false);
        if (!playbackSmokeTest) main.post(playerProgressTicker);
    }

    private void refreshPlayerSelection(Track track) {
        if (playerTitleView != null) playerTitleView.setText(track.title);
        if (playerArtistView != null) playerArtistView.setText(track.artist);
        if (playerSourceView != null) playerSourceView.setText("SOURCE  ·  " + displayProvider(track));
        if (playerToggleView != null) {
            playerToggleView.setEnabled(true);
            playerToggleView.setText(MusicPlaybackService.isPlaying() ? "Ⅱ" : "▶");
        }
    }

    private void showQueueDialog() {
        if (playbackQueue.isEmpty() && currentTrack != null) {
            playbackQueue.add(currentTrack);
            playbackQueueIndex = MusicPlaybackService.hasTrack() ? 0 : -1;
            persistUiQueue();
        }
        if (playbackQueue.isEmpty() && visibleTracks.isEmpty() && currentTrack == null) {
            Toast.makeText(this, "Find a song or import local audio, then add it to your queue.", Toast.LENGTH_SHORT).show();
            return;
        }
        Dialog dialog = new Dialog(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(18), dp(14), dp(18), dp(18));
        root.setBackground(createThemeBackdrop());
        LinearLayout heading = new LinearLayout(this);
        heading.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout titleStack = new LinearLayout(this);
        titleStack.setOrientation(LinearLayout.VERTICAL);
        titleStack.addView(text("YOUR LISTENING ROOM", 9, ACCENT, true));
        TextView title = text("Playing queue", 27, TEXT, true);
        LinearLayout.LayoutParams titleLp = new LinearLayout.LayoutParams(-1, -2);
        titleLp.topMargin = dp(4);
        titleStack.addView(title, titleLp);
        heading.addView(titleStack, new LinearLayout.LayoutParams(0, -2, 1));
        TextView close = text("×", 27, TEXT, true);
        close.setGravity(Gravity.CENTER);
        close.setContentDescription("Close queue");
        close.setBackground(roundRect(PANEL, 50));
        heading.addView(close, new LinearLayout.LayoutParams(dp(44), dp(44)));
        close.setOnClickListener(v -> dialog.dismiss());
        root.addView(heading);
        TextView explanation = text("Move songs, play any position, or clear only the upcoming part of your mix.", 12, MUTED, false);
        explanation.setLineSpacing(dp(2), 1.04f);
        LinearLayout.LayoutParams explanationLp = new LinearLayout.LayoutParams(-1, -2);
        explanationLp.topMargin = dp(6);
        root.addView(explanation, explanationLp);

        LinearLayout tools = new LinearLayout(this);
        tools.setGravity(Gravity.CENTER_VERTICAL);
        TextView mixMode = text("SHUFFLE: " + shuffleMode.toUpperCase(Locale.ROOT) + "  ·  REPEAT: " + repeatMode.toUpperCase(Locale.ROOT), 9, ACCENT, true);
        mixMode.setMaxLines(2);
        tools.addView(mixMode, new LinearLayout.LayoutParams(0, -2, 1));
        TextView clearUpcoming = text("CLEAR UPCOMING", 9, TEXT, true);
        clearUpcoming.setGravity(Gravity.CENTER);
        clearUpcoming.setPadding(dp(8), dp(10), dp(8), dp(10));
        clearUpcoming.setBackground(roundRect(PANEL_ALT, 12));
        tools.addView(clearUpcoming);
        Runnable[] refresh = new Runnable[1];
        clearUpcoming.setOnClickListener(v -> {
            int keepThrough = MusicPlaybackService.hasTrack() ? Math.max(-1, playbackQueueIndex) : -1;
            for (int i = playbackQueue.size() - 1; i > keepThrough; i--) playbackQueue.remove(i);
            if (keepThrough < 0) playbackQueueIndex = -1;
            persistUiQueue();
            syncQueueToService();
            refresh[0].run();
        });
        LinearLayout.LayoutParams toolsLp = new LinearLayout.LayoutParams(-1, -2);
        toolsLp.topMargin = dp(14);
        root.addView(tools, toolsLp);

        ScrollView scroller = new ScrollView(this);
        scroller.setFillViewport(false);
        scroller.setVerticalScrollBarEnabled(false);
        LinearLayout queueRows = new LinearLayout(this);
        queueRows.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams scrollerLp = new LinearLayout.LayoutParams(-1, 0, 1);
        scrollerLp.topMargin = dp(10);
        root.addView(scroller, scrollerLp);
        scroller.addView(queueRows);

        refresh[0] = () -> {
            queueRows.removeAllViews();
            if (playbackQueue.isEmpty()) {
                TextView empty = text("Your queue is empty. Add songs from the ⋮ menu on any result.", 14, MUTED, false);
                empty.setPadding(dp(12), dp(24), dp(12), dp(24));
                queueRows.addView(empty);
                return;
            }
            for (int i = 0; i < playbackQueue.size(); i++) {
                final int itemIndex = i;
                Track item = playbackQueue.get(i);
                boolean isCurrent = MusicPlaybackService.hasTrack() && i == playbackQueueIndex;
                LinearLayout card = new LinearLayout(this);
                card.setGravity(Gravity.CENTER_VERTICAL);
                card.setPadding(dp(8), dp(8), dp(6), dp(8));
                card.setBackground(roundRect(isCurrent ? PANEL_ALT : PANEL, 15));
                LinearLayout.LayoutParams cardLp = new LinearLayout.LayoutParams(-1, -2);
                cardLp.bottomMargin = dp(7);
                queueRows.addView(card, cardLp);
                TextView number = text(isCurrent ? "▶" : String.format(Locale.ROOT, "%02d", i + 1), 11,
                        isCurrent ? ACCENT : MUTED, true);
                number.setGravity(Gravity.CENTER);
                card.addView(number, new LinearLayout.LayoutParams(dp(30), dp(38)));
                FrameLayout cover = createCoverFrame(item, dp(48));
                LinearLayout.LayoutParams coverLp = new LinearLayout.LayoutParams(dp(48), dp(48));
                coverLp.leftMargin = dp(3);
                coverLp.rightMargin = dp(9);
                card.addView(cover, coverLp);
                LinearLayout trackInfo = new LinearLayout(this);
                trackInfo.setOrientation(LinearLayout.VERTICAL);
                TextView trackName = text(item.title, 12, TEXT, true);
                trackName.setMaxLines(2);
                TextView trackArtist = text(item.artist, 10, MUTED, false);
                trackArtist.setMaxLines(1);
                trackInfo.addView(trackName);
                LinearLayout.LayoutParams artistLp = new LinearLayout.LayoutParams(-1, -2);
                artistLp.topMargin = dp(3);
                trackInfo.addView(trackArtist, artistLp);
                card.addView(trackInfo, new LinearLayout.LayoutParams(0, -2, 1));
                trackInfo.setOnClickListener(v -> {
                    playbackQueueIndex = itemIndex;
                    preserveQueueOnNextPlay = true;
                    playTrack(item);
                    dialog.dismiss();
                });
                TextView up = queueMiniButton("↑", "Move up");
                TextView down = queueMiniButton("↓", "Move down");
                TextView remove = queueMiniButton("×", "Remove from queue");
                card.addView(up);
                card.addView(down);
                card.addView(remove);
                up.setEnabled(itemIndex > 0);
                down.setEnabled(itemIndex < playbackQueue.size() - 1);
                remove.setEnabled(!(MusicPlaybackService.hasTrack() && isCurrent));
                up.setOnClickListener(v -> {
                    if (itemIndex <= 0) return;
                    Collections.swap(playbackQueue, itemIndex, itemIndex - 1);
                    if (playbackQueueIndex == itemIndex) playbackQueueIndex = itemIndex - 1;
                    else if (playbackQueueIndex == itemIndex - 1) playbackQueueIndex = itemIndex;
                    persistUiQueue(); syncQueueToService(); refresh[0].run();
                });
                down.setOnClickListener(v -> {
                    if (itemIndex >= playbackQueue.size() - 1) return;
                    Collections.swap(playbackQueue, itemIndex, itemIndex + 1);
                    if (playbackQueueIndex == itemIndex) playbackQueueIndex = itemIndex + 1;
                    else if (playbackQueueIndex == itemIndex + 1) playbackQueueIndex = itemIndex;
                    persistUiQueue(); syncQueueToService(); refresh[0].run();
                });
                remove.setOnClickListener(v -> {
                    if (MusicPlaybackService.hasTrack() && itemIndex == playbackQueueIndex) {
                        Toast.makeText(this, "The playing track stays in the queue until it finishes.", Toast.LENGTH_SHORT).show();
                        return;
                    }
                    playbackQueue.remove(itemIndex);
                    if (itemIndex < playbackQueueIndex) playbackQueueIndex--;
                    if (playbackQueue.isEmpty()) playbackQueueIndex = -1;
                    persistUiQueue(); syncQueueToService(); refresh[0].run();
                });
            }
        };

        TextView addVisible = text("＋  ADD CURRENT RESULTS TO QUEUE", 10, TEXT, true);
        addVisible.setGravity(Gravity.CENTER);
        addVisible.setPadding(dp(10), dp(14), dp(10), dp(14));
        addVisible.setBackground(roundRect(PANEL_ALT, 15));
        LinearLayout.LayoutParams addVisibleLp = new LinearLayout.LayoutParams(-1, -2);
        addVisibleLp.topMargin = dp(10);
        root.addView(addVisible, addVisibleLp);
        addVisible.setOnClickListener(v -> {
            for (Track track : new ArrayList<>(visibleTracks)) addTrackToQueue(track, false);
            refresh[0].run();
        });
        TextView footer = text("Changes are saved on this device. External-only listings still require their official player.", 10, MUTED, false);
        footer.setGravity(Gravity.CENTER);
        footer.setPadding(dp(6), dp(10), dp(6), 0);
        root.addView(footer);
        dialog.setContentView(root);
        dialog.setCancelable(true);
        dialog.show();
        Window window = dialog.getWindow();
        if (window != null) {
            window.setBackgroundDrawable(new ColorDrawable(BG));
            window.setLayout(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT);
            window.setStatusBarColor(BG);
            window.setNavigationBarColor(BG);
            window.addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS);
        }
        refresh[0].run();
    }

    private TextView queueMiniButton(String symbol, String description) {
        TextView button = text(symbol, 17, TEXT, true);
        button.setGravity(Gravity.CENTER);
        button.setContentDescription(description);
        button.setBackground(roundRect(PANEL_ALT, 50));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(30), dp(34));
        lp.leftMargin = dp(3);
        button.setLayoutParams(lp);
        return button;
    }

    private void showTracks(List<Track> tracks, String status) {
        visibleTracks.clear();
        visibleTracks.addAll(tracks);
        resultsList.removeAllViews();
        if ("local".equals(source)) {
            TextView addLocal = text("＋  ADD LOCAL AUDIO / FLAC", 11, ACCENT, true);
            addLocal.setGravity(Gravity.CENTER);
            addLocal.setBackground(roundRect(PANEL_ALT, 15));
            addLocal.setPadding(dp(12), dp(14), dp(12), dp(14));
            addLocal.setOnClickListener(v -> chooseLocalAudio());
            LinearLayout.LayoutParams addLocalLp = new LinearLayout.LayoutParams(-1, -2);
            addLocalLp.bottomMargin = dp(9);
            resultsList.addView(addLocal, addLocalLp);
        }
        if (tracks.isEmpty()) {
            LinearLayout emptyCard = new LinearLayout(this);
            emptyCard.setOrientation(LinearLayout.VERTICAL);
            emptyCard.setPadding(dp(17), dp(17), dp(17), dp(17));
            emptyCard.setBackground(panelCardDrawable());
            TextView emptyTitle = text("Nothing in this orbit yet.", 16, TEXT, true);
            emptyCard.addView(emptyTitle);
            TextView empty = text(status, 12, MUTED, false);
            LinearLayout.LayoutParams emptyLp = new LinearLayout.LayoutParams(-1, -2);
            emptyLp.topMargin = dp(7);
            emptyCard.addView(empty, emptyLp);
            if ("all".equals(source)) {
                TextView retry = text("↻  Try search again", 12, ACCENT, true);
                retry.setPadding(0, dp(15), 0, 0);
                retry.setOnClickListener(v -> loadAllSources(searchField.getText().toString().trim()));
                emptyCard.addView(retry);
            }
            resultsList.addView(emptyCard, new LinearLayout.LayoutParams(-1, -2));
        } else {
            for (Track track : tracks) {
                LinearLayout card = new LinearLayout(this);
                card.setGravity(Gravity.CENTER_VERTICAL);
                card.setOrientation(LinearLayout.HORIZONTAL);
                card.setPadding(dp(10), dp(10), dp(9), dp(10));
                card.setBackground(panelCardDrawable());

                FrameLayout cover = createCoverFrame(track, dp(72));
                LinearLayout.LayoutParams coverLp = new LinearLayout.LayoutParams(dp(72), dp(72));
                coverLp.rightMargin = dp(12);
                card.addView(cover, coverLp);

                LinearLayout info = new LinearLayout(this);
                info.setGravity(Gravity.CENTER_VERTICAL);
                info.setOrientation(LinearLayout.VERTICAL);
                TextView name = text(track.title, 14, TEXT, true);
                name.setMaxLines(2);
                TextView artist = text(track.artist, 12, MUTED, false);
                artist.setMaxLines(1);
                LinearLayout.LayoutParams artistLp = new LinearLayout.LayoutParams(-1, -2);
                artistLp.topMargin = dp(4);
                info.addView(name);
                info.addView(artist, artistLp);
                TextView sourceBadge = text(displayProvider(track), 9, ACCENT, true);
                sourceBadge.setLetterSpacing(0.03f);
                sourceBadge.setMaxLines(1);
                sourceBadge.setEllipsize(android.text.TextUtils.TruncateAt.END);
                LinearLayout.LayoutParams sourceBadgeLp = new LinearLayout.LayoutParams(-1, -2);
                sourceBadgeLp.topMargin = dp(5);
                info.addView(sourceBadge, sourceBadgeLp);
                card.addView(info, new LinearLayout.LayoutParams(0, -2, 1));

                TextView like = text(isFavorite(track) ? "♥" : "♡", 22,
                        isFavorite(track) ? ACCENT : MUTED, true);
                like.setGravity(Gravity.CENTER);
                like.setContentDescription(isFavorite(track) ? "Remove from liked tracks" : "Add to liked tracks");
                like.setBackground(roundRect(PANEL_ALT, 50));
                LinearLayout.LayoutParams likeLp = new LinearLayout.LayoutParams(dp(39), dp(39));
                likeLp.leftMargin = dp(5);
                card.addView(like, likeLp);
                like.setOnClickListener(v -> {
                    boolean nowLiked = toggleFavorite(track);
                    like.setText(nowLiked ? "♥" : "♡");
                    like.setTextColor(nowLiked ? ACCENT : MUTED);
                    if ("liked".equals(source) && !nowLiked) {
                        showTracks(new ArrayList<>(favoriteTracks), favoriteTracks.size() + " saved tracks on this device");
                    }
                });
                TextView more = text("⋮", 23, TEXT, true);
                more.setGravity(Gravity.CENTER);
                more.setContentDescription("More options for " + track.title);
                more.setBackground(roundRect(PANEL_ALT, 50));
                LinearLayout.LayoutParams moreLp = new LinearLayout.LayoutParams(dp(36), dp(39));
                moreLp.leftMargin = dp(4);
                card.addView(more, moreLp);
                more.setOnClickListener(v -> showTrackActions(track));

                card.setOnClickListener(v -> playTrack(track));
                LinearLayout.LayoutParams cardLp = new LinearLayout.LayoutParams(-1, -2);
                cardLp.bottomMargin = dp(8);
                resultsList.addView(card, cardLp);
                if ("archive".equals(track.provider)) {
                    TextView action = text("↓  Download licensed audio", 10, ACCENT, true);
                    action.setPadding(dp(8), dp(1), 0, dp(8));
                    action.setOnClickListener(v -> downloadArchive(track));
                    resultsList.addView(action);
                }
            }
        }
        statusText.setText(status);
    }

    private void setLoading(String message) {
        statusText.setText(message);
        visibleTracks.clear();
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
        int radius = "Retro CRT".equals(activeTheme) ? 7 :
                ("Dark Academia".equals(activeTheme) ? 12 : ("Midwest Emo".equals(activeTheme) ? 10 :
                (("Y2K Glass".equals(activeTheme) || "Frutiger Aero".equals(activeTheme)) ? 24 : 19)));
        GradientDrawable d;
        if ("AMOLED".equals(activeTheme)) {
            d = roundRect(Color.rgb(15, 15, 19), radius);
            d.setStroke(dp(1), Color.rgb(49, 49, 57));
        } else if ("Frutiger Aero".equals(activeTheme)) {
            d = new GradientDrawable(GradientDrawable.Orientation.TL_BR,
                    new int[]{Color.rgb(251, 255, 255), Color.rgb(219, 247, 252), Color.rgb(183, 230, 242)});
            d.setCornerRadius(dp(radius));
            d.setStroke(dp(1), Color.argb(210, 255, 255, 255));
        } else if ("Y2K Glass".equals(activeTheme)) {
            d = new GradientDrawable(GradientDrawable.Orientation.TL_BR,
                    new int[]{Color.rgb(185, 219, 246), Color.rgb(72, 107, 166), Color.rgb(51, 67, 117)});
            d.setCornerRadius(dp(radius));
            d.setStroke(dp(1), Color.argb(150, 239, 249, 255));
        } else if ("Dark Academia".equals(activeTheme)) {
            d = new GradientDrawable(GradientDrawable.Orientation.TL_BR,
                    new int[]{Color.rgb(64, 51, 38), PANEL});
            d.setCornerRadius(dp(radius));
            d.setStroke(dp(1), Color.argb(105, Color.red(ACCENT), Color.green(ACCENT), Color.blue(ACCENT)));
        } else if ("Retro CRT".equals(activeTheme)) {
            d = roundRect(PANEL, radius);
            d.setStroke(dp(1), Color.argb(125, Color.red(ACCENT), Color.green(ACCENT), Color.blue(ACCENT)));
        } else if ("Midwest Emo".equals(activeTheme)) {
            d = new GradientDrawable(GradientDrawable.Orientation.TL_BR,
                    new int[]{Color.rgb(55, 43, 61), Color.rgb(31, 27, 37)});
            d.setCornerRadius(dp(radius));
            d.setStroke(dp(1), Color.rgb(102, 71, 98));
        } else if ("Synthwave".equals(activeTheme) || "Vaporwave".equals(activeTheme)) {
            d = new GradientDrawable(GradientDrawable.Orientation.TL_BR,
                    new int[]{PANEL_ALT, PANEL, BG});
            d.setCornerRadius(dp(radius));
            d.setStroke(dp(1), Color.argb(150, Color.red(ACCENT), Color.green(ACCENT), Color.blue(ACCENT)));
        } else if ("Cyberpunk".equals(activeTheme) || "Aurora Forest".equals(activeTheme)) {
            d = new GradientDrawable(GradientDrawable.Orientation.TL_BR,
                    new int[]{PANEL, BG});
            d.setCornerRadius(dp(radius));
            d.setStroke(dp(1), Color.argb(150, Color.red(ACCENT), Color.green(ACCENT), Color.blue(ACCENT)));
        } else if ("Sunset Coast".equals(activeTheme) || "Candy Pop".equals(activeTheme)) {
            d = new GradientDrawable(GradientDrawable.Orientation.TL_BR,
                    new int[]{PANEL_ALT, PANEL});
            d.setCornerRadius(dp(radius));
            d.setStroke(dp(1), Color.argb(120, Color.red(ACCENT), Color.green(ACCENT), Color.blue(ACCENT)));
        } else {
            d = new GradientDrawable(GradientDrawable.Orientation.TL_BR,
                    new int[]{blendColor(PANEL_ALT, ACCENT, 0.10f), PANEL});
            d.setCornerRadius(dp(radius));
            d.setStroke(dp(1), Color.argb(38, Color.red(ACCENT), Color.green(ACCENT), Color.blue(ACCENT)));
        }
        return d;
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
        if (track == null) {
            statusText.setText("Choose a result first.");
            return;
        }
        int visibleIndex = visibleTracks.indexOf(track);
        if (visibleIndex >= 0 && !preserveQueueOnNextPlay) {
            playbackQueue = new ArrayList<>(visibleTracks);
            playbackQueueIndex = visibleIndex;
            if (!"off".equals(shuffleMode)) shuffleUpcomingTracks();
        }
        preserveQueueOnNextPlay = false;
        persistUiQueue();
        if (needsExternalPlayback(track)) {
            currentTrack = track;
            nowPlayingText.setText(track.title + " · ready to open externally");
            playPauseButton.setText("↗");
            if (miniArtwork != null) {
                miniArtwork.setImageResource(R.drawable.spotty_app_icon);
                if (!track.artworkUrl.isEmpty()) loadTrackArtwork(miniArtwork, track.artworkUrl);
            }
            showPlayerDialog(track);
            return;
        }
        if ("archive".equals(track.provider)) {
            checkingArchiveLicense = true;
            currentTrack = track;
            nowPlayingText.setText(track.title + " — checking licence");
            statusText.setText("Checking the open-audio licence…");
            showPlayerDialog(track);
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
                    if (!allowed) throw new Exception("No verifiable Creative Commons/public-domain licence was found.");
                    JSONArray files = response.optJSONArray("files");
                    if (files == null) throw new Exception("No accessible files are available for this item.");
                    JSONObject chosen = chooseArchiveAudioFile(files, true);
                    if (chosen == null) throw new Exception("No supported public audio file was found.");
                    String selected = chosen.optString("name", "");
                    String stream = chooseArchiveStreamUrl(track.id, selected);
                    String quality = describeAudioFile(chosen);
                    Track playable = new Track(track.id, track.title, track.artist,
                            "Internet Archive · " + quality, "archive-playable", stream, track.artworkUrl,
                            license, track.album, track.genre, track.playlist);
                    main.post(() -> {
                        checkingArchiveLicense = false;
                        currentTrack = playable;
                        if (activePlayerSelection != null && activePlayerSelection.id.equals(track.id)) {
                            activePlayerSelection = playable;
                            refreshPlayerSelection(playable);
                        }
                        beginPlayback(playable);
                    });
                } catch (Exception e) {
                    main.post(() -> {
                        checkingArchiveLicense = false;
                        statusText.setText("This archive item cannot be played");
                        nowPlayingText.setText(shortError(e));
                        if (playerToggleView != null) {
                            playerToggleView.setEnabled(true);
                            playerToggleView.setText("↻");
                        }
                        if (playerSourceView != null) {
                            playerSourceView.setText("SOURCE · INTERNET ARCHIVE · ITEM NOT PLAYABLE");
                        }
                    });
                }
            });
            return;
        }
        if (selectionIsCurrent(track) && MusicPlaybackService.hasTrack()) {
            showPlayerDialog(track);
            return;
        }
        beginPlayback(track);
        showPlayerDialog(track);
    }

    private boolean selectionIsCurrent(Track track) {
        if (track == null || currentTrack == null) return false;
        if ("archive".equals(track.provider) && "archive-playable".equals(currentTrack.provider)) {
            return track.id.equals(currentTrack.id);
        }
        return sameTrack(currentTrack, track);
    }

    private boolean needsExternalPlayback(Track track) {
        return track != null && ("youtube".equals(track.provider) || "playlist-entry".equals(track.provider));
    }

    private String displayProvider(Track track) {
        if (track == null) return "SPOTTY";
        switch (track.provider) {
            case "audius": return "AUDIUS · FULL TRACK";
            case "deezer-preview": return "DEEZER · PREVIEW";
            case "archive": case "archive-playable": return "INTERNET ARCHIVE · OPEN AUDIO";
            case "local": return "ON THIS DEVICE";
            case "radio": return "INTERNET RADIO";
            case "youtube": return "YOUTUBE · EXTERNAL PLAYER";
            case "playlist-entry": return "IMPORTED PLAYLIST";
            default: return track.provider.isEmpty() ? "SPOTTY" : track.provider.toUpperCase(Locale.ROOT);
        }
    }


    private boolean isDebugSmokeFixtureUrl(String provider, String url) {
        if ((getApplicationInfo().flags & ApplicationInfo.FLAG_DEBUGGABLE) == 0
                || !"local".equals(provider) || url == null || url.isEmpty()) return false;
        String normalized = url.replace('\\', '/');
        String packageFilesMarker = "/" + getPackageName() + "/files/";
        if (!(normalized.startsWith("/data/user/") || normalized.startsWith("/data/data/")
                || normalized.startsWith(getFilesDir().getAbsolutePath() + File.separator))
                || !normalized.contains(packageFilesMarker)) return false;
        String name = new File(normalized).getName();
        return "spotty-ci-smoke.wav".equals(name)
                || "spotty-ci-short-two.wav".equals(name)
                || "spotty-ci-short-three.wav".equals(name)
                || "spotty-ci-short-smart.wav".equals(name);
    }

    private String serializeBackgroundQueue(Track current) {
        List<Track> playable = new ArrayList<>();
        for (Track candidate : playbackQueue) {
            if (candidate.playUrl == null || candidate.playUrl.isEmpty()) continue;
            if ("youtube".equals(candidate.provider) || "playlist-entry".equals(candidate.provider)) continue;
            String url = candidate.playUrl;
            boolean ciPrivateFixture = isDebugSmokeFixtureUrl(candidate.provider, url);
            if (!(url.startsWith("https://") || url.startsWith("http://") || url.startsWith("content://")
                    || ciPrivateFixture)) continue;
            playable.add(candidate);
        }
        activeBackgroundQueueIndex = -1;
        // Preserve the selected queue slot, even when the same track/URL appears more than once.
        if (playbackQueueIndex >= 0 && playbackQueueIndex < playbackQueue.size()) {
            Track selected = playbackQueue.get(playbackQueueIndex);
            if (selected.playUrl.equals(current.playUrl)) {
                int filteredIndex = 0;
                for (int i = 0; i < playbackQueueIndex; i++) {
                    Track candidate = playbackQueue.get(i);
                    String url = candidate.playUrl == null ? "" : candidate.playUrl;
                    boolean ciPrivateFixture = isDebugSmokeFixtureUrl(candidate.provider, url);
                    if (!url.isEmpty() && !"youtube".equals(candidate.provider)
                            && !"playlist-entry".equals(candidate.provider)
                            && (url.startsWith("https://") || url.startsWith("http://")
                            || url.startsWith("content://") || ciPrivateFixture)) filteredIndex++;
                }
                activeBackgroundQueueIndex = filteredIndex;
                playable.set(activeBackgroundQueueIndex, current);
            }
        }
        if (activeBackgroundQueueIndex < 0) {
            for (int i = 0; i < playable.size(); i++) {
                if (playable.get(i).playUrl.equals(current.playUrl)) {
                    playable.set(i, current);
                    activeBackgroundQueueIndex = i;
                    break;
                }
            }
        }
        if (activeBackgroundQueueIndex < 0) {
            int insertAt = Math.max(0, Math.min(playbackQueueIndex, playable.size()));
            playable.add(insertAt, current);
            activeBackgroundQueueIndex = insertAt;
        }
        JSONArray serialized = new JSONArray();
        for (Track track : playable) {
            JSONObject item = new JSONObject();
            try {
                item.put("url", track.playUrl);
                item.put("title", track.title);
                item.put("artist", track.artist);
                item.put("album", track.album);
                item.put("genre", track.genre);
                item.put("playlist", track.playlist);
                item.put("provider", track.provider);
                serialized.put(item);
            } catch (Exception ignored) {}
        }
        return serialized.toString();
    }


    private void beginPlayback(Track track) {
        Track playableTrack = "local".equals(track.provider) ? enrichLocalTrackMetadata(track) : track;
        if (playableTrack.playUrl.isEmpty()) {
            statusText.setText("This listing has no in-app audio stream.");
            return;
        }
        releasePlayer();
        currentTrack = playableTrack;
        paused = false;
        preparing = true;
        nowPlayingText.setText(playableTrack.title + " — " + playableTrack.artist);
        playPauseButton.setText("…");
        if (miniArtwork != null) {
            miniArtwork.setImageResource(R.drawable.spotty_app_icon);
            if (!playableTrack.artworkUrl.isEmpty()) loadTrackArtwork(miniArtwork, playableTrack.artworkUrl);
        }
        if (playerTitleView != null) refreshPlayerSelection(activePlayerSelection == null ? playableTrack : activePlayerSelection);
        statusText.setText("Starting playback…");
        boolean debugPlaybackSmoke = (getApplicationInfo().flags & ApplicationInfo.FLAG_DEBUGGABLE) != 0
                && getIntent().getBooleanExtra("spotty_ci_smoke_playback", false);
        if (!debugPlaybackSmoke) requestNotificationPermissionIfNeeded();
        try {
            String backgroundQueue = serializeBackgroundQueue(playableTrack);
            MusicPlaybackService.play(this, playableTrack.playUrl, playableTrack.title, playableTrack.artist,
                    backgroundQueue, activeBackgroundQueueIndex);
        } catch (Exception e) {
            preparing = false;
            playPauseButton.setText("▶");
            statusText.setText("Could not start playback: " + shortError(e));
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
                item.put("album", track.album);
                item.put("genre", track.genre);
                item.put("playlist", track.playlist);
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
                // Persist user-selected content URIs and HTTP(S) sources. CI's private-file fixtures
                // are also restorable in the debuggable smoke APK, but never in release builds.
                boolean ciPrivateFixture = isDebugSmokeFixtureUrl(provider, playUrl);
                if (!playUrl.isEmpty() && !(playUrl.startsWith("https://") || playUrl.startsWith("http://")
                        || ("local".equals(provider) && playUrl.startsWith("content://")) || ciPrivateFixture)) continue;
                tracks.add(new Track(item.optString("id", ""), item.optString("title", ""),
                        item.optString("artist", ""), item.optString("subtitle", ""),
                        provider, playUrl, item.optString("artworkUrl", ""), item.optString("license", ""),
                        item.optString("album", ""), item.optString("genre", ""), item.optString("playlist", "")));
            }
        } catch (Exception ignored) {}
        return tracks;
    }

    private void playNextTrack() {
        if (playbackQueue.isEmpty()) {
            statusText.setText("Add a result to the queue first.");
            return;
        }
        if (playbackQueueIndex + 1 >= playbackQueue.size()) {
            if (!"playlist".equals(repeatMode)) { statusText.setText("You are at the end of the queue."); return; }
            playbackQueueIndex = 0;
        } else playbackQueueIndex++;
        Track track = playbackQueue.get(playbackQueueIndex);
        statusText.setText("Next • " + track.title);
        preserveQueueOnNextPlay = true;
        playTrack(track);
        persistUiQueue();
    }

    private void playPreviousTrack() {
        if (playbackQueue.isEmpty()) {
            statusText.setText("Play a result first to start a queue.");
            return;
        }
        if (MusicPlaybackService.currentPosition() > 3000) {
            MusicPlaybackService.seekToStart();
            statusText.setText("Restarted track");
            return;
        }
        if (playbackQueueIndex <= 0 && !"playlist".equals(repeatMode)) {
            statusText.setText("You are at the beginning of the queue.");
            MusicPlaybackService.seekToStart();
            return;
        }
        playbackQueueIndex = (playbackQueueIndex - 1 + playbackQueue.size()) % playbackQueue.size();
        Track track = playbackQueue.get(playbackQueueIndex);
        statusText.setText("Previous • " + track.title);
        preserveQueueOnNextPlay = true;
        playTrack(track);
        persistUiQueue();
    }

    private void addTrackToQueue(Track track, boolean playNext) {
        if (track == null) return;
        if (playbackQueue.isEmpty() && currentTrack != null && MusicPlaybackService.hasTrack()) {
            playbackQueue.add(currentTrack);
            playbackQueueIndex = 0;
        }
        int insertion = playbackQueue.size();
        if (playNext) insertion = MusicPlaybackService.hasTrack() && playbackQueueIndex >= 0
                ? Math.min(playbackQueueIndex + 1, playbackQueue.size()) : 0;
        playbackQueue.add(insertion, track);
        if (playbackQueueIndex >= insertion) playbackQueueIndex++;
        persistUiQueue();
        syncQueueToService();
        Toast.makeText(this, playNext ? "Added to play next" : "Added to queue", Toast.LENGTH_SHORT).show();
    }

    private void persistUiQueue() {
        if (playbackQueueIndex < 0 || playbackQueueIndex >= playbackQueue.size()) {
            if (!MusicPlaybackService.hasTrack()) playbackQueueIndex = -1;
        }
        getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                .putString("ui_queue_json", serializeTrackList(playbackQueue))
                .putInt("ui_queue_index", playbackQueueIndex)
                .putString("repeat_mode", repeatMode)
                .putString("shuffle_mode", shuffleMode)
                .apply();
    }

    private int indexOfQueueUrl(String url) {
        if (url == null || url.isEmpty()) return -1;
        for (int i = 0; i < playbackQueue.size(); i++) if (url.equals(playbackQueue.get(i).playUrl)) return i;
        return -1;
    }

    private void syncQueueToService() {
        persistUiQueue();
        if (!MusicPlaybackService.hasTrack() || currentTrack == null || needsExternalPlayback(currentTrack)) return;
        String queueJson = serializeBackgroundQueue(currentTrack);
        MusicPlaybackService.updateQueue(this, queueJson, activeBackgroundQueueIndex, repeatMode);
    }

    private TextView modeButton(String label, boolean active) {
        TextView button = text(label, 9, active ? Color.WHITE : TEXT, true);
        button.setGravity(Gravity.CENTER);
        button.setPadding(dp(3), dp(6), dp(3), dp(6));
        button.setMaxLines(2);
        button.setBackground(roundRect(active ? ACCENT : PANEL_ALT, 13));
        return button;
    }

    private String repeatLabel() {
        if ("playlist".equals(repeatMode)) return "REPEAT PLAYLIST";
        if ("track".equals(repeatMode)) return "REPEAT TRACK";
        return "REPEAT OFF";
    }

    private void updatePlaybackModeButtons() {
        if (playerShuffleButton != null) {
            boolean active = "shuffle".equals(shuffleMode);
            playerShuffleButton.setText(active ? "✓ SHUFFLE" : "SHUFFLE");
            playerShuffleButton.setTextColor(active ? Color.WHITE : TEXT);
            playerShuffleButton.setBackground(roundRect(active ? ACCENT : PANEL_ALT, 13));
        }
        if (playerSmartShuffleButton != null) {
            boolean active = "smart".equals(shuffleMode);
            playerSmartShuffleButton.setText(active ? "✦ SMART SHUFFLE" : "SMART SHUFFLE");
            playerSmartShuffleButton.setTextColor(active ? Color.WHITE : TEXT);
            playerSmartShuffleButton.setBackground(roundRect(active ? ACCENT : PANEL_ALT, 13));
        }
        if (playerRepeatButton != null) {
            boolean active = !"off".equals(repeatMode);
            playerRepeatButton.setText(repeatLabel());
            playerRepeatButton.setTextColor(active ? Color.WHITE : TEXT);
            playerRepeatButton.setBackground(roundRect(active ? ACCENT : PANEL_ALT, 13));
        }
    }

    private void activateShuffleMode(String requestedMode) {
        shuffleMode = requestedMode.equals(shuffleMode) ? "off" : requestedMode;
        getSharedPreferences(PREFS, MODE_PRIVATE).edit().putString("shuffle_mode", shuffleMode).apply();
        if (!"off".equals(shuffleMode)) shuffleUpcomingTracks();
        updatePlaybackModeButtons();
        persistUiQueue();
        syncQueueToService();
        statusText.setText("Shuffle mode • " + shuffleMode.toUpperCase(Locale.ROOT));
    }

    private void shuffleUpcomingTracks() {
        int start = (MusicPlaybackService.hasTrack() && playbackQueueIndex >= 0)
                ? playbackQueueIndex + 1 : Math.max(0, playbackQueueIndex);
        start = Math.min(start, playbackQueue.size());
        List<Track> upcoming = new ArrayList<>(playbackQueue.subList(start, playbackQueue.size()));
        Track anchor = playbackQueueIndex >= 0 && playbackQueueIndex < playbackQueue.size()
                ? playbackQueue.get(playbackQueueIndex) : currentTrack;
        if (anchor == null && !visibleTracks.isEmpty()) anchor = visibleTracks.get(0);

        if ("smart".equals(shuffleMode)) {
            // Smart Shuffle extends a manually-built mix with similar playable tracks from the
            // current catalogue results. It never fabricates a stream or re-adds queued URLs.
            List<Track> suggestions = new ArrayList<>();
            for (Track candidate : visibleTracks) {
                if (candidate == null || candidate.playUrl.isEmpty() || needsExternalPlayback(candidate)) continue;
                boolean alreadyQueued = false;
                for (Track queued : playbackQueue) {
                    if (sameTrack(queued, candidate)
                            || (!candidate.playUrl.isEmpty() && candidate.playUrl.equals(queued.playUrl))) {
                        alreadyQueued = true;
                        break;
                    }
                }
                boolean alreadySuggested = false;
                if (!alreadyQueued) {
                    for (Track suggested : suggestions) {
                        if (sameTrack(suggested, candidate)
                                || (!candidate.playUrl.isEmpty() && candidate.playUrl.equals(suggested.playUrl))) {
                            alreadySuggested = true;
                            break;
                        }
                    }
                }
                if (!alreadyQueued && !alreadySuggested) suggestions.add(candidate);
            }
            if (anchor != null) {
                final Track reference = anchor;
                suggestions.sort((a, b) -> Integer.compare(smartShuffleScore(b, reference), smartShuffleScore(a, reference)));
                int maxSuggestions = Math.min(12, suggestions.size());
                for (int i = 0; i < maxSuggestions; i++) upcoming.add(suggestions.get(i));
            }
        }

        if ("smart".equals(shuffleMode) && anchor != null) {
            final Track reference = anchor;
            upcoming.sort((a, b) -> Integer.compare(smartShuffleScore(b, reference), smartShuffleScore(a, reference)));
            // Preserve the relevance ranking, but randomize tracks within equally relevant tiers.
            // Shuffling first and sorting afterwards was deterministic and erased shuffle variety.
            List<Track> variedUpcoming = new ArrayList<>();
            int tierStart = 0;
            while (tierStart < upcoming.size()) {
                int tierScore = smartShuffleScore(upcoming.get(tierStart), reference);
                int tierEnd = tierStart + 1;
                while (tierEnd < upcoming.size()
                        && smartShuffleScore(upcoming.get(tierEnd), reference) == tierScore) tierEnd++;
                List<Track> tier = new ArrayList<>(upcoming.subList(tierStart, tierEnd));
                Collections.shuffle(tier);
                variedUpcoming.addAll(tier);
                tierStart = tierEnd;
            }
            upcoming = variedUpcoming;
        } else {
            Collections.shuffle(upcoming);
        }
        while (playbackQueue.size() > start) playbackQueue.remove(playbackQueue.size() - 1);
        playbackQueue.addAll(upcoming);
    }

    private int smartShuffleScore(Track candidate, Track anchor) {
        if (candidate == null || anchor == null) return 0;
        int score = 0;
        if (!candidate.artist.isEmpty() && candidate.artist.equalsIgnoreCase(anchor.artist)) score += 5;
        if (!candidate.genre.isEmpty() && candidate.genre.equalsIgnoreCase(anchor.genre)) score += 4;
        if (!candidate.album.isEmpty() && candidate.album.equalsIgnoreCase(anchor.album)) score += 3;
        if (!candidate.provider.isEmpty() && candidate.provider.equals(anchor.provider)) score += 1;
        return score;
    }

    private void cycleRepeatMode() {
        if ("off".equals(repeatMode)) repeatMode = "playlist";
        else if ("playlist".equals(repeatMode)) repeatMode = "track";
        else repeatMode = "off";
        getSharedPreferences(PREFS, MODE_PRIVATE).edit().putString("repeat_mode", repeatMode).apply();
        updatePlaybackModeButtons();
        persistUiQueue();
        syncQueueToService();
        statusText.setText("Repeat mode • " + repeatMode.toUpperCase(Locale.ROOT));
    }

    private void showTrackActions(Track track) {
        if (track == null) return;
        List<String> actions = new ArrayList<>();
        actions.add("Play now");
        actions.add("Play next");
        actions.add("Add to queue");
        actions.add("Add to playlist");
        if ("archive".equals(track.provider)) actions.add("Download licensed audio");
        if ("local".equals(track.provider)) actions.add("Remove from local library");
        if ("user_playlist".equals(source)) actions.add("Remove from this playlist");
        String[] choices = actions.toArray(new String[0]);
        new AlertDialog.Builder(this).setTitle(track.title).setItems(choices, (d, which) -> {
            String action = choices[which];
            if ("Play now".equals(action)) playTrack(track);
            else if ("Play next".equals(action)) addTrackToQueue(track, true);
            else if ("Add to queue".equals(action)) addTrackToQueue(track, false);
            else if ("Add to playlist".equals(action)) choosePlaylistForTrack(track);
            else if ("Download licensed audio".equals(action)) downloadArchive(track);
            else if ("Remove from local library".equals(action)) {
                for (int i = localTracks.size() - 1; i >= 0; i--) if (sameTrack(localTracks.get(i), track)) localTracks.remove(i);
                persistLocalTracks();
                showTracks(new ArrayList<>(localTracks), localTracks.size() + " local files");
            } else if ("Remove from this playlist".equals(action)) {
                UserPlaylist playlist = findUserPlaylist(activeUserPlaylist);
                if (playlist != null) {
                    for (int i = playlist.tracks.size() - 1; i >= 0; i--) if (sameTrack(playlist.tracks.get(i), track)) { playlist.tracks.remove(i); break; }
                    persistUserPlaylists();
                    showTracks(new ArrayList<>(playlist.tracks), playlist.tracks.size() + " tracks • saved on this device");
                }
            }
        }).setNegativeButton("Cancel", null).show();
    }

    private void togglePlayback() {
        if (!MusicPlaybackService.hasTrack() && currentTrack == null) {
            statusText.setText("Choose a track first.");
            return;
        }
        if (MusicPlaybackService.isPreparing()) {
            statusText.setText("Still connecting to the stream…");
            return;
        }
        MusicPlaybackService.toggle(this);
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
        // Called when switching away from Spotty-managed audio, not when the Activity exits.
        MusicPlaybackService.stop(this);
        player = null;
        paused = false;
        preparing = false;
    }

    @Override
    protected void onDestroy() {
        // Close Activity-owned dialogs before their window token becomes invalid.
        if (themeDialog != null && themeDialog.isShowing()) themeDialog.dismiss();
        if (settingsDialog != null && settingsDialog.isShowing()) settingsDialog.dismiss();
        if (playerDialog != null && playerDialog.isShowing()) playerDialog.dismiss();
        themeDialog = null;
        settingsDialog = null;
        playerDialog = null;
        if (playbackStateReceiver != null) {
            try { unregisterReceiver(playbackStateReceiver); } catch (IllegalArgumentException ignored) {}
            playbackStateReceiver = null;
        }
        // The process-scoped I/O pool must survive Activity recreation; do not shut it down here.
        main.removeCallbacksAndMessages(null);
        super.onDestroy();
    }

    private static final class UserPlaylist {
        final String name;
        final List<Track> tracks = new ArrayList<>();
        UserPlaylist(String name) { this.name = name == null || name.trim().isEmpty() ? "My playlist" : name.trim(); }
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
        final String album;
        final String genre;
        final String playlist;

        Track(String id, String title, String artist, String subtitle, String provider,
              String playUrl, String artworkUrl, String license) {
            this(id, title, artist, subtitle, provider, playUrl, artworkUrl, license, "", "", "");
        }

        Track(String id, String title, String artist, String subtitle, String provider,
              String playUrl, String artworkUrl, String license, String album, String genre, String playlist) {
            this.id = id == null ? "" : id;
            this.title = title == null || title.isEmpty() ? "Untitled" : title;
            this.artist = artist == null || artist.isEmpty() ? "Unknown artist" : artist;
            this.subtitle = subtitle == null ? "" : subtitle;
            this.provider = provider == null ? "" : provider;
            this.playUrl = playUrl == null ? "" : playUrl;
            this.artworkUrl = artworkUrl == null ? "" : artworkUrl;
            this.license = license == null ? "" : license;
            this.album = album == null ? "" : album.trim();
            this.genre = genre == null ? "" : genre.trim();
            this.playlist = playlist == null ? "" : playlist.trim();
        }
    }
}
