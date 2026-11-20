package ca.feedline.service;

import org.junit.jupiter.api.Test;

import java.net.URI;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

class UrlSafetyTest {

    @Test
    void rejectsNonHttpSchemesAndMissingHosts() {
        UrlSafety guard = new UrlSafety(true);

        assertRejected(guard, "file:///etc/passwd");
        assertRejected(guard, "ftp://example.test/feed.xml");
        assertRejected(guard, "http:///feed.xml");
    }

    @Test
    void rejectsPrivateSpecialAndLoopbackTargets() {
        UrlSafety strict = new UrlSafety(false);
        UrlSafety loopbackAllowed = new UrlSafety(true);

        assertRejected(strict, "http://localhost/feed.xml");
        assertRejected(strict, "http://127.0.0.1/feed.xml");
        assertRejected(strict, "http://[::1]/feed.xml");
        assertRejected(loopbackAllowed, "http://10.0.0.1/feed.xml");
        assertRejected(loopbackAllowed, "http://172.16.0.1/feed.xml");
        assertRejected(loopbackAllowed, "http://192.168.1.1/feed.xml");
        assertRejected(loopbackAllowed, "http://100.64.0.1/feed.xml");
        assertRejected(loopbackAllowed, "http://169.254.169.254/latest/meta-data/");
        assertRejected(loopbackAllowed, "http://0.0.0.0/feed.xml");
        assertRejected(loopbackAllowed, "http://224.0.0.1/feed.xml");
        assertRejected(loopbackAllowed, "http://[fd00::1]/feed.xml");
        assertRejected(loopbackAllowed, "http://[::ffff:127.0.0.1]/feed.xml");
    }

    @Test
    void allowsPublicTargetsAndExplicitLoopbackOverride() {
        UrlSafety strict = new UrlSafety(false);
        UrlSafety loopbackAllowed = new UrlSafety(true);

        assertDoesNotThrow(() -> strict.validate(URI.create("http://8.8.8.8/feed.xml")));
        assertDoesNotThrow(() -> strict.validate(URI.create("https://1.1.1.1/feed.xml")));
        assertDoesNotThrow(() -> loopbackAllowed.validate(URI.create("http://localhost/feed.xml")));
        assertDoesNotThrow(() -> loopbackAllowed.validate(URI.create("http://127.0.0.1/feed.xml")));
    }

    private static void assertRejected(UrlSafety guard, String url) {
        assertThrows(UrlSafety.UnsafeUrlException.class, () -> guard.validate(URI.create(url)), url);
    }
}
