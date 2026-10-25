package cn.wordflow.app;

import static org.junit.Assert.*;

import java.util.*;
import org.junit.Test;

public class DeckMergerTest {
  @Test
  public void multipleFilesMergeCaseInsensitiveAndCountEveryOccurrence() {
    var one = CsvParser.parse("word,sentence\nApple,An apple.\npear\nAPPLE,");
    var merged = DeckMerger.merge(List.of(), one);
    merged = DeckMerger.merge(merged, CsvParser.parse("apple,A fresh apple.\nPEAR\nbanana,"));
    assertEquals(3, merged.size());
    assertEquals("Apple", merged.get(0).word);
    assertEquals(3, merged.get(0).frequency);
    assertEquals(2, merged.get(1).frequency);
    assertEquals(List.of("Apple", "An apple.", "A fresh apple."), merged.get(0).fragments());
    assertEquals(List.of("pear"), merged.get(1).fragments());
  }

  @Test
  public void identicalExamplesDeduplicateButFrequencyStillIncrements() {
    var entry = new WordEntry("run", "I run.").editExample(-1, "We run.");
    var result = DeckMerger.merge(List.of(entry), List.of(new WordEntry("RUN", "I run."))).get(0);
    assertEquals(2, result.frequency);
    assertEquals(List.of("run", "I run.", "We run."), result.fragments());
    assertEquals(2, result.editExample(0, "They run.").frequency);
    assertEquals(2, result.deleteExample(0).frequency);
    assertEquals(1, entry.frequency);
  }

  @Test
  public void emptyExampleRowsAndSingleColumnHeadersImport() {
    assertEquals(List.of("first"), CsvParser.parse("word\nfirst\nsecond").get(0).fragments());
    assertEquals(2, CsvParser.parse("word,sentence\nfirst,\nsecond").size());
    assertEquals(1, CsvParser.parse("词汇,\n苹果,").size());
  }

  @Test
  public void persistenceMigratesOldAndPreservesMergedData() throws Exception {
    var old = WordStore.decode("[{\"name\":\"old\",\"entries\":[{\"word\":\"a\"}]}]");
    assertEquals(1, old.get(0).entries.get(0).frequency);
    var rows =
        DeckMerger.merge(old.get(0).entries, CsvParser.parse("a,An example.\na,Another example."));
    rows.set(0, rows.get(0).editExample(-1, "My example."));
    var deck = new WordStore.Deck("merged", rows);
    var decoded = WordStore.decode(WordStore.encode(List.of(deck))).get(0);
    assertTrue(deck.sameContent(decoded));
    assertEquals(3, decoded.entries.get(0).frequency);
    assertEquals(
        List.of("An example.", "Another example."), decoded.entries.get(0).importedExamples);
    assertFalse(
        deck.sameContent(new WordStore.Deck("merged", List.of(new WordEntry("a", "An example.")))));
  }

  @Test
  public void combinedDeckLimitRejectsBatchWithoutMutatingOriginal() {
    List<WordEntry> base = new ArrayList<>();
    for (int i = 0; i < CsvParser.MAX_ROWS; i++) base.add(new WordEntry("w" + i, ""));
    try {
      DeckMerger.merge(base, List.of(new WordEntry("extra", "")));
      fail();
    } catch (IllegalArgumentException expected) {
      assertEquals(CsvParser.MAX_ROWS, base.size());
    }
  }
}
