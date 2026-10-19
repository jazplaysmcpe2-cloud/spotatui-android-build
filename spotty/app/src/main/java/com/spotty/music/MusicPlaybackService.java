package com.spotty.music;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.media.AudioAttributes;
import android.media.AudioFocusRequest;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;


/**
 * Owns the active audio stream outside the Activity lifecycle. Playback starts only
 * after an explicit user tap and remains visible as an Android media notification.
 */
public final class MusicPlaybackService extends Service {
    public static final String ACTION_PLAY = "com.spotty.music.action.PLAY";
    public static final String ACTION_TOGGLE = "com.spotty.music.action.TOGGLE";
    public static final String ACTION_STOP = "com.spotty.music.action.STOP";
    public static final String ACTION_STATE = "com.spotty.music.action.PLAYBACK_STATE";
    private static final String CHANNEL_ID = "spotty_playback";
    private static final int NOTIFICATION_ID = 7401;
    private static final int MAX_RETRIES = 2;
    private static volatile MusicPlaybackService instance;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private MediaPlayer mediaPlayer;
    private String currentUrl = "";
    private String currentTitle = "Spotty";
    private String currentArtist = "";
    private String state = "stopped";
    private boolean preparing;
    private boolean resumeOnFocusGain;
    private int retryCount;
    private AudioManager audioManager;
    private AudioFocusRequest audioFocusRequest;
    private PowerManager.WakeLock wakeLock;

    private final AudioManager.OnAudioFocusChangeListener focusListener = change -> {
        if (mediaPlayer == null) return;
        if (change == AudioManager.AUDIOFOCUS_GAIN) {
            mediaPlayer.setVolume(1f, 1f);
            if (resumeOnFocusGain) {
                resumeOnFocusGain = false;
                try { mediaPlayer.start(); setState("playing", "Playing"); }
                catch (IllegalStateException ignored) { scheduleRetry("Audio focus returned"); }
            }
        } else if (change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK) {
            try { mediaPlayer.setVolume(0.25f, 0.25f); } catch (IllegalStateException ignored) {}
        } else if (change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT) {
            try {
                if (mediaPlayer.isPlaying()) {
                    mediaPlayer.pause();
                    resumeOnFocusGain = true;
                    releaseWakeLock();
                    setState("paused", "Paused for another audio app");
                }
            } catch (IllegalStateException ignored) {}
        } else if (change == AudioManager.AUDIOFOCUS_LOSS) {
            try {
                if (mediaPlayer.isPlaying()) mediaPlayer.pause();
            } catch (IllegalStateException ignored) {}
            resumeOnFocusGain = false;
            releaseWakeLock();
            setState("paused", "Paused by audio focus");
        }
    };

    public static void play(Context context, String url, String title, String artist) {
        Intent intent = new Intent(context, MusicPlaybackService.class)
                .setAction(ACTION_PLAY)
                .putExtra("url", url)
                .putExtra("title", title)
                .putExtra("artist", artist);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) context.startForegroundService(intent);
        else context.startService(intent);
    }

    public static void toggle(Context context) {
        MusicPlaybackService service = instance;
        if (service != null) service.togglePlayback();
    }

    public static void stop(Context context) {
        MusicPlaybackService service = instance;
        if (service != null) service.stopPlaybackAndSelf();
    }

    public static boolean hasTrack() {
        MusicPlaybackService service = instance;
        return service != null && !service.currentUrl.isEmpty();
    }

    public static String currentUrl() {
        MusicPlaybackService service = instance;
        return service == null ? "" : service.currentUrl;
    }

    public static String currentTitle() {
        MusicPlaybackService service = instance;
        return service == null ? "Spotty" : service.currentTitle;
    }

    public static String currentArtist() {
        MusicPlaybackService service = instance;
        return service == null ? "" : service.currentArtist;
    }

    public static boolean isPreparing() {
        MusicPlaybackService service = instance;
        return service != null && service.preparing;
    }

    public static boolean isPlaying() {
        MusicPlaybackService service = instance;
        if (service == null || service.mediaPlayer == null) return false;
        try { return service.mediaPlayer.isPlaying(); }
        catch (IllegalStateException ignored) { return false; }
    }

    public static int currentPosition() {
        MusicPlaybackService service = instance;
        if (service == null || service.mediaPlayer == null || service.preparing) return 0;
        try { return service.mediaPlayer.getCurrentPosition(); }
        catch (IllegalStateException ignored) { return 0; }
    }

    public static void seekToStart() {
        MusicPlaybackService service = instance;
        if (service == null || service.mediaPlayer == null || service.preparing) return;
        try { service.mediaPlayer.seekTo(0); } catch (IllegalStateException ignored) {}
    }

    @Override public void onCreate() {
        super.onCreate();
        instance = this;
        createNotificationChannel();
        audioManager = (AudioManager) getSystemService(AUDIO_SERVICE);
        PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, getPackageName() + ":audio-playback");
        wakeLock.setReferenceCounted(false);
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null ? null : intent.getAction();
        if (ACTION_STOP.equals(action)) {
            stopPlaybackAndSelf();
            return START_NOT_STICKY;
        }
        if (ACTION_TOGGLE.equals(action)) {
            togglePlayback();
            return START_STICKY;
        }
        if (ACTION_PLAY.equals(action)) {
            currentUrl = intent.getStringExtra("url");
            currentTitle = safe(intent.getStringExtra("title"), "Untitled track");
            currentArtist = safe(intent.getStringExtra("artist"), "");
            retryCount = 0;
            getPreferences().edit()
                    .putString("last_url", currentUrl)
                    .putString("last_title", currentTitle)
                    .putString("last_artist", currentArtist)
                    .putBoolean("playback_active", true)
                    .apply();
        } else if (intent == null) {
            if (!getPreferences().getBoolean("playback_active", false)) {
                stopSelf();
                return START_NOT_STICKY;
            }
            currentUrl = getPreferences().getString("last_url", "");
            currentTitle = getPreferences().getString("last_title", "Spotty");
            currentArtist = getPreferences().getString("last_artist", "");
            if (currentUrl.isEmpty()) {
                stopSelf();
                return START_NOT_STICKY;
            }
        } else {
            return START_NOT_STICKY;
        }

        startForeground(NOTIFICATION_ID, buildNotification("Connecting"));
        if (requestAudioFocus()) prepareCurrentStream();
        else failAndStop("Another app currently owns audio focus");
        return START_STICKY;
    }

    private boolean requestAudioFocus() {
        if (audioManager == null) return true;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            AudioAttributes attributes = new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build();
            audioFocusRequest = new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                    .setAudioAttributes(attributes)
                    .setOnAudioFocusChangeListener(focusListener, handler)
                    .setWillPauseWhenDucked(false)
                    .build();
            return audioManager.requestAudioFocus(audioFocusRequest) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED;
        }
        return audioManager.requestAudioFocus(focusListener, AudioManager.STREAM_MUSIC,
                AudioManager.AUDIOFOCUS_GAIN) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED;
    }

    private void prepareCurrentStream() {
        releaseCurrentPlayer();
        preparing = true;
        resumeOnFocusGain = false;
        setState("connecting", retryCount == 0 ? "Connecting" : "Reconnecting");
        MediaPlayer next = new MediaPlayer();
        mediaPlayer = next;
        next.setAudioAttributes(new AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                .build());
        next.setOnPreparedListener(mp -> {
            if (mediaPlayer != mp) return;
            preparing = false;
            try {
                mp.start();
                acquireWakeLock();
                getPreferences().edit().putBoolean("playback_active", true).apply();
                setState("playing", "Playing");
            } catch (IllegalStateException e) {
                scheduleRetry("Could not start stream");
            }
        });
        next.setOnCompletionListener(mp -> {
            if (mediaPlayer != mp) return;
            preparing = false;
            setState("completed", "Track finished");
            releaseCurrentPlayer();
            releaseWakeLock();
            getPreferences().edit().putBoolean("playback_active", false).apply();
            stopForeground(STOP_FOREGROUND_REMOVE);
            stopSelf();
        });
        next.setOnErrorListener((mp, what, extra) -> {
            if (mediaPlayer == mp) scheduleRetry("Stream disconnected");
            return true;
        });
        try {
            Uri uri = Uri.parse(currentUrl);
            if ("content".equalsIgnoreCase(uri.getScheme())) next.setDataSource(this, uri);
            else next.setDataSource(currentUrl);
            next.prepareAsync();
        } catch (Exception e) {
            scheduleRetry("Stream could not be opened");
        }
    }

    private void scheduleRetry(String reason) {
        preparing = false;
        releaseCurrentPlayer();
        releaseWakeLock();
        if (retryCount < MAX_RETRIES && !currentUrl.isEmpty()) {
            retryCount++;
            setState("connecting", reason + " • retry " + retryCount + "/" + MAX_RETRIES);
            handler.postDelayed(this::prepareCurrentStream, retryCount == 1 ? 1200L : 3000L);
        } else {
            failAndStop(reason + " after retries");
        }
    }

    private void togglePlayback() {
        if (mediaPlayer == null) {
            broadcast("error", "No active track");
            return;
        }
        if (preparing) return;
        try {
            if (mediaPlayer.isPlaying()) {
                mediaPlayer.pause();
                releaseWakeLock();
                getPreferences().edit().putBoolean("playback_active", false).apply();
                setState("paused", "Paused");
            } else {
                if (!requestAudioFocus()) {
                    broadcast("error", "Audio focus unavailable");
                    return;
                }
                mediaPlayer.start();
                acquireWakeLock();
                getPreferences().edit().putBoolean("playback_active", true).apply();
                setState("playing", "Playing");
            }
        } catch (IllegalStateException e) {
            scheduleRetry("Player state error");
        }
    }

    private void setState(String value, String message) {
        state = value;
        updateNotification(message);
        broadcast(value, message);
    }

    private void broadcast(String value, String message) {
        Intent intent = new Intent(ACTION_STATE).setPackage(getPackageName())
                .putExtra("state", value)
                .putExtra("message", message)
                .putExtra("title", currentTitle)
                .putExtra("artist", currentArtist);
        sendBroadcast(intent);
    }

    private Notification buildNotification(String message) {
        Intent open = new Intent(this, MainActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent content = PendingIntent.getActivity(this, 7401, open,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Intent toggle = new Intent(this, MusicPlaybackService.class).setAction(ACTION_TOGGLE);
        PendingIntent togglePending = PendingIntent.getService(this, 7402, toggle,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Intent stop = new Intent(this, MusicPlaybackService.class).setAction(ACTION_STOP);
        PendingIntent stopPending = PendingIntent.getService(this, 7403, stop,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        boolean active = "playing".equals(state) || "connecting".equals(state);
        Notification.Builder builder = new Notification.Builder(this, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_media_play)
                .setContentTitle(currentTitle)
                .setContentText((currentArtist.isEmpty() ? "Spotty" : currentArtist) + " • " + message)
                .setContentIntent(content)
                .setCategory(Notification.CATEGORY_TRANSPORT)
                .setVisibility(Notification.VISIBILITY_PUBLIC)
                .setOngoing(active)
                .setOnlyAlertOnce(true)
                .addAction(new Notification.Action.Builder(
                        active ? android.R.drawable.ic_media_pause : android.R.drawable.ic_media_play,
                        active ? "Pause" : "Resume", togglePending).build())
                .addAction(new Notification.Action.Builder(android.R.drawable.ic_menu_close_clear_cancel,
                        "Stop", stopPending).build());
        builder.setStyle(new Notification.MediaStyle().setShowActionsInCompactView(0, 1));
        return builder.build();
    }

    private void updateNotification(String message) {
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (nm != null && instance == this) nm.notify(NOTIFICATION_ID, buildNotification(message));
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(CHANNEL_ID, "Spotty playback",
                    NotificationManager.IMPORTANCE_LOW);
            channel.setDescription("Shows the current audio track and playback controls.");
            NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            if (nm != null) nm.createNotificationChannel(channel);
        }
    }

    private void acquireWakeLock() {
        try {
            if (wakeLock != null && !wakeLock.isHeld()) wakeLock.acquire();
        } catch (RuntimeException ignored) {}
    }

    private void releaseWakeLock() {
        try { if (wakeLock != null && wakeLock.isHeld()) wakeLock.release(); }
        catch (RuntimeException ignored) {}
    }

    private void releaseCurrentPlayer() {
        MediaPlayer old = mediaPlayer;
        mediaPlayer = null;
        if (old != null) {
            try { old.setOnPreparedListener(null); } catch (Exception ignored) {}
            try { old.setOnCompletionListener(null); } catch (Exception ignored) {}
            try { old.setOnErrorListener(null); } catch (Exception ignored) {}
            try { old.stop(); } catch (Exception ignored) {}
            try { old.reset(); } catch (Exception ignored) {}
            try { old.release(); } catch (Exception ignored) {}
        }
    }

    private void failAndStop(String message) {
        preparing = false;
        setState("error", message);
        getPreferences().edit().putBoolean("playback_active", false).apply();
        releaseCurrentPlayer();
        releaseWakeLock();
        abandonAudioFocus();
        stopForeground(STOP_FOREGROUND_REMOVE);
        stopSelf();
    }

    private void stopPlaybackAndSelf() {
        handler.removeCallbacksAndMessages(null);
        setState("stopped", "Stopped");
        getPreferences().edit().putBoolean("playback_active", false).apply();
        releaseCurrentPlayer();
        releaseWakeLock();
        abandonAudioFocus();
        stopForeground(STOP_FOREGROUND_REMOVE);
        stopSelf();
    }

    private void abandonAudioFocus() {
        if (audioManager == null) return;
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && audioFocusRequest != null)
                audioManager.abandonAudioFocusRequest(audioFocusRequest);
            else audioManager.abandonAudioFocus(focusListener);
        } catch (RuntimeException ignored) {}
    }

    private android.content.SharedPreferences getPreferences() {
        return getSharedPreferences("spotty_preferences", MODE_PRIVATE);
    }

    private String safe(String value, String fallback) {
        return value == null || value.trim().isEmpty() ? fallback : value.trim();
    }

    @Override public void onTaskRemoved(Intent rootIntent) {
        // User may leave Spotty while listening; don't stop intentional audio playback.
        super.onTaskRemoved(rootIntent);
    }

    @Override public void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        releaseCurrentPlayer();
        releaseWakeLock();
        abandonAudioFocus();
        if (instance == this) instance = null;
        super.onDestroy();
    }

    @Override public IBinder onBind(Intent intent) { return null; }
}
