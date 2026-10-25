package cn.wordflow.app;

import java.text.BreakIterator;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Bound the work and PCM allocation per neural synthesis request. */
final class SpeechChunks {
    static final int LIMIT = 240;
    static List<String> split(String text) {
        List<String> chunks = new ArrayList<>();
        BreakIterator sentences = BreakIterator.getSentenceInstance(Locale.US);
        sentences.setText(text);
        int start = sentences.first();
        for (int end = sentences.next(); end != BreakIterator.DONE; start = end, end = sentences.next()) {
            String sentence = text.substring(start, end).trim();
            while (sentence.length() > LIMIT) {
                int boundary = sentence.lastIndexOf(' ', LIMIT);
                if (boundary < LIMIT / 2) boundary = LIMIT;
                if (Character.isHighSurrogate(sentence.charAt(boundary - 1))) boundary--;
                chunks.add(sentence.substring(0, boundary).trim());
                sentence = sentence.substring(boundary).trim();
            }
            if (!sentence.isEmpty()) chunks.add(sentence);
        }
        return chunks;
    }
}
