package com.winlator.xenvironment.components;

import com.winlator.core.envvars.EnvVars;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Masks credentials before environment dumps hit logcat. Unredacted
 * environment is never written anywhere.
 */
public final class EnvRedactor {
    private static final String[] SENSITIVE_KEY_PARTS = {
        "TOKEN", "PASSWORD", "SECRET", "AUTH", "COOKIE", "SESSION", "KEY",
        "STEAMID", "STEAMUSER", "REFRESH", "EPICOVT", "ENTITLEMENT",
    };

    private static final String SENSITIVE_KEY_PATTERN =
            "[A-Z0-9_]*(?:TOKEN|PASSWORD|SECRET|AUTH|COOKIE|SESSION|KEY|STEAMID|STEAMUSER|REFRESH|EPICOVT|ENTITLEMENT)[A-Z0-9_]*";
    private static final Pattern ASSIGNMENT_PATTERN = Pattern.compile(
            "(?i)(\\b" + SENSITIVE_KEY_PATTERN + "\\s*=\\s*)(\\\"[^\\\"]*\\\"|'[^']*'|[^\\s,;&]+)"
    );
    private static final Pattern ARGUMENT_PATTERN = Pattern.compile(
            "(?i)(--?" + SENSITIVE_KEY_PATTERN + "\\s+)(\\\"[^\\\"]*\\\"|'[^']*'|[^\\s,;&]+)"
    );
    private static final Pattern BEARER_PATTERN = Pattern.compile(
            "(?i)(Bearer\\s+)[A-Za-z0-9._~+/=-]+"
    );
    private static final Pattern JSON_VALUE_PATTERN = Pattern.compile(
            "(?i)(\"(?:access[_-]?token|refresh[_-]?token|auth[_-]?code|exchange[_-]?code|id[_-]?token|client[_-]?secret|code|password|token)\\s*\"\\s*:\\s*\")([^\"\\\\]|\\\\.)*"
    );

    private EnvRedactor() {}

    public static boolean isSensitiveKey(String key) {
        if (key == null) return false;
        String upper = key.toUpperCase();
        for (String part : SENSITIVE_KEY_PARTS) {
            if (upper.contains(part)) return true;
        }
        return false;
    }

    public static String redact(EnvVars envVars) {
        if (envVars == null) return "[]";
        StringBuilder sb = new StringBuilder();
        for (String name : envVars) {
            String value = isSensitiveKey(name) ? "<redacted>" : envVars.get(name);
            if (sb.length() > 0) sb.append(' ');
            sb.append(name).append('=').append(value);
        }
        return sb.toString();
    }

    /** Masks sensitive entries in the raw envp form accepted by execve. */
    public static String redact(String[] envp) {
        if (envp == null) return "[]";
        StringBuilder sb = new StringBuilder("[");
        for (String entry : envp) {
            if (sb.length() > 1) sb.append(", ");
            if (entry == null) {
                sb.append("null");
                continue;
            }
            int equals = entry.indexOf('=');
            if (equals <= 0) {
                sb.append(redactText(entry));
                continue;
            }
            String key = entry.substring(0, equals);
            sb.append(key).append('=');
            sb.append(isSensitiveKey(key) ? "<redacted>" : entry.substring(equals + 1));
        }
        return sb.append(']').toString();
    }

    /**
     * Masks credentials embedded in commands, URLs, or diagnostic messages.
     * This is intentionally conservative: losing a debug value is preferable
     * to persisting a bearer token or storefront ownership credential.
     */
    public static String redactText(String text) {
        if (text == null) return "null";
        String redacted = replaceValue(BEARER_PATTERN, text);
        redacted = replaceValue(JSON_VALUE_PATTERN, redacted);
        redacted = replaceValue(ASSIGNMENT_PATTERN, redacted);
        return replaceValue(ARGUMENT_PATTERN, redacted);
    }

    private static String replaceValue(Pattern pattern, String input) {
        Matcher matcher = pattern.matcher(input);
        StringBuffer output = new StringBuffer();
        while (matcher.find()) {
            matcher.appendReplacement(output, Matcher.quoteReplacement(matcher.group(1) + "<redacted>"));
        }
        matcher.appendTail(output);
        return output.toString();
    }

    /** Single-quote shell escaping: safe for spaces, quotes, cyrillic, &, ;, $. */
    public static String shellQuote(String arg) {
        if (arg.matches("[A-Za-z0-9_\\-./:=,+%@]+")) return arg;
        return "'" + arg.replace("'", "'\\''") + "'";
    }
}
