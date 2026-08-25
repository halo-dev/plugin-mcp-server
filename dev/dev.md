# 插件工具 Provider 接入指南

其他 Halo 插件可以通过协议无关的 MCP Server API 贡献工具。该集成是渐进式的：
MCP Server 不存在时，Provider 插件仍可安装并保持启用；MCP Server 安装并启用后，
Provider 提供的工具会自动加入工具目录。

## 添加 API 依赖

正式版已经发布到 Maven Central，Provider 插件直接添加编译期依赖即可：

```groovy
repositories {
    mavenCentral()
}

dependencies {
    compileOnly "run.halo.mcpserver:api:1.0.0"
}
```

只在编译期依赖 API，不要通过 `implementation` 把它或 MCP Java SDK 打入 Provider 插件。
如果测试代码会加载 API 类型，还需要把同一依赖加入测试编译和运行时类路径：

```groovy
dependencies {
    testCompileOnly "run.halo.mcpserver:api:1.0.0"
    testRuntimeOnly "run.halo.mcpserver:api:1.0.0"
}
```

### 使用本地 API JAR

同时开发 MCP Server 和 Provider 插件时，可以先在 MCP Server 仓库构建 API JAR：

```bash
./gradlew :api:jar
```

然后在 Provider 插件中引用生成的 JAR。请把路径替换为实际位置；当前 `main` 分支默认生成
`api-1.0.0-SNAPSHOT.jar`：

```groovy
def mcpServerApiJar = files('/path/to/plugin-mcp-server/api/build/libs/api-1.0.0-SNAPSHOT.jar')

dependencies {
    compileOnly mcpServerApiJar
    testCompileOnly mcpServerApiJar
    testRuntimeOnly mcpServerApiJar
}
```

本仓库的 [`examples/`](../examples/) 使用的就是本地 JAR 方式。

### 使用 SNAPSHOT 版本

需要验证尚未正式发布的 API 时，可以使用 Maven Central Snapshots：

```groovy
repositories {
    maven {
        url = uri('https://central.sonatype.com/repository/maven-snapshots/')
        mavenContent {
            snapshotsOnly()
        }
    }
    mavenCentral()
}

dependencies {
    compileOnly "run.halo.mcpserver:api:1.0.0-SNAPSHOT"
    testCompileOnly "run.halo.mcpserver:api:1.0.0-SNAPSHOT"
    testRuntimeOnly "run.halo.mcpserver:api:1.0.0-SNAPSHOT"
}
```

`main` 分支推送后会发布 `gradle.properties` 中的 SNAPSHOT 版本；推送 `v*` 标签后会发布
标签对应的正式版本，例如 `v1.0.0` 会发布 `run.halo.mcpserver:api:1.0.0`。

## 声明可选插件依赖

在 Provider 插件的 `plugin.yaml` 中，以 `?` 后缀声明 MCP Server。这让 Halo 在 MCP
Server 存在时正确安排插件加载和重载顺序，同时不会阻止 Provider 插件独立启动：

```yaml
spec:
    requires: ">=2.26.0"
    pluginDependencies:
        mcp-server?: ">=1.0.0 & <2.0.0"
```

## 实现 Provider

实现 `McpToolProvider`，但不要直接给 Provider 类添加 `@Component`。工具名必须全局唯一，
格式为 `<插件 metadata.name>/<工具名>`，并且一个 Provider 中的所有工具必须使用同一个
插件命名空间。MCP Server 会根据 Provider 类实际所在的插件加载路径校验命名空间归属，
冒用其他插件命名空间的 Provider 会被忽略。

每个工具都必须提供合法的 JSON Schema 对象类型输入 Schema、权限回调和响应式 Handler。
如果声明了 `outputSchema`，成功结果的 `structuredContent` 必须满足该 Schema。

```java
public final class ExportToolProvider implements McpToolProvider {
    @Override
    public Flux<McpToolDefinition> tools() {
        return Flux.just(McpToolDefinition.builder()
            .name("my-plugin/export")
            .title("Export data")
            .description("Export plugin data.")
            .displayTitle("导出数据")
            .displayDescription("导出插件数据。")
            .inputSchema(Map.of(
                "type", "object",
                "properties", Map.of(),
                "additionalProperties", false))
            .annotations(McpToolAnnotations.readOnly("Export data"))
            .permission(invocation -> Mono.just(true))
            .handler(invocation -> Mono.just(
                McpToolResult.success(Map.of("items", List.of()))))
            .build());
    }
}
```

访问密钥的工具白名单会在 Provider 权限回调之前校验。如果白名单已经足够，可以让回调
返回 `true`；如果插件还有自己的领域权限，需要在回调中继续检查。预期内的失败应返回
`McpToolResult.error(code, message)` 或抛出 `McpToolException`，错误码应使用稳定值，例如
`INVALID_ARGUMENT`、`NOT_FOUND` 或 `FORBIDDEN`。

`title` 和 `description` 会发送给 MCP 客户端，应该面向 Agent 编写。可选的 `displayTitle`
和 `displayDescription` 只用于 Halo 的 MCP 管理界面，因此插件可以提供中文管理文案，而不
改变协议描述；省略时，管理界面会回退到协议字段。

## 按条件注册 Provider

通过配置类注册 Provider Bean，并用 API 类名作为启用条件。必须使用字符串形式，因为
MCP Server 不存在时，Provider 插件的运行时类路径中没有该 API：

```java
@Configuration
@ConditionalOnClass(name = "run.halo.mcpserver.api.McpToolProvider")
public class McpToolConfiguration {
    @Bean
    ExportToolProvider exportToolProvider() {
        return new ExportToolProvider();
    }
}
```

MCP Server 会在每次 `tools/list` 和 `tools/call` 时重新解析所有已启用的 Provider，因此
插件生命周期变化会在下一次请求生效。新贡献的工具不会自动加入已有密钥；管理员必须在
「工具 → MCP 服务」中选择它。`tools/list` 只返回当前密钥已经选择的工具。

单个 Provider 不可用、返回无效工具或抛出异常时会被记录并忽略，不会隐藏内置工具或其他
健康 Provider 的工具。如果多个 Provider 贡献同名工具，冲突的工具都会被忽略。

## 构建与验证

先构建 API，再构建 Provider 插件：

```bash
./gradlew :api:jar
./gradlew -p examples/plugin-mcp-tool-demo build
```

构建 Provider 插件后，检查其 JAR，确认没有打包 `run/halo/mcpserver/api` 类。至少验证以下
运行状态：

1. 未安装 MCP Server 时启用 Provider 插件，插件应正常启动。
2. 启用 MCP Server 后，贡献的工具应出现在「MCP 服务」中。
3. 将工具加入访问密钥并调用 `tools/list`，响应中应只包含该密钥选择的工具。
4. 禁用 MCP Server 后，Provider 插件应继续保持启用。

完整示例位于 [`plugin-mcp-tool-demo`](../examples/plugin-mcp-tool-demo/) 和
[`plugin-mcp-tool-clock`](../examples/plugin-mcp-tool-clock/)。
