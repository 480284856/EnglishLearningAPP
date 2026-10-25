package cn.wordflow.app;
import com.k2fsa.sherpa.onnx.*;
public class KokoroJniSmoke {
    public static void main(String[] args) {
        String root = args[0];
        OfflineTtsConfig config = new OfflineTtsConfig();
        config.getModel().setNumThreads(2);
        var k = config.getModel().getKokoro();
        k.setModel(root + "/model.int8.onnx"); k.setVoices(root + "/voices.bin");
        k.setTokens(root + "/tokens.txt"); k.setDataDir(root + "/espeak-ng-data"); k.setLang("en-us");
        OfflineTts model = new OfflineTts(null, config);
        try {
            long[] count = {0};
            GeneratedAudio audio = model.generateWithCallback("Hello, learner.", 0, 0.85f, new KokoroCallback() { protected int onSamples(float[] pcm) { count[0] += pcm.length; return 1; } });
            float peak = 0;
            for (float sample : audio.getSamples()) {
                if (!Float.isFinite(sample)) throw new AssertionError("Non-finite audio");
                peak = Math.max(peak, Math.abs(sample));
            }
            if (count[0] <= 0 || audio.getSamples().length != count[0] || peak < 0.001f) throw new AssertionError("No valid PCM");
            int[] callbacks = {0};
            model.generateWithCallback("First sentence. Second sentence. Third sentence.", 0, 1.0f, new KokoroCallback() { protected int onSamples(float[] pcm) { callbacks[0]++; return 0; } });
            if (callbacks[0] != 1) throw new AssertionError("Cancellation did not stop callbacks");
            System.out.println("JNI smoke passed: " + model.sampleRate() + " Hz, " + model.numSpeakers() + " voices, " + count[0] + " samples, peak=" + peak + ", callback cancellation passed");
        } finally { model.release(); }
    }
}
