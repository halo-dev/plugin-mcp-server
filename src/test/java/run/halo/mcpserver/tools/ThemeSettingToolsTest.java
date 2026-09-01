package run.halo.mcpserver.tools;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;
import run.halo.app.core.extension.Setting;
import run.halo.app.core.extension.Theme;
import run.halo.app.extension.ConfigMap;
import run.halo.app.extension.Metadata;
import run.halo.app.extension.ReactiveExtensionClient;
import run.halo.app.infra.SystemSetting;
import run.halo.mcpserver.McpAuthorization;
import run.halo.mcpserver.api.McpToolException;
import tools.jackson.databind.json.JsonMapper;

@ExtendWith(MockitoExtension.class)
class ThemeSettingToolsTest {

    @Mock
    ReactiveExtensionClient client;

    @Mock
    McpAuthorization authorization;

    ThemeSettingTools tools;

    @BeforeEach
    void setUp() {
        tools = new ThemeSettingTools(client, authorization);
        stubActiveTheme();
    }

    @Test
    void updateSchemaAcceptsInitialConfigVersion() {
        reset(client);
        var update = tools.tools().stream()
                .filter(tool -> tool.specification().tool().name().equals(ThemeSettingTools.UPDATE_GROUP))
                .findFirst()
                .orElseThrow();
        var properties =
                (Map<?, ?>) update.specification().tool().inputSchema().get("properties");
        var expectedVersion = (Map<?, ?>) properties.get("expectedVersion");

        assertThat(expectedVersion.get("minimum")).isEqualTo(0);
    }

    @Test
    void listsActiveThemeSettingGroupsWithoutLoadingConfig() {
        when(client.fetch(Setting.class, "theme-hao-setting"))
                .thenReturn(Mono.just(setting(
                        form("basics", "Basic", Map.of("$formkit", "text", "name", "title")),
                        form("layout", "Layout", Map.of("$formkit", "switch", "name", "enabled")))));

        var result = data(tools.listGroups(Map.of()).block());

        assertThat(result)
                .containsEntry("themeName", "theme-hao")
                .containsEntry("themeDisplayName", "Hao")
                .containsEntry("themeVersion", "1.2.3");
        assertThat(result.get("groups"))
                .isEqualTo(List.of(
                        Map.of("name", "basics", "label", "Basic"),
                        Map.of("name", "layout", "label", "Layout")));
        verify(client, never()).fetch(ConfigMap.class, "theme-hao-config");
    }

    @Test
    void getsStoredGroupValuesAndRawFormSchema() {
        var schema = Map.<String, Object>of(
                "$formkit", "text", "name", "title", "value", "Schema default");
        when(client.fetch(Setting.class, "theme-hao-setting"))
                .thenReturn(Mono.just(setting(form("basics", "Basic", schema))));
        when(client.fetch(ConfigMap.class, "theme-hao-config"))
                .thenReturn(Mono.just(configMap(
                        "theme-hao-config", 0L, Map.of("basics", "{\"title\":\"Stored value\"}"))));

        var result = data(tools.getGroup(Map.of("group", "basics")).block());

        assertThat(result)
                .containsEntry("themeName", "theme-hao")
                .containsEntry("group", "basics")
                .containsEntry("label", "Basic")
                .containsEntry("configVersion", 0L);
        assertThat(result.get("formSchema")).isEqualTo(List.of(schema));
        assertThat(result.get("values")).isEqualTo(Map.of("title", "Stored value"));
    }

    @Test
    void treatsAMissingStoredGroupAsAnEmptyObject() {
        when(client.fetch(Setting.class, "theme-hao-setting"))
                .thenReturn(Mono.just(setting(form("basics", "Basic", Map.of()))));
        when(client.fetch(ConfigMap.class, "theme-hao-config"))
                .thenReturn(Mono.just(configMap("theme-hao-config", 8L, Map.of())));

        var result = data(tools.getGroup(Map.of("group", "basics")).block());

        assertThat(result.get("values")).isEqualTo(Map.of());
    }

    @Test
    void mergePatchesOnlyTheRequestedGroup() {
        when(client.fetch(Setting.class, "theme-hao-setting"))
                .thenReturn(Mono.just(setting(form("basics", "Basic", Map.of()))));
        var configMap = configMap(
                "theme-hao-config",
                0L,
                Map.of(
                        "basics", "{\"nested\":{\"keep\":1,\"remove\":2},\"items\":[1,2]}",
                        "layout", "{\"enabled\":true}"));
        when(client.fetch(ConfigMap.class, "theme-hao-config"))
                .thenReturn(Mono.just(configMap));
        when(client.update(any(ConfigMap.class))).thenAnswer(invocation -> {
            var updated = invocation.getArgument(0, ConfigMap.class);
            updated.getMetadata().setVersion(1L);
            return Mono.just(updated);
        });
        var nestedPatch = new LinkedHashMap<String, Object>();
        nestedPatch.put("remove", null);
        nestedPatch.put("add", 3);
        var patch = Map.<String, Object>of("nested", nestedPatch, "items", List.of(4));

        var result = data(tools.updateGroup(Map.of(
                        "themeName", "theme-hao",
                        "group", "basics",
                        "patch", patch,
                        "expectedVersion", 0L))
                .block());

        var captor = ArgumentCaptor.forClass(ConfigMap.class);
        verify(client).update(captor.capture());
        var saved = captor.getValue();
        assertThat(saved.getData().get("layout")).isEqualTo("{\"enabled\":true}");
        var savedGroup = JsonMapper.shared().readTree(saved.getData().get("basics"));
        assertThat(savedGroup.get("nested").get("keep").asInt()).isEqualTo(1);
        assertThat(savedGroup.get("nested").has("remove")).isFalse();
        assertThat(savedGroup.get("nested").get("add").asInt()).isEqualTo(3);
        assertThat(savedGroup.get("items").size()).isEqualTo(1);
        assertThat(savedGroup.get("items").get(0).asInt()).isEqualTo(4);
        assertThat(result).containsEntry("configVersion", 1L);
        assertThat(result.get("values")).isEqualTo(Map.of(
                "nested", Map.of("keep", 1, "add", 3), "items", List.of(4)));
    }

    @Test
    void rejectsAStaleThemeOrConfigVersion() {
        StepVerifier.create(tools.updateGroup(Map.of(
                        "themeName", "theme-other",
                        "group", "basics",
                        "patch", Map.of("title", "New"),
                        "expectedVersion", 8L)))
                .expectErrorSatisfies(error -> assertToolError(error, "CONFLICT", "active theme changed"))
                .verify();

        when(client.fetch(Setting.class, "theme-hao-setting"))
                .thenReturn(Mono.just(setting(form("basics", "Basic", Map.of()))));
        when(client.fetch(ConfigMap.class, "theme-hao-config"))
                .thenReturn(Mono.just(configMap("theme-hao-config", 9L, Map.of("basics", "{}"))));

        StepVerifier.create(tools.updateGroup(Map.of(
                        "themeName", "theme-hao",
                        "group", "basics",
                        "patch", Map.of("title", "New"),
                        "expectedVersion", 8L)))
                .expectErrorSatisfies(error -> assertToolError(error, "CONFLICT", "expected version 8"))
                .verify();
        verify(client, never()).update(any(ConfigMap.class));
    }

    @Test
    void rejectsCorruptStoredValuesAndSkipsEmptyPatches() {
        when(client.fetch(Setting.class, "theme-hao-setting"))
                .thenReturn(Mono.just(setting(form("basics", "Basic", Map.of()))));
        when(client.fetch(ConfigMap.class, "theme-hao-config"))
                .thenReturn(Mono.just(configMap("theme-hao-config", 8L, Map.of("basics", "[]"))))
                .thenReturn(Mono.just(configMap(
                        "theme-hao-config", 8L, Map.of("basics", "{\"title\":\"Old\"}"))));

        StepVerifier.create(tools.getGroup(Map.of("group", "basics")))
                .expectErrorSatisfies(error -> assertToolError(
                        error, "THEME_SETTING_UNAVAILABLE", "must contain a JSON object"))
                .verify();

        var result = data(tools.updateGroup(Map.of(
                        "themeName", "theme-hao",
                        "group", "basics",
                        "patch", Map.of(),
                        "expectedVersion", 8L))
                .block());

        assertThat(result)
                .containsEntry("configVersion", 8L)
                .containsEntry("values", Map.of("title", "Old"));
        verify(client, never()).update(any(ConfigMap.class));
    }

    private void stubActiveTheme() {
        when(client.fetch(ConfigMap.class, SystemSetting.SYSTEM_CONFIG))
                .thenReturn(Mono.just(configMap(
                        SystemSetting.SYSTEM_CONFIG,
                        1L,
                        Map.of(SystemSetting.Theme.GROUP, "{\"active\":\"theme-hao\"}"))));
        var theme = new Theme();
        theme.setMetadata(metadata("theme-hao", 3L));
        var spec = new Theme.ThemeSpec();
        spec.setDisplayName("Hao");
        spec.setVersion("1.2.3");
        spec.setSettingName("theme-hao-setting");
        spec.setConfigMapName("theme-hao-config");
        theme.setSpec(spec);
        when(client.fetch(Theme.class, "theme-hao")).thenReturn(Mono.just(theme));
    }

    private static Setting setting(Setting.SettingForm... forms) {
        var setting = new Setting();
        setting.setMetadata(metadata("theme-hao-setting", 1L));
        var spec = new Setting.SettingSpec();
        spec.setForms(List.of(forms));
        setting.setSpec(spec);
        return setting;
    }

    private static Setting.SettingForm form(
            String group, String label, Map<String, Object> schema) {
        var form = new Setting.SettingForm();
        form.setGroup(group);
        form.setLabel(label);
        form.setFormSchema(List.of(schema));
        return form;
    }

    private static ConfigMap configMap(String name, long version, Map<String, String> data) {
        var configMap = new ConfigMap();
        configMap.setMetadata(metadata(name, version));
        configMap.setData(new LinkedHashMap<>(data));
        return configMap;
    }

    private static Metadata metadata(String name, long version) {
        var metadata = new Metadata();
        metadata.setName(name);
        metadata.setVersion(version);
        return metadata;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> data(ToolSupport.ToolPayload payload) {
        return (Map<String, Object>) payload.data();
    }

    private static void assertToolError(Throwable error, String code, String message) {
        assertThat(error)
                .isInstanceOf(McpToolException.class)
                .hasMessageContaining(message);
        assertThat(((McpToolException) error).code()).isEqualTo(code);
    }
}
