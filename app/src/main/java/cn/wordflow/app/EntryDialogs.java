package cn.wordflow.app;

import android.app.*;
import android.widget.*;

/** Word details and example editing. Saving and playback are delegated to the owner. */
final class EntryDialogs {
  interface Actions {
    void play(int index);

    void save(int index, WordEntry entry);

    void generate(int index);
  }

  private static final int GREEN = 0xff267567, INK = 0xff203b34;
  private final Activity activity;
  private final Actions actions;
  private final UiWidgets ui;

  EntryDialogs(Activity activity, Actions actions) {
    this.activity = activity;
    this.actions = actions;
    ui = new UiWidgets(activity);
  }

  void show(int index, WordEntry entry) {
    LinearLayout content = new LinearLayout(activity);
    content.setOrientation(LinearLayout.VERTICAL);
    content.setPadding(ui.dp(20), ui.dp(8), ui.dp(20), ui.dp(8));
    content.addView(ui.text("导入例句", 13, GREEN));
    content.addView(
        ui.text(
            entry.importedExamples.isEmpty()
                ? "暂无导入例句"
                : String.join("\n\n", entry.importedExamples),
            17,
            INK));
    content.addView(ui.text("我的例句", 13, GREEN));
    AlertDialog dialog =
        new AlertDialog.Builder(activity)
            .setTitle(entry.word + " · 词频 " + entry.frequency)
            .setNegativeButton("关闭", null)
            .setPositiveButton(
                "从这里播放",
                (d, w) -> {
                  actions.play(index);
                })
            .create();
    for (int i = 0; i < entry.customExamples.size(); i++) {
      final int example = i;
      content.addView(ui.text((i + 1) + ". " + entry.customExamples.get(i), 17, INK));
      LinearLayout exampleActions = ui.row();
      content.addView(exampleActions);
      exampleActions.addView(
          ui.button(
              "编辑",
              v -> {
                dialog.dismiss();
                edit(index, entry, example, null);
              }),
          ui.weighted());
      exampleActions.addView(
          ui.button(
              "删除",
              v ->
                  new AlertDialog.Builder(activity)
                      .setTitle("删除这条自定义例句？")
                      .setNegativeButton("取消", null)
                      .setPositiveButton(
                          "删除",
                          (d, which) -> {
                            dialog.dismiss();
                            actions.save(index, entry.deleteExample(example));
                          })
                      .show()),
          ui.weighted());
    }
    content.addView(
        ui.button(
            "使用大模型生成例句",
            v -> {
              dialog.dismiss();
              actions.generate(index);
            }));
    content.addView(
        ui.button(
            "＋ 添加我的例句",
            v -> {
              dialog.dismiss();
              edit(index, entry, -1, null);
            }));
    ScrollView scroll = new ScrollView(activity);
    scroll.addView(content);
    dialog.setView(scroll);
    dialog.show();
  }

  void edit(int index, WordEntry entry, int example, String suggestion) {
    EditText input = new EditText(activity);
    input.setHint("输入自己的英语例句");
    input.setMinLines(3);
    input.setMaxLines(8);
    input.setInputType(
        android.text.InputType.TYPE_CLASS_TEXT
            | android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE
            | android.text.InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
    input.setFilters(
        new android.text.InputFilter[] {
          new android.text.InputFilter.LengthFilter(CsvParser.MAX_FIELD)
        });
    if (suggestion != null) input.setText(suggestion);
    else if (example >= 0) input.setText(entry.customExamples.get(example));
    LinearLayout box = new LinearLayout(activity);
    box.setPadding(ui.dp(20), ui.dp(8), ui.dp(20), ui.dp(8));
    box.addView(input, new LinearLayout.LayoutParams(-1, -2));
    AlertDialog dialog =
        new AlertDialog.Builder(activity)
            .setTitle(
                suggestion != null
                    ? "生成预览 · 编辑后保存"
                    : example < 0 ? "添加例句 · " + entry.word : "编辑我的例句")
            .setView(box)
            .setNegativeButton("取消", null)
            .setPositiveButton("保存", null)
            .create();
    dialog.setOnShowListener(
        d ->
            dialog
                .getButton(AlertDialog.BUTTON_POSITIVE)
                .setOnClickListener(
                    v -> {
                      try {
                        WordEntry next = entry.editExample(example, input.getText().toString());
                        actions.save(index, next);
                        dialog.dismiss();
                      } catch (IllegalArgumentException e) {
                        input.setError(e.getMessage());
                      }
                    }));
    dialog.show();
  }
}
