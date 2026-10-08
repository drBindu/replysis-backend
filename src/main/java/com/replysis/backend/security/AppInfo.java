package com.replysis.backend.security;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.util.regex.Pattern;

/**
 * Which Replysis app made a request: its platform ("windows" or "mac") and its version.
 *
 * The desktop apps send these on every call to our own server (X-App-Platform and X-App-Version). They are only labels for the
 * admin page and the usage log. Nothing is decided from them: anybody can send any value, so they never grant, charge or limit.
 * Anything that is not a known platform is dropped rather than stored, and the version is accepted only in a plain shape.
 */
public record AppInfo(String platform, String version) {

    private static final Pattern VERSION = Pattern.compile("^[0-9A-Za-z.+-]{1,24}$");

    /** Reads the two header values. Null when the platform is missing or not one we know. */
    public static AppInfo parse(String platformHeader, String versionHeader) {
        if (platformHeader == null) return null;
        String p = platformHeader.trim().toLowerCase();
        String platform = switch (p) {
            case "windows", "win", "win32" -> "windows";
            case "mac", "macos", "osx", "darwin" -> "mac";
            default -> null;
        };
        if (platform == null) return null;

        String v = versionHeader == null ? "" : versionHeader.trim();
        return new AppInfo(platform, VERSION.matcher(v).matches() ? v : "");
    }

    /** The app behind the request this thread is serving, or null outside a request or when it did not say. */
    public static AppInfo current() {
        try {
            var attributes = RequestContextHolder.getRequestAttributes();
            if (!(attributes instanceof ServletRequestAttributes servlet)) return null;
            HttpServletRequest request = servlet.getRequest();
            return parse(request.getHeader("X-App-Platform"), request.getHeader("X-App-Version"));
        } catch (Exception e) {
            return null;
        }
    }
}
