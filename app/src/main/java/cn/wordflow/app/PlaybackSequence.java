package cn.wordflow.app;

/** Main-thread playback cursor; tokens reject callbacks from cancelled utterances. */
final class PlaybackSequence {
  int word, phase, repetition;
  private long generation;
  private String active = "";

  void reset(int word, int phase) {
    cancel();
    this.word = word;
    this.phase = phase;
    repetition = 0;
  }

  void cancel() {
    generation++;
    active = "";
  }

  String begin() {
    active = (++generation) + ":" + word + ":" + phase;
    return active;
  }

  boolean matches(String token) {
    return !active.isEmpty() && active.equals(token);
  }

  boolean advance(String token, int size, boolean loop) {
    return advance(token, size, loop, 1);
  }

  boolean advance(String token, int size, boolean loop, int lastPhase) {
    return advance(token, size, loop, lastPhase, 1);
  }

  boolean advance(String token, int size, boolean loop, int lastPhase, int repeats) {
    if (repeats < 1 || repeats > 3) throw new IllegalArgumentException("重复次数为 1–3");
    if (!matches(token)) return false;
    active = "";
    if (phase < lastPhase) phase++;
    else if (++repetition < repeats) phase = 0;
    else {
      repetition = 0;
      phase = 0;
      word++;
      if (word >= size) {
        word = 0;
        return loop;
      }
    }
    return true;
  }
}
