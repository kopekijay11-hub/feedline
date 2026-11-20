package ca.feedline.service;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * Sends GET requests with explicit, bounded redirect handling.
 *
 * <p>{@link HttpClient.Redirect#NORMAL} uses the JDK's own hardcoded redirect
 * limit and ignores {@code feed.max-redirects}. Redirects are followed manually
 * so the configured bound is actually enforced. Each hop keeps the original
 * headers (including User-Agent) and request timeout.
 */
final class FeedHttpClient {

    private final HttpClient client;
    private final int maxRedirects;
    private final Duration readTimeout;
    private final UrlSafety urlSafety;

    FeedHttpClient(FeedFetchConfig config) {
        this.client = HttpClient.newBuilder()
                .connectTimeout(config.connectTimeout())
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
        this.maxRedirects = config.maxRedirects();
        this.readTimeout = config.readTimeout();
        this.urlSafety = new UrlSafety(config.allowLoopback());
    }

    /**
     * Send the request, following at most {@code maxRedirects} redirects.
     *
     * <p>The caller owns the returned response body. When the limit is exhausted
     * the final redirect response is returned unchanged so callers can classify it.
     */
    HttpResponse<InputStream> send(HttpRequest request) throws IOException, InterruptedException {
        HttpRequest current = request;
        for (int redirects = 0; ; redirects++) {
            urlSafety.validate(current.uri());
            HttpResponse<InputStream> response = client.send(current, HttpResponse.BodyHandlers.ofInputStream());
            if (!isRedirect(response.statusCode()) || redirects >= maxRedirects) {
                return response;
            }

            String location = response.headers().firstValue("Location").orElse(null);
            if (location == null || location.isBlank()) {
                return response;
            }

            URI target = current.uri().resolve(location);
            try {
                urlSafety.validate(target);
            } catch (UrlSafety.UnsafeUrlException e) {
                closeBody(response);
                throw e;
            }

            closeBody(response);
            current = withUri(current, target);
        }
    }

    private static boolean isRedirect(int statusCode) {
        return statusCode == 301 || statusCode == 302 || statusCode == 303
                || statusCode == 307 || statusCode == 308;
    }

    private HttpRequest withUri(HttpRequest request, URI target) {
        HttpRequest.Builder builder = HttpRequest.newBuilder(target).timeout(readTimeout);
        request.headers().map().forEach((name, values) -> values.forEach(value -> builder.header(name, value)));
        return builder.build();
    }

    private static void closeBody(HttpResponse<InputStream> response) throws IOException {
        InputStream body = response.body();
        if (body != null) {
            body.close();
        }
    }
}
