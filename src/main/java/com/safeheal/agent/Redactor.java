package com.safeheal.agent;

import java.nio.charset.StandardCharsets;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class Redactor {

    public static final int PRE_CAP_BYTES = 256 * 1024; // 256 KB
    public static final int MAX_CAP_BYTES = 50 * 1024;  // 50 KB (51,200 bytes)

    // Regex 1: Opaque bearer tokens (case-insensitive "bearer", followed by non-whitespace)
    private static final Pattern BEARER_PATTERN = Pattern.compile("(?i)\\bbearer\\s+\\S+");

    // Regex 2: AWS access key IDs (case-sensitive: AKIA or ASIA followed by 16 alphanumeric uppercase)
    private static final Pattern AWS_KEY_PATTERN = Pattern.compile("\\b(AKIA|ASIA)[0-9A-Z]{16}\\b");

    // Regex 3: Full PEM blocks
    private static final Pattern FULL_PEM_PATTERN = Pattern.compile("-----BEGIN [A-Z0-9 ]+-----[\\s\\S]*?-----END [A-Z0-9 ]+-----");

    // Regex 4: Truncated PEM head fragment ending with -----END ...-----
    private static final Pattern TRUNCATED_PEM_PATTERN = Pattern.compile("(?s)\\A(?:.*?\\n)?([A-Za-z0-9+/=\\r\\n]+-----END [A-Z0-9 ]+-----)");

    // Regex 5: JSON-quoted sensitive keys ("password": "...", "apiKey": 123)
    private static final Pattern JSON_SENSITIVE_PATTERN = Pattern.compile(
        "(?i)\"([^\"]*(?:password|secret|token|api[_-]?key|credential)[^\"]*)\"\\s*:\\s*(?:\"[^\"]*\"|[^,\\s\\}\\]]+)"
    );

    // Regex 6: Sensitive key-value assignments (e.g. password=..., aws_secret_access_key: ...)
    private static final Pattern ASSIGNMENT_SENSITIVE_PATTERN = Pattern.compile(
        "(?i)\\b([a-z0-9_.-]*(?:password|secret|token|api[_-]?key|credential)[a-z0-9_.-]*)\\s*[:=]\\s*(['\"][^'\"]*['\"]|[^\\s'\";,]+)"
    );

    public String redact(String input) {
        if (input == null || input.isEmpty()) {
            return input;
        }

        // 1. Pre-cap input at 256 KB before running regexes (keeping the tail)
        String content = tailBytes(input, PRE_CAP_BYTES);

        // 2. Full PEM blocks
        content = FULL_PEM_PATTERN.matcher(content).replaceAll("[REDACTED_PEM_BLOCK]");

        // 3. Truncated PEM blocks (END without preceding BEGIN)
        if (content.contains("-----END ") && !content.contains("-----BEGIN ")) {
            content = redactTruncatedPem(content);
        }

        // 4. Opaque bearer tokens
        content = BEARER_PATTERN.matcher(content).replaceAll("Bearer [REDACTED]");

        // 5. AWS Key IDs (case-sensitive)
        content = AWS_KEY_PATTERN.matcher(content).replaceAll("[REDACTED_AWS_KEY]");

        // 6. JSON-quoted keys
        content = JSON_SENSITIVE_PATTERN.matcher(content).replaceAll("\"$1\": \"[REDACTED]\"");

        // 7. Key-value assignments
        content = ASSIGNMENT_SENSITIVE_PATTERN.matcher(content).replaceAll("$1: [REDACTED]");

        // 8. Final cap: 50 KB keeping the tail
        return tailBytes(content, MAX_CAP_BYTES);
    }

    private String redactTruncatedPem(String text) {
        int endIdx = text.indexOf("-----END ");
        if (endIdx < 0) {
            return text;
        }
        int endClosing = text.indexOf("-----", endIdx + 9);
        if (endClosing < 0) {
            return text;
        }
        int pemEnd = endClosing + 5;

        // Find the line boundary preceding the start of base64/PEM content
        int lineStart = text.lastIndexOf('\n', endIdx);
        // If there are multiple lines before END containing base64, redact from lineStart or beginning
        int redactStart = 0;
        if (lineStart > 0) {
            // Find earliest line that looks like PEM body
            String[] lines = text.substring(0, pemEnd).split("\n");
            int offset = 0;
            for (String line : lines) {
                String trimmed = line.trim();
                if (trimmed.matches("^[A-Za-z0-9+/=]{16,}$") || trimmed.startsWith("-----END ")) {
                    redactStart = offset;
                    break;
                }
                offset += line.length() + 1;
            }
        }

        return text.substring(0, redactStart) + "[REDACTED_PEM_BLOCK]" + text.substring(pemEnd);
    }

    public static String tailBytes(String str, int maxBytes) {
        byte[] bytes = str.getBytes(StandardCharsets.UTF_8);
        if (bytes.length <= maxBytes) {
            return str;
        }

        int startByte = bytes.length - maxBytes;
        // Avoid splitting multi-byte UTF-8 character (continuation bytes have top bits 10xxxxxx, i.e. 0x80 to 0xBF)
        while (startByte < bytes.length && (bytes[startByte] & 0xC0) == 0x80) {
            startByte++;
        }

        return new String(bytes, startByte, bytes.length - startByte, StandardCharsets.UTF_8);
    }
}
