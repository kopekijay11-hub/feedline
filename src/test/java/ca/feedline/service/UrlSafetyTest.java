package ca.feedline.service;

import org.junit.jupiter.api.Test;

import java.net.InetAddress;
import java.net.URI;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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

    @Test
    void rejectsDnsAnswerThatChangesBetweenValidationAndConnect() throws Exception {
        InetAddress first = InetAddress.getByName("8.8.8.8");
        InetAddress rebound = InetAddress.getByName("1.1.1.1");
        AtomicInteger calls = new AtomicInteger();
        UrlSafety guard = new UrlSafety(false, host -> calls.getAndIncrement() == 0
                ? new InetAddress[]{first} : new InetAddress[]{rebound});

        UrlSafety.ValidatedTarget validated = guard.validate(URI.create("http://example.test/feed.xml"));

        assertThrows(UrlSafety.UnsafeUrlException.class, () -> guard.assertUnchanged(validated));
    }

    @Test
    void blockedAddressMessagesNameNoTopology() {
        UrlSafety strict = new UrlSafety(false);

        UrlSafety.UnsafeUrlException direct = assertThrows(UrlSafety.UnsafeUrlException.class,
                () -> strict.validate(URI.create("http://10.0.0.1/feed.xml")));
        assertEquals("Blocked target address", direct.getMessage());
        assertFalse(direct.getMessage().contains("10.0.0.1"));
        assertFalse(direct.getMessage().contains("feed"));
    }

    @Test
    void acceptsStableDnsAnswerOnRecheck() throws Exception {
        InetAddress address = InetAddress.getByName("8.8.8.8");
        UrlSafety guard = new UrlSafety(false, host -> new InetAddress[]{address});

        UrlSafety.ValidatedTarget validated = guard.validate(URI.create("http://example.test/feed.xml"));

        assertDoesNotThrow(() -> guard.assertUnchanged(validated));
    }

    private static void assertRejected(UrlSafety guard, String url) {
        assertThrows(UrlSafety.UnsafeUrlException.class, () -> guard.validate(URI.create(url)), url);
    }
}
