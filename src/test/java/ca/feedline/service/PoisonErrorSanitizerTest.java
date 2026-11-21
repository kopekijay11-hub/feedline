package ca.feedline.service;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class PoisonErrorSanitizerTest {

    @Test
    void returnsNullForNullOrBlankInput() {
        assertThat(PoisonErrorSanitizer.sanitize(null)).isNull();
        assertThat(PoisonErrorSanitizer.sanitize("   ")).isNull();
        assertThat(PoisonErrorSanitizer.sanitize("\n\t")).isNull();
    }

    @Test
    void redactsBearerTokensAndKeyValueSecrets() {
        String sanitized = PoisonErrorSanitizer.sanitize(
                "Authorization: Bearer abcdef123456; api_key=topsecret; password=hunter2; secret=s3cr3t");

        assertThat(sanitized)
                .doesNotContain("abcdef123456", "topsecret", "hunter2", "s3cr3t")
                .contains("Authorization: [REDACTED]", "api_key=[REDACTED]", "password=[REDACTED]", "secret=[REDACTED]");
        assertThat(PoisonErrorSanitizer.sanitize("upstream replied Bearer bare-token-value"))
                .isEqualTo("upstream replied Bearer [REDACTED]");
    }

    @Test
    void redactsUrlUserinfoAndAuthorizationSchemes() {
        String sanitized = PoisonErrorSanitizer.sanitize(
                "POST https://user:pw@example.test/hook failed; authorization=Basic dXNlcjpwYXNz");

        assertThat(sanitized)
                .doesNotContain("user:pw", "dXNlcjpwYXNz")
                .contains("https://[REDACTED]@example.test", "authorization=[REDACTED]");
    }

    @Test
    void stripsStackFramesAndCollapsesControlCharacters() {
        String sanitized = PoisonErrorSanitizer.sanitize(
                "boom\n    at com.example.KafkaClient.send(KafkaClient.java:42)\n"
                        + "    ... 5 more\n\tcaused by timeout\r\n");

        assertThat(sanitized)
                .doesNotContain("\n", "\r", "\t", "\tat ", "KafkaClient.java")
                .isEqualTo("boom caused by timeout");
    }

    @Test
    void truncatesToBoundedLength() {
        String sanitized = PoisonErrorSanitizer.sanitize("x".repeat(2000));

        assertThat(sanitized).hasSize(PoisonErrorSanitizer.MAX_LENGTH);
        assertThat(sanitized).endsWith("... [truncated]");
    }
}