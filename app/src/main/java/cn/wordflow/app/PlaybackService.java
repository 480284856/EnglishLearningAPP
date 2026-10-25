package cn.wordflow.app;

import android.app.*;
import android.content.*;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.media.*;
import android.media.session.*;
import android.os.*;
import java.util.*;

/** Owns TTS and audio resources independently of the visible activity. */
public final class PlaybackService extends Service {
  private static final String CHANNEL = "wordflow_playback";
  static final String PLAY = "cn.wordflow.app.PLAY",
      PAUSE = "cn.wordflow.app.PAUSE",
      PRECACHE = "cn.wordflow.app.PRECACHE",
      PREVIOUS = "cn.wordflow.app.PREVIOUS",
      NEXT = "cn.wordflow.app.NEXT",
      STOP = "cn.wordflow.app.STOP";
  private static final int NOTICE = 41;

  final class LocalBinder extends Binder {
    PlaybackService service() {
      return PlaybackService.this;
    }
  }

  interface Listener {
    void changed(State state);
  }

  static final class State {
    final boolean ready, playing, loop, hasDeck, warming;
    final long cacheBytes;
    final int cacheEntries;
    final int deckIndex, wordIndex, phase, repeats, repetition;
    final String temporaryExample;
    final float rate;
    final String message;

    State(PlaybackService s) {
      ready = s.ready;
      playing = s.playing;
      warming = s.warming;
      cacheBytes = s.cacheBytes;
      cacheEntries = s.cacheEntries;
      loop = s.loop;
      hasDeck = s.deck != null;
      deckIndex = s.deckIndex;
      wordIndex = s.cursor.word;
      phase = s.cursor.phase;
      rate = s.rate;
      message = s.message;
      repeats = s.repeats;
      repetition = s.cursor.repetition;
      temporaryExample = s.temporaryExample;
    }
  }

  private final LocalBinder binder = new LocalBinder();
  private final List<Listener> listeners = new ArrayList<>();
  private final Handler handler = new Handler(Looper.getMainLooper());
  private final PlaybackSequence cursor = new PlaybackSequence();
  private WordStore.Deck deck;
  private int deckIndex, repeats;
  private String temporaryExample = "", previousDynamic = "";
  private DynamicExamples dynamicExamples;
  private ListeningStats listeningStats;
  private boolean ready, playing, foreground, destroyed, loop, warming;
  private long cacheBytes;
  private int cacheEntries, cacheDone, cacheTotal, warmGeneration;
  private String warmToken = "";
  private float rate;
  private String message = "正在准备 Kokoro 离线语音…";
  private KokoroSpeechEngine tts;
  private AudioManager audio;
  private AudioFocusRequest focus;
  private PowerManager.WakeLock wakeLock;
  private MediaSession session;
  private Runnable pending;
  private final BroadcastReceiver noisy =
      new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
          if (playing) pause("耳机已断开 · 已暂停");
        }
      };

  @Override
  public void onCreate() {
    super.onCreate();
    rate = getSharedPreferences("playback", MODE_PRIVATE).getFloat("rate", 0.85f);
    repeats = getSharedPreferences("playback", MODE_PRIVATE).getInt("repeats", 1);
    dynamicExamples = new DynamicExamples(this);
    listeningStats = new ListeningStats(this);
    audio = (AudioManager) getSystemService(AUDIO_SERVICE);
    AudioAttributes attributes =
        new AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .build();
    focus =
        new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(attributes)
            .setOnAudioFocusChangeListener(
                change -> {
                  if (playing && change < 0) pause("音频被占用 · 已暂停");
                },
                handler)
            .build();
    wakeLock =
        ((PowerManager) getSystemService(POWER_SERVICE))
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "WordFlow:Playback");
    wakeLock.setReferenceCounted(false);
    registerReceiver(noisy, new IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY));
    NotificationChannel channel =
        new NotificationChannel(CHANNEL, "词表播放", NotificationManager.IMPORTANCE_LOW);
    channel.setDescription("息屏听读及播放控制");
    channel.setSound(null, null);
    getSystemService(NotificationManager.class).createNotificationChannel(channel);
    session = new MediaSession(this, "WordFlow");
    session.setCallback(
        new MediaSession.Callback() {
          @Override
          public void onPlay() {
            startFromControl();
          }

          @Override
          public void onPause() {
            pause("已暂停 · 继续时重读当前片段");
          }

          @Override
          public void onSkipToNext() {
            move(1);
          }

          @Override
          public void onSkipToPrevious() {
            move(-1);
          }

          @Override
          public void onStop() {
            stopPlayback();
          }
        },
        handler);
    tts =
        new KokoroSpeechEngine(
            this,
            new KokoroSpeechEngine.Listener() {
              @Override
              public void audioProgress(long endMillis, long renderedMillis) {
                listeningStats.record(endMillis, renderedMillis);
              }

              @Override
              public void ready() {
                if (destroyed) return;
                ready = true;
                message = "Kokoro 离线英语 · 支持息屏听读";
                publish();
              }

              @Override
              public void initializationFailed() {
                if (destroyed) return;
                ready = false;
                message = "Kokoro 模型加载失败，请检查剩余空间并重新打开应用";
                publish();
              }

              @Override
              public void synthesizing(String token) {
                if (playing && cursor.matches(token)) {
                  message = "Kokoro 正在离线合成，请稍候…";
                  publish();
                }
              }

              @Override
              public void audioStarted(String token, boolean cached) {
                if (playing && cursor.matches(token)) {
                  message = cached ? "正在播放缓存音频 · 无需重新合成" : "Kokoro 息屏听读中 · 单词 → 例句";
                  publish();
                }
              }

              @Override
              public void keepAlive(String token) {
                if (playing && cursor.matches(token)) wakeLock.acquire(30 * 60 * 1000L);
              }

              @Override
              public void cacheStats(SpeechCache.Stats stats) {
                cacheBytes = stats.bytes;
                cacheEntries = stats.entries;
                publish();
              }

              @Override
              public void cacheCleared(boolean success) {
                message = success ? "语音缓存已清理 · 词表和模型已保留" : "缓存清理失败，请稍后重试";
                publish();
              }

              @Override
              public void warmProgress(String token, int done, int total) {
                if (!warming || !token.equals(warmToken)) return;
                cacheDone = done;
                cacheTotal = total;
                wakeLock.acquire(30 * 60 * 1000L);
                message = "正在预缓存 " + done + " / " + total + " 段 · 可取消";
                publish();
              }

              @Override
              public void warmFinished(String token, String result, int done, int total) {
                if (!warming || !token.equals(warmToken)) return;
                String reason =
                    result.equals("done")
                        ? "预缓存完成 · 共 " + done + " 段（含已有缓存）"
                        : result.equals("full")
                            ? "已缓存 " + done + " 段 · 256 MB 空间已满，已停止"
                            : "缓存失败，已保留完成的 " + done + " 段，请检查剩余空间后重试";
                finish(reason);
              }

              @Override
              public void completed(String token) {
                PlaybackService.this.completed(token);
              }

              @Override
              public void failed(String token) {
                speechError(token);
              }
            });
  }

  @Override
  public IBinder onBind(Intent intent) {
    return binder;
  }

  boolean usesDeck(WordStore.Deck other) {
    return deck != null && deck.sameContent(other);
  }

  void addListener(Listener listener) {
    listeners.add(listener);
    listener.changed(new State(this));
  }

  void removeListener(Listener listener) {
    listeners.remove(listener);
  }

  void selectDeck(int index, WordStore.Deck selected, int word, int phase) {
    boolean wasWarming = warming;
    playing = false;
    warming = false;
    cancelSpeech();
    releaseAudio();
    if (wasWarming) leaveForeground();
    if (ready) message = "Kokoro 离线英语 · 支持息屏听读";
    deckIndex = index;
    deck = selected;
    cursor.reset(
        selected == null ? 0 : Math.max(0, Math.min(word, selected.entries.size() - 1)),
        selected == null
            ? 0
            : Math.max(
                0,
                Math.min(
                    phase,
                    selected
                            .entries
                            .get(Math.max(0, Math.min(word, selected.entries.size() - 1)))
                            .fragments()
                            .size()
                        - 1)));
    if (selected == null) finish("播放已停止");
    else publish();
  }

  void setRate(float value) {
    rate = Math.max(0.5f, Math.min(1.5f, value));
    getSharedPreferences("playback", MODE_PRIVATE).edit().putFloat("rate", rate).apply();
    if (warming) finish("语速已改变，预缓存已停止 · 已完成的音频已保留");
    if (playing) {
      cancelSpeech();
      speak();
    } else publish();
  }

  void setRepeats(int value) {
    if (value < 1 || value > 3 || repeats == value) return;
    repeats = value;
    getSharedPreferences("playback", MODE_PRIVATE).edit().putInt("repeats", value).apply();
    cursor.repetition = 0;
    if (playing) {
      cancelSpeech();
      speak();
    } else publish();
  }

  void setLoop(boolean value) {
    if (loop != value) {
      loop = value;
      publish();
    }
  }

  private void startFromControl() {
    // Foreground promotion precedes the Android 15 audio-focus request.
    if (!ready || deck == null) return;
    startForegroundService(new Intent(this, PlaybackService.class).setAction(PLAY));
  }

  private void promote(boolean forCache) {
    if (foreground) stopForeground(STOP_FOREGROUND_REMOVE);
    if (Build.VERSION.SDK_INT >= 29)
      startForeground(
          NOTICE,
          notification(),
          forCache
              ? ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
              : ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK);
    else startForeground(NOTICE, notification());
    foreground = true;
  }

  private void leaveForeground() {
    session.setActive(false);
    if (foreground) {
      stopForeground(STOP_FOREGROUND_REMOVE);
      foreground = false;
    }
    stopSelf();
  }

  @Override
  public int onStartCommand(Intent intent, int flags, int startId) {
    String action = intent == null ? STOP : intent.getAction();
    if (PLAY.equals(action)) {
      // Also satisfies the promotion deadline if a stale notification restarts us.
      if (warming) {
        warming = false;
        cancelSpeech();
        releaseAudio();
      }
      promote(false);
      if (!ready || deck == null) {
        stopPlayback();
        return START_NOT_STICKY;
      }
      if (!playing) {
        if (audio.requestAudioFocus(focus) != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
          finish("暂时无法获取音频焦点，请稍后重试");
        } else {
          playing = true;
          session.setActive(true);
          message = "息屏听读中 · 单词 → 例句";
          speak();
        }
      }
    } else if (PRECACHE.equals(action)) {
      playing = false;
      warming = true;
      cancelSpeech();
      releaseAudio();
      session.setActive(false);
      cacheDone = 0;
      cacheTotal = 0;
      if (deck != null) for (WordEntry entry : deck.entries) cacheTotal += entry.fragments().size();
      message = "正在准备预缓存词表…";
      promote(true);
      if (!ready || deck == null) {
        finish("请等待 Kokoro 准备完成后重试");
        return START_NOT_STICKY;
      }
      wakeLock.acquire(30 * 60 * 1000L);
      warmToken = "cache:" + (++warmGeneration);
      List<String> texts = new ArrayList<>();
      for (WordEntry entry : deck.entries) {
        texts.addAll(entry.fragments());
      }
      publish();
      tts.warm(texts, rate, warmToken);
    } else if (PAUSE.equals(action)) pause("已暂停 · 继续时重读当前片段");
    else if (NEXT.equals(action)) move(1);
    else if (PREVIOUS.equals(action)) move(-1);
    else stopPlayback();
    return START_NOT_STICKY;
  }

  void pause(String reason) {
    boolean wasWarming = warming;
    playing = false;
    warming = false;
    cancelSpeech();
    releaseAudio();
    message = reason;
    if (wasWarming) leaveForeground();
    publish();
  }

  private void cancelSpeech() {
    cursor.cancel();
    temporaryExample = "";
    if (dynamicExamples != null) dynamicExamples.cancel();
    if (pending != null) {
      handler.removeCallbacks(pending);
      pending = null;
    }
    if (tts != null) tts.stop();
  }

  private void releaseAudio() {
    if (wakeLock.isHeld()) wakeLock.release();
    audio.abandonAudioFocusRequest(focus);
  }

  void move(int delta) {
    if (deck == null) return;
    if (warming) finish("已停止预缓存 · 已完成的音频已保留");
    boolean resume = playing;
    cancelSpeech();
    cursor.reset(Math.max(0, Math.min(deck.entries.size() - 1, cursor.word + delta)), 0);
    if (resume) speak();
    else publish();
  }

  private void speak() {
    if (!playing || deck == null || destroyed) return;
    // Renew for each fragment, including its following gap. The bound avoids a
    // permanent lock if an inference or audio-device callback stalls.
    wakeLock.acquire(30 * 60 * 1000L);
    String token = cursor.begin();
    publish();
    WordEntry entry = deck.entries.get(cursor.word);
    if (cursor.phase >= entry.fragments().size()) {
      // A cancelled request or transient sentence is regenerated on resume.
      requestDynamic(token, entry);
      return;
    }
    tts.speak(entry.fragments().get(cursor.phase), rate, token);
  }

  private void completed(String token) {
    if (!playing || !cursor.matches(token)) return;
    WordEntry entry = deck.entries.get(cursor.word);
    if (cursor.phase == entry.fragments().size() - 1) {
      requestDynamic(token, entry);
      return;
    }
    advance(token);
  }

  private void requestDynamic(String token, WordEntry entry) {
    message = "正在检查网络并生成临时例句…";
    publish();
    String avoid = String.join("\n", entry.fragments()) + "\n" + previousDynamic;
    // Bound prompt size; previous output is session memory, never a saved example.
    if (avoid.length() > 7000) avoid = avoid.substring(avoid.length() - 7000);
    dynamicExamples.generate(
        entry.word,
        avoid,
        (result, reason) -> {
          if (!playing || !cursor.matches(token)) return;
          if (result.isEmpty()) {
            if (!reason.isEmpty()) message = reason;
            advance(token);
            return;
          }
          temporaryExample = result;
          previousDynamic = result;
          cursor.phase = entry.fragments().size();
          String generatedToken = cursor.begin();
          message = "临时 AI 例句 · 本次播放后丢弃";
          publish();
          tts.speakTransient(result, rate, generatedToken);
        });
  }

  private void advance(String token) {
    // Dynamic completion is terminal for this pass; it never requests another sentence recursively.
    int last = Math.max(cursor.phase, deck.entries.get(cursor.word).fragments().size() - 1);
    temporaryExample = "";
    if (!cursor.advance(token, deck.entries.size(), loop, last, repeats)) {
      finish("本轮词表已播放完成");
      return;
    }
    publish();
    pending =
        () -> {
          pending = null;
          if (playing) speak();
        };
    handler.postDelayed(pending, 450);
  }

  private void speechError(String token) {
    if (playing && cursor.matches(token)) pause("Kokoro 朗读失败，请重试或重新打开应用");
  }

  private void finish(String reason) {
    pause(reason);
    leaveForeground();
  }

  private void stopPlayback() {
    finish(warming ? "预缓存已取消 · 已完成的音频已保留" : "播放已停止");
  }

  void cancelWarm() {
    if (warming) finish("预缓存已取消 · 已完成的音频已保留");
  }

  void clearCache() {
    finish("正在清理语音缓存…");
    tts.clearCache();
  }

  @Override
  public void onTimeout(int startId, int fgsType) {
    finish("缓存达到系统时限 · 已完成的音频已保留");
  }

  // MediaSession playback notifications are exempt on Android 13. Ordinary
  // precache progress notifications are updated only with notification permission.
  @android.annotation.SuppressLint("NotificationPermission")
  private void publish() {
    if (destroyed) return;
    long actions =
        PlaybackState.ACTION_PLAY
            | PlaybackState.ACTION_PAUSE
            | PlaybackState.ACTION_SKIP_TO_NEXT
            | PlaybackState.ACTION_SKIP_TO_PREVIOUS
            | PlaybackState.ACTION_STOP;
    session.setPlaybackState(
        new PlaybackState.Builder()
            .setActions(actions)
            .setState(
                playing ? PlaybackState.STATE_PLAYING : PlaybackState.STATE_PAUSED,
                PlaybackState.PLAYBACK_POSITION_UNKNOWN,
                playing ? rate : 0)
            .build());
    if (deck != null) {
      WordEntry entry = deck.entries.get(cursor.word);
      session.setMetadata(
          new MediaMetadata.Builder()
              .putString(MediaMetadata.METADATA_KEY_TITLE, entry.word)
              .putString(MediaMetadata.METADATA_KEY_ARTIST, deck.name)
              .putString(MediaMetadata.METADATA_KEY_DISPLAY_DESCRIPTION, entry.sentence)
              .build());
    }
    if (foreground
        && (!warming
            || Build.VERSION.SDK_INT < 33
            || checkSelfPermission("android.permission.POST_NOTIFICATIONS")
                == PackageManager.PERMISSION_GRANTED))
      getSystemService(NotificationManager.class).notify(NOTICE, notification());
    State state = new State(this);
    for (Listener listener : new ArrayList<>(listeners)) listener.changed(state);
  }

  private PendingIntent action(String name, int id) {
    Intent intent = new Intent(this, PlaybackService.class).setAction(name);
    return PLAY.equals(name)
        ? PendingIntent.getForegroundService(
            this, id, intent, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT)
        : PendingIntent.getService(
            this, id, intent, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
  }

  private Notification notification() {
    PendingIntent open =
        PendingIntent.getActivity(
            this,
            0,
            new Intent(this, MainActivity.class)
                .putExtra("showPlayer", true)
                .setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
    if (warming) {
      return new Notification.Builder(this, CHANNEL)
          .setSmallIcon(R.drawable.ic_playback)
          .setContentTitle("正在预缓存词表 · " + (deck == null ? "" : deck.name))
          .setContentText(cacheDone + " / " + cacheTotal + " 段 · 已完成的缓存会保留")
          .setProgress(cacheTotal, cacheDone, cacheTotal <= 0)
          .setContentIntent(open)
          .setOnlyAlertOnce(true)
          .setOngoing(true)
          .addAction(
              new Notification.Action.Builder(
                      android.R.drawable.ic_menu_close_clear_cancel, "取消缓存", action(STOP, 4))
                  .build())
          .build();
    }
    String title = deck == null ? "词间 · WordFlow" : deck.entries.get(cursor.word).word;
    String detail =
        deck == null
            ? "准备播放词表"
            : deck.name
                + " · "
                + (cursor.word + 1)
                + "/"
                + deck.entries.size()
                + (playing ? " · 正在听读" : " · 已暂停");
    return new Notification.Builder(this, CHANNEL)
        .setSmallIcon(R.drawable.ic_playback)
        .setContentTitle(title)
        .setContentText(detail)
        .setContentIntent(open)
        .setOnlyAlertOnce(true)
        .setOngoing(playing)
        .setVisibility(Notification.VISIBILITY_PUBLIC)
        .setCategory(Notification.CATEGORY_TRANSPORT)
        .addAction(
            new Notification.Action.Builder(
                    android.R.drawable.ic_media_previous, "上一词", action(PREVIOUS, 1))
                .build())
        .addAction(
            new Notification.Action.Builder(
                    playing ? android.R.drawable.ic_media_pause : android.R.drawable.ic_media_play,
                    playing ? "暂停" : "继续",
                    action(playing ? PAUSE : PLAY, 2))
                .build())
        .addAction(
            new Notification.Action.Builder(
                    android.R.drawable.ic_media_next, "下一词", action(NEXT, 3))
                .build())
        .addAction(
            new Notification.Action.Builder(
                    android.R.drawable.ic_menu_close_clear_cancel, "停止", action(STOP, 4))
                .build())
        .setStyle(
            new Notification.MediaStyle()
                .setMediaSession(session.getSessionToken())
                .setShowActionsInCompactView(0, 1, 2))
        .setDeleteIntent(action(STOP, 4))
        .build();
  }

  @Override
  public void onDestroy() {
    destroyed = true;
    playing = false;
    cancelSpeech();
    releaseAudio();
    unregisterReceiver(noisy);
    session.release();
    dynamicExamples.close();
    tts.close();
    handler.removeCallbacksAndMessages(null);
    listeners.clear();
    if (foreground) stopForeground(STOP_FOREGROUND_REMOVE);
    super.onDestroy();
  }
}
