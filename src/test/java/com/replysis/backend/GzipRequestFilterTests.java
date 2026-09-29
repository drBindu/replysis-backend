package com.replysis.backend;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.replysis.backend.security.GzipRequestFilter;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.zip.GZIPOutputStream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * A request the client compressed must reach the handlers exactly as if it had not been, and a
 * request that lies about being compressed must be refused before anything is charged.
 *
 * Why the feature exists: the prompt is 19 to 23 KB and its upload was the slowest, most erratic
 * part of getting an answer on a slow uplink (0.13 to 1.08 s for 13 KB, against a steady 0.13 s
 * for a tiny request, measured 2026-09-29).
 */
class GzipRequestFilterTests {

    private static byte[] gzip(byte[] plain) throws IOException {
        var out = new ByteArrayOutputStream();
        try (var gz = new GZIPOutputStream(out)) { gz.write(plain); }
        return out.toByteArray();
    }

    private final GzipRequestFilter filter = new GzipRequestFilter();

    /** Runs the filter and hands back what the handler behind it would see. */
    private static class Seen {
        String body;
        String encodingHeader = "unset";
        String lengthHeader = "unset";
        long contentLength;
        boolean sameRequestObject;
        IOException readFailure;
        boolean reached;
    }

    private Seen run(MockHttpServletRequest request, MockHttpServletResponse response) throws Exception {
        var seen = new Seen();
        var original = new AtomicReference<HttpServletRequest>(request);
        var chain = new MockFilterChain() {
            @Override
            public void doFilter(jakarta.servlet.ServletRequest req, jakarta.servlet.ServletResponse res) throws IOException {
                seen.reached = true;
                var http = (HttpServletRequest) req;
                seen.sameRequestObject = http == original.get();
                seen.encodingHeader = http.getHeader("Content-Encoding");
                seen.lengthHeader = http.getHeader("Content-Length");
                seen.contentLength = http.getContentLengthLong();
                try {
                    seen.body = new String(http.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
                } catch (IOException e) {
                    seen.readFailure = e;
                }
            }
        };
        filter.doFilter(request, response, chain);
        return seen;
    }

    private static MockHttpServletRequest post(byte[] body, String encoding) {
        var r = new MockHttpServletRequest("POST", "/api/v1/interview/ask");
        r.setContentType("application/json");
        r.setContent(body);
        if (encoding != null) {
            r.addHeader("Content-Encoding", encoding);
            r.addHeader("Content-Length", String.valueOf(body.length));
        }
        return r;
    }

    @Test
    void aCompressedRequestReachesTheHandlerAsPlainJson() throws Exception {
        String json = "{\"question\":\"What is dependency injection?\",\"messages\":[{\"role\":\"user\",\"content\":\"héllo — ünïcode\"}]}";
        var seen = run(post(gzip(json.getBytes(StandardCharsets.UTF_8)), "gzip"), new MockHttpServletResponse());

        assertTrue(seen.reached);
        assertEquals(json, seen.body, "the handler reads the original bytes");
        Map<?, ?> parsed = new ObjectMapper().readValue(seen.body, Map.class);
        assertEquals("What is dependency injection?", parsed.get("question"));
    }

    @Test
    void theHandlerIsNotToldTheBodyIsStillCompressed() throws Exception {
        var seen = run(post(gzip("{\"question\":\"x\"}".getBytes(StandardCharsets.UTF_8)), "gzip"), new MockHttpServletResponse());
        assertNull(seen.encodingHeader, "no Content-Encoding, or something downstream would try to decompress it again");
        assertNull(seen.lengthHeader, "the compressed length is not the length of what it reads");
        assertEquals(-1, seen.contentLength);
    }

    @Test
    void theHeaderIsMatchedWithoutRegardToCase() throws Exception {
        String json = "{\"question\":\"x\"}";
        var seen = run(post(gzip(json.getBytes(StandardCharsets.UTF_8)), "  GZip "), new MockHttpServletResponse());
        assertEquals(json, seen.body);
    }

    @Test
    void aRequestThatIsNotCompressedIsLeftAlone() throws Exception {
        String json = "{\"question\":\"plain\"}";
        var request = post(json.getBytes(StandardCharsets.UTF_8), null);
        var seen = run(request, new MockHttpServletResponse());
        assertTrue(seen.sameRequestObject, "not wrapped, not touched: every shipped client keeps working exactly as before");
        assertEquals(json, seen.body);
    }

    @Test
    void anotherEncodingIsAlsoLeftAlone() throws Exception {
        var request = post("{}".getBytes(StandardCharsets.UTF_8), "identity");
        var seen = run(request, new MockHttpServletResponse());
        assertTrue(seen.sameRequestObject);
    }

    @Test
    void aBodyThatSaysGzipButIsNotIsRefusedBeforeAnyHandlerRuns() throws Exception {
        var response = new MockHttpServletResponse();
        var seen = run(post("this is plain text, not gzip".getBytes(StandardCharsets.UTF_8), "gzip"), response);
        assertFalse(seen.reached, "no handler, so no charge");
        assertEquals(400, response.getStatus());
    }

    @Test
    void aSmallFileThatExpandsWithoutLimitIsStopped() throws Exception {
        // 9 MB of zeros compresses to a few KB. The cap is 8 MB.
        byte[] bomb = gzip(new byte[9 * 1024 * 1024]);
        assertTrue(bomb.length < 64 * 1024, "the file itself is small (" + bomb.length + " bytes)");

        var seen = run(post(bomb, "gzip"), new MockHttpServletResponse());
        assertNotNull(seen.readFailure, "reading past the cap fails instead of filling memory");
    }

    @Test
    void aLargeButLegitimateRequestGoesThrough() throws Exception {
        // 500 KB of real-looking text, far more than any prompt.
        String text = "You are the candidate answering a live interview. ".repeat(10_000);
        String json = "{\"question\":\"q\",\"pad\":\"" + text + "\"}";
        var seen = run(post(gzip(json.getBytes(StandardCharsets.UTF_8)), "gzip"), new MockHttpServletResponse());
        assertEquals(json, seen.body);
    }

    @Test
    void theReaderSeesTheSameText() throws Exception {
        String json = "{\"question\":\"héllo\"}";
        var request = post(gzip(json.getBytes(StandardCharsets.UTF_8)), "gzip");
        request.setCharacterEncoding("UTF-8");
        var text = new AtomicReference<String>();
        var chain = new MockFilterChain() {
            @Override
            public void doFilter(jakarta.servlet.ServletRequest req, jakarta.servlet.ServletResponse res) throws IOException {
                text.set(req.getReader().readLine());
            }
        };
        filter.doFilter(request, new MockHttpServletResponse(), chain);
        assertEquals(json, text.get());
    }
}
