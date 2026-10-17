package com.replysis.backend.voice;

import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import jakarta.servlet.http.HttpServletRequest;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

/**
 * PROOF OF CONCEPT for phone interviews: a call comes in to a Twilio number, is connected on to the user's other phone,
 * and both sides of the audio are streamed to our server, where {@link VoiceMediaHandler} only measures them.
 *
 * Off unless VOICE_POC_ENABLED=true. It answers as if the address did not exist when off, and refuses every request whose
 * Twilio signature is wrong, so nothing but Twilio can start a call here. No audio and no words are stored.
 *
 * Environment (names only; the values live on the server, never in chat or in git):
 *   VOICE_POC_ENABLED     true to switch it on
 *   TWILIO_AUTH_TOKEN     checks that a request really came from Twilio
 *   VOICE_POC_DIAL_TO     the phone to connect the interviewer to, in +E.164 form
 *   VOICE_PUBLIC_BASE     defaults to https://replysis.com
 */
@RestController
@RequestMapping("/api/v1/voice")
public class VoiceProofController {

    static boolean enabled() { return "true".equalsIgnoreCase(System.getenv("VOICE_POC_ENABLED")); }
    static String authToken() { return System.getenv("TWILIO_AUTH_TOKEN"); }
    static String publicBase() {
        String b = System.getenv("VOICE_PUBLIC_BASE");
        return b == null || b.isBlank() ? "https://replysis.com" : b.replaceAll("/+$", "");
    }

    @PostMapping(value = "/incoming", produces = MediaType.APPLICATION_XML_VALUE)
    public ResponseEntity<String> incoming(
            @RequestParam Map<String, String> params,
            @RequestHeader(value = "X-Twilio-Signature", required = false) String signature,
            HttpServletRequest request) {

        if (!enabled()) return ResponseEntity.notFound().build();

        String url = publicBase() + "/api/v1/voice/incoming";
        if (!TwilioSignature.valid(authToken(), url, params, signature)) {
            System.err.println("[VOICE] Rejected a call webhook with a wrong signature. " + describe(request, params, signature));
            return ResponseEntity.status(403).build();
        }

        String callSid = params.getOrDefault("CallSid", "");
        String dialTo = System.getenv("VOICE_POC_DIAL_TO");
        if (dialTo == null || !dialTo.matches("\\+[1-9][0-9]{6,14}")) {
            System.err.println("[VOICE] VOICE_POC_DIAL_TO is missing or not in +E.164 form");
            return xml("<Response><Say>This line is not set up yet.</Say><Hangup/></Response>");
        }

        // The configured phone calling its own Twilio number cannot be connected to itself: that is the one phone test,
        // so a pretend interviewer plays instead of a second leg being dialled.
        if (dialTo.equals(params.getOrDefault("From", ""))) return xml(demoTwiml(callSid));

        // The number the interviewer reached on our side is the caller id of the second leg: Twilio only lets a call
        // present a number it owns, and presenting the original caller's number would be refused.
        String ourNumber = params.getOrDefault("To", "");
        System.out.println("[VOICE] Call " + redact(callSid) + " in, connecting on and streaming both tracks");

        String wsUrl = publicBase().replaceFirst("^http", "ws") + "/api/v1/voice/media";
        String body = "<Response>"
                + "<Start><Stream url=\"" + wsUrl + "\" track=\"both_tracks\">"
                + "<Parameter name=\"callSid\" value=\"" + escape(callSid) + "\"/>"
                + "<Parameter name=\"sig\" value=\"" + mediaSignature(callSid) + "\"/>"
                + "</Stream></Start>"
                + "<Dial answerOnBridge=\"true\"" + (ourNumber.matches("\\+[0-9]+") ? " callerId=\"" + ourNumber + "\"" : "") + ">"
                + "<Number>" + dialTo + "</Number></Dial>"
                + "</Response>";
        return xml(body);
    }

    /**
     * One phone test: Twilio calls the owner's phone, and a pretend interviewer speaks questions while the owner answers.
     * The pretend interviewer is Twilio's own voice (the outbound track) and the owner is the inbound track, so both tracks
     * carry speech with no second phone. Same checks as {@link #incoming}.
     */
    @PostMapping(value = "/demo", produces = MediaType.APPLICATION_XML_VALUE)
    public ResponseEntity<String> demo(
            @RequestParam Map<String, String> params,
            @RequestHeader(value = "X-Twilio-Signature", required = false) String signature,
            HttpServletRequest request) {

        if (!enabled()) return ResponseEntity.notFound().build();
        String url = publicBase() + "/api/v1/voice/demo";
        if (!TwilioSignature.valid(authToken(), url, params, signature)) {
            System.err.println("[VOICE] Rejected a demo webhook with a wrong signature. " + describe(request, params, signature));
            return ResponseEntity.status(403).build();
        }
        return xml(demoTwiml(params.getOrDefault("CallSid", "")));
    }

    /** The pretend interviewer: Twilio's voice asks two questions with pauses to answer in, while both tracks stream to us. */
    private static String demoTwiml(String callSid) {
        System.out.println("[VOICE] Demo call " + redact(callSid) + " answered, streaming both tracks");
        String wsUrl = publicBase().replaceFirst("^http", "ws") + "/api/v1/voice/media";
        return "<Response>"
                + "<Start><Stream url=\"" + wsUrl + "\" track=\"both_tracks\">"
                + "<Parameter name=\"callSid\" value=\"" + escape(callSid) + "\"/>"
                + "<Parameter name=\"sig\" value=\"" + mediaSignature(callSid) + "\"/>"
                + "</Stream></Start>"
                + "<Say>Hello. This is a test interviewer. Question one. Tell me about a challenging project you worked on.</Say>"
                + "<Pause length=\"14\"/>"
                + "<Say>Question two. Why do you want this role?</Say>"
                + "<Pause length=\"14\"/>"
                + "<Say>Thank you. That is the end of the test.</Say>"
                + "</Response>";
    }

    /**
     * What a refused webhook looked like, for working out why, with nothing secret in it: whether a signature came, which
     * parameter names, which header names and the sender's user agent. Values, tokens and numbers are never logged.
     */
    static String describe(HttpServletRequest request, Map<String, String> params, String signature) {
        java.util.List<String> headers = java.util.Collections.list(request.getHeaderNames());
        return "signature header " + (signature == null ? "absent" : "present, " + signature.length() + " chars")
                + "; params " + new java.util.TreeSet<>(params.keySet())
                + "; headers " + new java.util.TreeSet<>(headers)
                + "; user agent " + request.getHeader("User-Agent")
                + "; content type " + request.getContentType();
    }

    /** What the media socket must present to prove it belongs to this call. */
    static String mediaSignature(String callSid) {
        String token = authToken();
        if (token == null || token.isEmpty()) return "";
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(token.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] raw = mac.doFinal(("media|" + callSid).getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder();
            for (byte b : raw) hex.append(String.format("%02x", b));
            return hex.toString();
        } catch (Exception e) {
            throw new IllegalStateException("HMAC unavailable", e);
        }
    }

    static String redact(String sid) {
        return sid.length() > 8 ? sid.substring(0, 4) + "..." + sid.substring(sid.length() - 4) : "CA...";
    }

    private static String escape(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }

    private static ResponseEntity<String> xml(String body) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).contentType(MediaType.APPLICATION_XML).body(body);
    }

    /** Test helper: a params map the way Twilio form-encodes it. */
    static Map<String, String> copy(Map<String, String> in) { return new HashMap<>(in); }
}
