package cn.wordflow.app;

import java.io.*;
import java.nio.file.*;
import java.security.*;
import java.util.*;
import java.util.zip.CRC32;

/** Versioned, checksummed PCM-float files; incomplete writes are never cache hits. */
final class SpeechCache {
    static final long LIMIT = 256L * 1024 * 1024;
    private static final int MAGIC = 0x57464331, HEADER = 20;
    private static final Object LOCK = new Object();
    private final File directory;
    private final long limit;
    static final class Stats {
        final long bytes; final int entries;
        Stats(long bytes, int entries) { this.bytes = bytes; this.entries = entries; }
    }
    SpeechCache(File directory) { this(directory, LIMIT); }
    SpeechCache(File directory, long limit) { this.directory = directory; this.limit = limit; }
    static String key(String model, String text, int voice, float speed) {
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            try (DataOutputStream out = new DataOutputStream(bytes)) {
                out.writeUTF("pcm-float-v1"); out.writeUTF(model); out.writeUTF(text.trim());
                out.writeInt(voice); out.writeInt(Float.floatToIntBits(speed));
            }
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(bytes.toByteArray());
            StringBuilder result = new StringBuilder(64);
            for (byte b : digest) result.append("0123456789abcdef".charAt((b & 255) >>> 4)).append("0123456789abcdef".charAt(b & 15));
            return result.toString();
        } catch (IOException | NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }
    private File file(String key) {
        if (!key.matches("[0-9a-f]{64}")) throw new IllegalArgumentException("Invalid cache key");
        return new File(directory, key + ".pcm");
    }
    static final class Entry implements Closeable {
        final int sampleRate;
        final long frames;
        private final DataInputStream in;
        private long remaining;
        Entry(File file, int sampleRate, long frames) throws IOException {
            this.sampleRate = sampleRate; this.frames = frames; remaining = frames;
            in = new DataInputStream(new BufferedInputStream(new FileInputStream(file), 65536));
            in.readInt(); in.readInt(); in.readLong(); in.readInt();
        }
        int read(float[] buffer) throws IOException {
            int n = (int)Math.min(buffer.length, remaining);
            for (int i = 0; i < n; i++) buffer[i] = in.readFloat();
            remaining -= n; return n;
        }
        @Override public void close() throws IOException { in.close(); }
    }
    Entry open(String key) {
        synchronized (LOCK) {
            File file = file(key);
            if (!file.isFile()) return null;
            try {
                int rate; long frames; long checksum;
                try (DataInputStream in = new DataInputStream(new BufferedInputStream(new FileInputStream(file), 65536))) {
                    if (in.readInt() != MAGIC) throw new IOException("Invalid PCM header");
                    rate = in.readInt(); frames = in.readLong(); checksum = in.readInt() & 0xffffffffL;
                    if (rate < 8000 || rate > 192000 || frames <= 0 || frames > (limit - HEADER) / 4 || file.length() != HEADER + frames * 4) throw new IOException("Invalid PCM length");
                    CRC32 crc = new CRC32(); byte[] buffer = new byte[65536]; int n;
                    while ((n = in.read(buffer)) != -1) crc.update(buffer, 0, n);
                    if (crc.getValue() != checksum) throw new IOException("Invalid PCM checksum");
                }
                Entry entry = new Entry(file, rate, frames);
                file.setLastModified(System.currentTimeMillis());
                return entry;
            } catch (IOException e) { file.delete(); return null; }
        }
    }
    Writer begin(String key, int sampleRate) throws IOException {
        synchronized (LOCK) {
            File destination = file(key);
            if (!directory.isDirectory() && !directory.mkdirs()) throw new IOException("Cannot create speech cache");
            File temporary = File.createTempFile("audio-", ".tmp", directory);
            try { return new Writer(destination, temporary, sampleRate); }
            catch (IOException e) { temporary.delete(); throw e; }
        }
    }
    final class Writer implements Closeable {
        private final File destination, temporary;
        private final DataOutputStream out;
        private final CRC32 crc = new CRC32();
        private long frames;
        private boolean closed;
        Writer(File destination, File temporary, int sampleRate) throws IOException {
            this.destination = destination; this.temporary = temporary;
            out = new DataOutputStream(new BufferedOutputStream(new FileOutputStream(temporary), 65536));
            try { out.writeInt(MAGIC); out.writeInt(sampleRate); out.writeLong(0); out.writeInt(0); }
            catch (IOException e) { try { out.close(); } catch (IOException ignored) { } throw e; }
        }
        void append(float[] samples) throws IOException {
            if (closed) throw new IOException("Writer closed");
            if (HEADER + (frames + samples.length) * 4 > limit) throw new IOException("Clip exceeds cache limit");
            byte[] bytes = new byte[Math.min(samples.length, 4096) * 4];
            for (int start = 0; start < samples.length;) {
                int n = Math.min(bytes.length / 4, samples.length - start);
                for (int i = 0; i < n; i++) {
                    float sample = samples[start + i];
                    if (Float.isNaN(sample) || Float.isInfinite(sample)) throw new IOException("Invalid PCM sample");
                    int bits = Float.floatToIntBits(sample), p = i * 4;
                    bytes[p] = (byte)(bits >>> 24); bytes[p + 1] = (byte)(bits >>> 16); bytes[p + 2] = (byte)(bits >>> 8); bytes[p + 3] = (byte)bits;
                }
                out.write(bytes, 0, n * 4); crc.update(bytes, 0, n * 4); start += n;
            }
            frames += samples.length;
        }
        boolean hasSamples() { return frames > 0; }
        boolean commit(boolean evict) throws IOException {
            if (closed) throw new IOException("Writer closed");
            out.close(); closed = true;
            if (frames == 0) { temporary.delete(); return false; }
            try {
                try (RandomAccessFile header = new RandomAccessFile(temporary, "rw")) {
                    header.seek(8); header.writeLong(frames); header.writeInt((int)crc.getValue()); header.getFD().sync();
                }
                synchronized (LOCK) {
                    File[] files = files(); long bytes = 0;
                    for (File file : files) if (!file.equals(destination)) bytes += file.length();
                    long required = temporary.length();
                    if (bytes + required > limit) {
                        if (!evict) return false;
                        Arrays.sort(files, Comparator.comparingLong(File::lastModified));
                        for (File file : files) {
                            if (bytes + required <= limit) break;
                            if (file.equals(destination)) continue;
                            long length = file.length(); if (file.delete()) bytes -= length;
                        }
                    }
                    if (bytes + required > limit) return false;
                    Files.move(temporary.toPath(), destination.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
                    destination.setLastModified(System.currentTimeMillis());
                    return true;
                }
            } finally { temporary.delete(); }
        }
        @Override public void close() throws IOException {
            try { if (!closed) { closed = true; out.close(); } } finally { temporary.delete(); }
        }
    }
    private File[] files() {
        File[] files = directory.listFiles((dir, name) -> name.endsWith(".pcm"));
        return files == null ? new File[0] : files;
    }
    Stats stats() {
        synchronized (LOCK) {
            File[] files = files(); long bytes = 0; for (File file : files) bytes += file.length();
            return new Stats(bytes, files.length);
        }
    }
    void cleanTemporary() throws IOException {
        synchronized (LOCK) {
            File[] files = directory.listFiles((dir, name) -> name.endsWith(".tmp"));
            if (files != null) for (File file : files) if (!file.delete()) throw new IOException("Cannot remove partial audio");
        }
    }
    void clear() throws IOException {
        synchronized (LOCK) {
            File[] files = directory.listFiles();
            if (files == null) return;
            for (File file : files) if ((file.getName().endsWith(".pcm") || file.getName().endsWith(".tmp")) && !file.delete()) throw new IOException("Cannot remove speech cache");
        }
    }
}
