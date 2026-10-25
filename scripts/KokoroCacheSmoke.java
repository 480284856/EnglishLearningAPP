package cn.wordflow.app;

import com.k2fsa.sherpa.onnx.*;
import java.io.*;

/** Linux JNI smoke test using the application's real cache and native callback classes. */
public class KokoroCacheSmoke {
    public static void main(String[] args) throws Exception {
        String root = args[0]; File directory = new File(args[1]);
        SpeechCache cache = new SpeechCache(directory); cache.clear();
        OfflineTtsConfig config = new OfflineTtsConfig(); config.getModel().setNumThreads(2);
        var k = config.getModel().getKokoro();
        k.setModel(root + "/model.int8.onnx"); k.setVoices(root + "/voices.bin");
        k.setTokens(root + "/tokens.txt"); k.setDataDir(root + "/espeak-ng-data"); k.setLang("en-us");
        OfflineTts model = new OfflineTts(null, config);
        String key = SpeechCache.key("smoke-model", "Hello, learner.", 0, 0.85f);
        try {
            int generated = 0; long expectedFrames; float peak = 0;
            try (SpeechCache.Writer writer = cache.begin(key, model.sampleRate())) {
                boolean[] failed = {false};
                GeneratedAudio audio = model.generateWithCallback("Hello, learner.", 0, 0.85f, new KokoroCallback() {
                    @Override protected int onSamples(float[] pcm) {
                        try { writer.append(pcm); return 1; } catch (IOException e) { failed[0] = true; return 0; }
                    }
                });
                generated++; expectedFrames = audio.getSamples().length;
                if (failed[0] || expectedFrames <= 0 || !writer.commit(true)) throw new AssertionError("Native cache write failed");
                for (float sample : audio.getSamples()) peak = Math.max(peak, Math.abs(sample));
            }
            // Simulate a restarted app: a new cache instance must satisfy playback
            // without a second call to model.generateWithCallback.
            SpeechCache reopened = new SpeechCache(directory);
            try (SpeechCache.Entry hit = reopened.open(key)) {
                if (hit == null || hit.frames != expectedFrames || hit.sampleRate != 24000) throw new AssertionError("Cache miss after restart");
                float[] pcm = new float[4096]; int n; long frames = 0; float cachedPeak = 0;
                while ((n = hit.read(pcm)) > 0) {
                    frames += n;
                    for (int i = 0; i < n; i++) cachedPeak = Math.max(cachedPeak, Math.abs(pcm[i]));
                }
                if (frames != expectedFrames || cachedPeak != peak || generated != 1) throw new AssertionError("Cached PCM differs");
            }
            String differentRate = SpeechCache.key("smoke-model", "Hello, learner.", 0, 1f);
            if (reopened.open(differentRate) != null) throw new AssertionError("Wrong speed reused");
            String cancelled = SpeechCache.key("smoke-model", "First sentence. Second sentence.", 0, 1f);
            int[] callbacks = {0};
            try (SpeechCache.Writer partial = cache.begin(cancelled, model.sampleRate())) {
                model.generateWithCallback("First sentence. Second sentence.", 0, 1f, new KokoroCallback() {
                    @Override protected int onSamples(float[] pcm) {
                        callbacks[0]++;
                        try { partial.append(pcm); } catch (IOException ignored) { }
                        return 0;
                    }
                });
                // Cancelled requests intentionally never commit.
            }
            if (callbacks[0] != 1 || cache.open(cancelled) != null || cache.stats().entries != 1) throw new AssertionError("Cancelled audio became a cache hit");
            System.out.println("Cache JNI smoke passed: " + expectedFrames + " exact PCM frames persisted; restart hit skipped synthesis; speed isolated; cancelled partial not cached");
        } finally { model.release(); }
    }
}
