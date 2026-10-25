package cn.wordflow.app;

import static org.junit.Assert.*;

import org.junit.Test;

public class SpeechChunksTest {
  @Test
  public void leavesSingleWordAndShortSentenceIntact() {
    assertEquals(java.util.List.of("curious"), SpeechChunks.split("curious"));
    assertEquals(
        java.util.List.of("With practice, you will become more confident."),
        SpeechChunks.split(" With practice, you will become more confident. "));
  }

  @Test
  public void preservesSentenceOrderAndAllWords() {
    String text = "Every journey begins with a single step. Practice makes progress! Keep going.";
    assertEquals(text, String.join(" ", SpeechChunks.split(text)));
  }

  @Test
  public void boundsLongInputAndDoesNotLoseWords() {
    String text = "A very long example sentence with many words ".repeat(80).trim();
    var chunks = SpeechChunks.split(text);
    assertTrue(chunks.size() > 1);
    assertEquals(text, String.join(" ", chunks));
    for (String chunk : chunks)
      assertTrue(chunk.length() <= SpeechChunks.LIMIT && !chunk.isEmpty());
  }

  @Test
  public void splitsUnbrokenInputWithoutCuttingSurrogatePairs() {
    String text = "a".repeat(239) + "\uD83D\uDE00" + "b".repeat(600);
    var chunks = SpeechChunks.split(text);
    assertEquals(text, String.join("", chunks));
    for (String c : chunks) {
      assertTrue(c.length() <= SpeechChunks.LIMIT);
      assertFalse(Character.isHighSurrogate(c.charAt(c.length() - 1)));
      assertFalse(Character.isLowSurrogate(c.charAt(0)));
    }
    assertTrue(SpeechChunks.split(" \n ").isEmpty());
  }
}
