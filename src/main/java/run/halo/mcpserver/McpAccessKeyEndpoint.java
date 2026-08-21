package run.halo.mcpserver;

import static org.springdoc.core.fn.builders.apiresponse.Builder.responseBuilder;
import static org.springdoc.core.fn.builders.parameter.Builder.parameterBuilder;
import static org.springdoc.core.fn.builders.requestbody.Builder.requestBodyBuilder;
import static org.springdoc.webflux.core.fn.SpringdocRouteBuilder.route;

import io.swagger.v3.oas.annotations.enums.ParameterIn;
import io.swagger.v3.oas.annotations.media.Schema;
import java.net.URI;
import java.security.Principal;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.context.ReactiveSecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.server.RouterFunction;
import org.springframework.web.reactive.function.server.ServerRequest;
import org.springframework.web.reactive.function.server.ServerResponse;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.server.ServerWebInputException;
import reactor.core.publisher.Mono;
import run.halo.app.core.extension.endpoint.CustomEndpoint;
import run.halo.app.extension.GroupVersion;

@Component
class McpAccessKeyEndpoint implements CustomEndpoint {

    private static final GroupVersion GROUP_VERSION =
            new GroupVersion("console.api.mcp.halo.run", "v1alpha1");

    private final McpAccessKeyService accessKeyService;
    private final McpToolCatalog toolCatalog;

    McpAccessKeyEndpoint(McpAccessKeyService accessKeyService, McpToolCatalog toolCatalog) {
        this.accessKeyService = accessKeyService;
        this.toolCatalog = toolCatalog;
    }

    @Override
    public RouterFunction<ServerResponse> endpoint() {
        final var tag = "console.api.mcp.halo.run/v1alpha1/McpAccessKey";
        return route()
                .GET("/keys", this::list, builder -> builder
                        .operationId("listMcpAccessKeys")
                        .description("List MCP access keys.")
                        .tag(tag)
                        .response(responseBuilder()
                                .responseCode("200")
                                .implementation(AccessKeyView[].class)))
                .POST("/keys", this::create, builder -> builder
                        .operationId("createMcpAccessKey")
                        .description("Create an MCP access key.")
                        .tag(tag)
                        .requestBody(requestBodyBuilder()
                                .required(true)
                                .implementation(CreateKeyRequest.class))
                        .response(responseBuilder()
                                .responseCode("201")
                                .implementation(CreatedKeyResponse.class)))
                .PUT("/keys/{name}", this::update, builder -> builder
                        .operationId("updateMcpAccessKey")
                        .description("Update an MCP access key.")
                        .tag(tag)
                        .parameter(nameParameter())
                        .requestBody(requestBodyBuilder()
                                .required(true)
                                .implementation(UpdateKeyRequest.class))
                        .response(responseBuilder()
                                .responseCode("200")
                                .implementation(AccessKeyView.class)))
                .POST("/keys/{name}/rotate", this::rotate, builder -> builder
                        .operationId("rotateMcpAccessKey")
                        .description("Rotate an MCP access key.")
                        .tag(tag)
                        .parameter(nameParameter())
                        .response(responseBuilder()
                                .responseCode("200")
                                .implementation(CreatedKeyResponse.class)))
                .DELETE("/keys/{name}", this::delete, builder -> builder
                        .operationId("deleteMcpAccessKey")
                        .description("Delete an MCP access key.")
                        .tag(tag)
                        .parameter(nameParameter())
                        .response(responseBuilder().responseCode("204")))
                .GET("/tools", this::tools, builder -> builder
                        .operationId("listMcpTools")
                        .description("List MCP tools available for access keys.")
                        .tag(tag)
                        .response(responseBuilder()
                                .responseCode("200")
                                .implementation(McpToolCatalog.ToolDescriptor[].class)))
                .build();
    }

    private static org.springdoc.core.fn.builders.parameter.Builder nameParameter() {
        return parameterBuilder()
                .name("name")
                .in(ParameterIn.PATH)
                .description("MCP access key metadata name.")
                .required(true)
                .implementation(String.class);
    }

    private Mono<ServerResponse> list(ServerRequest request) {
        return accessKeyService.list()
                .map(McpAccessKeyEndpoint::view)
                .collectList()
                .flatMap(keys -> ServerResponse.ok().bodyValue(keys));
    }

    private Mono<ServerResponse> create(ServerRequest request) {
        return request.bodyToMono(CreateKeyRequest.class)
                .switchIfEmpty(Mono.error(new ServerWebInputException("Request body is required")))
                .flatMap(body -> validateTools(body.allowedTools()).thenReturn(body))
                .zipWith(currentUsername())
                .flatMap(tuple -> accessKeyService.create(
                        tuple.getT1().displayName(),
                        tuple.getT2(),
                        tools(tuple.getT1().allowedTools()),
                        tuple.getT1().expiresAt()))
                .flatMap(created -> ServerResponse.created(URI.create("keys/" + created.accessKey()
                                .getMetadata()
                                .getName()))
                        .bodyValue(new CreatedKeyResponse(view(created.accessKey()), created.token())))
                .onErrorMap(IllegalArgumentException.class, error ->
                        new ServerWebInputException(error.getMessage(), null, error));
    }

    private Mono<ServerResponse> update(ServerRequest request) {
        var name = request.pathVariable("name");
        return request.bodyToMono(UpdateKeyRequest.class)
                .switchIfEmpty(Mono.error(new ServerWebInputException("Request body is required")))
                .flatMap(body -> validateTools(body.allowedTools()).thenReturn(body))
                .flatMap(body -> accessKeyService.update(
                        name,
                        body.displayName(),
                        tools(body.allowedTools()),
                        body.expiresAt(),
                        body.enabled()))
                .flatMap(key -> ServerResponse.ok().bodyValue(view(key)))
                .onErrorMap(McpAccessKeyService.AccessKeyNotFoundException.class, error ->
                        new ResponseStatusException(HttpStatus.NOT_FOUND, error.getMessage(), error));
    }

    private Mono<ServerResponse> rotate(ServerRequest request) {
        return accessKeyService.rotate(request.pathVariable("name"))
                .flatMap(created -> ServerResponse.ok()
                        .bodyValue(new CreatedKeyResponse(view(created.accessKey()), created.token())))
                .onErrorMap(McpAccessKeyService.AccessKeyNotFoundException.class, error ->
                        new ResponseStatusException(HttpStatus.NOT_FOUND, error.getMessage(), error));
    }

    private Mono<ServerResponse> delete(ServerRequest request) {
        return accessKeyService.delete(request.pathVariable("name"))
                .then(ServerResponse.noContent().build())
                .onErrorMap(McpAccessKeyService.AccessKeyNotFoundException.class, error ->
                        new ResponseStatusException(HttpStatus.NOT_FOUND, error.getMessage(), error));
    }

    private Mono<ServerResponse> tools(ServerRequest request) {
        return toolCatalog.tools().flatMap(tools -> ServerResponse.ok().bodyValue(tools));
    }

    private Mono<Void> validateTools(Set<String> requestedTools) {
        var requested = tools(requestedTools);
        return toolCatalog.availableNames().flatMap(available -> {
            var unknown = new LinkedHashSet<>(requested);
            unknown.removeAll(available);
            if (!unknown.isEmpty()) {
                return Mono.error(new ServerWebInputException("Unknown MCP tools: " + unknown));
            }
            return Mono.empty();
        });
    }

    private static Mono<String> currentUsername() {
        return ReactiveSecurityContextHolder.getContext()
                .map(context -> context.getAuthentication())
                .map(Principal::getName)
                .switchIfEmpty(Mono.error(new ResponseStatusException(
                        HttpStatus.UNAUTHORIZED, "Authentication is required")));
    }

    private static Set<String> tools(Set<String> tools) {
        return tools == null ? Set.of() : Set.copyOf(tools);
    }

    private static AccessKeyView view(McpAccessKey key) {
        var metadata = key.getMetadata();
        var spec = key.getSpec();
        var status = key.getStatus();
        return new AccessKeyView(
                metadata.getName(),
                spec.getDisplayName(),
                spec.getKeyPrefix(),
                spec.getOwnerName(),
                spec.isEnabled(),
                spec.getExpiresAt(),
                spec.getAllowedTools() == null ? Set.of() : Set.copyOf(spec.getAllowedTools()),
                status == null ? null : status.getLastUsedAt(),
                metadata.getCreationTimestamp(),
                metadata.getDeletionTimestamp());
    }

    @Override
    public GroupVersion groupVersion() {
        return GROUP_VERSION;
    }

    @Schema(name = "CreateMcpAccessKeyRequest")
    record CreateKeyRequest(
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String displayName,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) Set<String> allowedTools,
            Instant expiresAt) {}

    @Schema(name = "UpdateMcpAccessKeyRequest")
    record UpdateKeyRequest(
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String displayName,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) Set<String> allowedTools,
            Instant expiresAt,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) boolean enabled) {}

    @Schema(name = "McpAccessKey")
    record AccessKeyView(
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String name,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String displayName,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String keyPrefix,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String ownerName,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) boolean enabled,
            Instant expiresAt,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) Set<String> allowedTools,
            Instant lastUsedAt,
            Instant creationTimestamp,
            Instant deletionTimestamp) {}

    @Schema(name = "CreatedMcpAccessKey")
    record CreatedKeyResponse(
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) AccessKeyView key,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String token) {}
}
