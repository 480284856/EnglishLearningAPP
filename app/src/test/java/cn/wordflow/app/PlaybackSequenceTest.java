package cn.wordflow.app;

import static org.junit.Assert.*;

import org.junit.Test;

public class PlaybackSequenceTest {
  @Test
  public void readsWordThenSentenceThenNextWord() {
    PlaybackSequence s = new PlaybackSequence();
    assertTrue(s.advance(s.begin(), 2, false));
    assertEquals(0, s.word);
    assertEquals(1, s.phase);
    assertTrue(s.advance(s.begin(), 2, false));
    assertEquals(1, s.word);
    assertEquals(0, s.phase);
    assertTrue(s.advance(s.begin(), 2, false));
    assertFalse(s.advance(s.begin(), 2, false));
    assertEquals(0, s.word);
    assertEquals(0, s.phase);
  }

  @Test
  public void loopStartsAgainOnlyAfterLastSentence() {
    PlaybackSequence s = new PlaybackSequence();
    assertTrue(s.advance(s.begin(), 1, true));
    assertEquals(1, s.phase);
    assertTrue(s.advance(s.begin(), 1, true));
    assertEquals(0, s.word);
    assertEquals(0, s.phase);
  }

  @Test
  public void pauseCancelsCallbackAndResumeRepeatsCurrentFragment() {
    PlaybackSequence s = new PlaybackSequence();
    s.reset(4, 1);
    String stopped = s.begin();
    s.cancel();
    assertFalse(s.matches(stopped));
    assertFalse(s.advance(stopped, 10, false));
    assertEquals(4, s.word);
    assertEquals(1, s.phase);
    String resumed = s.begin();
    assertNotEquals(stopped, resumed);
    assertTrue(s.advance(resumed, 10, false));
    assertEquals(5, s.word);
  }

  @Test
  public void skippingAndChangingDeckRejectOldCallbacks() {
    PlaybackSequence s = new PlaybackSequence();
    String old = s.begin();
    s.reset(2, 0);
    String next = s.begin();
    assertFalse(s.advance(old, 4, false));
    assertEquals(2, s.word);
    assertTrue(s.advance(next, 4, false));
    assertFalse(s.advance(next, 4, false));
    s.reset(0, 0);
    assertFalse(s.matches(next));
    assertFalse(s.advance("", 1, false));
  }
}
