package com.replysis.backend.voice;

import org.junit.jupiter.api.Test;

import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** The pieces of the phone proof of concept that must be exactly right: Twilio's signature and the audio measurement. */
class VoiceProofTests {

    @Test
    void twilioSignatureMatchesTheDocumentedExample() {
        // The worked example from Twilio's webhook security documentation.
        Map<String, String> params = new LinkedHashMap<>();
        params.put("CallSid", "CA1234567890ABCDE");
        params.put("Caller", "+14158675310");
        params.put("Digits", "1234");
        params.put("From", "+14158675310");
        params.put("To", "+18005551212");
        String signature = TwilioSignature.sign("12345", "https://mycompany.com/myapp.php?foo=1&bar=2", params);
        assertEquals("GvWf1cFY/Q7PnoempGyD5oXAezc=", signature);
    }

    @Test
    void aWrongOrMissingSignatureIsRefused() {
        Map<String, String> params = Map.of("CallSid", "CA1");
        String good = TwilioSignature.sign("token", "https://replysis.com/api/v1/voice/incoming", params);
        assertTrue(TwilioSignature.valid("token", "https://replysis.com/api/v1/voice/incoming", params, good));
        assertFalse(TwilioSignature.valid("token", "https://replysis.com/api/v1/voice/incoming", params, "AAAA"));
        assertFalse(TwilioSignature.valid("token", "https://replysis.com/api/v1/voice/incoming", params, null));
        assertFalse(TwilioSignature.valid("other", "https://replysis.com/api/v1/voice/incoming", params, good), "wrong token");
        assertFalse(TwilioSignature.valid("", "https://replysis.com/api/v1/voice/incoming", params, good), "no token set");
        assertFalse(TwilioSignature.valid("token", "https://replysis.com/api/v1/voice/other", params, good), "another address");
    }

    @Test
    void muLawSilenceIsQuietAndLoudSoundIsLoud() {
        assertEquals(0, CallAudioStats.decode((byte) 0xFF), "mu-law 0xFF is zero");
        assertTrue(CallAudioStats.decode((byte) 0x00) < -30000, "0x00 is the most negative");
        assertTrue(CallAudioStats.decode((byte) 0x80) > 30000, "0x80 is the most positive");
        assertTrue(CallAudioStats.rms(new byte[160]) > 30000, "all 0x00 frames are loud");
        byte[] quiet = new byte[160];
        java.util.Arrays.fill(quiet, (byte) 0xFF);
        assertEquals(0.0, CallAudioStats.rms(quiet), 0.001);
    }

    @Test
    void eachTrackIsCountedSeparatelyAndOnlyLoudFramesCountAsSpeech() {
        CallAudioStats stats = new CallAudioStats();
        byte[] loud = new byte[160];                  // 0x00 = loud negative samples
        byte[] silent = new byte[160];
        java.util.Arrays.fill(silent, (byte) 0xFF);
        for (int i = 0; i < 100; i++) stats.add("inbound", Base64.getEncoder().encodeToString(loud));   // 2 s of speech
        for (int i = 0; i < 100; i++) stats.add("outbound", Base64.getEncoder().encodeToString(silent)); // 2 s of silence
        assertEquals(2.0, stats.speechSeconds("inbound"), 0.001);
        assertEquals(0.0, stats.speechSeconds("outbound"), 0.001);
        assertEquals(0.0, stats.speechSeconds("never-seen"), 0.001);
        assertTrue(stats.summary().contains("inbound: 2.0s audio, 2.0s speech"));
        assertTrue(stats.summary().contains("outbound: 2.0s audio, 0.0s speech"));
    }

    @Test
    void theMediaSignatureNeedsTheTokenAndBindsToTheCall() {
        // No token set in the test environment: the signature is empty, which the media handler treats as "refuse".
        if (System.getenv("TWILIO_AUTH_TOKEN") == null) assertEquals("", VoiceProofController.mediaSignature("CA1"));
        assertEquals("CA12...CDEF", VoiceProofController.redact("CA123456789ABCDEF"));
    }

    @Test
    void anUnsignedRelayIsRefusedUnlessTheAccountIsConfigured() {
        // The environment variable is not set in tests, so no unsigned request can ever pass here.
        Map<String, String> p = Map.of("AccountSid", "ACx", "From", "+15550001111");
        assertFalse(VoiceProofController.trialRelayAllowed(p, "+15550001111"));
        assertFalse(VoiceProofController.trialRelayAllowed(p, null));
    }
}
