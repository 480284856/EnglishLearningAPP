package cn.wordflow.app;

import android.app.*;
import android.content.*;
import android.database.Cursor;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.*;
import android.provider.OpenableColumns;
import android.view.*;
import android.widget.*;
import java.io.*;
import java.nio.ByteBuffer;
import java.nio.charset.*;
import java.util.*;
import java.util.concurrent.*;

public class MainActivity extends Activity {
    private static final int GREEN = 0xff267567, INK = 0xff203b34, MUTED = 0xff6c7c74, BG = 0xfff6f7f2;
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private List<WordStore.Deck> decks = new ArrayList<>();
    private WordStore store;
    private boolean ready, playing, importing, destroyed, warming, warmAfterPermission;
    private long cacheBytes;
    private int cacheEntries;
    private int deckIndex, wordIndex, phase;
    private float rate = 0.85f;
    private boolean home = true, profile;
    private ScrollView profileView;
    private AiSettings aiSettings;
    private final ExecutorService aiWorker = Executors.newSingleThreadExecutor();
    private SentenceGenerator aiRequest;
    private AlertDialog aiProgress;
    private LinearLayout footer, importRow, detailRow, tuning, caching;
    private Button back;
    private TextView pageTitle;
    private TextView status, word, sentence, progress, rateLabel, empty;
    private Button play, importButton, remove, precacheButton;
    private ListView list;
    private BaseAdapter entriesAdapter;
    private CheckBox loop;
    private PlaybackService playback;
    private boolean bound;
    private final PlaybackService.Listener playbackListener = state -> {
        ready = state.ready; playing = state.playing; warming = state.warming;
        cacheBytes = state.cacheBytes; cacheEntries = state.cacheEntries;
        if (state.hasDeck && state.deckIndex < decks.size()) {
            deckIndex = state.deckIndex; wordIndex = state.wordIndex; phase = state.phase;

        }
        loop.setChecked(state.loop);
        status.setText(state.message); update();
    };
    private final ServiceConnection connection = new ServiceConnection() {
        @Override public void onServiceConnected(ComponentName name, IBinder binder) {
            playback = ((PlaybackService.LocalBinder)binder).service();
            playback.addListener(playbackListener);
            PlaybackService.State state = new PlaybackService.State(playback);
            rate = state.rate; rateLabel.setText(String.format(Locale.US, "语速 %.2f×", rate));
            speed.setProgress(Math.round((rate - 0.5f) * 100));
            if (!decks.isEmpty() && (!state.hasDeck || !playback.usesDeck(current()))) syncDeck();
        }
        @Override public void onServiceDisconnected(ComponentName name) {
            playback = null; ready = false; playing = false; warming = false;
            status.setText("播放服务已断开，请重新打开应用"); update();
        }
    };
    private SeekBar speed;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        store = new WordStore(this); aiSettings = new AiSettings(this);
        try { decks = store.load(); } catch (Exception e) { message("词表读取失败", "保存的数据无法读取。请保留应用数据，重新打开应用后再试。"); }
        if (state != null) { home = state.getBoolean("home", true); profile = state.getBoolean("profile", false); deckIndex = state.getInt("deck"); wordIndex = state.getInt("word"); phase = state.getInt("phase"); }
        if (!getSharedPreferences("playback", MODE_PRIVATE).contains("rate"))
            getSharedPreferences("playback", MODE_PRIVATE).edit().putFloat("rate", getPreferences(0).getFloat("rate", 0.85f)).apply();
        rate = getSharedPreferences("playback", MODE_PRIVATE).getFloat("rate", 0.85f);
        if (deckIndex >= decks.size()) deckIndex = 0;
        if (!decks.isEmpty() && wordIndex >= current().entries.size()) wordIndex = 0;
        if (getIntent().getBooleanExtra("showPlayer", false)) home = false;
        buildUi(); refreshDecks();
    }
    private void buildUi() {
        LinearLayout root = new LinearLayout(this); root.setOrientation(LinearLayout.VERTICAL); root.setBackgroundColor(BG);
        root.setOnApplyWindowInsetsListener((v, insets) -> { v.setPadding(dp(16), dp(8) + insets.getSystemWindowInsetTop(), dp(16), dp(8) + insets.getSystemWindowInsetBottom()); return insets; });
        setContentView(root);
        LinearLayout header = row(); root.addView(header);
        back = button("‹ 返回", v -> { if (profile) profile = false; else home = true; update(); }); header.addView(back);
        pageTitle = text("我的词表", 25, INK); pageTitle.setTypeface(null, Typeface.BOLD); pageTitle.setSingleLine(true); pageTitle.setEllipsize(android.text.TextUtils.TruncateAt.END); header.addView(pageTitle, weighted());
        header.addView(button("个人", v -> { profile = true; rebuildProfile(); update(); }));
        importRow = row(); root.addView(importRow);
        importButton = button("＋ 导入 CSV", v -> chooseCsv()); importRow.addView(importButton, weighted());
        importRow.addView(button("格式说明", v -> message("CSV 导入", "UTF-8 编码：第一列词汇，第二列例句（可留空），也支持只有一列词汇。\n表头可选 word,sentence 或 词汇,例句。\n例句含逗号或换行时需用双引号包围。\n最多 5 MB、10,000 个单词，每个字段最多 3500 字符。")));
        detailRow = row(); root.addView(detailRow);
        detailRow.addView(text("点击单词查看或添加自己的例句", 13, MUTED), weighted());
        remove = button("删除词表", v -> deleteDeck()); detailRow.addView(remove);
        empty = text("还没有词表\n点击「导入 CSV」开始学习", 17, MUTED); empty.setGravity(Gravity.CENTER); root.addView(empty, new LinearLayout.LayoutParams(-1, 0, 1));
        list = new ListView(this); list.setDivider(new android.graphics.drawable.ColorDrawable(BG)); list.setDividerHeight(dp(8)); root.addView(list, new LinearLayout.LayoutParams(-1, 0, 1));
        entriesAdapter = new BaseAdapter() {
            public int getCount() { return home ? decks.size() : decks.isEmpty() ? 0 : current().entries.size(); }
            public Object getItem(int i) { return home ? decks.get(i) : current().entries.get(i); }
            public long getItemId(int i) { return i; }
            public View getView(int i, View recycled, ViewGroup parent) {
                LinearLayout card;
                if (recycled == null) { card = new LinearLayout(MainActivity.this); card.setOrientation(LinearLayout.VERTICAL); card.setPadding(dp(16), dp(12), dp(16), dp(12)); card.addView(text("", 20, INK)); TextView sub = text("", 14, MUTED); sub.setMaxLines(3); sub.setEllipsize(android.text.TextUtils.TruncateAt.END); card.addView(sub); }
                else card = (LinearLayout)recycled;
                TextView title = (TextView)card.getChildAt(0), sub = (TextView)card.getChildAt(1);
                if (home) { WordStore.Deck d = decks.get(i); title.setText(d.name + "   ›"); sub.setText(d.entries.size() + " 个单词 · 点击进入"); }
                else { CsvParser.Entry e = current().entries.get(i); title.setText((i + 1) + "   " + e.word + (i == wordIndex ? "   •" : "")); sub.setText((e.sentence.isEmpty() ? "暂无导入例句" : e.sentence) + "\n我的例句 " + e.customExamples.size() + " 条 · 点击查看 / 添加"); }
                card.setBackground(shape(!home && i == wordIndex ? 0xffe1eee6 : Color.WHITE, 16)); return card;
            }
        };
        list.setAdapter(entriesAdapter);
        list.setOnItemClickListener((parent, view, position, id) -> {
            if (importing) return;
            if (home) { pause(); deckIndex = position; wordIndex = 0; phase = 0; home = false; syncDeck(); update(); list.setSelection(0); }
            else showEntry(position);
        });
        profileView = new ScrollView(this); root.addView(profileView, new LinearLayout.LayoutParams(-1, 0, 1)); rebuildProfile();
        footer = new LinearLayout(this); footer.setOrientation(LinearLayout.VERTICAL); footer.setPadding(dp(12), dp(10), dp(12), dp(6)); footer.setBackground(shape(Color.WHITE, 20)); root.addView(footer);
        progress = text("", 12, GREEN); footer.addView(progress);
        word = text("", 21, INK); word.setMaxLines(1); word.setEllipsize(android.text.TextUtils.TruncateAt.END); word.setTypeface(null, Typeface.BOLD); footer.addView(word);
        sentence = text("", 14, MUTED); sentence.setMaxLines(2); sentence.setEllipsize(android.text.TextUtils.TruncateAt.END); footer.addView(sentence);
        LinearLayout controls = row(); footer.addView(controls);
        controls.addView(button("上一词", v -> move(-1)), weighted());
        play = button("播放词表", v -> { if (playing) pause(); else start(); }); play.setTextColor(Color.WHITE); play.setBackgroundTintList(android.content.res.ColorStateList.valueOf(GREEN)); controls.addView(play, weighted());
        controls.addView(button("下一词", v -> move(1)), weighted());
        status = text("正在准备 Kokoro 离线语音…", 12, MUTED); footer.addView(status);
        tuning = row(); footer.addView(tuning);
        rateLabel = text(String.format(Locale.US, "%.2f×", rate), 13, MUTED); tuning.addView(rateLabel);
        speed = new SeekBar(this); speed.setMax(100); speed.setProgress(Math.round((rate - 0.5f) * 100)); tuning.addView(speed, weighted());
        speed.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            public void onProgressChanged(SeekBar bar, int value, boolean user) { rate = 0.5f + value / 100f; rateLabel.setText(String.format(Locale.US, "%.2f×", rate)); }
            public void onStartTrackingTouch(SeekBar bar) { }
            public void onStopTrackingTouch(SeekBar bar) { getSharedPreferences("playback", MODE_PRIVATE).edit().putFloat("rate", rate).apply(); if (playback != null) playback.setRate(rate); }
        });
        loop = new CheckBox(this); loop.setText("循环"); loop.setTextSize(13); loop.setOnCheckedChangeListener((b, checked) -> { if (playback != null) playback.setLoop(checked); }); tuning.addView(loop);
        caching = row(); footer.addView(caching);
        precacheButton = button("预缓存词表", v -> precache()); caching.addView(precacheButton, weighted()); caching.addView(button("缓存管理", v -> showCache()), weighted());
    }
    private WordStore.Deck current() { return decks.get(deckIndex); }
    private void refreshDecks() { update(); }
    private void update() {
        boolean has = !decks.isEmpty();
        back.setVisibility(home && !profile ? View.GONE : View.VISIBLE);
        pageTitle.setText(profile ? "个人主页" : home ? "我的词表" : has ? current().name : "词表");
        importRow.setVisibility(home && !profile ? View.VISIBLE : View.GONE); detailRow.setVisibility(!home && !profile && has ? View.VISIBLE : View.GONE);
        empty.setVisibility(!profile && !has ? View.VISIBLE : View.GONE); list.setVisibility(!profile && has ? View.VISIBLE : View.GONE); profileView.setVisibility(profile ? View.VISIBLE : View.GONE);
        footer.setVisibility(!profile && has && (!home || playing || warming) ? View.VISIBLE : View.GONE);
        tuning.setVisibility(home ? View.GONE : View.VISIBLE); caching.setVisibility(home ? View.GONE : View.VISIBLE); sentence.setVisibility(home ? View.GONE : View.VISIBLE);
        importButton.setEnabled(!importing); remove.setEnabled(has && !importing); play.setEnabled(has && ready && !importing);
        play.setText(playing ? "暂停" : "播放词表"); precacheButton.setText(warming ? "取消预缓存" : "预缓存词表"); precacheButton.setEnabled(warming || (has && ready && !importing));
        if (has) {
            wordIndex = Math.max(0, Math.min(wordIndex, current().entries.size() - 1));
            CsvParser.Entry e = current().entries.get(wordIndex); List<String> fragments = e.fragments();
            phase = Math.max(0, Math.min(phase, fragments.size() - 1));
            word.setText(e.word);
            sentence.setText(phase > 0 ? fragments.get(phase) : fragments.size() > 1 ? fragments.get(1) : "暂无例句，可点击单词添加");
            progress.setText(current().name + " · " + (wordIndex + 1) + "/" + current().entries.size() + " · " + (phase == 0 ? "单词" : "例句 " + phase + "/" + (fragments.size() - 1)));
        }
        entriesAdapter.notifyDataSetChanged();
    }
    private void showEntry(int index) {
        if (importing || decks.isEmpty()) return;
        CsvParser.Entry entry = current().entries.get(index);
        LinearLayout content = new LinearLayout(this); content.setOrientation(LinearLayout.VERTICAL); content.setPadding(dp(20), dp(8), dp(20), dp(8));
        content.addView(text("导入例句", 13, GREEN)); content.addView(text(entry.sentence.isEmpty() ? "暂无导入例句" : entry.sentence, 17, INK));
        content.addView(text("我的例句", 13, GREEN));
        AlertDialog dialog = new AlertDialog.Builder(this).setTitle(entry.word).setNegativeButton("关闭", null)
                .setPositiveButton("从这里播放", (d, w) -> { pause(); wordIndex = index; phase = 0; syncDeck(); update(); start(); }).create();
        for (int i = 0; i < entry.customExamples.size(); i++) {
            final int example = i;
            content.addView(text((i + 1) + ". " + entry.customExamples.get(i), 17, INK));
            LinearLayout actions = row(); content.addView(actions);
            actions.addView(button("编辑", v -> { dialog.dismiss(); editExample(index, example); }), weighted());
            actions.addView(button("删除", v -> new AlertDialog.Builder(this).setTitle("删除这条自定义例句？").setNegativeButton("取消", null)
                    .setPositiveButton("删除", (d, which) -> { dialog.dismiss(); saveEntry(index, entry.deleteExample(example)); }).show()), weighted());
        }
        content.addView(button("使用大模型生成例句", v -> { dialog.dismiss(); generateExample(index); }));
        content.addView(button("＋ 添加我的例句", v -> { dialog.dismiss(); editExample(index, -1); }));
        ScrollView scroll = new ScrollView(this); scroll.addView(content); dialog.setView(scroll); dialog.show();
    }
    private void editExample(int index, int example) { editExample(index, example, null); }
    private void editExample(int index, int example, String suggestion) {
        if (importing) return;
        CsvParser.Entry entry = current().entries.get(index);
        EditText input = new EditText(this); input.setHint("输入自己的英语例句"); input.setMinLines(3); input.setMaxLines(8);
        input.setInputType(android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE | android.text.InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        input.setFilters(new android.text.InputFilter[]{new android.text.InputFilter.LengthFilter(CsvParser.MAX_FIELD)});
        if (suggestion != null) input.setText(suggestion);
        else if (example >= 0) input.setText(entry.customExamples.get(example));
        LinearLayout box = new LinearLayout(this); box.setPadding(dp(20), dp(8), dp(20), dp(8)); box.addView(input, new LinearLayout.LayoutParams(-1, -2));
        AlertDialog dialog = new AlertDialog.Builder(this).setTitle(suggestion != null ? "生成预览 · 编辑后保存" : example < 0 ? "添加例句 · " + entry.word : "编辑我的例句").setView(box).setNegativeButton("取消", null).setPositiveButton("保存", null).create();
        dialog.setOnShowListener(d -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            try { CsvParser.Entry next = entry.editExample(example, input.getText().toString()); saveEntry(index, next); dialog.dismiss(); }
            catch (IllegalArgumentException e) { input.setError(e.getMessage()); }
        })); dialog.show();
    }
    private void saveEntry(int index, CsvParser.Entry entry) {
        if (importing) return;
        pause();
        int selected = deckIndex;
        List<CsvParser.Entry> entries = new ArrayList<>(current().entries); entries.set(index, entry);
        List<WordStore.Deck> next = new ArrayList<>(decks); next.set(selected, new WordStore.Deck(current().name, entries));
        importing = true; status.setText("正在保存例句…"); update();
        worker.execute(() -> {
            try {
                store.save(next);
                runOnUiThread(() -> { if (destroyed) return; decks.clear(); decks.addAll(next); deckIndex = selected; wordIndex = index; phase = 0; syncDeck(); importing = false; update(); status.setText("例句已保存"); });
            } catch (Exception e) { runOnUiThread(() -> { if (destroyed) return; importing = false; update(); message("保存失败", "原有例句已保留，请重试。"); }); }
        });
    }
    @Override public void onBackPressed() { if (profile) { profile = false; update(); } else if (!home) { home = true; update(); } else super.onBackPressed(); }
    @Override protected void onNewIntent(Intent intent) { super.onNewIntent(intent); setIntent(intent); if (intent.getBooleanExtra("showPlayer", false)) { home = false; profile = false; update(); } }
    private EditText keyInput(String hint) {
        EditText input = new EditText(this); input.setHint(hint); input.setSingleLine(true);
        input.setInputType(android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD);
        input.setSaveEnabled(false); input.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS);
        input.setImeOptions(android.view.inputmethod.EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING);
        return input;
    }
    private void rebuildProfile() {
        if (profileView == null) return;
        LinearLayout content = new LinearLayout(this); content.setOrientation(LinearLayout.VERTICAL); content.setPadding(dp(4), dp(12), dp(4), dp(24)); profileView.removeAllViews(); profileView.addView(content);
        content.addView(text("大模型例句设置", 23, INK));
        content.addView(text("仅点击生成时发送当前单词给所选服务商。生成结果先预览，保存到「我的例句」，不会覆盖导入例句。", 15, MUTED));
        Spinner provider = new Spinner(this); provider.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, new String[]{"OpenRouter", "DeepSeek"})); provider.setSelection(aiSettings.provider().equals("DeepSeek") ? 1 : 0); content.addView(provider);
        content.addView(text("DeepSeek API Key", 16, GREEN));
        EditText deepseek = keyInput(aiSettings.hasKey("DeepSeek") ? "已配置 · 留空保留原 Key" : "填入 DeepSeek API Key"); content.addView(deepseek);
        content.addView(button("清除 DeepSeek Key", v -> { aiSettings.clear("DeepSeek"); deepseek.setText(""); deepseek.setHint("未配置"); }));
        content.addView(text("OpenRouter API Key", 16, GREEN));
        EditText openrouter = keyInput(aiSettings.hasKey("OpenRouter") ? "已配置 · 留空保留原 Key" : "填入 OpenRouter API Key"); content.addView(openrouter);
        content.addView(button("清除 OpenRouter Key", v -> { aiSettings.clear("OpenRouter"); openrouter.setText(""); openrouter.setHint("未配置"); }));
        content.addView(text("OpenRouter 模型 ID", 16, GREEN));
        EditText model = new EditText(this); model.setSingleLine(true); model.setText(aiSettings.model()); content.addView(model);
        content.addView(text("默认 openrouter/free，路由到平台免费模型。免费额度和可用性以平台为准；填写其他模型 ID 可能收费。DeepSeek 固定使用 deepseek-flash，按其账户规则计费。", 14, MUTED));
        content.addView(button("保存设置", v -> {
            try {
                aiSettings.save(provider.getSelectedItem().toString(), model.getText().toString(), deepseek.getText().toString(), openrouter.getText().toString());
                deepseek.setText(""); openrouter.setText(""); rebuildProfile(); message("设置已保存", "Key 已在本机加密保存。请返回词表，点开单词后选择「使用大模型生成例句」。");
            } catch (IllegalArgumentException e) { message("无法保存", e.getMessage()); }
            catch (Exception e) { message("无法保存", "本机密钥存储或设置保存失败，请重试。"); }
        }));
        content.addView(button("OpenRouter 获取 Key / 免费模型说明", v -> openUrl("https://openrouter.ai/openrouter/free")));
        content.addView(button("DeepSeek 开放平台", v -> openUrl("https://platform.deepseek.com/")));
        content.addView(text("模型生成需要联网，应用不会后台自动调用，也不会把 Key 放进词表导出或源码。", 13, MUTED));
    }
    private void openUrl(String url) {
        try { startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url))); }
        catch (ActivityNotFoundException e) { message("无法打开", "请使用浏览器访问服务商官网。"); }
    }
    private void cancelAi() {
        if (aiRequest != null) { aiRequest.cancel(); aiRequest = null; }
        if (aiProgress != null) { aiProgress.dismiss(); aiProgress = null; }
    }
    private void generateExample(int index) {
        if (importing) return;
        if (current().entries.get(index).customExamples.size() >= 20) { message("例句数量已达上限", "每个单词最多 20 条自定义例句，请先删除一条再生成。"); return; }
        String provider = aiSettings.provider();
        if (!aiSettings.hasKey(provider)) {
            new AlertDialog.Builder(this).setTitle("先配置 " + provider + " API Key").setMessage("请在个人主页选择服务商并保存自己的 Key。")
                    .setNegativeButton("取消", null).setPositiveButton("前往设置", (d, w) -> { profile = true; rebuildProfile(); update(); }).show(); return;
        }
        final int selected = deckIndex;
        final CsvParser.Entry original = current().entries.get(index);
        SentenceGenerator job = new SentenceGenerator(); aiRequest = job;
        String model = aiSettings.model();
        aiProgress = new AlertDialog.Builder(this).setTitle("正在生成例句").setMessage(provider + " 正在为「" + original.word + "」生成例句，请稍候。")
                .setNegativeButton("取消", (d, w) -> cancelAi()).setOnCancelListener(d -> cancelAi()).create(); aiProgress.show();
        aiWorker.execute(() -> {
            try {
                String generated = job.generate(original.word, provider, model, aiSettings.key(provider));
                runOnUiThread(() -> {
                    if (destroyed || aiRequest != job) return;
                    aiRequest = null; aiProgress.dismiss(); aiProgress = null;
                    if (deckIndex == selected && current().entries.get(index) == original) editExample(index, -1, generated);
                });
            } catch (Exception e) {
                runOnUiThread(() -> {
                    if (destroyed || aiRequest != job) return;
                    aiRequest = null; aiProgress.dismiss(); aiProgress = null;
                    String reason = e instanceof java.net.SocketTimeoutException ? "请求超时，请稍后重试" : e instanceof IOException && e.getMessage() != null && !e.getMessage().contains("http") ? e.getMessage() : "网络或密钥读取失败，请检查连接及个人设置后重试";
                    message("生成失败", reason);
                });
            }
        });
    }
    private void syncDeck() {
        if (playback != null) playback.selectDeck(deckIndex, decks.isEmpty() ? null : current(), wordIndex, phase);
    }
    private void start() {
        if (!ready || decks.isEmpty() || importing || playback == null) return;
        try { startForegroundService(new Intent(this, PlaybackService.class).setAction(PlaybackService.PLAY)); }
        catch (IllegalStateException | SecurityException e) { status.setText("无法启动后台播放，请回到应用内重试"); }
    }
    private void pause() {
        if (playback != null) playback.pause("已暂停 · 继续时重读当前片段");
        else { playing = false; update(); }
    }
    private void move(int delta) { if (!decks.isEmpty() && !importing && playback != null) playback.move(delta); }
    private void precache() {
        if (playback == null) return;
        if (warming) { playback.cancelWarm(); return; }
        if (!ready || decks.isEmpty() || importing) return;
        new AlertDialog.Builder(this).setTitle("预缓存「" + current().name + "」？")
                .setMessage("按当前语速生成单词和例句，之后可直接复用。\n\n合成可能耗时较长，可息屏等待或随时取消；播放、切词或调速会停止预缓存，已完成的音频会保留。\n\n语音缓存上限 256 MB。达到上限后批量缓存会停止，避免挤掉已完成的内容。")
                .setNegativeButton("取消", null).setPositiveButton("开始缓存", (d, w) -> requestWarm()).show();
    }
    private void requestWarm() {
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission("android.permission.POST_NOTIFICATIONS") != android.content.pm.PackageManager.PERMISSION_GRANTED
                && !getPreferences(0).getBoolean("cacheNoticeAsked", false)) {
            getPreferences(0).edit().putBoolean("cacheNoticeAsked", true).apply();
            warmAfterPermission = true; requestPermissions(new String[]{"android.permission.POST_NOTIFICATIONS"}, 21); return;
        }
        startWarm();
    }
    private void startWarm() {
        if (!ready || decks.isEmpty() || importing || playback == null) return;
        try { startForegroundService(new Intent(this, PlaybackService.class).setAction(PlaybackService.PRECACHE)); }
        catch (IllegalStateException | SecurityException e) { status.setText("无法启动后台缓存，请回到应用内重试"); }
    }
    @Override public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grants) {
        super.onRequestPermissionsResult(requestCode, permissions, grants);
        if (requestCode == 21) {
            boolean resume = warmAfterPermission; warmAfterPermission = false;
            if (resume && bound) startWarm();
        }
    }
    private void showCache() {
        String description = String.format(Locale.CHINA, "已保存 %d 段语音，占用 %.1f / 256 MB。\n\n首次听读会自动缓存完整音频。同一文本、语速和声音再次播放时直接复用；换语速会生成另一份。\n\n达到上限后，普通播放会淘汰较久未用的缓存。系统清理应用缓存后需要重新生成。清理语音不会删除词表或模型。", cacheEntries, cacheBytes / (1024d * 1024d));
        new AlertDialog.Builder(this).setTitle("语音缓存").setMessage(description).setNegativeButton("关闭", null)
                .setPositiveButton("清理语音缓存", (d, w) -> new AlertDialog.Builder(this).setTitle("清理所有语音缓存？")
                        .setMessage("将停止当前播放和预缓存。只删除合成音频，词表和 Kokoro 模型会保留。")
                        .setNegativeButton("取消", null).setPositiveButton("清理", (dialog, which) -> { if (playback != null) playback.clearCache(); }).show()).show();
    }
    private void chooseCsv() {
        if (importing) return;
        pause();
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT).setType("*/*").addCategory(Intent.CATEGORY_OPENABLE);
        try { startActivityForResult(intent, 10); } catch (ActivityNotFoundException e) { message("无法选择文件", "请安装或启用系统文件管理器。"); }
    }
    @Override protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (request != 10 || result != RESULT_OK || data == null || data.getData() == null) return;
        Uri uri = data.getData(); importing = true; importButton.setEnabled(false); status.setText("正在导入…"); update();
        worker.execute(() -> {
            try {
                String name = "导入的词表";
                try (Cursor c = getContentResolver().query(uri, new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) { if (c != null && c.moveToFirst()) name = c.getString(0); }
                if (name == null || name.trim().isEmpty()) name = "导入的词表";
                name = name.replaceFirst("(?i)\\.csv$", "");
                ByteArrayOutputStream bytes = new ByteArrayOutputStream();
                try (InputStream in = getContentResolver().openInputStream(uri)) {
                    if (in == null) throw new IOException("无法打开文件");
                    byte[] buffer = new byte[8192]; int n;
                    while ((n = in.read(buffer)) != -1) { if (bytes.size() + n > 5 * 1024 * 1024) throw new IOException("文件不能超过 5 MB"); bytes.write(buffer, 0, n); }
                }
                String csv = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes.toByteArray())).toString();
                WordStore.Deck added = new WordStore.Deck(name, CsvParser.parse(csv));
                List<WordStore.Deck> next = new ArrayList<>(decks); next.add(added); store.save(next);
                runOnUiThread(() -> { if (destroyed) return; decks.clear(); decks.addAll(next); deckIndex = decks.size() - 1; wordIndex = 0; phase = 0; home = false; syncDeck(); finishImport(); refreshDecks(); status.setText("已导入 " + added.entries.size() + " 个词汇"); });
            } catch (Exception e) { runOnUiThread(() -> { if (destroyed) return; finishImport(); message("导入失败", e instanceof CharacterCodingException ? "文件不是有效的 UTF-8 编码。请另存为 CSV UTF-8 后重新导入。" : (e.getMessage() == null ? "无法读取该文件，请重试。" : e.getMessage())); status.setText("导入失败，现有词表已保留"); }); }
        });
    }
    private void finishImport() { importing = false; importButton.setEnabled(true); update(); }
    private void deleteDeck() {
        if (decks.isEmpty() || importing) return;
        pause();
        new AlertDialog.Builder(this).setTitle("删除「" + current().name + "」？").setMessage("删除后可通过原 CSV 重新导入。").setNegativeButton("取消", null).setPositiveButton("删除", (d, w) -> {
            List<WordStore.Deck> next = new ArrayList<>(decks); next.remove(deckIndex);
            try { store.save(next); decks.clear(); decks.addAll(next); deckIndex = 0; wordIndex = 0; phase = 0; home = true; syncDeck(); refreshDecks(); } catch (Exception e) { message("删除失败", "无法保存更改，请重试。"); }
        }).show();
    }
    @Override protected void onSaveInstanceState(Bundle out) { super.onSaveInstanceState(out); out.putBoolean("profile", profile); out.putBoolean("home", home); out.putInt("deck", deckIndex); out.putInt("word", wordIndex); out.putInt("phase", phase); }
    @Override protected void onStart() {
        super.onStart();
        bound = bindService(new Intent(this, PlaybackService.class), connection, Context.BIND_AUTO_CREATE);
    }
    @Override protected void onStop() {
        cancelAi();
        if (playback != null) playback.removeListener(playbackListener);
        if (bound) { unbindService(connection); bound = false; }
        playback = null; ready = false; warmAfterPermission = false;
        super.onStop();
    }
    @Override protected void onDestroy() { destroyed = true; cancelAi(); aiWorker.shutdownNow(); worker.shutdown(); super.onDestroy(); }
    private void message(String title, String body) { new AlertDialog.Builder(this).setTitle(title).setMessage(body).setPositiveButton("知道了", null).show(); }
    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }
    private TextView text(String value, int size, int color) { TextView v = new TextView(this); v.setText(value); v.setTextSize(size); v.setTextColor(color); v.setPadding(0, dp(3), 0, dp(3)); return v; }
    private Button button(String title, View.OnClickListener click) { Button b = new Button(this); b.setText(title); b.setAllCaps(false); b.setTextSize(13); b.setMinWidth(0); b.setMinimumWidth(0); b.setOnClickListener(click); return b; }
    private LinearLayout row() { LinearLayout v = new LinearLayout(this); v.setOrientation(LinearLayout.HORIZONTAL); v.setGravity(Gravity.CENTER_VERTICAL); return v; }
    private LinearLayout.LayoutParams weighted() { return new LinearLayout.LayoutParams(0, -2, 1); }
    private GradientDrawable shape(int color, int radius) { GradientDrawable d = new GradientDrawable(); d.setColor(color); d.setCornerRadius(dp(radius)); return d; }
}
