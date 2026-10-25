package cn.wordflow.app;

import android.app.*;
import android.content.*;
import android.graphics.Color;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.*;
import android.view.*;
import android.widget.*;
import java.io.*;
import java.nio.charset.CharacterCodingException;
import java.util.*;
import java.util.concurrent.*;

public class MainActivity extends Activity {
  private static final int GREEN = 0xff267567,
      INK = 0xff203b34,
      MUTED = 0xff6c7c74,
      BG = 0xfff6f7f2;
  private final ExecutorService worker = Executors.newSingleThreadExecutor();
  private List<WordStore.Deck> decks = new ArrayList<>();
  private WordStore store;
  private boolean ready, playing, importing, destroyed, warming, warmAfterPermission;
  private long cacheBytes;
  private int cacheEntries;
  private int deckIndex, wordIndex, phase;
  private float rate = 0.85f;
  private boolean home = true, profile;
  private ProfileView profileView;
  private Spinner repeatSelect;
  private String temporaryExample = "";
  private int importTarget = -1;
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
  private DeckListAdapter entriesAdapter;
  private UiWidgets ui;
  private EntryDialogs entryDialogs;
  private CheckBox loop;
  private PlaybackService playback;
  private boolean bound;
  private final PlaybackService.Listener playbackListener =
      state -> {
        ready = state.ready;
        playing = state.playing;
        warming = state.warming;
        temporaryExample = state.temporaryExample;
        if (repeatSelect != null && repeatSelect.getSelectedItemPosition() != state.repeats - 1)
          repeatSelect.setSelection(state.repeats - 1);
        cacheBytes = state.cacheBytes;
        cacheEntries = state.cacheEntries;
        if (state.hasDeck && state.deckIndex < decks.size()) {
          deckIndex = state.deckIndex;
          wordIndex = state.wordIndex;
          phase = state.phase;
        }
        loop.setChecked(state.loop);
        status.setText(state.message);
        update();
      };
  private final ServiceConnection connection =
      new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder binder) {
          playback = ((PlaybackService.LocalBinder) binder).service();
          playback.addListener(playbackListener);
          PlaybackService.State state = new PlaybackService.State(playback);
          rate = state.rate;
          rateLabel.setText(String.format(Locale.US, "语速 %.2f×", rate));
          speed.setProgress(Math.round((rate - 0.5f) * 100));
          if (!decks.isEmpty() && (!state.hasDeck || !playback.usesDeck(current()))) syncDeck();
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
          playback = null;
          ready = false;
          playing = false;
          warming = false;
          status.setText("播放服务已断开，请重新打开应用");
          update();
        }
      };
  private SeekBar speed;

  @Override
  public void onCreate(Bundle state) {
    super.onCreate(state);
    ui = new UiWidgets(this);
    entryDialogs =
        new EntryDialogs(
            this,
            new EntryDialogs.Actions() {
              public void play(int index) {
                pause();
                wordIndex = index;
                phase = 0;
                syncDeck();
                update();
                start();
              }

              public void save(int index, WordEntry entry) {
                saveEntry(index, entry);
              }

              public void generate(int index) {
                generateExample(index);
              }
            });
    store = new WordStore(this);
    aiSettings = new AiSettings(this);
    try {
      decks = store.load();
    } catch (Exception e) {
      message("词表读取失败", "保存的数据无法读取。请保留应用数据，重新打开应用后再试。");
    }
    if (state != null) {
      home = state.getBoolean("home", true);
      profile = state.getBoolean("profile", false);
      deckIndex = state.getInt("deck");
      wordIndex = state.getInt("word");
      phase = state.getInt("phase");
      importTarget = state.getInt("importTarget", -1);
    }
    if (!getSharedPreferences("playback", MODE_PRIVATE).contains("rate"))
      getSharedPreferences("playback", MODE_PRIVATE)
          .edit()
          .putFloat("rate", getPreferences(0).getFloat("rate", 0.85f))
          .apply();
    rate = getSharedPreferences("playback", MODE_PRIVATE).getFloat("rate", 0.85f);
    if (deckIndex >= decks.size()) deckIndex = 0;
    if (!decks.isEmpty() && wordIndex >= current().entries.size()) wordIndex = 0;
    if (getIntent().getBooleanExtra("showPlayer", false)) home = false;
    buildUi();
    refreshDecks();
  }

  private void buildUi() {
    LinearLayout root = new LinearLayout(this);
    root.setOrientation(LinearLayout.VERTICAL);
    root.setBackgroundColor(BG);
    root.setOnApplyWindowInsetsListener(
        (v, insets) -> {
          v.setPadding(
              ui.dp(16),
              ui.dp(8) + insets.getSystemWindowInsetTop(),
              ui.dp(16),
              ui.dp(8) + insets.getSystemWindowInsetBottom());
          return insets;
        });
    setContentView(root);
    LinearLayout header = ui.row();
    root.addView(header);
    back =
        ui.button(
            "‹ 返回",
            v -> {
              if (profile) profile = false;
              else home = true;
              update();
            });
    header.addView(back);
    pageTitle = ui.text("我的词表", 25, INK);
    pageTitle.setTypeface(null, Typeface.BOLD);
    pageTitle.setSingleLine(true);
    pageTitle.setEllipsize(android.text.TextUtils.TruncateAt.END);
    header.addView(pageTitle, ui.weighted());
    header.addView(
        ui.button(
            "个人",
            v -> {
              profile = true;
              rebuildProfile();
              update();
            }));
    importRow = ui.row();
    root.addView(importRow);
    importButton = ui.button("＋ 导入 CSV", v -> chooseCsv());
    importRow.addView(importButton, ui.weighted());
    importRow.addView(
        ui.button(
            "格式说明",
            v ->
                message(
                    "CSV 导入",
                    "UTF-8 编码：第一列词汇，第二列例句（可留空），也支持只有一列词汇。\n"
                        + "表头可选 word,sentence 或 词汇,例句。\n"
                        + "例句含逗号或换行时需用双引号包围。\n"
                        + "每文件最多 5 MB、10,000 行；每词表最多 10,000 个不同词。可多选文件，进入词表后可继续导入；重复词合并，词频加"
                        + " 1，不同例句保留。每个字段最多 3500 字符。")));
    detailRow = ui.row();
    root.addView(detailRow);
    detailRow.addView(ui.text("点击单词查看或添加自己的例句", 13, MUTED), ui.weighted());
    detailRow.addView(ui.button("＋ 导入文件", v -> chooseCsv()));
    remove = ui.button("删除词表", v -> deleteDeck());
    detailRow.addView(remove);
    empty = ui.text("还没有词表\n点击「导入 CSV」开始学习", 17, MUTED);
    empty.setGravity(Gravity.CENTER);
    root.addView(empty, new LinearLayout.LayoutParams(-1, 0, 1));
    list = new ListView(this);
    list.setDivider(new android.graphics.drawable.ColorDrawable(BG));
    list.setDividerHeight(ui.dp(8));
    root.addView(list, new LinearLayout.LayoutParams(-1, 0, 1));
    entriesAdapter = new DeckListAdapter(this);
    list.setAdapter(entriesAdapter);
    list.setOnItemClickListener(
        (parent, view, position, id) -> {
          if (importing) return;
          if (home) {
            pause();
            deckIndex = position;
            wordIndex = 0;
            phase = 0;
            home = false;
            syncDeck();
            update();
            list.setSelection(0);
          } else showEntry(position);
        });
    profileView = new ProfileView(this);
    root.addView(profileView, new LinearLayout.LayoutParams(-1, 0, 1));
    rebuildProfile();
    footer = new LinearLayout(this);
    footer.setOrientation(LinearLayout.VERTICAL);
    footer.setPadding(ui.dp(12), ui.dp(10), ui.dp(12), ui.dp(6));
    footer.setBackground(ui.shape(Color.WHITE, 20));
    root.addView(footer);
    progress = ui.text("", 12, GREEN);
    footer.addView(progress);
    word = ui.text("", 21, INK);
    word.setMaxLines(1);
    word.setEllipsize(android.text.TextUtils.TruncateAt.END);
    word.setTypeface(null, Typeface.BOLD);
    footer.addView(word);
    sentence = ui.text("", 14, MUTED);
    sentence.setMaxLines(2);
    sentence.setEllipsize(android.text.TextUtils.TruncateAt.END);
    footer.addView(sentence);
    LinearLayout controls = ui.row();
    footer.addView(controls);
    controls.addView(ui.button("上一词", v -> move(-1)), ui.weighted());
    play =
        ui.button(
            "播放词表",
            v -> {
              if (playing) pause();
              else start();
            });
    play.setTextColor(Color.WHITE);
    play.setBackgroundTintList(android.content.res.ColorStateList.valueOf(GREEN));
    controls.addView(play, ui.weighted());
    controls.addView(ui.button("下一词", v -> move(1)), ui.weighted());
    status = ui.text("正在准备 Kokoro 离线语音…", 12, MUTED);
    footer.addView(status);
    tuning = ui.row();
    footer.addView(tuning);
    rateLabel = ui.text(String.format(Locale.US, "%.2f×", rate), 13, MUTED);
    tuning.addView(rateLabel);
    speed = new SeekBar(this);
    speed.setMax(100);
    speed.setProgress(Math.round((rate - 0.5f) * 100));
    tuning.addView(speed, ui.weighted());
    speed.setOnSeekBarChangeListener(
        new SeekBar.OnSeekBarChangeListener() {
          public void onProgressChanged(SeekBar bar, int value, boolean user) {
            rate = 0.5f + value / 100f;
            rateLabel.setText(String.format(Locale.US, "%.2f×", rate));
          }

          public void onStartTrackingTouch(SeekBar bar) {}

          public void onStopTrackingTouch(SeekBar bar) {
            getSharedPreferences("playback", MODE_PRIVATE).edit().putFloat("rate", rate).apply();
            if (playback != null) playback.setRate(rate);
          }
        });
    loop = new CheckBox(this);
    loop.setText("循环");
    loop.setTextSize(13);
    loop.setOnCheckedChangeListener(
        (b, checked) -> {
          if (playback != null) playback.setLoop(checked);
        });
    tuning.addView(loop);
    LinearLayout repetitions = ui.row();
    footer.addView(repetitions);
    repetitions.addView(ui.text("整项播放", 13, MUTED));
    repeatSelect = new Spinner(this);
    repeatSelect.setAdapter(
        new ArrayAdapter<>(
            this,
            android.R.layout.simple_spinner_dropdown_item,
            new String[] {"1 次", "2 次", "3 次"}));
    repeatSelect.setSelection(
        getSharedPreferences("playback", MODE_PRIVATE).getInt("repeats", 1) - 1);
    repeatSelect.setOnItemSelectedListener(
        new AdapterView.OnItemSelectedListener() {
          public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
            if (playback != null) playback.setRepeats(position + 1);
          }

          public void onNothingSelected(AdapterView<?> parent) {}
        });
    repetitions.addView(repeatSelect, ui.weighted());
    repetitions.addView(ui.text("单词＋例句为一遍", 12, MUTED));
    caching = ui.row();
    footer.addView(caching);
    precacheButton = ui.button("预缓存词表", v -> precache());
    caching.addView(precacheButton, ui.weighted());
    caching.addView(ui.button("缓存管理", v -> showCache()), ui.weighted());
  }

  private WordStore.Deck current() {
    return decks.get(deckIndex);
  }

  private void refreshDecks() {
    update();
  }

  private void update() {
    boolean has = !decks.isEmpty();
    back.setVisibility(home && !profile ? View.GONE : View.VISIBLE);
    pageTitle.setText(profile ? "个人主页" : home ? "我的词表" : has ? current().name : "词表");
    importRow.setVisibility(home && !profile ? View.VISIBLE : View.GONE);
    detailRow.setVisibility(!home && !profile && has ? View.VISIBLE : View.GONE);
    empty.setVisibility(!profile && !has ? View.VISIBLE : View.GONE);
    list.setVisibility(!profile && has ? View.VISIBLE : View.GONE);
    profileView.setVisibility(profile ? View.VISIBLE : View.GONE);
    footer.setVisibility(
        !profile && has && (!home || playing || warming) ? View.VISIBLE : View.GONE);
    tuning.setVisibility(home ? View.GONE : View.VISIBLE);
    caching.setVisibility(home ? View.GONE : View.VISIBLE);
    sentence.setVisibility(home ? View.GONE : View.VISIBLE);
    importButton.setEnabled(!importing);
    remove.setEnabled(has && !importing);
    play.setEnabled(has && ready && !importing);
    play.setText(playing ? "暂停" : "播放词表");
    precacheButton.setText(warming ? "取消预缓存" : "预缓存词表");
    precacheButton.setEnabled(warming || (has && ready && !importing));
    if (has) {
      wordIndex = Math.max(0, Math.min(wordIndex, current().entries.size() - 1));
      WordEntry e = current().entries.get(wordIndex);
      List<String> fragments = e.fragments();
      if (temporaryExample.isEmpty()) phase = Math.max(0, Math.min(phase, fragments.size() - 1));
      word.setText(e.word);
      sentence.setText(
          !temporaryExample.isEmpty()
              ? "临时 AI · " + temporaryExample
              : phase > 0
                  ? fragments.get(phase)
                  : fragments.size() > 1 ? fragments.get(1) : "暂无例句，可点击单词添加");
      progress.setText(
          current().name
              + " · "
              + (wordIndex + 1)
              + "/"
              + current().entries.size()
              + " · "
              + (!temporaryExample.isEmpty()
                  ? "临时 AI 例句"
                  : phase == 0 ? "单词" : "例句 " + phase + "/" + (fragments.size() - 1)));
    }
    entriesAdapter.submit(decks, home, deckIndex, wordIndex);
  }

  private void showEntry(int index) {
    if (!importing && !decks.isEmpty()) entryDialogs.show(index, current().entries.get(index));
  }

  private void editExample(int index, int example, String suggestion) {
    if (!importing) entryDialogs.edit(index, current().entries.get(index), example, suggestion);
  }

  private void saveEntry(int index, WordEntry entry) {
    if (importing) return;
    pause();
    int selected = deckIndex;
    List<WordEntry> entries = new ArrayList<>(current().entries);
    entries.set(index, entry);
    List<WordStore.Deck> next = new ArrayList<>(decks);
    next.set(selected, new WordStore.Deck(current().name, entries));
    importing = true;
    status.setText("正在保存例句…");
    update();
    worker.execute(
        () -> {
          try {
            store.save(next);
            runOnUiThread(
                () -> {
                  if (destroyed) return;
                  decks.clear();
                  decks.addAll(next);
                  deckIndex = selected;
                  wordIndex = index;
                  phase = 0;
                  syncDeck();
                  importing = false;
                  update();
                  status.setText("例句已保存");
                });
          } catch (Exception e) {
            runOnUiThread(
                () -> {
                  if (destroyed) return;
                  importing = false;
                  update();
                  message("保存失败", "原有例句已保留，请重试。");
                });
          }
        });
  }

  @Override
  public void onBackPressed() {
    if (profile) {
      profile = false;
      update();
    } else if (!home) {
      home = true;
      update();
    } else super.onBackPressed();
  }

  @Override
  protected void onNewIntent(Intent intent) {
    super.onNewIntent(intent);
    setIntent(intent);
    if (intent.getBooleanExtra("showPlayer", false)) {
      home = false;
      profile = false;
      update();
    }
  }

  private void rebuildProfile() {
    if (profileView != null) profileView.rebuild();
  }

  private void cancelAi() {
    if (aiRequest != null) {
      aiRequest.cancel();
      aiRequest = null;
    }
    if (aiProgress != null) {
      aiProgress.dismiss();
      aiProgress = null;
    }
  }

  private void generateExample(int index) {
    if (importing) return;
    if (!NetworkStatus.online(this)) {
      message("当前没有网络", "请连接网络后重试。离线听读仍可使用。");
      return;
    }
    if (current().entries.get(index).customExamples.size() >= 20) {
      message("例句数量已达上限", "每个单词最多 20 条自定义例句，请先删除一条再生成。");
      return;
    }
    String provider = aiSettings.provider();
    if (!aiSettings.hasKey(provider)) {
      new AlertDialog.Builder(this)
          .setTitle("先配置 " + provider + " API Key")
          .setMessage("请在个人主页选择服务商并保存自己的 Key。")
          .setNegativeButton("取消", null)
          .setPositiveButton(
              "前往设置",
              (d, w) -> {
                profile = true;
                rebuildProfile();
                update();
              })
          .show();
      return;
    }
    final int selected = deckIndex;
    final WordEntry original = current().entries.get(index);
    SentenceGenerator job = new SentenceGenerator();
    aiRequest = job;
    String model = aiSettings.model();
    aiProgress =
        new AlertDialog.Builder(this)
            .setTitle("正在生成例句")
            .setMessage(provider + " 正在为「" + original.word + "」生成例句，请稍候。")
            .setNegativeButton("取消", (d, w) -> cancelAi())
            .setOnCancelListener(d -> cancelAi())
            .create();
    aiProgress.show();
    aiWorker.execute(
        () -> {
          try {
            String generated =
                job.generate(original.word, provider, model, aiSettings.key(provider));
            runOnUiThread(
                () -> {
                  if (destroyed || aiRequest != job) return;
                  aiRequest = null;
                  aiProgress.dismiss();
                  aiProgress = null;
                  if (deckIndex == selected && current().entries.get(index) == original)
                    editExample(index, -1, generated);
                });
          } catch (Exception e) {
            runOnUiThread(
                () -> {
                  if (destroyed || aiRequest != job) return;
                  aiRequest = null;
                  aiProgress.dismiss();
                  aiProgress = null;
                  String reason =
                      e instanceof java.net.SocketTimeoutException
                          ? "请求超时，请稍后重试"
                          : e instanceof IOException
                                  && e.getMessage() != null
                                  && !e.getMessage().contains("http")
                              ? e.getMessage()
                              : "网络或密钥读取失败，请检查连接及个人设置后重试";
                  message("生成失败", reason);
                });
          }
        });
  }

  private void syncDeck() {
    if (playback != null)
      playback.selectDeck(deckIndex, decks.isEmpty() ? null : current(), wordIndex, phase);
  }

  private void start() {
    if (!ready || decks.isEmpty() || importing || playback == null) return;
    try {
      startForegroundService(
          new Intent(this, PlaybackService.class).setAction(PlaybackService.PLAY));
    } catch (IllegalStateException | SecurityException e) {
      status.setText("无法启动后台播放，请回到应用内重试");
    }
  }

  private void pause() {
    if (playback != null) playback.pause("已暂停 · 继续时重读当前片段");
    else {
      playing = false;
      update();
    }
  }

  private void move(int delta) {
    if (!decks.isEmpty() && !importing && playback != null) playback.move(delta);
  }

  private void precache() {
    if (playback == null) return;
    if (warming) {
      playback.cancelWarm();
      return;
    }
    if (!ready || decks.isEmpty() || importing) return;
    new AlertDialog.Builder(this)
        .setTitle("预缓存「" + current().name + "」？")
        .setMessage(
            "按当前语速生成单词和例句，之后可直接复用。\n\n"
                + "合成可能耗时较长，可息屏等待或随时取消；播放、切词或调速会停止预缓存，已完成的音频会保留。\n\n"
                + "语音缓存上限 256 MB。达到上限后批量缓存会停止，避免挤掉已完成的内容。")
        .setNegativeButton("取消", null)
        .setPositiveButton("开始缓存", (d, w) -> requestWarm())
        .show();
  }

  private void requestWarm() {
    if (Build.VERSION.SDK_INT >= 33
        && checkSelfPermission("android.permission.POST_NOTIFICATIONS")
            != android.content.pm.PackageManager.PERMISSION_GRANTED
        && !getPreferences(0).getBoolean("cacheNoticeAsked", false)) {
      getPreferences(0).edit().putBoolean("cacheNoticeAsked", true).apply();
      warmAfterPermission = true;
      requestPermissions(new String[] {"android.permission.POST_NOTIFICATIONS"}, 21);
      return;
    }
    startWarm();
  }

  private void startWarm() {
    if (!ready || decks.isEmpty() || importing || playback == null) return;
    try {
      startForegroundService(
          new Intent(this, PlaybackService.class).setAction(PlaybackService.PRECACHE));
    } catch (IllegalStateException | SecurityException e) {
      status.setText("无法启动后台缓存，请回到应用内重试");
    }
  }

  @Override
  public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grants) {
    super.onRequestPermissionsResult(requestCode, permissions, grants);
    if (requestCode == 21) {
      boolean resume = warmAfterPermission;
      warmAfterPermission = false;
      if (resume && bound) startWarm();
    }
  }

  private void showCache() {
    String description =
        String.format(
            Locale.CHINA,
            "已保存 %d 段语音，占用 %.1f / 256 MB。\n\n"
                + "首次听读会自动缓存完整音频。同一文本、语速和声音再次播放时直接复用；换语速会生成另一份。\n\n"
                + "达到上限后，普通播放会淘汰较久未用的缓存。系统清理应用缓存后需要重新生成。清理语音不会删除词表或模型。",
            cacheEntries,
            cacheBytes / (1024d * 1024d));
    new AlertDialog.Builder(this)
        .setTitle("语音缓存")
        .setMessage(description)
        .setNegativeButton("关闭", null)
        .setPositiveButton(
            "清理语音缓存",
            (d, w) ->
                new AlertDialog.Builder(this)
                    .setTitle("清理所有语音缓存？")
                    .setMessage("将停止当前播放和预缓存。只删除合成音频，词表和 Kokoro 模型会保留。")
                    .setNegativeButton("取消", null)
                    .setPositiveButton(
                        "清理",
                        (dialog, which) -> {
                          if (playback != null) playback.clearCache();
                        })
                    .show())
        .show();
  }

  private void chooseCsv() {
    if (importing) return;
    pause();
    importTarget = home ? -1 : deckIndex;
    Intent intent =
        new Intent(Intent.ACTION_OPEN_DOCUMENT)
            .setType("*/*")
            .addCategory(Intent.CATEGORY_OPENABLE)
            .putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
    try {
      startActivityForResult(intent, 10);
    } catch (ActivityNotFoundException e) {
      message("无法选择文件", "请安装或启用系统文件管理器。");
    }
  }

  @Override
  protected void onActivityResult(int request, int result, Intent data) {
    super.onActivityResult(request, result, data);
    if (request != 10 || result != RESULT_OK || data == null) return;
    List<Uri> uris = new ArrayList<>();
    if (data.getClipData() != null) {
      for (int i = 0; i < data.getClipData().getItemCount(); i++) {
        Uri uri = data.getClipData().getItemAt(i).getUri();
        if (uri != null && !uris.contains(uri)) uris.add(uri);
      }
    } else if (data.getData() != null) uris.add(data.getData());
    if (uris.isEmpty()) return;
    if (uris.size() > 50) {
      message("文件过多", "一次最多导入 50 个文件。");
      return;
    }
    final int target = importTarget;
    final List<WordStore.Deck> snapshot = new ArrayList<>(decks);
    importing = true;
    status.setText("正在合并导入…");
    update();
    worker.execute(
        () -> {
          try {
            CsvImportReader reader = new CsvImportReader(getContentResolver());
            WordStore.Deck base = target >= 0 ? snapshot.get(target) : null;
            List<WordEntry> merged = base == null ? new ArrayList<>() : base.entries;
            String name = base == null ? "" : base.name;
            for (Uri uri : uris) {
              WordStore.Deck file = reader.read(uri);
              if (name.isEmpty()) name = file.name;
              merged = DeckMerger.merge(merged, file.entries);
            }
            WordStore.Deck updated = new WordStore.Deck(name, merged);
            List<WordStore.Deck> next = new ArrayList<>(snapshot);
            int selected = target >= 0 ? target : next.size();
            if (target >= 0) next.set(target, updated);
            else next.add(updated);
            store.save(next);
            runOnUiThread(
                () -> {
                  if (destroyed) return;
                  decks.clear();
                  decks.addAll(next);
                  deckIndex = selected;
                  wordIndex = 0;
                  phase = 0;
                  home = false;
                  profile = false;
                  syncDeck();
                  finishImport();
                  status.setText(
                      "已合并 "
                          + uris.size()
                          + " 个文件，共 "
                          + updated.entries.size()
                          + " 个不同单词；重复词已累加词频");
                });
          } catch (Exception e) {
            runOnUiThread(
                () -> {
                  if (destroyed) return;
                  finishImport();
                  message(
                      "导入失败",
                      e instanceof CharacterCodingException
                          ? "文件不是有效的 UTF-8 编码。请另存为 CSV UTF-8 后重新导入。"
                          : (e.getMessage() == null ? "无法读取该文件，请重试。" : e.getMessage()));
                  status.setText("导入失败，整批文件均未保存，原词表已保留");
                });
          }
        });
  }

  private void finishImport() {
    importing = false;
    importButton.setEnabled(true);
    update();
  }

  private void deleteDeck() {
    if (decks.isEmpty() || importing) return;
    pause();
    new AlertDialog.Builder(this)
        .setTitle("删除「" + current().name + "」？")
        .setMessage("删除后可通过原 CSV 重新导入。")
        .setNegativeButton("取消", null)
        .setPositiveButton(
            "删除",
            (d, w) -> {
              List<WordStore.Deck> next = new ArrayList<>(decks);
              next.remove(deckIndex);
              try {
                store.save(next);
                decks.clear();
                decks.addAll(next);
                deckIndex = 0;
                wordIndex = 0;
                phase = 0;
                home = true;
                syncDeck();
                refreshDecks();
              } catch (Exception e) {
                message("删除失败", "无法保存更改，请重试。");
              }
            })
        .show();
  }

  @Override
  protected void onSaveInstanceState(Bundle out) {
    super.onSaveInstanceState(out);
    out.putInt("importTarget", importTarget);
    out.putBoolean("profile", profile);
    out.putBoolean("home", home);
    out.putInt("deck", deckIndex);
    out.putInt("word", wordIndex);
    out.putInt("phase", phase);
  }

  @Override
  protected void onStart() {
    super.onStart();
    bound =
        bindService(new Intent(this, PlaybackService.class), connection, Context.BIND_AUTO_CREATE);
  }

  @Override
  protected void onStop() {
    cancelAi();
    if (playback != null) playback.removeListener(playbackListener);
    if (bound) {
      unbindService(connection);
      bound = false;
    }
    playback = null;
    ready = false;
    warmAfterPermission = false;
    super.onStop();
  }

  @Override
  protected void onDestroy() {
    destroyed = true;
    cancelAi();
    aiWorker.shutdownNow();
    worker.shutdown();
    super.onDestroy();
  }

  private void message(String title, String body) {
    new AlertDialog.Builder(this)
        .setTitle(title)
        .setMessage(body)
        .setPositiveButton("知道了", null)
        .show();
  }
}
