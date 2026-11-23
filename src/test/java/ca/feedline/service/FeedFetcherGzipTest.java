package ca.feedline.service;

import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.ByteArrayOutputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.zip.GZIPOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class FeedFetcherGzipTest {

    @Test
    void gzipBombOverCapIsRejected() throws Exception {
        int cap = 5 * 1024 * 1024;
        // 6 MB of decompressed payload.
        byte[] decompressed = new byte[6 * 1024 * 1024];
        for (int i = 0; i < decompressed.length; i++) {
            decompressed[i] = (byte) 'A';
        }
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (GZIPOutputStream gz = new GZIPOutputStream(baos)) {
            gz.write(decompressed);
        }
        byte[] compressed = baos.toByteArray();

        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/bomb", ex -> {
            ex.getResponseHeaders().set("Content-Type", "application/xml");
            ex.getResponseHeaders().set("Content-Encoding", "gzip");
            ex.sendResponseHeaders(200, compressed.length);
            try (OutputStream os = ex.getResponseBody()) {
                os.write(compressed);
            }
        });
        server.start();
        try {
            FeedFetchConfig config = new FeedFetchConfig(
                    "https://example.test",
                    Duration.ofSeconds(5),
                    Duration.ofSeconds(15),
                    cap,
                    1,
                    Duration.ZERO,
                    0,
                    true);
            org.mockito.Mockito.mock(RobotsTxtParser.class);
            RobotsTxtParser robots = Mockito.mock(RobotsTxtParser.class);
            Mockito.when(robots.getRules(Mockito.anyString()))
                    .thenReturn(RobotsTxtParser.RobotsRules.ALLOW_ALL);
            FeedFetcher fetcher = new FeedFetcher(config, robots, new Sleeper() {
                @Override
                public void sleep(long millis) throws InterruptedException { }
            }, Clock.fixed(Instant.parse("2026-01-01T12:00:00Z"), ZoneId.of("UTC")));

            FeedFetchResult result = fetcher.fetch("http://localhost:" + server.getAddress().getPort() + "/bomb", null, null);

            assertEquals(413, result.httpCode());
            assertNull(result.content());
        } finally {
            server.stop(0);
        }
    }
}
