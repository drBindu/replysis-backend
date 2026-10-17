package com.replysis.backend.voice;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.Map;
import java.util.TreeMap;

/**
 * Twilio's webhook signature: Base64(HMAC-SHA1(authToken, url + each POST parameter name and value, sorted by name)).
 * Every call webhook is checked with this before anything else, so nobody but Twilio can start a call session.
 */
public final class TwilioSignature {
    private TwilioSignature() {}

    public static String sign(String authToken, String url, Map<String, String> params) {
        StringBuilder data = new StringBuilder(url);
        for (Map.Entry<String, String> e : new TreeMap<>(params).entrySet()) {
            data.append(e.getKey()).append(e.getValue());
        }
        try {
            Mac mac = Mac.getInstance("HmacSHA1");
            mac.init(new SecretKeySpec(authToken.getBytes(StandardCharsets.UTF_8), "HmacSHA1"));
            return Base64.getEncoder().encodeToString(mac.doFinal(data.toString().getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("HMAC unavailable", e);
        }
    }

    /** Constant time, so a wrong signature does not leak how much of it was right. */
    public static boolean valid(String authToken, String url, Map<String, String> params, String header) {
        if (authToken == null || authToken.isEmpty() || header == null) return false;
        byte[] expected = sign(authToken, url, params).getBytes(StandardCharsets.UTF_8);
        return MessageDigest.isEqual(expected, header.getBytes(StandardCharsets.UTF_8));
    }
}
