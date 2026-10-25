package cn.wordflow.app;

import static org.junit.Assert.*;

import java.util.*;
import org.junit.Test;

public class ExamplesTest {
  @Test
  public void importsWordsWithMissingExamplesAndKeepsPlaybackOrder() {
    var rows = CsvParser.parse("word,sentence\na,\nb,Example B.\nc");
    assertEquals(List.of("a"), rows.get(0).fragments());
    assertEquals(List.of("b", "Example B."), rows.get(1).fragments());
    assertEquals(List.of("c"), rows.get(2).fragments());
  }

  @Test
  public void ownExamplesDoNotOverwriteImportedSentence() {
    var original = new WordEntry("learn", "We learn English.");
    var edited =
        original.editExample(-1, "I learn every day.").editExample(-1, "We learn together.");
    assertEquals(0, original.customExamples.size());
    assertEquals(
        List.of("learn", "We learn English.", "I learn every day.", "We learn together."),
        edited.fragments());
    edited = edited.editExample(0, "I learn at home.").deleteExample(1);
    assertEquals("We learn English.", edited.sentence);
    assertEquals(List.of("I learn at home."), edited.customExamples);
  }

  @Test
  public void missingOfficialExampleCanHaveOwnExamples() {
    assertEquals(
        List.of("hello", "Hello, world!"),
        new WordEntry("hello", "").editExample(-1, "Hello, world!").fragments());
    try {
      new WordEntry("a", "").editExample(-1, "  ");
      fail();
    } catch (IllegalArgumentException expected) {
    }
  }

  @Test
  public void migratesLegacyJsonAndRoundTripsMultipleExamples() throws Exception {
    var old =
        WordStore.decode(
            "[{\"name\":\"old\",\"entries\":[{\"word\":\"a\",\"sentence\":\"A"
                + " sentence.\"},{\"word\":\"b\"}]}]");
    assertEquals("A sentence.", old.get(0).entries.get(0).sentence);
    assertEquals(List.of("b"), old.get(0).entries.get(1).fragments());
    var own = old.get(0).entries.get(0).editExample(-1, "My sentence.");
    var decoded =
        WordStore.decode(WordStore.encode(List.of(new WordStore.Deck("saved", List.of(own)))));
    assertEquals(own.fragments(), decoded.get(0).entries.get(0).fragments());
  }

  @Test
  public void changedCustomExamplesInvalidateServiceSnapshotWithoutResettingUnchangedPlayback() {
    var entry = new WordEntry("a", "");
    var old = new WordStore.Deck("one", List.of(entry));
    assertTrue(old.sameContent(new WordStore.Deck("one", List.of(new WordEntry("a", "")))));
    assertFalse(
        old.sameContent(new WordStore.Deck("one", List.of(entry.editExample(-1, "An example.")))));
  }

  @Test
  public void cursorSkipsMissingExamplesAndPlaysAllCustomOnesBeforeNextWord() {
    PlaybackSequence cursor = new PlaybackSequence();
    assertTrue(cursor.advance(cursor.begin(), 2, false, 0));
    assertEquals(1, cursor.word);
    assertEquals(0, cursor.phase);
    for (int phase = 1; phase <= 3; phase++) {
      assertTrue(cursor.advance(cursor.begin(), 2, false, 3));
      assertEquals(phase, cursor.phase);
    }
    assertFalse(cursor.advance(cursor.begin(), 2, false, 3));
    assertEquals(0, cursor.word);
    assertEquals(0, cursor.phase);
  }
}
