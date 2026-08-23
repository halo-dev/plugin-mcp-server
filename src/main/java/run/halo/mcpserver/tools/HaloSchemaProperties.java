package run.halo.mcpserver.tools;

import com.fasterxml.jackson.core.type.TypeReference;
import io.swagger.v3.core.converter.ModelConverters;
import io.swagger.v3.core.util.Json;
import java.lang.reflect.Field;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springdoc.core.providers.SpringDocJavadocProvider;
import org.springframework.util.StringUtils;

final class HaloSchemaProperties {

    private static final SpringDocJavadocProvider JAVADOCS = new SpringDocJavadocProvider();
    private static final Map<PropertyKey, Map<String, Object>> CACHE = new ConcurrentHashMap<>();

    private HaloSchemaProperties() {}

    static Map<String, Object> property(Class<?> owner, String name) {
        return new LinkedHashMap<>(CACHE.computeIfAbsent(new PropertyKey(owner, name), HaloSchemaProperties::resolve));
    }

    private static Map<String, Object> resolve(PropertyKey key) {
        var resolved = ModelConverters.getInstance().readAllAsResolvedSchema(key.owner());
        var properties = resolved.schema.getProperties();
        if (properties == null || !properties.containsKey(key.name())) {
            throw new IllegalArgumentException(
                    "Halo schema property not found: " + key.owner().getName() + "." + key.name());
        }
        var propertySchema = properties.get(key.name());
        var result = Json.mapper().convertValue(propertySchema, new TypeReference<Map<String, Object>>() {});
        normalize(result);
        var description = (String) result.get("description");
        if (!StringUtils.hasText(description)) {
            description = JAVADOCS.getFieldJavadoc(field(key));
        }
        if (StringUtils.hasText(description)) {
            result.put("description", description.strip().replaceAll("\\s+", " "));
        }
        return Map.copyOf(result);
    }

    private static Field field(PropertyKey key) {
        try {
            return key.owner().getDeclaredField(key.name());
        } catch (NoSuchFieldException error) {
            throw new IllegalArgumentException(
                    "Halo model field not found: " + key.owner().getName() + "." + key.name(), error);
        }
    }

    private static void normalize(Map<String, Object> schema) {
        schema.remove("nullable");
        schema.values().forEach(value -> {
            if (value instanceof Map<?, ?> nested) {
                normalize(cast(nested));
            } else if (value instanceof List<?> items) {
                items.stream()
                        .filter(Map.class::isInstance)
                        .map(Map.class::cast)
                        .map(HaloSchemaProperties::cast)
                        .forEach(HaloSchemaProperties::normalize);
            }
        });
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> cast(Map<?, ?> value) {
        return (Map<String, Object>) value;
    }

    private record PropertyKey(Class<?> owner, String name) {}
}
