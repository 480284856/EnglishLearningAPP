package cn.wordflow.app;

import android.content.Context;
import android.util.AtomicFile;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

final class WordStore {
    static final class Deck {
        final String name;
        final List<CsvParser.Entry> entries;
        Deck(String name, List<CsvParser.Entry> entries) { this.name = name; this.entries = entries; }
        boolean sameContent(Deck other) {
            if (other == null || !name.equals(other.name) || entries.size() != other.entries.size()) return false;
            for (int i = 0; i < entries.size(); i++) {
                CsvParser.Entry a = entries.get(i), b = other.entries.get(i);
                if (!a.word.equals(b.word) || !a.sentence.equals(b.sentence) || !a.customExamples.equals(b.customExamples)) return false;
            }
            return true;
        }
        @Override public String toString() { return name + "  ·  " + entries.size() + " 词"; }
    }
    private final AtomicFile file;
    WordStore(Context context) { file = new AtomicFile(new File(context.getFilesDir(), "decks.json")); }
    List<Deck> load() throws Exception {
        if (!file.getBaseFile().exists()) return new ArrayList<>();
        return decode(new String(file.readFully(), StandardCharsets.UTF_8));
    }
    static List<Deck> decode(String text) throws Exception {
        List<Deck> decks = new ArrayList<>();
        JSONArray array = new JSONArray(text);
        for (int i = 0; i < array.length(); i++) {
            JSONObject obj = array.getJSONObject(i);
            JSONArray items = obj.getJSONArray("entries");
            List<CsvParser.Entry> entries = new ArrayList<>();
            for (int j = 0; j < items.length(); j++) {
                JSONObject e = items.getJSONObject(j);
                JSONArray custom = e.optJSONArray("customExamples");
                List<String> examples = new ArrayList<>();
                if (custom != null) for (int k = 0; k < custom.length(); k++) examples.add(custom.getString(k));
                entries.add(new CsvParser.Entry(e.getString("word"), e.optString("sentence", ""), examples));
            }
            if (!entries.isEmpty()) decks.add(new Deck(obj.getString("name"), entries));
        }
        return decks;
    }
    static String encode(List<Deck> decks) throws Exception {
        JSONArray array = new JSONArray();
        for (Deck deck : decks) {
            JSONArray entries = new JSONArray();
            for (CsvParser.Entry e : deck.entries) entries.put(new JSONObject().put("word", e.word).put("sentence", e.sentence).put("customExamples", new JSONArray(e.customExamples)));
            array.put(new JSONObject().put("name", deck.name).put("entries", entries));
        }
        return array.toString();
    }
    void save(List<Deck> decks) throws Exception {
        String data = encode(decks);
        FileOutputStream stream = null;
        try {
            stream = file.startWrite();
            stream.write(data.getBytes(StandardCharsets.UTF_8));
            file.finishWrite(stream);
        } catch (Exception e) { if (stream != null) file.failWrite(stream); throw e; }
    }
}
