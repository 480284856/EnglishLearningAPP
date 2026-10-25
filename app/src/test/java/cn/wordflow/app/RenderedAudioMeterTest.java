package cn.wordflow.app;

import static org.junit.Assert.*;

import org.junit.Test;

public class RenderedAudioMeterTest {
  @Test
  public void countsPartialAudioAndNeverCountsWaitingOrFlushTwice() {
    var meter = new RenderedAudioMeter(24000);
    assertEquals(0, meter.take(0)); // model/network/buffering wait
    assertEquals(500, meter.take(12000));
    assertEquals(0, meter.take(12000)); // paused
    assertEquals(250, meter.take(18000)); // played portion before cancel
    assertEquals(0, meter.take(0)); // AudioTrack flush resets head
    assertEquals(0, meter.take(18000));
  }

  @Test
  public void fractionalFramesAccumulateWithoutPerPollRoundingLoss() {
    var meter = new RenderedAudioMeter(24000);
    long total = 0;
    for (int frames = 1; frames <= 24000; frames++) total += meter.take(frames);
    assertEquals(1000, total);
    assertEquals(1000, new RenderedAudioMeter(48000).take(48000));
  }

  @Test
  public void transientDuplicateComparisonIgnoresCaseAndWhitespace() {
    assertFalse(SentenceGenerator.novel("I run every day.", "run\nI  run every day.\nWe run."));
    assertFalse(SentenceGenerator.novel("WE RUN.", "We run."));
    assertTrue(SentenceGenerator.novel("She runs in the park.", "We run."));
  }
}
