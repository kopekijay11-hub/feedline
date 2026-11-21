package ca.feedline.service;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Builds the bounded, secret-free {@code lastError} summary shown by the admin
 * poisoned-outbox API.
 *
 * <p>The raw error is written by the relay from the root cause message only (no
 * stack trace). This sanitizer is the second line of defence for rows written by
 * earlier versions or by out-of-band tooling: it strips stack-frame lines,
 * redacts credentials, collapses control characters, and caps the result so a
 * single poisoned row cannot return an oversized or sensitive response.
 */
public final class PoisonErrorSanitizer {

    static final int MAX_LENGTH = 500;

    private static final String REDACTED = "[REDACTED]";
    private static final String TRUNCATION_SUFFIX = " ... [truncated]";

    private static final Pattern STACK_FRAME = Pattern.compile("(?m)^\\s*(?:at\\s[\\w.$]+\\([^)]*\\)|\\.\\.\\.\\s+\\d+\\s+more)\\s*$");
    private static final Pattern BEARER = Pattern.compile("(?i)\\b(bearer\\s+)[A-Za-z0-9._~+/=-]{6,}");
    private static final Pattern AUTHORIZATION_SCHEME = Pattern.compile(
            "(?i)\\b(authorization\\s*[:=]\\s*)(?:basic|bearer|digest|token)\\s+[^\\s,;]+");
    private static final Pattern KEY_VALUE_SECRET = Pattern.compile(
            "(?i)\\b(api[_-]?key|access[_-]?token|refresh[_-]?token|auth[_-]?token|token|password|passwd|secret|authorization)"
                    + "(\\s*[:=]\\s*)([^\\s,;&\"']+)");
    private static final Pattern URL_USERINFO = Pattern.compile(
            "(?i)([a-z][a-z0-9+.-]*://)([^/\\s:@]+):([^/\\s@]+)@");
    private static final Pattern TOKEN_PREFIX = Pattern.compile(
            "\\b(?:sk|pk|ghp|gho|xox[baprs])-[A-Za-z0-9]{8,}\\b");
    private static final Pattern CONTROL_CHARS = Pattern.compile("\\p{Cntrl}");
    private static final Pattern WHITESPACE = Pattern.compile("\\s+");

    private PoisonErrorSanitizer() {
    }

    public static String sanitize(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String value = STACK_FRAME.matcher(raw).replaceAll(" ");
        value = redactBearer(value);
        value = redactAuthorizationScheme(value);
        value = redactKeyValueSecrets(value);
        value = URL_USERINFO.matcher(value).replaceAll("$1" + REDACTED + "@");
        value = TOKEN_PREFIX.matcher(value).replaceAll(REDACTED);
        value = CONTROL_CHARS.matcher(value).replaceAll(" ");
        value = WHITESPACE.matcher(value).replaceAll(" ").trim();
        if (value.isBlank()) {
            return null;
        }
        if (value.length() > MAX_LENGTH) {
            value = value.substring(0, MAX_LENGTH - TRUNCATION_SUFFIX.length()) + TRUNCATION_SUFFIX;
        }
        return value;
    }

    private static String redactBearer(String value) {
        Matcher matcher = BEARER.matcher(value);
        StringBuilder result = new StringBuilder();
        while (matcher.find()) {
            matcher.appendReplacement(result, Matcher.quoteReplacement(matcher.group(1) + REDACTED));
        }
        matcher.appendTail(result);
        return result.toString();
    }

    private static String redactAuthorizationScheme(String value) {
        Matcher matcher = AUTHORIZATION_SCHEME.matcher(value);
        StringBuilder result = new StringBuilder();
        while (matcher.find()) {
            String replacement = matcher.group(1) + REDACTED;
            matcher.appendReplacement(result, Matcher.quoteReplacement(replacement));
        }
        matcher.appendTail(result);
        return result.toString();
    }

    private static String redactKeyValueSecrets(String value) {
        Matcher matcher = KEY_VALUE_SECRET.matcher(value);
        StringBuilder result = new StringBuilder();
        while (matcher.find()) {
            String replacement = matcher.group(1) + matcher.group(2) + REDACTED;
            matcher.appendReplacement(result, Matcher.quoteReplacement(replacement));
        }
        matcher.appendTail(result);
        return result.toString();
    }
}