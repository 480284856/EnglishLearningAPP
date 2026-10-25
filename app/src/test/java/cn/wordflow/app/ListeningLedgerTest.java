package cn.wordflow.app;

import static org.junit.Assert.*;

import java.time.*;
import java.util.*;
import org.junit.Test;

public class ListeningLedgerTest {
  @Test
  public void countsOnlyRenderedDurationAndSplitsAtLocalMidnight() {
    ZoneId zone = ZoneId.of("Asia/Shanghai");
    long end = ZonedDateTime.of(2026, 10, 26, 0, 0, 2, 0, zone).toInstant().toEpochMilli();
    assertEquals(
        Map.of("2026-10-25", 3000L, "2026-10-26", 2000L), ListeningLedger.split(end, 5000, zone));
    assertTrue(ListeningLedger.split(end, 0, zone).isEmpty());
  }

  @Test
  public void daylightSavingDaysAreNotAssumedToBe24Hours() {
    ZoneId zone = ZoneId.of("America/New_York");
    long start = LocalDate.of(2026, 3, 8).atStartOfDay(zone).toInstant().toEpochMilli();
    long end = LocalDate.of(2026, 3, 9).atStartOfDay(zone).toInstant().toEpochMilli();
    assertEquals(23 * 3600000L, end - start);
    assertEquals(
        Map.of("2026-03-08", 23 * 3600000L), ListeningLedger.split(end, end - start, zone));
  }

  @Test
  public void formattingKeepsSecondsAndUnboundedTotalHours() {
    assertEquals("00:00:00", ListeningLedger.format(0));
    assertEquals("01:02:03", ListeningLedger.format(3723999));
    assertEquals("123:00:00", ListeningLedger.format(123 * 3600000L));
  }
}
