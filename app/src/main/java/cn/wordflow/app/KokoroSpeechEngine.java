package cn.wordflow.app;

import android.content.Context;
import android.content.res.AssetManager;
import android.media.*;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import com.k2fsa.sherpa.onnx.*;
import java.io.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;

/** Offline Kokoro synthesis and PCM playback; the native model stays on one worker. */
final class KokoroSpeechEngine {
  interface Listener {
    void audioProgress(long endMillis, long renderedMillis);

    void ready();

    void initializationFailed();

    void synthesizing(String token);

    void audioStarted(String token, boolean cached);

    void keepAlive(String token);

    void cacheStats(SpeechCache.Stats stats);

    void cacheCleared(boolean success);

    void warmProgress(String token, int done, int total);

    void warmFinished(String token, String result, int done, int total);

    void completed(String token);

    void failed(String token);
  }

  private static final Object INSTALL_LOCK = new Object();
  private static final Semaphore MODEL_SLOT = new Semaphore(1);
  private static final String MODEL_VERSION = "kokoro-int8-en-v0_19-c9f0dd39";
  private final ExecutorService worker = Executors.newSingleThreadExecutor();
  private final Handler main = new Handler(Looper.getMainLooper());
  private final Handler telemetry = new Handler(Looper.getMainLooper());
  private final AtomicLong request = new AtomicLong();
  private final Object audioLock = new Object();
  private final Listener listener;
  private final Context context;
  private final SpeechCache cache;
  private volatile boolean closed;
  private boolean ownsModelSlot;
  private OfflineTts model; // accessed exclusively on worker
  private RenderedAudioMeter audioMeter;
  private AudioTrack track; // publish/release under audioLock; writes only on worker

  KokoroSpeechEngine(Context context, Listener listener) {
    this.context = context.getApplicationContext();
    this.listener = listener;
    cache = new SpeechCache(new File(this.context.getCacheDir(), "kokoro-audio-v1"));
    worker.execute(
        () -> {
          try {
            reportCacheStats();
            MODEL_SLOT.acquire();
            ownsModelSlot = true;
            try {
              cache.cleanTemporary();
            } catch (IOException ignored) {
            }
            File files = prepareModel();
            if (closed) return;
            OfflineTtsConfig config = new OfflineTtsConfig();
            config.getModel().setNumThreads(2);
            config.getModel().setProvider("cpu");
            OfflineTtsKokoroModelConfig kokoro = config.getModel().getKokoro();
            kokoro.setModel(new File(files, "model.int8.onnx").getAbsolutePath());
            kokoro.setVoices(new File(files, "voices.bin").getAbsolutePath());
            kokoro.setTokens(new File(files, "tokens.txt").getAbsolutePath());
            kokoro.setDataDir(new File(files, "espeak-ng-data").getAbsolutePath());
            kokoro.setLang("en-us");
            model = new OfflineTts(null, config);
            if (model.sampleRate() <= 0 || model.numSpeakers() <= 0)
              throw new IOException("Invalid model");
            main.post(
                () -> {
                  if (!closed) listener.ready();
                });
          } catch (Exception | LinkageError | OutOfMemoryError e) {
            releaseModel();
            android.util.Log.e("WordFlowKokoro", "Kokoro initialization failed", e);
            main.post(
                () -> {
                  if (!closed) listener.initializationFailed();
                });
          }
        });
  }

  void speak(String text, float speed, String token) {
    stop();
    long id = request.get();
    worker.execute(() -> synthesize(text, speed, token, id, true));
  }

  void speakTransient(String text, float speed, String token) {
    stop();
    long id = request.get();
    worker.execute(() -> synthesize(text, speed, token, id, false));
  }

  private boolean current(long id) {
    return !closed && request.get() == id;
  }

  void stop() {
    request.incrementAndGet();
    synchronized (audioLock) {
      if (track != null) {
        reportRendered(track);
        try {
          track.pause();
          track.flush();
          track.stop();
        } catch (IllegalStateException ignored) {
        }
      }
    }
  }

  private String cacheKey(String text, float speed) {
    return SpeechCache.key(MODEL_VERSION, text, 0, speed);
  }

  private void reportCacheStats() {
    SpeechCache.Stats stats = cache.stats();
    main.post(
        () -> {
          if (!closed) listener.cacheStats(stats);
        });
  }

  private static void discard(SpeechCache.Writer writer) {
    if (writer != null)
      try {
        writer.close();
      } catch (IOException ignored) {
      }
  }

  private AudioTrack output(int sampleRate, long id) {
    int minimum =
        AudioTrack.getMinBufferSize(
            sampleRate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_FLOAT);
    if (minimum <= 0) throw new IllegalStateException("Unsupported audio output");
    AudioTrack result =
        new AudioTrack.Builder()
            .setAudioAttributes(
                new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build())
            .setAudioFormat(
                new AudioFormat.Builder()
                    .setSampleRate(sampleRate)
                    .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build())
            .setTransferMode(AudioTrack.MODE_STREAM)
            .setBufferSizeInBytes(Math.max(minimum, sampleRate))
            .build();
    synchronized (audioLock) {
      if (!current(id)) {
        result.release();
        return null;
      }
      track = result;
      audioMeter = new RenderedAudioMeter(sampleRate);
      result.play();
      main.post(() -> monitor(result));
    }
    return result;
  }

  // Only AudioTrack's rendered head counts: synthesis, buffering, gaps and network wait do not.
  private void reportRendered(AudioTrack sink) {
    if (track != sink || audioMeter == null) return;
    long delta = audioMeter.take(sink.getPlaybackHeadPosition() & 0xffffffffL);
    if (delta > 0) {
      long end = System.currentTimeMillis();
      if (Looper.myLooper() == Looper.getMainLooper()) listener.audioProgress(end, delta);
      else telemetry.post(() -> listener.audioProgress(end, delta));
    }
  }

  private void monitor(AudioTrack sink) {
    synchronized (audioLock) {
      if (track != sink || closed) return;
      reportRendered(sink);
    }
    main.postDelayed(() -> monitor(sink), 1000);
  }

  private int write(AudioTrack sink, float[] samples, int size, long id) {
    int offset = 0;
    while (offset < size && current(id)) {
      int n = sink.write(samples, offset, Math.min(2048, size - offset), AudioTrack.WRITE_BLOCKING);
      if (n <= 0) throw new IllegalStateException("PCM output failed");
      offset += n;
    }
    return offset;
  }

  private void drain(AudioTrack sink, long frames, long id) throws InterruptedException {
    long deadline = SystemClock.elapsedRealtime() + 20000;
    while (current(id) && (sink.getPlaybackHeadPosition() & 0xffffffffL) < frames) {
      if (SystemClock.elapsedRealtime() > deadline)
        throw new IllegalStateException("Audio output stalled");
      Thread.sleep(10);
    }
  }

  private void synthesize(String text, float speed, String token, long id, boolean persistent) {
    if (!current(id)) return;
    AudioTrack audio = null;
    SpeechCache.Writer[] recording = {null};
    try {
      String key = cacheKey(text, speed);
      try (SpeechCache.Entry hit = persistent ? cache.open(key) : null) {
        if (hit != null) {
          audio = output(hit.sampleRate, id);
          if (audio == null) return;
          main.post(
              () -> {
                if (current(id)) listener.audioStarted(token, true);
              });
          float[] buffer = new float[4096];
          int n;
          long written = 0, heartbeat = 0;
          while (current(id) && (n = hit.read(buffer)) > 0) {
            written += write(audio, buffer, n, id);
            if (written - heartbeat >= hit.sampleRate * 60L) {
              heartbeat = written;
              main.post(
                  () -> {
                    if (current(id)) listener.keepAlive(token);
                  });
            }
          }
          drain(audio, written, id);
          main.post(
              () -> {
                if (current(id)) listener.completed(token);
              });
          return;
        }
      }
      main.post(
          () -> {
            if (current(id)) listener.synthesizing(token);
          });
      if (model == null) throw new IllegalStateException("Kokoro not ready");
      audio = output(model.sampleRate(), id);
      if (audio == null) return;
      if (persistent)
        try {
          recording[0] = cache.begin(key, model.sampleRate());
        } catch (IOException ignored) {
        }
      final AudioTrack sink = audio;
      final long[] written = {0};
      final boolean[] failed = {false}, started = {false};
      for (String chunk : SpeechChunks.split(text)) {
        if (!current(id)) return;
        model.generateWithCallback(
            chunk,
            0,
            speed,
            new KokoroCallback() {
              @Override
              protected int onSamples(float[] samples) {
                if (!current(id)) return 0;
                // Cache writes are optional: storage failures must not stop playback.
                if (recording[0] != null)
                  try {
                    recording[0].append(samples);
                  } catch (IOException e) {
                    discard(recording[0]);
                    recording[0] = null;
                  }
                try {
                  written[0] += write(sink, samples, samples.length, id);
                  if (!started[0] && written[0] > 0) {
                    started[0] = true;
                    main.post(
                        () -> {
                          if (current(id)) listener.audioStarted(token, false);
                        });
                  }
                  return current(id) ? 1 : 0;
                } catch (RuntimeException e) {
                  failed[0] = true;
                  return 0;
                }
              }
            });
        if (failed[0] && current(id)) throw new IllegalStateException("Audio output failed");
        main.post(
            () -> {
              if (current(id)) listener.keepAlive(token);
            });
      }
      if (!current(id)) return;
      if (written[0] == 0) throw new IllegalStateException("No speech generated");
      if (recording[0] != null)
        try {
          recording[0].commit(true);
        } catch (IOException ignored) {
        }
      reportCacheStats();
      drain(sink, written[0], id);
      main.post(
          () -> {
            if (current(id)) listener.completed(token);
          });
    } catch (Exception | LinkageError | OutOfMemoryError e) {
      android.util.Log.e("WordFlowKokoro", "Speech generation/playback failed", e);
      main.post(
          () -> {
            if (current(id)) listener.failed(token);
          });
    } finally {
      discard(recording[0]);
      synchronized (audioLock) {
        if (track == audio && audio != null) {
          reportRendered(audio);
          track = null;
        }
        if (audio != null) audio.release();
      }
    }
  }

  void clearCache() {
    stop();
    worker.execute(
        () -> {
          boolean success;
          try {
            cache.clear();
            success = true;
          } catch (IOException e) {
            success = false;
          }
          reportCacheStats();
          final boolean result = success;
          main.post(
              () -> {
                if (!closed) listener.cacheCleared(result);
              });
        });
  }

  void warm(List<String> texts, float speed, String token) {
    stop();
    long id = request.get();
    worker.execute(
        () -> {
          int done = 0;
          try {
            // Deduplicate words / examples, using the same key as normal playback.
            Set<String> seen = new HashSet<>();
            List<String> unique = new ArrayList<>();
            for (String text : texts) if (seen.add(cacheKey(text, speed))) unique.add(text);
            final int total = unique.size();
            for (String text : unique) {
              if (!current(id)) return;
              String key = cacheKey(text, speed);
              final int completedBefore = done;
              try (SpeechCache.Entry hit = cache.open(key)) {
                if (hit == null) {
                  try (SpeechCache.Writer writer = cache.begin(key, model.sampleRate())) {
                    final boolean[] failed = {false};
                    for (String chunk : SpeechChunks.split(text)) {
                      if (!current(id)) return;
                      model.generateWithCallback(
                          chunk,
                          0,
                          speed,
                          new KokoroCallback() {
                            @Override
                            protected int onSamples(float[] samples) {
                              if (!current(id)) return 0;
                              try {
                                writer.append(samples);
                                return 1;
                              } catch (IOException e) {
                                failed[0] = true;
                                return 0;
                              }
                            }
                          });
                      if (failed[0]) throw new IOException("Cache storage write failed");
                      main.post(
                          () -> {
                            if (current(id)) listener.warmProgress(token, completedBefore, total);
                          });
                    }
                    if (!current(id)) return;
                    if (!writer.hasSamples()) throw new IOException("No speech generated");
                    if (!writer.commit(false)) {
                      reportCacheStats();
                      final int completed = done;
                      main.post(
                          () -> {
                            if (current(id)) listener.warmFinished(token, "full", completed, total);
                          });
                      return;
                    }
                  }
                }
              }
              done++;
              final int completed = done;
              reportCacheStats();
              main.post(
                  () -> {
                    if (current(id)) listener.warmProgress(token, completed, total);
                  });
            }
            final int completed = done;
            main.post(
                () -> {
                  if (current(id)) listener.warmFinished(token, "done", completed, total);
                });
          } catch (Exception | LinkageError | OutOfMemoryError e) {
            android.util.Log.e("WordFlowKokoro", "Precache failed", e);
            reportCacheStats();
            final int completed = done;
            main.post(
                () -> {
                  if (current(id)) listener.warmFinished(token, "failed", completed, texts.size());
                });
          }
        });
  }

  void close() {
    closed = true;
    stop();
    // Do not free the native model while an inference callback is still using it.
    worker.execute(this::releaseModel);
    worker.shutdown();
    main.removeCallbacksAndMessages(null);
  }

  private void releaseModel() {
    try {
      if (model != null) {
        model.release();
        model = null;
      }
    } finally {
      if (ownsModelSlot) {
        ownsModelSlot = false;
        MODEL_SLOT.release();
      }
    }
  }

  private File prepareModel() throws IOException {
    synchronized (INSTALL_LOCK) {
      File target = new File(context.getNoBackupFilesDir(), MODEL_VERSION);
      File marker = new File(target, ".ready");
      if (marker.isFile() && new File(target, "model.int8.onnx").length() > 0) return target;
      copyAssets(context.getAssets(), "kokoro", target);
      try (FileOutputStream out = new FileOutputStream(marker)) {
        out.write(1);
        out.getFD().sync();
      }
      return target;
    }
  }

  private void copyAssets(AssetManager assets, String path, File target) throws IOException {
    if (closed) throw new IOException("Cancelled");
    String[] children = assets.list(path);
    if (children != null && children.length > 0) {
      if (!target.isDirectory() && !target.mkdirs())
        throw new IOException("Cannot create model directory");
      for (String child : children) copyAssets(assets, path + "/" + child, new File(target, child));
    } else {
      try (InputStream in = assets.open(path);
          FileOutputStream out = new FileOutputStream(target)) {
        byte[] buffer = new byte[65536];
        int n;
        while ((n = in.read(buffer)) != -1) {
          if (closed) throw new IOException("Cancelled");
          out.write(buffer, 0, n);
        }
      }
    }
  }
}
