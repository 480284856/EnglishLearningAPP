package cn.wordflow.app;

/** Main-thread playback cursor; tokens reject callbacks from cancelled utterances. */
final class PlaybackSequence {
    int word, phase;
    private long generation;
    private String active = "";
    void reset(int word, int phase) { cancel(); this.word = word; this.phase = phase; }
    void cancel() { generation++; active = ""; }
    String begin() { active = (++generation) + ":" + word + ":" + phase; return active; }
    boolean matches(String token) { return !active.isEmpty() && active.equals(token); }
    boolean advance(String token, int size, boolean loop) {
        return advance(token, size, loop, 1);
    }
    boolean advance(String token, int size, boolean loop, int lastPhase) {
        if (!matches(token)) return false;
        active = "";
        if (phase < lastPhase) phase++;
        else { phase = 0; word++; if (word >= size) { word = 0; return loop; } }
        return true;
    }
}
