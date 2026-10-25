package cn.wordflow.app;

import android.content.*;
import java.time.*;
import java.util.*;

/** Persistent counters independent of Activity lifetime and speech cache. */
final class ListeningStats {
  private final SharedPreferences prefs;

  ListeningStats(Context context) {
    prefs = context.getSharedPreferences("listening-stats-v1", Context.MODE_PRIVATE);
  }

  void record(long endMillis, long audioMillis) {
    SharedPreferences.Editor edit = prefs.edit();
    for (Map.Entry<String, Long> e :
        ListeningLedger.split(endMillis, audioMillis, ZoneId.systemDefault()).entrySet())
      edit.putLong(e.getKey(), prefs.getLong(e.getKey(), 0) + e.getValue());
    edit.apply();
  }

  Map<String, Long> days() {
    Map<String, Long> days = new TreeMap<>(Collections.reverseOrder());
    for (Map.Entry<String, ?> e : prefs.getAll().entrySet())
      if (e.getValue() instanceof Long) days.put(e.getKey(), (Long) e.getValue());
    return days;
  }

  String summary() {
    Map<String, Long> days = days();
    long total = 0;
    for (long duration : days.values()) total += duration;
    String today = LocalDate.now().toString();
    StringBuilder text =
        new StringBuilder("今日  ")
            .append(ListeningLedger.format(days.getOrDefault(today, 0L)))
            .append("\n累计  ")
            .append(ListeningLedger.format(total))
            .append("\n\n每日记录（最近 30 个有收听的日期）");
    int shown = 0;
    for (Map.Entry<String, Long> e : days.entrySet()) {
      if (++shown > 30) break;
      text.append('\n')
          .append(e.getKey())
          .append("  ")
          .append(ListeningLedger.format(e.getValue()));
    }
    return text.toString();
  }
}
