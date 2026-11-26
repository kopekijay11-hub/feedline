package ca.feedline.service;

import java.io.IOException;
import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * Validates that a URL may be fetched without exposing internal network targets.
 *
 * <p>Both the scheme and every resolved address are checked. Validation is
 * fail-closed: if DNS cannot be resolved, or any resolved address is private,
 * loopback, link-local, CGNAT, multicast, unspecified, or an IPv4-mapped IPv6
 * literal, the URL is rejected. The validated addresses are returned so the
 * caller can reject a DNS answer that changes before the connection is made.
 */
final class UrlSafety {

    @FunctionalInterface
    interface Resolver {
        InetAddress[] resolve(String host) throws UnknownHostException;
    }

    record ValidatedTarget(URI uri, List<InetAddress> addresses) {
        ValidatedTarget(URI uri, InetAddress[] addresses) {
            this(uri, List.copyOf(Arrays.asList(addresses)));
        }
    }

    private final boolean allowLoopback;
    private final Resolver resolver;

    UrlSafety(boolean allowLoopback) {
        this(allowLoopback, InetAddress::getAllByName);
    }

    UrlSafety(boolean allowLoopback, Resolver resolver) {
        this.allowLoopback = allowLoopback;
        this.resolver = resolver;
    }

    ValidatedTarget validate(URI uri) throws UnsafeUrlException {
        if (uri == null) {
            throw new UnsafeUrlException("URL must not be null");
        }
        String scheme = uri.getScheme();
        if (!"http".equalsIgnoreCase(scheme) && !"https".equalsIgnoreCase(scheme)) {
            throw new UnsafeUrlException("Unsupported scheme: " + scheme);
        }
        String host = uri.getHost();
        if (host == null || host.isBlank()) {
            throw new UnsafeUrlException("URL has no host: " + uri);
        }
        String normalized = normalizeHost(host);
        if (("localhost".equals(normalized) || normalized.endsWith(".localhost")) && !allowLoopback) {
            throw new UnsafeUrlException("Localhost target blocked: " + host);
        }
        if (normalized.contains(":ffff:")) {
            throw new UnsafeUrlException("IPv4-mapped IPv6 target blocked: " + host);
        }

        InetAddress[] addresses = resolve(host);
        for (InetAddress address : addresses) {
            if (!isSafe(address)) {
                throw new UnsafeUrlException("Blocked target address");
            }
        }
        return new ValidatedTarget(uri, addresses);
    }

    void assertUnchanged(ValidatedTarget target) throws UnsafeUrlException {
        String host = target.uri().getHost();
        InetAddress[] current = resolve(host);
        List<InetAddress> currentList = List.of(current);
        if (currentList.size() != target.addresses().size()
                || !target.addresses().containsAll(currentList)) {
            throw new UnsafeUrlException("DNS answer changed for " + host + " before connect");
        }
        for (InetAddress address : current) {
            if (!isSafe(address)) {
                throw new UnsafeUrlException("Blocked target address after DNS recheck");
            }
        }
    }

    private InetAddress[] resolve(String host) throws UnsafeUrlException {
        InetAddress[] addresses;
        try {
            addresses = resolver.resolve(host);
        } catch (UnknownHostException e) {
            throw new UnsafeUrlException("DNS resolution failed for " + host, e);
        }
        if (addresses == null || addresses.length == 0) {
            throw new UnsafeUrlException("DNS returned no addresses for " + host);
        }
        return addresses;
    }

    private static String normalizeHost(String host) {
        String normalized = host.toLowerCase(Locale.ROOT);
        while (normalized.endsWith(".")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        if (normalized.startsWith("[") && normalized.endsWith("]")) {
            normalized = normalized.substring(1, normalized.length() - 1);
        }
        return normalized;
    }

    private boolean isSafe(InetAddress address) {
        byte[] bytes = address.getAddress();
        if (isIpv4Mapped(bytes)) {
            return false;
        }
        if (address.isAnyLocalAddress() || address.isMulticastAddress()
                || address.isLinkLocalAddress() || address.isSiteLocalAddress()
                || isCgnat(bytes) || isUniqueLocalIpv6(bytes)) {
            return false;
        }
        return !address.isLoopbackAddress() || allowLoopback;
    }

    private static boolean isIpv4Mapped(byte[] bytes) {
        if (bytes.length != 16) {
            return false;
        }
        for (int i = 0; i < 10; i++) {
            if (bytes[i] != 0) {
                return false;
            }
        }
        return (bytes[10] & 0xff) == 0xff && (bytes[11] & 0xff) == 0xff;
    }

    private static boolean isCgnat(byte[] bytes) {
        return bytes.length == 4 && bytes[0] == 100 && (bytes[1] & 0xc0) == 64;
    }

    private static boolean isUniqueLocalIpv6(byte[] bytes) {
        return bytes.length == 16 && (bytes[0] & 0xfe) == 0xfc;
    }

    static final class UnsafeUrlException extends IOException {
        private static final long serialVersionUID = 1L;

        UnsafeUrlException(String message) {
            super(message);
        }

        UnsafeUrlException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
