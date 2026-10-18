package com.spotty.music;

import android.app.Activity;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.media.AudioAttributes;
import android.media.MediaPlayer;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.widget.Button;
import android.widget.EditText;
import android.widget.HorizontalScrollView;
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
    private static final int BG = Color.rgb(12, 15, 14);
    private static final int PANEL = Color.rgb(26, 31, 29);
    private static final int PANEL_ALT = Color.rgb(35, 41, 38);
    private static final int TEXT = Color.rgb(245, 246, 242);
    private static final int MUTED = Color.rgb(162, 171, 165);
    private static final int ACCENT = Color.rgb(133, 235, 158);

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

    @Override
    public void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().setStatusBarColor(BG);
        getWindow().setNavigationBarColor(BG);
        getWindow().getDecorView().setSystemUiVisibility(0);
        buildUi();
        loadCurrentSource("");
    }

    private void buildUi() {
        LinearLayout screen = new LinearLayout(this);
        screen.setOrientation(LinearLayout.VERTICAL);
        screen.setBackgroundColor(BG);
        screen.setPadding(dp(18), dp(10), dp(18), dp(8));

        LinearLayout header = new LinearLayout(this);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setOrientation(LinearLayout.HORIZONTAL);

        TextView mark = new TextView(this);
        mark.setText("♫");
        mark.setTextColor(ACCENT);
        mark.setTextSize(32);
        mark.setTypeface(Typeface.DEFAULT_BOLD);
        header.addView(mark, new LinearLayout.LayoutParams(dp(42), dp(48)));

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
        sourceRow.addView(sourceButton("Audius", "audius"));
        sourceRow.addView(sourceButton("Open Archive", "archive"));
        sourceRow.addView(sourceButton("Radio", "radio"));
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

        playPauseButton = text("▶", 22, ACCENT, true);
        playPauseButton.setGravity(Gravity.CENTER);
        playPauseButton.setBackground(roundRect(PANEL_ALT, 50));
        playPauseButton.setOnClickListener(v -> togglePlayback());
        mini.addView(playPauseButton, new LinearLayout.LayoutParams(dp(44), dp(44)));

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

    private void submitSearch() {
        String query = searchField.getText().toString().trim();
        ((InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE))
                .hideSoftInputFromWindow(searchField.getWindowToken(), 0);
        loadCurrentSource(query);
    }

    private void loadCurrentSource(String query) {
        if ("youtube".equals(source)) loadYoutube(query);
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
        resultHeading.setText(query.isEmpty() ? "POPULAR MUSIC ON YOUTUBE" : "YOUTUBE SEARCH");
        setLoading(query.isEmpty() ? "Finding popular music…" : "Searching YouTube videos and audio…");
        String q = query.isEmpty() ? "music sort:views" : query;
        String[] instances = new String[] {
                "https://inv.nadeko.net",
                "https://invidious.nerdvpn.de",
                "https://yt.chocolatemoo53.com",
                "https://invidious.tiekoetter.com"
        };

        // Race the documented public search instances. One unhealthy server must
        // not block all the others or leave the search spinner running indefinitely.
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
                            main.post(() -> showTracks(found, found.size() + " results • playback via Morphe YouTube"));
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
                main.post(() -> showYoutubeFallback(query, failure));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                main.post(() -> showYoutubeFallback(query, e));
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
        showError("YouTube catalog search is temporarily unavailable: " + shortError(e));
        TextView fallback = text("Open this search in Morphe YouTube  ↗", 14, ACCENT, true);
        fallback.setPadding(dp(8), dp(12), dp(8), dp(16));
        fallback.setOnClickListener(v -> openYoutubeSearch(query));
        resultsList.addView(fallback);
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
                card.setBackground(roundRect(PANEL, 15));
                TextView name = text(track.title, 15, TEXT, true);
                name.setMaxLines(2);
                TextView artist = text(track.artist, 13, MUTED, false);
                artist.setMaxLines(1);
                TextView provider = text(track.subtitle, 10, ACCENT, false);
                provider.setMaxLines(1);
                card.addView(name);
                LinearLayout.LayoutParams artistLp = new LinearLayout.LayoutParams(-1, -2);
                artistLp.topMargin = dp(4);
                card.addView(artist, artistLp);
                LinearLayout.LayoutParams providerLp = new LinearLayout.LayoutParams(-1, -2);
                providerLp.topMargin = dp(7);
                card.addView(provider, providerLp);
                card.setOnClickListener(v -> playTrack(track));
                LinearLayout.LayoutParams cardLp = new LinearLayout.LayoutParams(-1, -2);
                cardLp.bottomMargin = dp(8);
                resultsList.addView(card, cardLp);
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

    private void playTrack(Track track) {
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
                    String selected = "";
                    for (int i = 0; i < files.length(); i++) {
                        JSONObject file = files.optJSONObject(i);
                        if (file == null || file.optBoolean("private", false)) continue;
                        String name = file.optString("name", "");
                        String low = name.toLowerCase(Locale.ROOT);
                        if (low.endsWith(".mp3") || low.endsWith(".ogg") || low.endsWith(".m4a")
                                || low.endsWith(".aac") || low.endsWith(".flac") || low.endsWith(".opus")) {
                            selected = name;
                            if (low.endsWith(".mp3")) break;
                        }
                    }
                    if (selected.isEmpty()) throw new Exception("No supported public audio file was found for this item.");
                    String stream = "https://archive.org/download/" + Uri.encode(track.id) + "/" + Uri.encode(selected);
                    Track playable = new Track(track.id, track.title, track.artist,
                            "Internet Archive • CC / public domain", "archive-playable", stream, "", license);
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
