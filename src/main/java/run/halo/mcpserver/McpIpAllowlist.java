package run.halo.mcpserver;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.UnknownHostException;
import java.util.LinkedHashSet;
import java.util.Set;
import org.springframework.security.util.matcher.InetAddressMatcher;
import org.springframework.security.util.matcher.InetAddressMatchers;
import org.springframework.util.StringUtils;

final class McpIpAllowlist {

    private McpIpAllowlist() {}

    static Set<String> normalize(Set<String> ranges) {
        var normalized = new LinkedHashSet<String>();
        if (ranges == null) {
            return normalized;
        }
        for (var range : ranges) {
            if (!StringUtils.hasText(range)) {
                continue;
            }
            var value = range.trim();
            try {
                compile(value);
            } catch (IllegalArgumentException error) {
                throw new IllegalArgumentException("Invalid IP address or CIDR: " + value, error);
            }
            normalized.add(value);
        }
        return normalized;
    }

    static boolean allows(Set<String> ranges, InetSocketAddress remoteAddress) {
        if (ranges == null || ranges.isEmpty()) {
            return true;
        }
        var resolved = resolveNumericAddress(remoteAddress);
        if (resolved.isEmpty()) {
            return false;
        }
        try {
            var address = resolved.get();
            var matchers = ranges.stream()
                    .map(McpIpAllowlist::compile)
                    .toList();
            for (var matcher : matchers) {
                if (matcher.matches(address)) {
                    return true;
                }
            }
        } catch (IllegalArgumentException error) {
            return false;
        }
        return false;
    }

    /**
     * Resolves the numeric client address for both IP authorization and rate limiting, whether
     * the socket address carried a resolved InetAddress or only a numeric host string.
     */
    static java.util.Optional<InetAddress> resolveNumericAddress(InetSocketAddress remoteAddress) {
        if (remoteAddress == null) {
            return java.util.Optional.empty();
        }
        try {
            return java.util.Optional.of(remoteAddress.getAddress() == null
                    ? parseNumericAddress(remoteAddress.getHostString())
                    : remoteAddress.getAddress());
        } catch (IllegalArgumentException error) {
            return java.util.Optional.empty();
        }
    }

    private static CompiledRange compile(String range) {
        var matcher = InetAddressMatchers.fromIpAddress(range);
        var slashIndex = range.indexOf('/');
        var address = slashIndex < 0 ? range : range.substring(0, slashIndex);
        var addressLength = parseNumericAddress(address).getAddress().length;
        if (slashIndex >= 0) {
            validateMask(range.substring(slashIndex + 1), addressLength * Byte.SIZE);
        }
        return new CompiledRange(addressLength, matcher);
    }

    private static InetAddress parseNumericAddress(String address) {
        var value = address.startsWith("[") && address.endsWith("]")
                ? address.substring(1, address.length() - 1)
                : address;
        InetAddressMatchers.fromIpAddress(value);
        try {
            return InetAddress.getByName(value);
        } catch (UnknownHostException error) {
            throw new IllegalArgumentException(error);
        }
    }

    private static void validateMask(String mask, int maxBits) {
        if (!mask.matches("[0-9]+")) {
            throw new IllegalArgumentException("Invalid CIDR mask");
        }
        try {
            if (Integer.parseInt(mask) > maxBits) {
                throw new IllegalArgumentException("Invalid CIDR mask");
            }
        } catch (NumberFormatException error) {
            throw new IllegalArgumentException("Invalid CIDR mask", error);
        }
    }

    private record CompiledRange(int addressLength, InetAddressMatcher matcher) {

        boolean matches(InetAddress address) {
            return address.getAddress().length == addressLength && matcher.matches(address);
        }
    }
}
