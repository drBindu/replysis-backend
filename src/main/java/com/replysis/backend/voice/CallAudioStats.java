package com.replysis.backend.voice;

import java.util.Base64;
import java.util.Map;
import java.util.TreeMap;

/**
 * What one phone call's audio looked like, per track, so a test call can say in numbers whether both people were heard.
 * Twilio sends 8 kHz mu-law, 20 ms a frame. Nothing is kept except these counts: no audio, no words.
 */
public final class CallAudioStats {

    public static final class Track {
        public long frames;
        public long bytes;
        /** Frames loud enough to be speech rather than line hiss. */
        public long speechFrames;
        public double peakRms;
    }

    /** Below this a 20 ms frame is treated as silence or hiss. Speech on a phone line is far above it. */
    static final double SPEECH_RMS = 500.0;

    private final Map<String, Track> tracks = new TreeMap<>();

    public void add(String track, String base64Payload) {
        byte[] pcm = Base64.getDecoder().decode(base64Payload);
        Track t = tracks.computeIfAbsent(track, k -> new Track());
        t.frames++;
        t.bytes += pcm.length;
        double rms = rms(pcm);
        if (rms >= SPEECH_RMS) t.speechFrames++;
        if (rms > t.peakRms) t.peakRms = rms;
    }

    public Map<String, Track> tracks() { return tracks; }

    /** Seconds of speech heard on a track: 20 ms a frame. */
    public double speechSeconds(String track) {
        Track t = tracks.get(track);
        return t == null ? 0 : t.speechFrames * 0.02;
    }

    /** Root mean square of a mu-law frame once decoded to 16 bit. */
    static double rms(byte[] mulaw) {
        if (mulaw.length == 0) return 0;
        double sum = 0;
        for (byte b : mulaw) {
            int s = decode(b);
            sum += (double) s * s;
        }
        return Math.sqrt(sum / mulaw.length);
    }

    /** ITU G.711 mu-law to linear 16 bit. */
    static int decode(byte value) {
        int u = ~value & 0xFF;
        int sign = u & 0x80;
        int exponent = (u >> 4) & 0x07;
        int mantissa = u & 0x0F;
        int sample = ((mantissa << 3) + 0x84) << exponent;
        sample -= 0x84;
        return sign != 0 ? -sample : sample;
    }

    public String summary() {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, Track> e : tracks.entrySet()) {
            Track t = e.getValue();
            sb.append(String.format("%s: %.1fs audio, %.1fs speech, peak %.0f; ",
                    e.getKey(), t.frames * 0.02, t.speechFrames * 0.02, t.peakRms));
        }
        return sb.length() == 0 ? "no audio received" : sb.toString().trim();
    }
}
