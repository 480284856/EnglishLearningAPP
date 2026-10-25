package cn.wordflow.app;

import java.time.*;
import java.util.*;

/** Splits actual rendered PCM time by the phone's local date, including midnight/DST. */
final class ListeningLedger {
  static Map<String, Long> split(long endMillis, long audioMillis, ZoneId zone) {
    Map<String, Long> result = new LinkedHashMap<>();
    if (audioMillis <= 0) return result;
    long cursor = endMillis - audioMillis;
    while (cursor < endMillis) {
      LocalDate day = Instant.ofEpochMilli(cursor).atZone(zone).toLocalDate();
      long boundary = day.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli();
      long until = Math.min(endMillis, boundary);
      result.merge(day.toString(), until - cursor, Long::sum);
      cursor = until;
    }
    return result;
  }

  static String format(long millis) {
    long seconds = Math.max(0, millis / 1000);
    return String.format(
        Locale.ROOT, "%02d:%02d:%02d", seconds / 3600, seconds / 60 % 60, seconds % 60);
  }
}
