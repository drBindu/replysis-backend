package com.replysis.backend.security;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.util.regex.Pattern;

/**
 * Which Replysis app made a request: its platform ("windows", "mac", "android" or "ios") and its version.
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
            // The phone app (2026-10-24).
            case "android" -> "android";
            case "ios", "iphone", "ipad" -> "ios";
            default -> null;
        };
        if (platform == null) return null;

        String v = versionHeader == null ? "" : versionHeader.trim();
        return new AppInfo(platform, VERSION.matcher(v).matches() ? v : "");
    }

    /** Set while work for a request runs on another thread (see carriedTo), where there is no request to read. */
    private static final ThreadLocal<AppInfo> CARRIED = new ThreadLocal<>();

    /**
     * The same work, run with this request's app attached. A charge done on a pool thread used to record its usage row with no
     * platform or version, so every voice answer showed as an unknown app in the admin speed figures.
     */
    public static <T> java.util.function.Supplier<T> carriedTo(java.util.function.Supplier<T> work) {
        final AppInfo app = current();
        return () -> {
            AppInfo before = CARRIED.get();
            CARRIED.set(app);
            try { return work.get(); }
            finally { if (before == null) CARRIED.remove(); else CARRIED.set(before); }
        };
    }

    /** The app behind the request this thread is serving, or null outside a request or when it did not say. */
    public static AppInfo current() {
        AppInfo carried = CARRIED.get();
        if (carried != null) return carried;
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
