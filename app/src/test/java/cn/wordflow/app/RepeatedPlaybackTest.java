package cn.wordflow.app;

import static org.junit.Assert.*;

import org.junit.Test;

public class RepeatedPlaybackTest {
  @Test
  public void repeatsEntireItemOneTwoOrThreeTimesIncludingAllExamples() {
    for (int repeats = 1; repeats <= 3; repeats++) {
      PlaybackSequence s = new PlaybackSequence();
      for (int pass = 0; pass < repeats; pass++) {
        assertEquals(0, s.word);
        assertEquals(pass, s.repetition);
        for (int phase = 0; phase < 3; phase++) {
          assertEquals(phase, s.phase);
          assertTrue(s.advance(s.begin(), 2, false, 2, repeats));
        }
      }
      assertEquals(1, s.word);
      assertEquals(0, s.phase);
      assertEquals(0, s.repetition);
    }
  }

  @Test
  public void missingExamplesRepeatWordOnlyAndFinishAfterThirdPass() {
    PlaybackSequence s = new PlaybackSequence();
    assertTrue(s.advance(s.begin(), 1, false, 0, 3));
    assertTrue(s.advance(s.begin(), 1, false, 0, 3));
    assertFalse(s.advance(s.begin(), 1, false, 0, 3));
  }

  @Test
  public void transientPhaseCompletesPassWithoutGrowingOrRecursiveGeneration() {
    PlaybackSequence s = new PlaybackSequence();
    s.reset(0, 2); // word, existing example, one transient example
    assertTrue(s.advance(s.begin(), 2, false, 2, 2));
    assertEquals(0, s.word);
    assertEquals(0, s.phase);
    assertEquals(1, s.repetition);
    s.phase = 2;
    assertTrue(s.advance(s.begin(), 2, false, 2, 2));
    assertEquals(1, s.word);
  }

  @Test
  public void cancelRejectsLateResultsAndSkipResetsRepeatCounter() {
    PlaybackSequence s = new PlaybackSequence();
    s.advance(s.begin(), 2, false, 0, 3);
    String old = s.begin();
    s.cancel();
    assertFalse(s.advance(old, 2, false, 0, 3));
    assertEquals(1, s.repetition);
    s.reset(1, 0);
    assertEquals(0, s.repetition);
    try {
      s.advance(s.begin(), 2, false, 0, 4);
      fail();
    } catch (IllegalArgumentException expected) {
    }
  }
}
