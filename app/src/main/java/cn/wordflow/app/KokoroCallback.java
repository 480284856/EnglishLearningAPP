package cn.wordflow.app;

/** sherpa JNI requires invoke(float[]):Integer, which a generic Java lambda lacks. */
abstract class KokoroCallback implements kotlin.jvm.functions.Function1<float[], Integer> {
  @Override
  public final Integer invoke(float[] samples) {
    return onSamples(samples);
  }

  protected abstract int onSamples(float[] samples);
}
