package com.replysis.backend.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;
import java.util.zip.GZIPInputStream;

/**
 * Accepts a request body that the client compressed with gzip.
 *
 * Why: every answer request carries the whole prompt, 19 to 23 KB once a resume is loaded. On a
 * slow or shared uplink that upload is the slowest part of getting an answer. A tiny request to
 * this server answers in a steady 0.11 to 0.15 s; a 13 KB one, over the same connection while
 * the app was also streaming microphone audio, took anywhere from 0.13 s to 1.08 s (measured
 * 2026-09-29). The prompt is repetitive text and compresses to under half, which is what gets
 * an answer started sooner for someone on a hotel or mobile connection.
 *
 * It only acts when a request says Content-Encoding: gzip. Everything else, including every
 * shipped desktop and Mac client, passes through untouched, so deploying this changes nothing
 * for anyone until the app that sends it is released.
 *
 * The decompressed size is capped, because a gzip file a few KB long can claim to be gigabytes.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class GzipRequestFilter extends OncePerRequestFilter {

    /** Well above the largest request a client legitimately sends, well below anything harmful. */
    static final long MAX_DECOMPRESSED_BYTES = 8L * 1024 * 1024;

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String encoding = request.getHeader("Content-Encoding");
        return encoding == null || !"gzip".equalsIgnoreCase(encoding.trim());
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        GZIPInputStream unzipped;
        try {
            unzipped = new GZIPInputStream(request.getInputStream());
        } catch (IOException notGzip) {
            // Said gzip, was not. Refused here, before any handler, so nothing is charged.
            response.sendError(HttpServletResponse.SC_BAD_REQUEST);
            return;
        }
        chain.doFilter(new Unzipped(request, new Capped(unzipped, MAX_DECOMPRESSED_BYTES)), response);
    }

    /** Reads at most a fixed number of bytes, then fails, so a small file cannot expand without limit. */
    static final class Capped extends InputStream {
        private final InputStream in;
        private long left;

        Capped(InputStream in, long max) {
            this.in = in;
            this.left = max;
        }

        @Override
        public int read() throws IOException {
            if (left <= 0) throw new IOException("Request body is larger than allowed once decompressed");
            int b = in.read();
            if (b >= 0) left--;
            return b;
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            if (len == 0) return 0;
            if (left <= 0) throw new IOException("Request body is larger than allowed once decompressed");
            int n = in.read(b, off, (int) Math.min(len, left));
            if (n > 0) left -= n;
            return n;
        }

        @Override
        public void close() throws IOException {
            in.close();
        }
    }

    /** The same request, with the body already decompressed and no header claiming otherwise. */
    static final class Unzipped extends HttpServletRequestWrapper {
        private final InputStream body;

        Unzipped(HttpServletRequest request, InputStream body) {
            super(request);
            this.body = body;
        }

        @Override
        public ServletInputStream getInputStream() {
            return new ServletInputStream() {
                @Override public int read() throws IOException { return body.read(); }
                @Override public int read(byte[] b, int off, int len) throws IOException { return body.read(b, off, len); }
                @Override public boolean isFinished() { return false; }
                @Override public boolean isReady() { return true; }
                @Override public void setReadListener(ReadListener listener) {
                    throw new UnsupportedOperationException("Blocking reads only");
                }
                @Override public void close() throws IOException { body.close(); }
            };
        }

        @Override
        public BufferedReader getReader() {
            String name = getCharacterEncoding();
            Charset charset = name == null ? StandardCharsets.UTF_8 : Charset.forName(name);
            return new BufferedReader(new InputStreamReader(body, charset));
        }

        // The length on the wire was the compressed one; the decompressed length is not known.
        @Override public int getContentLength() { return -1; }
        @Override public long getContentLengthLong() { return -1; }

        @Override
        public String getHeader(String name) {
            return hidden(name) ? null : super.getHeader(name);
        }

        @Override
        public Enumeration<String> getHeaders(String name) {
            return hidden(name) ? Collections.emptyEnumeration() : super.getHeaders(name);
        }

        @Override
        public Enumeration<String> getHeaderNames() {
            List<String> names = Collections.list(super.getHeaderNames());
            names.removeIf(Unzipped::hidden);
            return Collections.enumeration(names);
        }

        private static boolean hidden(String name) {
            return "Content-Encoding".equalsIgnoreCase(name) || "Content-Length".equalsIgnoreCase(name);
        }
    }
}
