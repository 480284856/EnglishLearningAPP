package cn.wordflow.app;

import android.content.Context;
import android.graphics.Color;
import android.view.*;
import android.widget.*;
import java.util.*;

/** Renders deck and word rows; does not mutate data or control playback. */
final class DeckListAdapter extends BaseAdapter {
  private static final int INK = 0xff203b34, MUTED = 0xff6c7c74;
  private final Context context;
  private final UiWidgets ui;
  private List<WordStore.Deck> decks = Collections.emptyList();
  private boolean home;
  private int deckIndex, wordIndex;

  DeckListAdapter(Context context) {
    this.context = context;
    ui = new UiWidgets(context);
  }

  void submit(List<WordStore.Deck> decks, boolean home, int deckIndex, int wordIndex) {
    this.decks = new ArrayList<>(decks);
    this.home = home;
    this.deckIndex = deckIndex;
    this.wordIndex = wordIndex;
    notifyDataSetChanged();
  }

  private WordStore.Deck current() {
    return decks.get(deckIndex);
  }

  public int getCount() {
    return home ? decks.size() : decks.isEmpty() ? 0 : current().entries.size();
  }

  public Object getItem(int i) {
    return home ? decks.get(i) : current().entries.get(i);
  }

  public long getItemId(int i) {
    return i;
  }

  public View getView(int i, View recycled, ViewGroup parent) {
    LinearLayout card;
    if (recycled == null) {
      card = new LinearLayout(context);
      card.setOrientation(LinearLayout.VERTICAL);
      card.setPadding(ui.dp(16), ui.dp(12), ui.dp(16), ui.dp(12));
      card.addView(ui.text("", 20, INK));
      TextView sub = ui.text("", 14, MUTED);
      sub.setMaxLines(3);
      sub.setEllipsize(android.text.TextUtils.TruncateAt.END);
      card.addView(sub);
    } else card = (LinearLayout) recycled;
    TextView title = (TextView) card.getChildAt(0), sub = (TextView) card.getChildAt(1);
    if (home) {
      WordStore.Deck d = decks.get(i);
      title.setText(d.name + "   ›");
      sub.setText(d.entries.size() + " 个单词 · 点击进入");
    } else {
      WordEntry e = current().entries.get(i);
      title.setText(
          (i + 1) + "   " + e.word + "  ×" + e.frequency + (i == wordIndex ? "   •" : ""));
      sub.setText(
          (e.sentence.isEmpty() ? "暂无导入例句" : e.sentence)
              + "\n我的例句 "
              + e.customExamples.size()
              + " 条 · 点击查看 / 添加");
    }
    card.setBackground(ui.shape(!home && i == wordIndex ? 0xffe1eee6 : Color.WHITE, 16));
    return card;
  }
}
