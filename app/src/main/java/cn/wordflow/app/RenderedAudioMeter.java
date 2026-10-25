package cn.wordflow.app;

/**
 * Monotonic accounting for one AudioTrack; measures played frames, not queued or generated frames.
 */
final class RenderedAudioMeter {
  private final int sampleRate;
  private long accountedMillis;

  RenderedAudioMeter(int sampleRate) {
    if (sampleRate <= 0) throw new IllegalArgumentException("Invalid sample rate");
    this.sampleRate = sampleRate;
  }

  long take(long renderedFrames) {
    long millis = renderedFrames * 1000L / sampleRate;
    long delta = Math.max(0, millis - accountedMillis);
    accountedMillis = Math.max(accountedMillis, millis);
    return delta;
  }
}
