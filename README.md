# BonfirePets

[English](#english) | [简体中文](#简体中文)

BonfirePets is Bonfire's BetterModel-based pet runtime for Paper 1.21.8.

BonfirePets 是 Bonfire 面向 Paper 1.21.8 的 BetterModel 宠物运行时核心。

---

## English

BonfirePets is the Bonfire 5.0 runtime replacement for `MCPets + ModelEngine`, rebuilt around BetterModel, migration safety, rollback support, and production-friendly runtime controls.

### Core Scope

- Import legacy MCPets pets, categories, menu assets, and optional player or MySQL data.
- Validate BetterModel and MythicMobs compatibility before migration.
- Keep migration snapshots, rollback jobs, and recovery records for safer cutover.
- Provide give, validation, import, rollback, and runtime debug flows through `/bpet`.

### Core Commands

- `/bpet import`
- `/bpet validate`
- `/bpet rollback`
- `/bpet give`
- `/bpet debug`

### Repository Layout

- `src/main/java/`: plugin runtime, migration logic, adapters, commands, and storage
- `src/main/resources/`: plugin configuration and command definitions
- `说明书/`: local Chinese operator notes
- `部署包/`: local deployment bundle workspace, excluded from Git release intent

### Build

```powershell
.\mvnw.cmd -q -DskipTests package
```

### License

This repository currently uses the `Bonfire Non-Commercial Source License 1.0`.
See [LICENSE](LICENSE) for the exact terms.

---

## 简体中文

BonfirePets 是 Bonfire 5.0 中用于替代 `MCPets + ModelEngine` 的宠物系统核心，重点放在 BetterModel 兼容、迁移安全、回滚能力与生产服运行时控制。

### 核心范围

- 导入旧版 MCPets 的宠物、分类、菜单资源，以及可选的玩家或 MySQL 数据。
- 在迁移前校验 BetterModel 与 MythicMobs 的兼容状态。
- 保存迁移快照、回滚任务与恢复记录，降低切换风险。
- 通过 `/bpet` 提供发放、校验、导入、回滚与运行时调试能力。

### 主要命令

- `/bpet import`
- `/bpet validate`
- `/bpet rollback`
- `/bpet give`
- `/bpet debug`

### 仓库结构

- `src/main/java/`：插件运行时、迁移逻辑、适配层、命令与存储实现
- `src/main/resources/`：插件配置与命令定义
- `说明书/`：本地中文说明资料
- `部署包/`：本地部署打包工作区，不作为 Git 发布内容

### 构建方式

```powershell
.\mvnw.cmd -q -DskipTests package
```

### 授权

本仓库当前采用 `Bonfire Non-Commercial Source License 1.0`。
具体条款见 [LICENSE](LICENSE)。
