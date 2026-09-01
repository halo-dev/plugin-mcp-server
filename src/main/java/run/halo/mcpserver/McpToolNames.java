package run.halo.mcpserver;

import java.util.Optional;
import java.util.regex.Pattern;
import run.halo.mcpserver.api.McpToolException;

final class McpToolNames {

    private static final String SEPARATOR = "__";
    private static final int MAX_LENGTH = 128;
    private static final int MAX_COMPONENT_LENGTH = 63;
    private static final Pattern PROTOCOL_NAME = Pattern.compile("[A-Za-z0-9_-]+");
    private static final Pattern LOCAL_NAME = Pattern.compile("[a-z][a-z0-9_]*");
    private static final char[] HEX = "0123456789abcdef".toCharArray();

    private McpToolNames() {}

    static String contributed(String pluginName, String localName) {
        if (pluginName == null || pluginName.isBlank()) {
            throw new McpToolException(
                    "TOOL_PROVIDER_UNAVAILABLE", "Cannot determine the MCP tool provider plugin");
        }
        if (localName == null
                || localName.length() > MAX_COMPONENT_LENGTH
                || !LOCAL_NAME.matcher(localName).matches()) {
            throw new McpToolException(
                    "INVALID_TOOL_NAME", "Tool name must be a lowercase snake_case local name");
        }
        var encodedPluginName = encode(pluginName);
        if (encodedPluginName.length() > MAX_COMPONENT_LENGTH) {
            throw new McpToolException(
                    "TOOL_PROVIDER_UNAVAILABLE",
                    "Encoded plugin ID must not exceed 63 characters for MCP tools");
        }
        var protocolName = encodedPluginName + SEPARATOR + localName;
        if (protocolName.length() > MAX_LENGTH || !PROTOCOL_NAME.matcher(protocolName).matches()) {
            throw new McpToolException(
                    "INVALID_TOOL_NAME", "Generated MCP tool name exceeds the protocol limits");
        }
        return protocolName;
    }

    static Optional<String> pluginName(String protocolName) {
        if (protocolName == null
                || protocolName.length() > MAX_LENGTH
                || !PROTOCOL_NAME.matcher(protocolName).matches()) {
            return Optional.empty();
        }
        var separator = protocolName.indexOf(SEPARATOR);
        if (separator <= 0
                || separator > MAX_COMPONENT_LENGTH
                || separator == protocolName.length() - SEPARATOR.length()) {
            return Optional.empty();
        }
        var localName = protocolName.substring(separator + SEPARATOR.length());
        if (localName.length() > MAX_COMPONENT_LENGTH || !LOCAL_NAME.matcher(localName).matches()) {
            return Optional.empty();
        }
        return decode(protocolName.substring(0, separator));
    }

    private static String encode(String value) {
        var encoded = new StringBuilder(value.length());
        value.codePoints().forEach(codePoint -> {
            if (isPlain(codePoint)) {
                encoded.appendCodePoint(codePoint);
                return;
            }
            encoded.append('_');
            for (var shift = 20; shift >= 0; shift -= 4) {
                encoded.append(HEX[(codePoint >>> shift) & 0xf]);
            }
        });
        return encoded.toString();
    }

    private static Optional<String> decode(String value) {
        var decoded = new StringBuilder(value.length());
        for (var index = 0; index < value.length(); index++) {
            var current = value.charAt(index);
            if (isPlain(current)) {
                decoded.append(current);
                continue;
            }
            if (current != '_' || index + 6 >= value.length()) {
                return Optional.empty();
            }
            var codePoint = 0;
            for (var offset = 1; offset <= 6; offset++) {
                var digit = Character.digit(value.charAt(index + offset), 16);
                if (digit < 0) {
                    return Optional.empty();
                }
                codePoint = (codePoint << 4) | digit;
            }
            if (!Character.isValidCodePoint(codePoint)) {
                return Optional.empty();
            }
            decoded.appendCodePoint(codePoint);
            index += 6;
        }
        return decoded.isEmpty() ? Optional.empty() : Optional.of(decoded.toString());
    }

    private static boolean isPlain(int value) {
        return value >= 'a' && value <= 'z'
                || value >= 'A' && value <= 'Z'
                || value >= '0' && value <= '9'
                || value == '-';
    }
}
