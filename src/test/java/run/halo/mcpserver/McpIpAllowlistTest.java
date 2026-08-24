package run.halo.mcpserver;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.InetSocketAddress;
import java.util.LinkedHashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;

class McpIpAllowlistTest {

    @Test
    void normalizesAndValidatesRanges() {
        var ranges = new LinkedHashSet<>(
                java.util.List.of(" 203.0.113.10 ", "203.0.113.0/24", "2001:db8::/32"));

        assertThat(McpIpAllowlist.normalize(ranges))
                .containsExactly("203.0.113.10", "203.0.113.0/24", "2001:db8::/32");
    }

    @Test
    void rejectsHostnamesAndInvalidMasks() {
        assertThatThrownBy(() -> McpIpAllowlist.normalize(Set.of("example.com")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Invalid IP address or CIDR: example.com");
        assertThatThrownBy(() -> McpIpAllowlist.normalize(Set.of("203.0.113.0/33")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Invalid IP address or CIDR: 203.0.113.0/33");
        assertThatThrownBy(() -> McpIpAllowlist.normalize(Set.of("203.0.113.0/-1")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Invalid IP address or CIDR: 203.0.113.0/-1");
        assertThatThrownBy(() -> McpIpAllowlist.normalize(Set.of("203.0.113.0/-0")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Invalid IP address or CIDR: 203.0.113.0/-0");
        assertThatThrownBy(() -> McpIpAllowlist.normalize(Set.of("203.0.113.0/+1")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Invalid IP address or CIDR: 203.0.113.0/+1");
    }

    @Test
    void allowsExactAddressesAndCidrsForBothAddressFamilies() {
        assertThat(McpIpAllowlist.allows(
                        Set.of("203.0.113.10"), new InetSocketAddress("203.0.113.10", 443)))
                .isTrue();
        assertThat(McpIpAllowlist.allows(
                        Set.of("203.0.113.0/24"), new InetSocketAddress("203.0.113.42", 443)))
                .isTrue();
        assertThat(McpIpAllowlist.allows(
                        Set.of("2001:db8::/32"), new InetSocketAddress("2001:db8::42", 443)))
                .isTrue();
    }

    @Test
    void rejectsMismatchesAndUnknownAddressesWhenConfigured() {
        assertThat(McpIpAllowlist.allows(
                        Set.of("203.0.113.0/24"), new InetSocketAddress("198.51.100.10", 443)))
                .isFalse();
        assertThat(McpIpAllowlist.allows(Set.of("203.0.113.0/24"), null))
                .isFalse();
        assertThat(McpIpAllowlist.allows(
                        Set.of("203.0.113.0/24"),
                        InetSocketAddress.createUnresolved("client.example.com", 443)))
                .isFalse();
    }

    @Test
    void rejectsAddressesFromAnotherAddressFamily() {
        assertThat(McpIpAllowlist.allows(
                        Set.of("0.0.0.0/0"), new InetSocketAddress("203.0.113.10", 443)))
                .isTrue();
        assertThat(McpIpAllowlist.allows(
                        Set.of("::/0"), new InetSocketAddress("2001:db8::10", 443)))
                .isTrue();
        assertThat(McpIpAllowlist.allows(
                        Set.of("cb00:710a::/32"), new InetSocketAddress("203.0.113.10", 443)))
                .isFalse();
        assertThat(McpIpAllowlist.allows(
                        Set.of("::/0"), new InetSocketAddress("203.0.113.10", 443)))
                .isFalse();
        assertThat(McpIpAllowlist.allows(
                        Set.of("0.0.0.0/0"), new InetSocketAddress("2001:db8::10", 443)))
                .isFalse();
    }

    @Test
    void allowsUnknownAddressesWhenNotConfigured() {
        assertThat(McpIpAllowlist.allows(Set.of(), null)).isTrue();
    }

    @Test
    void failsClosedWhenStoredConfigurationIsInvalid() {
        var ranges = new LinkedHashSet<>(java.util.List.of("203.0.113.10", "invalid"));

        assertThat(McpIpAllowlist.allows(
                        ranges, new InetSocketAddress("203.0.113.10", 443)))
                .isFalse();
    }
}
