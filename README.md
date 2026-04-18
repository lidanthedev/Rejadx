# ReJadx

ReJadx is a VS Code extension + Java language server for Android reverse engineering, powered by jadx.

It provides jadx-style workflows inside VS Code, including:

- Virtual source browsing for Java, Smali, and resources
- Class/package tree navigation
- Go to definition and find references
- Rename and comment support with persisted project state
- Decompiled code search and mappings export
- Dashboard controls for open/restart/stop and runtime telemetry

## Repository Layout

- `client/` — VS Code extension (TypeScript)
- `server/` — Java language server (LSP4J + jadx)
- `third_party/jadx/` — jadx source dependency

## Requirements

- VS Code `^1.85.0`
- Java 21+ runtime (required to run the bundled language server)
- Node.js 20+ (for development)

## Development

### 1) Install client dependencies

```bash
cd client
npm ci
```

### 2) Build server fat JAR and bundle it into the extension

```bash
cd server
./gradlew shadowJar
cd ../client
mkdir -p bin
cp ../server/build/libs/rejadx-server-all.jar bin/jadx-server.jar
```

### 3) Build extension bundle

```bash
cd client
npm run compile
```

### 4) Run extension in VS Code

Use the `Run Extension` launch config in `.vscode/launch.json`.

## Packaging VSIX

From `client/`:

```bash
npx @vscode/vsce package
```

The prepublish script will prepare the bundled server jar and compile the extension automatically.

## Releases

GitHub Actions uses semantic-release to:

- analyze Conventional Commit messages on `master`
- compute the next version and create a git tag/release
- generate/update `CHANGELOG.md`
- build and attach the `.vsix` artifact to the GitHub Release

Workflow file: `.github/workflows/release-vsix.yml`

## Notes

- The packaged extension intentionally includes `client/bin/jadx-server.jar`.
- The language server process is launched as `java -jar <extension>/bin/jadx-server.jar`.
- If Java 21+ is missing, the extension shows a clear startup error in VS Code.
