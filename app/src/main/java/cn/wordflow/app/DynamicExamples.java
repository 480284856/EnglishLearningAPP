package cn.wordflow.app;

import android.content.Context;
import android.os.*;
import java.util.concurrent.*;

/**
 * One cancellable request per item pass; results live in memory only. No retry or paid fallback.
 */
final class DynamicExamples {
  interface Callback {
    void done(String sentence, String reason);
  }

  private final Context context;
  private final AiSettings settings;
  private final ExecutorService worker = Executors.newSingleThreadExecutor();
  private final Handler main = new Handler(Looper.getMainLooper());
  private SentenceGenerator active;
  private Runnable timeout;

  DynamicExamples(Context context) {
    this.context = context;
    settings = new AiSettings(context);
  }

  void generate(String word, String avoid, Callback callback) {
    cancel();
    if (!settings.dynamic()) {
      callback.done("", "");
      return;
    }
    if (!NetworkStatus.online(context)) {
      callback.done("", "无网络，已跳过动态例句");
      return;
    }
    String provider = settings.provider(), model = settings.model();
    if (!settings.hasKey(provider)) {
      callback.done("", "未配置 Key，已跳过动态例句");
      return;
    }
    SentenceGenerator job = new SentenceGenerator();
    active = job;
    timeout =
        () -> {
          if (active != job) return;
          cancel();
          callback.done("", "生成超时，已继续播放");
        };
    main.postDelayed(timeout, 20000);
    worker.execute(
        () -> {
          String sentence = "", reason = "";
          try {
            sentence = job.generate(word, provider, model, settings.key(provider), avoid);
            if (!SentenceGenerator.novel(sentence, avoid)) {
              sentence = "";
              reason = "返回了已有例句，已跳过";
            }
          } catch (Exception e) {
            reason = "动态例句暂不可用，已继续播放";
          }
          final String result = sentence, message = reason;
          main.post(
              () -> {
                if (active != job) return;
                main.removeCallbacks(timeout);
                timeout = null;
                active = null;
                callback.done(result, message);
              });
        });
  }

  void cancel() {
    if (active != null) {
      active.cancel();
      active = null;
    }
    if (timeout != null) {
      main.removeCallbacks(timeout);
      timeout = null;
    }
  }

  void close() {
    cancel();
    worker.shutdownNow();
  }
}
