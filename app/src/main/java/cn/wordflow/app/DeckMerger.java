package cn.wordflow.app;

import java.util.*;

/** Stable, case-insensitive merge. Each occurrence contributes its import frequency. */
final class DeckMerger {
  static List<WordEntry> merge(List<WordEntry> existing, List<WordEntry> incoming) {
    Map<String, WordEntry> merged = new LinkedHashMap<>();
    add(merged, existing);
    add(merged, incoming);
    if (merged.size() > CsvParser.MAX_ROWS)
      throw new IllegalArgumentException("一个词表最多 10,000 个不同单词");
    return new ArrayList<>(merged.values());
  }

  private static void add(Map<String, WordEntry> merged, List<WordEntry> entries) {
    for (WordEntry next : entries) {
      String key = next.word.trim().toLowerCase(Locale.ROOT);
      WordEntry old = merged.get(key);
      if (old == null) {
        merged.put(key, next);
        continue;
      }
      Set<String> imported = new LinkedHashSet<>(old.importedExamples);
      imported.addAll(next.importedExamples);
      Set<String> custom = new LinkedHashSet<>(old.customExamples);
      custom.addAll(next.customExamples);
      int count = (int) Math.min(Integer.MAX_VALUE, (long) old.frequency + next.frequency);
      merged.put(
          key, new WordEntry(old.word, new ArrayList<>(imported), new ArrayList<>(custom), count));
    }
  }
}
