package cn.wordflow.app;

import java.util.*;

/** Immutable vocabulary data shared by imports, storage, UI and playback. */
public final class WordEntry {
  public static final int MAX_FIELD = 3500;
  public final String word, sentence;
  public final int frequency;
  public final List<String> importedExamples, customExamples;

  public WordEntry(String word, String sentence) {
    this(word, sentence, Collections.emptyList());
  }

  public WordEntry(String word, String sentence, List<String> custom) {
    this(
        word,
        sentence == null || sentence.trim().isEmpty()
            ? Collections.emptyList()
            : Collections.singletonList(sentence),
        custom,
        1);
  }

  public WordEntry(String word, List<String> imported, List<String> custom, int frequency) {
    if (word == null || word.trim().isEmpty() || word.trim().length() > MAX_FIELD)
      throw new IllegalArgumentException("词汇不能为空，且最多 3500 个字符");
    if (frequency < 1) throw new IllegalArgumentException("词频必须为正数");
    if (custom.size() > 20) throw new IllegalArgumentException("每个单词最多添加 20 条自定义例句");
    this.word = word.trim();
    this.frequency = frequency;
    importedExamples = validated(imported);
    customExamples = validated(custom);
    sentence = importedExamples.isEmpty() ? "" : importedExamples.get(0);
  }

  private static List<String> validated(List<String> source) {
    List<String> copy = new ArrayList<>();
    for (String text : source) {
      if (text == null || text.trim().isEmpty() || text.trim().length() > MAX_FIELD)
        throw new IllegalArgumentException("例句不能为空，且最多 3500 个字符");
      copy.add(text.trim());
    }
    return Collections.unmodifiableList(copy);
  }

  public List<String> fragments() {
    List<String> result = new ArrayList<>();
    result.add(word);
    result.addAll(importedExamples);
    result.addAll(customExamples);
    return result;
  }

  public WordEntry editExample(int index, String text) {
    List<String> next = new ArrayList<>(customExamples);
    if (index < 0) next.add(text);
    else next.set(index, text);
    return new WordEntry(word, importedExamples, next, frequency);
  }

  public WordEntry deleteExample(int index) {
    List<String> next = new ArrayList<>(customExamples);
    next.remove(index);
    return new WordEntry(word, importedExamples, next, frequency);
  }
}
