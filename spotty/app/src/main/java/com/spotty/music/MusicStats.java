package com.spotty.music;

import android.content.Context;
import android.content.SharedPreferences;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;

/** On-device playback metrics recorded by the native playback service. */
final class MusicStats {
    private static final String PREFS = "spotty_preferences";
    private static final String TOTAL_MS = "music_stats_total_listened_ms_v1";
    private static final String TOTAL_PLAYS = "music_stats_total_plays_v1";
    private static final String AGGREGATES = "music_stats_aggregates_v1";
    private static final String DAYS = "music_stats_days_v1";
    private static final String RECENT = "music_stats_recent_v1";
    private static final int RECENT_LIMIT = 1000;
    private static final int TOP_MAP_LIMIT = 2000;
    private MusicStats() {}

    static synchronized void recordTrackStarted(Context context, String title, String artist,
            String album, String genre, String playlist, String source) {
        String cleanTitle = clean(title);
        if (cleanTitle.isEmpty() || cleanTitle.equalsIgnoreCase("Untitled track")) return;
        SharedPreferences prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        long now = System.currentTimeMillis();
        String date = dayKey(now);
        String cleanArtist = clean(artist);
        String cleanAlbum = clean(album);
        String cleanGenre = clean(genre);
        String cleanPlaylist = clean(playlist);
        String sourceName = sourceName(source);

        JSONObject aggregates = parseObject(prefs.getString(AGGREGATES, "{}"));
        increment(aggregates, "artists", meaningful(cleanArtist) ? cleanArtist : "");
        increment(aggregates, "tracks", meaningful(cleanArtist) ? cleanTitle + " — " + cleanArtist : cleanTitle);
        increment(aggregates, "albums", cleanAlbum);
        increment(aggregates, "genres", cleanGenre);
        increment(aggregates, "playlists", cleanPlaylist);
        increment(aggregates, "sources", sourceName);

        JSONObject days = parseObject(prefs.getString(DAYS, "{}"));
        JSONObject day = days.optJSONObject(date);
        if (day == null) day = new JSONObject();
        try {
            day.put("plays", day.optInt("plays", 0) + 1);
            days.put(date, day);
        } catch (JSONException ignored) {}

        JSONArray recent = parseArray(prefs.getString(RECENT, "[]"));
        JSONObject event = new JSONObject();
        try {
            event.put("title", cleanTitle);
            event.put("artist", cleanArtist);
            event.put("album", cleanAlbum);
            event.put("genre", cleanGenre);
            event.put("playlist", cleanPlaylist);
            event.put("source", sourceName);
            event.put("played_at", now);
            recent.put(event);
            while (recent.length() > RECENT_LIMIT) recent = removeFirst(recent);
        } catch (JSONException ignored) {}
        prefs.edit()
                .putInt(TOTAL_PLAYS, prefs.getInt(TOTAL_PLAYS, 0) + 1)
                .putString(AGGREGATES, aggregates.toString())
                .putString(DAYS, days.toString())
                .putString(RECENT, recent.toString())
                .apply();
    }

    static synchronized void addListeningTime(Context context, long deltaMs) {
        if (deltaMs <= 0L) return;
        SharedPreferences prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        long current = Math.max(0L, prefs.getLong(TOTAL_MS, 0L));
        long safeDelta = Math.min(deltaMs, 6L * 60L * 60L * 1000L);
        long updated = current > Long.MAX_VALUE - safeDelta ? Long.MAX_VALUE : current + safeDelta;
        JSONObject days = parseObject(prefs.getString(DAYS, "{}"));
        String date = dayKey(System.currentTimeMillis());
        JSONObject day = days.optJSONObject(date);
        if (day == null) day = new JSONObject();
        try {
            long dayMs = day.optLong("millis", 0L);
            day.put("millis", dayMs > Long.MAX_VALUE - safeDelta ? Long.MAX_VALUE : dayMs + safeDelta);
            days.put(date, day);
        } catch (JSONException ignored) {}
        prefs.edit().putLong(TOTAL_MS, updated).putString(DAYS, days.toString()).apply();
    }

    static String buildSummaryText(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        long totalMs = Math.max(0L, prefs.getLong(TOTAL_MS, 0L));
        int totalPlays = Math.max(0, prefs.getInt(TOTAL_PLAYS, 0));
        JSONObject aggregates = parseObject(prefs.getString(AGGREGATES, "{}"));
        JSONObject days = parseObject(prefs.getString(DAYS, "{}"));
        JSONObject artists = aggregates.optJSONObject("artists");
        JSONObject albums = aggregates.optJSONObject("albums");
        JSONObject playlists = aggregates.optJSONObject("playlists");
        JSONObject tracks = aggregates.optJSONObject("tracks");
        JSONObject genres = aggregates.optJSONObject("genres");
        JSONObject sources = aggregates.optJSONObject("sources");

        int playedDays = 0;
        Iterator<String> dayKeys = days.keys();
        while (dayKeys.hasNext()) {
            JSONObject day = days.optJSONObject(dayKeys.next());
            if (day != null && (day.optInt("plays", 0) > 0 || day.optLong("millis", 0L) > 0L)) playedDays++;
        }
        StringBuilder out = new StringBuilder();
        out.append("Recorded by Spotty's in-app player only\n");
        out.append("Days played: ").append(playedDays).append("\n");
        out.append("Minutes listened: ").append(totalMs / 60000L).append(" min (").append(formatDuration(totalMs)).append(")\n");
        out.append("Songs played: ").append(totalPlays).append(" successful starts\n\n");
        appendTop(out, "Top artists", artists, "No artist metadata recorded yet.");
        appendTop(out, "Top albums", albums, "Album metadata is not available from every source yet.");
        appendTop(out, "Top playlists", playlists, "Playlist plays from external apps are not visible to Spotty.");
        appendTop(out, "Top tracks", tracks, "No successful track starts recorded yet.");
        appendTop(out, "Top genres", genres, "Genre metadata is not available from every source yet.");
        appendTop(out, "Top sources", sources, "No playable source has started yet.");
        out.append("Taste overview (on-device pattern summary)\n");
        out.append(buildTasteOverview(totalPlays, artists, genres, tracks, sources));
        out.append("\n\nAccuracy notes\n");
        out.append("A song is counted when Spotty's MediaPlayer successfully starts it. Listening time accumulates only while that player reports active playback. YouTube, YouTube Music, Spotify and SoundCloud handoffs play in another app, so Spotty cannot count those external sessions without supported account/scrobbling integration. Historical listening before this feature was enabled is not included.");
        out.append("\n\nRefresh: the display updates about once per second while open; the playback service checkpoints listening time every few seconds and on pause/stop. Millisecond polling would add load without improving real-world measurement accuracy.");
        return out.toString();
    }

    private static String buildTasteOverview(int totalPlays, JSONObject artists, JSONObject genres,
            JSONObject tracks, JSONObject sources) {
        if (totalPlays <= 0) return "Your profile is still empty. Start an actual playable track inside Spotty and the overview will develop from repeat listens, source-provided genre tags, and listening breadth. External app handoffs are excluded rather than guessed.";
        List<Entry> artistTop = topEntries(artists);
        List<Entry> genreTop = topEntries(genres);
        List<Entry> sourceTop = topEntries(sources);
        List<Entry> trackTop = topEntries(tracks);
        StringBuilder result = new StringBuilder();
        if (!artistTop.isEmpty()) {
            if (artistTop.size() == 1) result.append("Your most replayed artist so far is ").append(artistTop.get(0).key).append(".");
            else result.append("You return most often to ").append(artistTop.get(0).key).append(" and ").append(artistTop.get(1).key).append(".");
            result.append(" ");
            result.append(artistTop.size() >= 5
                    ? "Your recorded listening spans multiple artists, while the first remains your strongest repeat signal. "
                    : "Your history is still small enough that this ranking may shift quickly. ");
        } else {
            result.append("Artist metadata is still sparse, so the current pattern is based mainly on track starts. ");
        }
        if (!genreTop.isEmpty()) {
            result.append("The strongest source-provided genre tag is ").append(genreTop.get(0).key)
                    .append("; genre conclusions remain tentative because not all sources expose reliable tags. ");
        } else {
            result.append("There is not enough dependable genre metadata yet to infer a genre pattern. ");
        }
        result.append(!trackTop.isEmpty() && trackTop.get(0).count >= 3
                ? "Repeated track choices suggest some familiar songs are becoming regular returns. "
                : "There is not enough repeat-track evidence yet to distinguish comfort listens from exploration. ");
        if (!sourceTop.isEmpty()) result.append("Most recorded playback comes from ").append(sourceTop.get(0).key).append(". ");
        result.append("This is an explainable on-device statistical interpretation of Spotty sessions, not a cloud-generated personality assessment or a complete profile of you.");
        return result.toString();
    }

    private static void appendTop(StringBuilder out, String title, JSONObject counts, String emptyText) {
        out.append(title).append("\n");
        List<Entry> entries = topEntries(counts);
        if (entries.isEmpty()) {
            out.append("  ").append(emptyText).append("\n\n");
            return;
        }
        for (int i = 0; i < Math.min(5, entries.size()); i++) {
            Entry entry = entries.get(i);
            out.append("  ").append(i + 1).append(". ").append(entry.key).append(" — ")
                    .append(entry.count).append(entry.count == 1 ? " play" : " plays").append("\n");
        }
        out.append("\n");
    }

    private static List<Entry> topEntries(JSONObject counts) {
        List<Entry> result = new ArrayList<>();
        if (counts == null) return result;
        Iterator<String> keys = counts.keys();
        while (keys.hasNext()) {
            String key = keys.next();
            int count = counts.optInt(key, 0);
            if (meaningful(key) && count > 0) result.add(new Entry(key, count));
        }
        Collections.sort(result, new Comparator<Entry>() {
            @Override public int compare(Entry a, Entry b) {
                int countOrder = Integer.compare(b.count, a.count);
                return countOrder != 0 ? countOrder : a.key.compareToIgnoreCase(b.key);
            }
        });
        return result;
    }

    private static void increment(JSONObject root, String category, String value) {
        if (!meaningful(value)) return;
        JSONObject counts = root.optJSONObject(category);
        if (counts == null) counts = new JSONObject();
        int previous = counts.optInt(value, 0);
        try {
            counts.put(value, previous == Integer.MAX_VALUE ? previous : previous + 1);
            if (counts.length() > TOP_MAP_LIMIT) trimLowestCounts(counts);
            root.put(category, counts);
        } catch (JSONException ignored) {}
    }

    private static void trimLowestCounts(JSONObject counts) {
        String lowestKey = null;
        int lowest = Integer.MAX_VALUE;
        Iterator<String> keys = counts.keys();
        while (keys.hasNext()) {
            String key = keys.next();
            int count = counts.optInt(key, 0);
            if (count < lowest) { lowest = count; lowestKey = key; }
        }
        if (lowestKey != null) counts.remove(lowestKey);
    }

    private static JSONArray removeFirst(JSONArray array) {
        JSONArray result = new JSONArray();
        for (int i = 1; i < array.length(); i++) result.put(array.opt(i));
        return result;
    }

    private static JSONObject parseObject(String raw) {
        try { return new JSONObject(raw == null ? "{}" : raw); }
        catch (JSONException ignored) { return new JSONObject(); }
    }

    private static JSONArray parseArray(String raw) {
        try { return new JSONArray(raw == null ? "[]" : raw); }
        catch (JSONException ignored) { return new JSONArray(); }
    }

    private static String dayKey(long millis) {
        SimpleDateFormat format = new SimpleDateFormat("yyyy-MM-dd", Locale.ROOT);
        format.setTimeZone(TimeZone.getDefault());
        return format.format(new Date(millis));
    }

    private static String formatDuration(long millis) {
        long minutes = millis / 60000L;
        long hours = minutes / 60L;
        long rest = minutes % 60L;
        return hours == 0L ? minutes + " minutes total"
                : hours + " h " + String.format(Locale.ROOT, "%02d", rest) + " min total";
    }

    private static String sourceName(String source) {
        String value = clean(source).toLowerCase(Locale.ROOT);
        if (value.equals("local")) return "Local audio";
        if (value.equals("audius")) return "Audius";
        if (value.startsWith("archive")) return "Internet Archive";
        if (value.startsWith("deezer")) return "Deezer previews";
        if (value.equals("radio")) return "Internet radio";
        if (value.equals("playlist") || value.equals("playlist-entry")) return "Imported playlist";
        if (value.isEmpty()) return "Unknown source";
        return value.substring(0, 1).toUpperCase(Locale.ROOT) + value.substring(1);
    }

    private static boolean meaningful(String value) {
        String v = clean(value);
        return !v.isEmpty() && !v.equalsIgnoreCase("Unknown artist")
                && !v.equalsIgnoreCase("Unknown album") && !v.equalsIgnoreCase("Local library")
                && !v.equalsIgnoreCase("Untitled") && !v.equalsIgnoreCase("Untitled track")
                && !v.equalsIgnoreCase("Unknown source");
    }

    private static String clean(String value) { return value == null ? "" : value.trim(); }

    private static final class Entry {
        final String key;
        final int count;
        Entry(String key, int count) { this.key = key; this.count = count; }
    }
}
