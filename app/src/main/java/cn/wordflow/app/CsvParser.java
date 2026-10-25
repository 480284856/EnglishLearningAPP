package cn.wordflow.app;

import java.util.ArrayList;
import java.util.List;
import java.util.Collections;

/** Strict two-column RFC 4180 style CSV, with optional header and UTF-8 BOM. */
public final class CsvParser {
    public static final int MAX_ROWS = 10000;
    public static final int MAX_FIELD = 3500;
    public static final class Entry {
        public final String word, sentence;
        public final List<String> customExamples;
        public Entry(String word, String sentence) { this(word, sentence, Collections.emptyList()); }
        public Entry(String word, String sentence, List<String> custom) {
            this.word = word; this.sentence = sentence == null ? "" : sentence;
            if (custom.size() > 20) throw new IllegalArgumentException("每个单词最多添加 20 条自定义例句");
            List<String> copy = new ArrayList<>();
            for (String text : custom) {
                if (text == null || text.trim().isEmpty() || text.trim().length() > MAX_FIELD)
                    throw new IllegalArgumentException("例句不能为空，且最多 3500 个字符");
                copy.add(text.trim());
            }
            customExamples = Collections.unmodifiableList(copy);
        }
        public List<String> fragments() {
            List<String> result = new ArrayList<>(); result.add(word);
            if (!sentence.trim().isEmpty()) result.add(sentence);
            result.addAll(customExamples); return result;
        }
        public Entry editExample(int index, String text) {
            List<String> next = new ArrayList<>(customExamples);
            if (index < 0) next.add(text); else next.set(index, text);
            return new Entry(word, sentence, next);
        }
        public Entry deleteExample(int index) {
            List<String> next = new ArrayList<>(customExamples); next.remove(index);
            return new Entry(word, sentence, next);
        }
    }
    public static List<Entry> parse(String input) {
        if (input.startsWith("\uFEFF")) input = input.substring(1);
        List<Entry> result = new ArrayList<>();
        List<String> row = new ArrayList<>();
        StringBuilder field = new StringBuilder();
        boolean quoted = false, closed = false, first = true;
        int line = 1;
        for (int i = 0; i <= input.length(); i++) {
            boolean end = i == input.length();
            char c = end ? '\n' : input.charAt(i);
            if (quoted) {
                if (end) throw error(line, "引号没有闭合");
                if (c == '"') {
                    if (i + 1 < input.length() && input.charAt(i + 1) == '"') { field.append('"'); i++; }
                    else { quoted = false; closed = true; }
                } else { field.append(c); if (c == '\n') line++; }
            } else if (c == ',' || c == '\n' || c == '\r') {
                row.add(field.toString().trim()); field.setLength(0); closed = false;
                if (c != ',') {
                    if (row.size() != 1 || !row.get(0).isEmpty()) {
                        if (row.size() > 2) throw error(line, "每条记录最多有两列；例句内的逗号请用双引号包围");
                        String w = row.get(0), s = row.size() == 2 ? row.get(1) : "";
                        boolean header = first && ((w.equalsIgnoreCase("word") && (s.equalsIgnoreCase("sentence") || s.equalsIgnoreCase("example"))) || (w.equals("词汇") && s.equals("例句")));
                        if (!header) {
                            if (w.isEmpty()) throw error(line, "词汇不能为空");
                            if (w.length() > MAX_FIELD || s.length() > MAX_FIELD) throw error(line, "单个字段不能超过 " + MAX_FIELD + " 个字符");
                            result.add(new Entry(w, s));
                            if (result.size() > MAX_ROWS) throw error(line, "最多支持 " + MAX_ROWS + " 个词汇");
                        }
                        first = false;
                    }
                    row.clear();
                    if (c == '\r' && i + 1 < input.length() && input.charAt(i + 1) == '\n') i++;
                    line++;
                }
            } else if (c == '"') {
                if (field.length() != 0 || closed) throw error(line, "引号只能出现在字段开头");
                quoted = true;
            } else {
                if (closed) throw error(line, "闭合引号后只能是逗号或换行");
                field.append(c);
            }
            if (field.length() > MAX_FIELD) throw error(line, "单个字段过长");
        }
        if (result.isEmpty()) throw new IllegalArgumentException("文件中没有可导入的词汇");
        return result;
    }
    private static IllegalArgumentException error(int line, String message) {
        return new IllegalArgumentException("第 " + line + " 行：" + message);
    }
}
