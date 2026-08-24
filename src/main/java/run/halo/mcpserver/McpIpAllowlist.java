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
        if (remoteAddress == null || remoteAddress.getAddress() == null) {
            return false;
        }
        try {
            var matchers = ranges.stream()
                    .map(McpIpAllowlist::compile)
                    .toList();
            for (var matcher : matchers) {
                if (matcher.matches(remoteAddress.getAddress())) {
                    return true;
                }
            }
        } catch (IllegalArgumentException error) {
            return false;
        }
        return false;
    }

    private static CompiledRange compile(String range) {
        var matcher = InetAddressMatchers.fromIpAddress(range);
        var slashIndex = range.indexOf('/');
        var address = slashIndex < 0 ? range : range.substring(0, slashIndex);
        try {
            var addressLength = InetAddress.getByName(address).getAddress().length;
            if (slashIndex >= 0) {
                validateMask(range.substring(slashIndex + 1), addressLength * Byte.SIZE);
            }
            return new CompiledRange(addressLength, matcher);
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
