package cn.wordflow.app;

import static org.junit.Assert.*;

import java.io.*;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public class SpeechCacheTest {
  @Rule public TemporaryFolder temp = new TemporaryFolder();

  private String key(String text) {
    return SpeechCache.key("kokoro-model-a", text, 0, 0.85f);
  }

  private void put(SpeechCache cache, String text, float... samples) throws Exception {
    try (SpeechCache.Writer out = cache.begin(key(text), 24000)) {
      out.append(samples);
      assertTrue(out.commit(true));
    }
  }

  @Test
  public void persistsExactAudioAcrossCacheInstances() throws Exception {
    File dir = temp.newFolder();
    SpeechCache first = new SpeechCache(dir);
    put(first, "hello", 0.1f, -0.25f, 0.5f);
    SpeechCache reopened = new SpeechCache(dir);
    try (SpeechCache.Entry entry = reopened.open(key("hello"))) {
      assertNotNull(entry);
      assertEquals(24000, entry.sampleRate);
      assertEquals(3, entry.frames);
      float[] pcm = new float[4];
      assertEquals(3, entry.read(pcm));
      assertArrayEquals(new float[] {0.1f, -0.25f, 0.5f, 0}, pcm, 0);
      assertEquals(0, entry.read(pcm));
    }
    assertEquals(1, reopened.stats().entries);
  }

  @Test
  public void speedVoiceModelAndTextHaveSeparateKeys() {
    String normal = key("Hello");
    assertEquals(normal, key(" Hello "));
    assertNotEquals(normal, key("hello"));
    assertNotEquals(normal, SpeechCache.key("kokoro-model-b", "Hello", 0, 0.85f));
    assertNotEquals(normal, SpeechCache.key("kokoro-model-a", "Hello", 1, 0.85f));
    assertNotEquals(normal, SpeechCache.key("kokoro-model-a", "Hello", 0, 1.0f));
  }

  @Test
  public void incompleteAndCancelledWritesNeverReplaceValidAudio() throws Exception {
    SpeechCache cache = new SpeechCache(temp.newFolder());
    try (SpeechCache.Writer writer = cache.begin(key("cancelled"), 24000)) {
      writer.append(new float[] {0.1f});
      assertNull(cache.open(key("cancelled")));
    }
    assertNull(cache.open(key("cancelled")));
    assertEquals(0, cache.stats().entries);
    put(cache, "existing", 0.25f);
    try (SpeechCache.Writer writer = cache.begin(key("existing"), 24000)) {
      writer.append(new float[] {0.9f});
    }
    try (SpeechCache.Entry old = cache.open(key("existing"))) {
      float[] pcm = new float[1];
      old.read(pcm);
      assertEquals(0.25f, pcm[0], 0);
    }
  }

  @Test
  public void detectsPayloadCorruptionAndTruncation() throws Exception {
    File dir = temp.newFolder();
    SpeechCache cache = new SpeechCache(dir);
    put(cache, "corrupt", 0.25f, 0.5f);
    try (RandomAccessFile file =
        new RandomAccessFile(new File(dir, key("corrupt") + ".pcm"), "rw")) {
      file.seek(21);
      file.writeByte(0x77);
    }
    assertNull(cache.open(key("corrupt")));
    assertEquals(0, cache.stats().entries);
    put(cache, "truncated", 0.25f);
    try (RandomAccessFile file =
        new RandomAccessFile(new File(dir, key("truncated") + ".pcm"), "rw")) {
      file.setLength(file.length() - 1);
    }
    assertNull(cache.open(key("truncated")));
  }

  @Test
  public void evictsLeastRecentlyUsedAudioWithinBudget() throws Exception {
    File dir = temp.newFolder();
    SpeechCache cache = new SpeechCache(dir, 100);
    for (int i = 0; i < 3; i++) {
      put(cache, "item" + i, 0.1f, 0.2f, 0.3f);
      assertTrue(new File(dir, key("item" + i) + ".pcm").setLastModified(1000L + i));
    }
    try (SpeechCache.Entry touched = cache.open(key("item0"))) {
      assertNotNull(touched);
    }
    put(cache, "item3", 0.1f, 0.2f, 0.3f);
    assertNull(cache.open(key("item1")));
    try (SpeechCache.Entry kept = cache.open(key("item0"))) {
      assertNotNull(kept);
    }
    assertTrue(cache.stats().bytes <= 100);
    assertEquals(3, cache.stats().entries);
  }

  @Test
  public void bulkCacheStopsAtLimitWithoutEvictingCompletedEntries() throws Exception {
    File dir = temp.newFolder();
    SpeechCache cache = new SpeechCache(dir, 64);
    put(cache, "one", 0.1f, 0.2f, 0.3f);
    put(cache, "two", 0.1f, 0.2f, 0.3f);
    try (SpeechCache.Writer writer = cache.begin(key("three"), 24000)) {
      writer.append(new float[] {0.1f});
      assertFalse(writer.commit(false));
    }
    assertNull(cache.open(key("three")));
    assertEquals(2, cache.stats().entries);
    try (SpeechCache.Entry first = cache.open(key("one"))) {
      assertNotNull(first);
    }
    assertEquals(2, dir.list().length);
  }

  @Test
  public void clearRemovesOnlyAudioAndTemporaryFiles() throws Exception {
    File parent = temp.newFolder();
    File dir = new File(parent, "cache");
    SpeechCache cache = new SpeechCache(dir);
    File words = new File(parent, "decks.json");
    try (FileOutputStream out = new FileOutputStream(words)) {
      out.write(1);
    }
    put(cache, "one", 0.1f);
    File unrelated = new File(dir, "unrelated.txt");
    assertTrue(unrelated.createNewFile());
    cache.clear();
    assertNull(cache.open(key("one")));
    assertEquals(0, cache.stats().bytes);
    assertTrue(words.isFile());
    assertTrue(unrelated.isFile());
  }

  @Test
  public void removesOrphanTemporaryFilesWithoutDeletingCompletedAudio() throws Exception {
    File dir = temp.newFolder();
    SpeechCache cache = new SpeechCache(dir);
    put(cache, "kept", 0.1f);
    File orphan = new File(dir, "audio-orphan.tmp");
    assertTrue(orphan.createNewFile());
    cache.cleanTemporary();
    assertFalse(orphan.exists());
    try (SpeechCache.Entry entry = cache.open(key("kept"))) {
      assertNotNull(entry);
    }
  }

  @Test
  public void rejectsEmptyAndNonFiniteAudioWithoutLeavingPlayableFiles() throws Exception {
    SpeechCache cache = new SpeechCache(temp.newFolder());
    try (SpeechCache.Writer writer = cache.begin(key("empty"), 24000)) {
      assertFalse(writer.commit(true));
    }
    try (SpeechCache.Writer writer = cache.begin(key("bad"), 24000)) {
      try {
        writer.append(new float[] {Float.NaN});
        fail();
      } catch (IOException expected) {
      }
    }
    assertEquals(0, cache.stats().entries);
  }
}
