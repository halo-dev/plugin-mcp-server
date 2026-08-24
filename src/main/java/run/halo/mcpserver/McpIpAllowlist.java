package run.halo.mcpserver;

import java.net.InetSocketAddress;
import java.util.LinkedHashSet;
import java.util.Set;
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
                InetAddressMatchers.fromIpAddress(value);
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
                    .map(InetAddressMatchers::fromIpAddress)
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
}
