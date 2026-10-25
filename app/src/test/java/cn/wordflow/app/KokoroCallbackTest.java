package cn.wordflow.app;

import static org.junit.Assert.*;

import org.junit.Test;

public class KokoroCallbackTest {
  @Test
  public void exposesExactSignatureLookedUpBySherpaJni() throws Exception {
    KokoroCallback callback =
        new KokoroCallback() {
          @Override
          protected int onSamples(float[] pcm) {
            return pcm.length == 2 ? 1 : 0;
          }
        };
    var entry = callback.getClass().getMethod("invoke", float[].class);
    assertEquals(Integer.class, entry.getReturnType());
    assertEquals(1, entry.invoke(callback, new float[] {0.1f, 0.2f}));
  }

  @Test
  public void preservesCancellationReturnCode() {
    KokoroCallback callback =
        new KokoroCallback() {
          @Override
          protected int onSamples(float[] pcm) {
            return 0;
          }
        };
    assertEquals(Integer.valueOf(0), callback.invoke(new float[] {0.5f}));
  }
}
