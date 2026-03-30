# BonfirePets

![License](https://img.shields.io/badge/license-BNSL--1.0-red)
![Commercial Use](https://img.shields.io/badge/commercial-use%20by%20written%20permission%20only-critical)
![Platform](https://img.shields.io/badge/platform-Paper%201.21.8-brightgreen)
![Java](https://img.shields.io/badge/java-21-orange)
![Status](https://img.shields.io/badge/status-active-success)

BonfirePets is the Bonfire 5.0 runtime replacement for `MCPets + ModelEngine`, rebuilt around BetterModel and a safer migration pipeline.

## Highlights

- Imports legacy MCPets pets, categories, menu assets, player data, and optional MySQL rows.
- Validates BetterModel and MythicMobs compatibility before migration.
- Stores snapshots, rollback jobs, and migration records for cold-start recovery.
- Exposes import, validation, rollback, give, and runtime debug commands through `/bpet`.

## Core Commands

- `/bpet import`
- `/bpet validate`
- `/bpet rollback`
- `/bpet give`
- `/bpet debug`

## Build

```powershell
.\mvnw.cmd -q -DskipTests package
```

## Repository Scope

- This repository tracks source, config templates, and migration logic only.
- Build outputs, deployment bundles, and local probes are intentionally excluded from Git.

## License

Bonfire Non-Commercial Source License 1.0

Commercial use is prohibited unless you first obtain written permission from `mingxi7707@qq.com`.
