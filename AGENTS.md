# Repository Guidelines

## Project Structure & Module Organization

The root Gradle project contains the Halo plugin backend. Java sources live in `src/main/java/run/halo/mcpserver`, resources and extension declarations in `src/main/resources`, and JUnit tests in `src/test/java`.

- `api/`: protocol-neutral Java API used by third-party tool-provider plugins.
- `ui/`: Vue 3 Console UI built with Vite; source is under `ui/src`.
- `api-docs/`: generated OpenAPI documents.
- `examples/`: demo and clock provider plugins for extension testing.

Do not edit `ui/src/api/generated` manually; regenerate it from the backend contract.

## Build, Test, and Development Commands

- `./gradlew build`: compile Java, build the Vite UI, run tests, and package the plugin JAR.
- `./gradlew check`: run backend tests and the UI verification pipeline.
- `./gradlew haloServer`: start a compatible local Halo server.
- `./gradlew reload`: rebuild and reload the plugin into the running development server.
- `./gradlew generateApiClient`: regenerate `api-docs/` and the TypeScript Axios client.
- `cd ui && pnpm check`: run formatting checks, Oxlint, ESLint, TypeScript, and Vitest.
- `cd ui && pnpm dev`: rebuild the Console UI in Vite watch mode.

Use the pinned pnpm version from `ui/package.json`.

## Coding Style & Naming Conventions

Use Java 21, four-space indentation, `PascalCase` classes, and `camelCase` methods. Keep reactive APIs non-blocking and return `Mono` or `Flux` where appropriate. Java tests use the `*Test.java` suffix.

Vue components use `<script setup lang="ts">`, PascalCase filenames, and two-space indentation. Run `pnpm format` for Prettier fixes and `pnpm lint` for Oxlint/ESLint fixes. Built-in tools use `snake_case` names such as `halo_search_content`; contributed providers declare local `snake_case` names and the registry adds their plugin prefix.

This plugin is unreleased. Change current contracts directly; do not add compatibility code, legacy endpoints, aliases, migrations, fallback branches, or deprecated APIs.

## Testing Guidelines

Backend tests use JUnit 5, Spring Boot Test, and Reactor Test. Add focused tests beside the affected package and cover authorization, invalid input, and reactive error paths. UI tests use Vitest with jsdom and belong in `src/**/__tests__` or use `*.test.ts`. No minimum coverage threshold is configured.

## Commit & Pull Request Guidelines

This checkout has no Git metadata, so no repository-specific commit convention can be inferred. Use concise, imperative subjects and keep commits scoped to one change. Pull requests should explain behavior and security impact, list verification commands, link relevant issues, and include Console screenshots for visible UI changes. Commit regenerated OpenAPI and client artifacts whenever the API contract changes.

## Security & Configuration Tips

Never commit MCP keys. Use dedicated, least-privilege keys and pass them through `Authorization: Bearer ...`. Preserve the `/mcp` authentication boundary and validate tool allowlists before execution.
