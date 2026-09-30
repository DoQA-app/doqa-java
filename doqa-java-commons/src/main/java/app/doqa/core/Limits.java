package app.doqa.core;

import app.doqa.client.DoqaConfig;

/**
 * Payload-size guard rails: truncation with an explicit marker, and the active limits (from the
 * session config, or the client-core defaults when no session is up yet).
 *
 * <p>Internal adapter API.
 */
public final class Limits {

    /** Server column limits: a longer value gets the whole batch rejected. */
    public static final int MAX_EXTERNAL_ID = 255;
    public static final int MAX_NAME = 255;
    public static final int MAX_RUNNER_METHOD = 255;
    public static final int MAX_STEP_TITLE = 500;

    private Limits() {
    }

    /** Cut to {@code max} chars ending with an ellipsis; never splits a surrogate pair. */
    public static String clip(String s, int max) {
        if (s == null || s.length() <= max) {
            return s;
        }
        int end = max - 1;
        if (end > 0 && Character.isHighSurrogate(s.charAt(end - 1))) {
            end--;
        }
        return s.substring(0, end) + "…";
    }

    /** Truncate to {@code max} chars, appending a marker with the dropped size. */
    public static String truncate(String s, int max) {
        if (s == null || s.length() <= max) {
            return s;
        }
        return s.substring(0, max) + "\n… truncated (" + (s.length() - max) + " chars)";
    }

    /** Active limit for stringified parameter values. */
    public static int maxParameterLength() {
        DoqaConfig config = DoqaSession.currentConfig();
        return config != null ? config.maxParameterLength() : DoqaConfig.DEFAULT_MAX_PARAMETER_LENGTH;
    }
}
