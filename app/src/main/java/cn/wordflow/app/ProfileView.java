package cn.wordflow.app;

import android.app.*;
import android.content.*;
import android.net.Uri;
import android.os.*;
import android.view.*;
import android.widget.*;

/** Personal page: provider settings and listening history are isolated from deck navigation. */
final class ProfileView extends ScrollView {
  private static final int INK = 0xff203b34, MUTED = 0xff6c7c74, GREEN = 0xff267567;
  private final UiWidgets ui;
  private final AiSettings aiSettings;
  private final ListeningStats stats;
  private TextView statsText;
  private final Handler handler = new Handler(Looper.getMainLooper());
  private final Runnable tick =
      new Runnable() {
        public void run() {
          if (isShown() && statsText != null) statsText.setText(stats.summary());
          handler.postDelayed(this, 1000);
        }
      };

  ProfileView(Context context) {
    super(context);
    ui = new UiWidgets(context);
    aiSettings = new AiSettings(context);
    stats = new ListeningStats(context);
    rebuild();
  }

  @Override
  protected void onAttachedToWindow() {
    super.onAttachedToWindow();
    handler.removeCallbacks(tick);
    handler.post(tick);
  }

  @Override
  protected void onWindowVisibilityChanged(int visibility) {
    super.onWindowVisibilityChanged(visibility);
    if (handler == null) return;
    handler.removeCallbacks(tick);
    if (visibility == VISIBLE) handler.post(tick);
  }

  @Override
  protected void onDetachedFromWindow() {
    handler.removeCallbacks(tick);
    super.onDetachedFromWindow();
  }

  private void message(String title, String body) {
    new AlertDialog.Builder(getContext())
        .setTitle(title)
        .setMessage(body)
        .setPositiveButton("知道了", null)
        .show();
  }

  private EditText keyInput(String hint) {
    EditText input = new EditText(getContext());
    input.setHint(hint);
    input.setSingleLine(true);
    input.setInputType(
        android.text.InputType.TYPE_CLASS_TEXT
            | android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD);
    input.setSaveEnabled(false);
    input.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS);
    input.setImeOptions(android.view.inputmethod.EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING);
    return input;
  }

  void rebuild() {
    LinearLayout content = new LinearLayout(getContext());
    content.setOrientation(LinearLayout.VERTICAL);
    content.setPadding(ui.dp(4), ui.dp(12), ui.dp(4), ui.dp(24));
    removeAllViews();
    addView(content);
    content.addView(ui.text("收听时长", 23, INK));
    statsText = ui.text(stats.summary(), 16, INK);
    content.addView(statsText);
    content.addView(ui.text("按实际输出音频统计，暂停、合成及等待网络不计时；按手机本地日期记录。从此版本开始累计。", 13, MUTED));
    content.addView(ui.text("大模型例句设置", 23, INK));
    content.addView(ui.text("手动生成可预览后保存。开启动态例句后，播放每遍已有内容后会自动联网生成并朗读一条，不保存文本或音频。", 15, MUTED));
    Spinner provider = new Spinner(getContext());
    provider.setAdapter(
        new ArrayAdapter<>(
            getContext(),
            android.R.layout.simple_spinner_dropdown_item,
            new String[] {"OpenRouter", "DeepSeek"}));
    provider.setSelection(aiSettings.provider().equals("DeepSeek") ? 1 : 0);
    content.addView(provider);
    content.addView(ui.text("DeepSeek API Key", 16, GREEN));
    EditText deepseek =
        keyInput(aiSettings.hasKey("DeepSeek") ? "已配置 · 留空保留原 Key" : "填入 DeepSeek API Key");
    content.addView(deepseek);
    content.addView(
        ui.button(
            "清除 DeepSeek Key",
            v -> {
              aiSettings.clear("DeepSeek");
              deepseek.setText("");
              deepseek.setHint("未配置");
            }));
    content.addView(ui.text("OpenRouter API Key", 16, GREEN));
    EditText openrouter =
        keyInput(aiSettings.hasKey("OpenRouter") ? "已配置 · 留空保留原 Key" : "填入 OpenRouter API Key");
    content.addView(openrouter);
    content.addView(
        ui.button(
            "清除 OpenRouter Key",
            v -> {
              aiSettings.clear("OpenRouter");
              openrouter.setText("");
              openrouter.setHint("未配置");
            }));
    content.addView(ui.text("OpenRouter 模型 ID", 16, GREEN));
    EditText model = new EditText(getContext());
    model.setSingleLine(true);
    model.setText(aiSettings.model());
    content.addView(model);
    content.addView(
        ui.text(
            "默认 openrouter/free，路由到平台免费模型。免费额度和可用性以平台为准；填写其他模型 ID 可能收费。DeepSeek 固定使用"
                + " deepseek-flash，按其账户规则计费。",
            14,
            MUTED));
    CheckBox dynamic = new CheckBox(getContext());
    dynamic.setText("播放时自动生成临时例句");
    dynamic.setChecked(aiSettings.dynamic());
    content.addView(dynamic);
    content.addView(
        ui.text("每遍调用一次，重复播放和息屏播放时也会调用。可能消耗 API 额度和流量；离线、超时或服务异常会跳过，不重试或切换收费模型。", 14, MUTED));
    content.addView(
        ui.button(
            "保存设置",
            v -> {
              try {
                aiSettings.save(
                    provider.getSelectedItem().toString(),
                    model.getText().toString(),
                    deepseek.getText().toString(),
                    openrouter.getText().toString());
                aiSettings.setDynamic(dynamic.isChecked());
                deepseek.setText("");
                openrouter.setText("");
                rebuild();
                message("设置已保存", "Key 已在本机加密保存。请返回词表，点开单词后选择「使用大模型生成例句」。");
              } catch (IllegalArgumentException e) {
                message("无法保存", e.getMessage());
              } catch (Exception e) {
                message("无法保存", "本机密钥存储或设置保存失败，请重试。");
              }
            }));
    content.addView(
        ui.button(
            "OpenRouter 获取 Key / 免费模型说明", v -> openUrl("https://openrouter.ai/openrouter/free")));
    content.addView(ui.button("DeepSeek 开放平台", v -> openUrl("https://platform.deepseek.com/")));
    content.addView(ui.text("Key 仅在本机加密保存。自动生成只在听读且开关开启时调用；发送当前词及已有例句帮助避免重复。", 13, MUTED));
  }

  private void openUrl(String url) {
    try {
      getContext().startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
    } catch (ActivityNotFoundException e) {
      message("无法打开", "请使用浏览器访问服务商官网。");
    }
  }
}
