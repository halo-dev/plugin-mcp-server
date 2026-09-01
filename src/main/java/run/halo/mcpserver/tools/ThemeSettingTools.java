package run.halo.mcpserver.tools;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import reactor.core.publisher.Mono;
import run.halo.app.core.extension.Setting;
import run.halo.app.core.extension.Theme;
import run.halo.app.extension.ConfigMap;
import run.halo.app.extension.ReactiveExtensionClient;
import run.halo.app.infra.SystemSetting;
import run.halo.mcpserver.McpAuthorization;
import run.halo.mcpserver.api.McpToolException;
import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

@Component
class ThemeSettingTools extends ToolSupport implements ToolGroup {

    static final String LIST_GROUPS = "halo_list_theme_setting_groups";
    static final String GET_GROUP = "halo_get_theme_setting_group";
    static final String UPDATE_GROUP = "halo_update_theme_setting_group";
    private static final JsonMapper JSON_MAPPER = JsonMapper.shared();
    private static final TypeReference<Map<String, Object>> OBJECT_MAP =
            new TypeReference<>() {};

    private final ReactiveExtensionClient client;

    ThemeSettingTools(ReactiveExtensionClient client, McpAuthorization authorization) {
        super(authorization);
        this.client = client;
    }

    @Override
    public List<BuiltInTool> tools() {
        return List.of(listGroupsTool(), getGroupTool(), updateGroupTool());
    }

    Mono<ToolPayload> listGroups(Map<String, Object> arguments) {
        return activeTheme().flatMap(theme -> setting(theme)
                .map(setting -> listGroupsPayload(theme, forms(setting)))
                .defaultIfEmpty(listGroupsPayload(theme, List.of())));
    }

    Mono<ToolPayload> getGroup(Map<String, Object> arguments) {
        var group = requiredString(arguments, "group");
        return activeTheme()
                .flatMap(this::settings)
                .map(settings -> {
                    var form = form(settings.setting(), group);
                    var values = groupValues(settings.configMap(), group);
                    return payload(
                            groupPayload(settings.theme(), form, values, configVersion(settings.configMap())),
                            "Read theme setting group " + group);
                });
    }

    Mono<ToolPayload> updateGroup(Map<String, Object> arguments) {
        var themeName = resourceName(arguments, "themeName");
        var group = requiredString(arguments, "group");
        var patch = requiredPatch(arguments);
        var expectedVersion = requiredLong(arguments, "expectedVersion");
        return activeTheme()
                .flatMap(theme -> {
                    var activeThemeName = theme.getMetadata().getName();
                    if (!themeName.equals(activeThemeName)) {
                        return Mono.error(new McpToolException(
                                "CONFLICT",
                                "The active theme changed; expected " + themeName + ", actual " + activeThemeName));
                    }
                    return settings(theme);
                })
                .flatMap(settings -> {
                    form(settings.setting(), group);
                    var current = groupValues(settings.configMap(), group);
                    return checkVersion(configVersion(settings.configMap()), expectedVersion)
                            .then(Mono.defer(() -> {
                                if (patch.isEmpty()) {
                                    return Mono.just(payload(
                                            updatedGroupPayload(
                                                    settings.theme(), group, current, expectedVersion),
                                            "Theme setting group " + group + " was unchanged"));
                                }
                                var merged = mergePatch(current, patch);
                                var data = new LinkedHashMap<>(configData(settings.configMap()));
                                data.put(group, JSON_MAPPER.writeValueAsString(merged));
                                settings.configMap().setData(data);
                                return client.update(settings.configMap()).map(updated -> payload(
                                        updatedGroupPayload(
                                                settings.theme(), group, merged, configVersion(updated)),
                                        "Updated theme setting group " + group));
                            }));
                });
    }

    private BuiltInTool listGroupsTool() {
        return tool(
                LIST_GROUPS,
                "List active Halo theme setting groups",
                "List the active theme and its configurable setting groups.",
                "查询主题设置组",
                "查询当前启用主题及其可配置的设置组。",
                "THEME",
                objectSchema(Map.of(), List.of()),
                listGroupsOutputSchema(),
                READ_ONLY,
                this::listGroups);
    }

    private BuiltInTool getGroupTool() {
        return tool(
                GET_GROUP,
                "Get an active Halo theme setting group",
                "Read one active-theme setting group with its raw FormKit schema, stored values, and config version.",
                "读取主题设置组",
                "读取当前启用主题的指定设置组、表单结构、已存储值和配置版本。",
                "THEME",
                objectSchema(
                        map("group", stringSchema("Setting group name returned by the list tool.")),
                        List.of("group")),
                groupOutputSchema(true),
                READ_ONLY,
                this::getGroup);
    }

    private BuiltInTool updateGroupTool() {
        return tool(
                UPDATE_GROUP,
                "Update an active Halo theme setting group",
                "Apply a JSON Merge Patch to one active-theme setting group after verifying the theme and config "
                        + "version. Null removes an object field; arrays are replaced.",
                "更新主题设置组",
                "校验当前主题和配置版本后，为指定设置组应用 JSON Merge Patch。",
                "THEME",
                objectSchema(
                        map(
                                "themeName", stringSchema("Active theme metadata.name returned by a read tool."),
                                "group", stringSchema("Setting group name returned by the list tool."),
                                "patch", described(
                                        arbitraryObjectSchema(),
                                        "JSON Merge Patch for the selected group. Null removes a field; arrays "
                                                + "replace existing arrays."),
                                "expectedVersion", described(
                                        integerSchema(),
                                        "Current configVersion returned by the get tool.")),
                        List.of("themeName", "group", "patch", "expectedVersion")),
                groupOutputSchema(false),
                UPDATE,
                this::updateGroup);
    }

    private Mono<Theme> activeTheme() {
        return client.fetch(ConfigMap.class, SystemSetting.SYSTEM_CONFIG)
                .map(ThemeSettingTools::configData)
                .mapNotNull(data -> data.get(SystemSetting.Theme.GROUP))
                .flatMap(json -> Mono.fromCallable(() -> JSON_MAPPER.readValue(json, SystemSetting.Theme.class)))
                .onErrorMap(
                        error -> error instanceof JacksonException,
                        error -> new McpToolException(
                                "THEME_SETTING_UNAVAILABLE", "The active theme setting is invalid", error))
                .mapNotNull(SystemSetting.Theme::getActive)
                .filter(StringUtils::hasText)
                .switchIfEmpty(Mono.error(new McpToolException(
                        "THEME_SETTING_UNAVAILABLE", "No active theme is configured")))
                .flatMap(name -> client.fetch(Theme.class, name)
                        .switchIfEmpty(notFound("Active theme", name)));
    }

    private Mono<Setting> setting(Theme theme) {
        var spec = theme.getSpec();
        var settingName = spec == null ? null : spec.getSettingName();
        if (!StringUtils.hasText(settingName)) {
            return Mono.empty();
        }
        return client.fetch(Setting.class, settingName)
                .switchIfEmpty(Mono.error(new McpToolException(
                        "THEME_SETTING_UNAVAILABLE", "Theme setting not found: " + settingName)));
    }

    private Mono<ThemeSettings> settings(Theme theme) {
        var spec = theme.getSpec();
        var configMapName = spec == null ? null : spec.getConfigMapName();
        if (!StringUtils.hasText(configMapName)) {
            return Mono.error(new McpToolException(
                    "THEME_SETTING_UNAVAILABLE", "The active theme has no setting ConfigMap"));
        }
        return setting(theme)
                .switchIfEmpty(Mono.error(new McpToolException(
                        "THEME_SETTING_UNAVAILABLE", "The active theme has no settings")))
                .zipWith(client.fetch(ConfigMap.class, configMapName)
                        .switchIfEmpty(Mono.error(new McpToolException(
                                "THEME_SETTING_UNAVAILABLE",
                                "Theme setting ConfigMap not found: " + configMapName))))
                .map(tuple -> new ThemeSettings(theme, tuple.getT1(), tuple.getT2()));
    }

    private static ToolPayload listGroupsPayload(Theme theme, List<Setting.SettingForm> forms) {
        var groups = forms.stream()
                .map(form -> map("name", form.getGroup(), "label", form.getLabel()))
                .toList();
        return payload(
                map(
                        "themeName", theme.getMetadata().getName(),
                        "themeDisplayName", theme.getSpec().getDisplayName(),
                        "themeVersion", theme.getSpec().getVersion(),
                        "groups", groups),
                "Listed " + groups.size() + " theme setting groups");
    }

    private static Map<String, Object> groupPayload(
            Theme theme,
            Setting.SettingForm form,
            ObjectNode values,
            long configVersion) {
        var result = updatedGroupPayload(theme, form.getGroup(), values, configVersion);
        result.put("label", form.getLabel());
        result.put("formSchema", Objects.requireNonNullElse(form.getFormSchema(), List.of()));
        return result;
    }

    private static LinkedHashMap<String, Object> updatedGroupPayload(
            Theme theme, String group, ObjectNode values, long configVersion) {
        return map(
                "themeName", theme.getMetadata().getName(),
                "group", group,
                "values", JSON_MAPPER.convertValue(values, OBJECT_MAP),
                "configVersion", configVersion);
    }

    private static List<Setting.SettingForm> forms(Setting setting) {
        return setting.getSpec() == null || setting.getSpec().getForms() == null
                ? List.of()
                : setting.getSpec().getForms();
    }

    private static Setting.SettingForm form(Setting setting, String group) {
        return forms(setting).stream()
                .filter(candidate -> group.equals(candidate.getGroup()))
                .findFirst()
                .orElseThrow(() -> new McpToolException(
                        "NOT_FOUND", "Theme setting group not found: " + group));
    }

    private static ObjectNode requiredPatch(Map<String, Object> arguments) {
        var value = arguments.get("patch");
        if (!(value instanceof Map<?, ?>)) {
            throw new McpToolException("INVALID_ARGUMENT", "patch must be an object");
        }
        return JSON_MAPPER.convertValue(value, ObjectNode.class);
    }

    private static ObjectNode groupValues(ConfigMap configMap, String group) {
        var json = configData(configMap).get(group);
        if (json == null) {
            return JSON_MAPPER.createObjectNode();
        }
        final JsonNode value;
        try {
            value = JSON_MAPPER.readTree(json);
        } catch (JacksonException error) {
            throw new McpToolException(
                    "THEME_SETTING_UNAVAILABLE",
                    "Theme setting group " + group + " contains invalid JSON",
                    error);
        }
        if (!(value instanceof ObjectNode object)) {
            throw new McpToolException(
                    "THEME_SETTING_UNAVAILABLE",
                    "Theme setting group " + group + " must contain a JSON object");
        }
        return object;
    }

    private static ObjectNode mergePatch(ObjectNode target, ObjectNode patch) {
        patch.properties().forEach(entry -> {
            var name = entry.getKey();
            var value = entry.getValue();
            if (value.isNull()) {
                target.remove(name);
            } else if (value instanceof ObjectNode objectPatch) {
                var current = target.get(name);
                var objectTarget = current instanceof ObjectNode object ? object : JSON_MAPPER.createObjectNode();
                target.set(name, mergePatch(objectTarget, objectPatch));
            } else {
                target.set(name, value);
            }
        });
        return target;
    }

    private static long configVersion(ConfigMap configMap) {
        var metadata = configMap.getMetadata();
        var version = metadata == null ? null : metadata.getVersion();
        if (version == null) {
            throw new McpToolException(
                    "THEME_SETTING_UNAVAILABLE", "Theme setting ConfigMap has no version");
        }
        return version;
    }

    private static Map<String, String> configData(ConfigMap configMap) {
        return configMap.getData() == null ? Map.of() : configMap.getData();
    }

    private static Map<String, Object> listGroupsOutputSchema() {
        var groupSchema = objectSchema(
                map(
                        "name", described(stringSchema(), "Stable setting group name."),
                        "label", described(nullableOutputStringSchema(), "Display label of the setting group.")),
                List.of("name"));
        return objectSchema(
                map(
                        "themeName", described(stringSchema(), "Active theme metadata.name."),
                        "themeDisplayName", described(nullableOutputStringSchema(), "Active theme display name."),
                        "themeVersion", described(nullableOutputStringSchema(), "Active theme package version."),
                        "groups", described(
                                outputArraySchema(groupSchema),
                                "Setting groups declared by the active theme.")),
                List.of("themeName", "groups"));
    }

    private static Map<String, Object> groupOutputSchema(boolean includeForm) {
        var properties = map(
                "themeName", described(stringSchema(), "Active theme metadata.name."),
                "group", described(stringSchema(), "Setting group name."),
                "values", described(arbitraryObjectSchema(), "Stored values for the setting group."),
                "configVersion", described(outputIntegerSchema(), "Current ConfigMap metadata.version."));
        var required = new java.util.ArrayList<>(List.of("themeName", "group", "values", "configVersion"));
        if (includeForm) {
            properties.put("label", described(nullableOutputStringSchema(), "Display label of the setting group."));
            properties.put(
                    "formSchema",
                    described(outputArraySchema(Map.of()), "Raw FormKit schema declared by the theme."));
            required.add("formSchema");
        }
        return objectSchema(properties, required);
    }

    private static Map<String, Object> arbitraryObjectSchema() {
        return map("type", "object", "properties", Map.of(), "additionalProperties", true);
    }

    private record ThemeSettings(Theme theme, Setting setting, ConfigMap configMap) {}
}
