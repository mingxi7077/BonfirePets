# BonfirePets 开发更新日志 v0.2.0

日期: 2026-03-07  
阶段: 0.2.0

## 本次新增

1. `status` 输出增强, 增加 SQLite 统计、MCPets 源表玩家数量、最近备份摘要、软依赖状态
2. `snapshot list` 支持分页参数 `page` 与 `pageSize`
3. 回滚流程升级为“预览 -> 确认”两段式
4. 回滚确认令牌支持过期时间控制
5. 命令层数据库操作调整为异步执行, 降低主线程阻塞风险

## 本次保留的边界

1. 仍只支持 MCPets MySQL 主数据源
2. 仍不做 MCPets 在线缓存热刷新
3. 仍以 `names / inventories / data` 原值桥接为核心策略
4. 仍不把 `NBTAPI` 作为硬依赖

## 典型命令

- `/bpet status`
- `/bpet backup now`
- `/bpet snapshot list <player> [page] [pageSize]`
- `/bpet snapshot info <snapshotId>`
- `/bpet rollback <player> <snapshotId>`
- `/bpet rollback confirm <token>`
- `/bpet rollback status <jobUuid>`
- `/bpet cleanup run`
