# BonfirePets 开发更新日志 v0.3.0

日期: 2026-03-08  
阶段: 0.3.0

## 本次新增

1. 增加 MCPets MySQL 源表结构校验, 启动与 `status` 时会检查 `uuid / names / inventories / data` 四个关键列是否齐全
2. 当源表结构不兼容或源检查失败时, 桥接进入只读保护状态, 回滚会被拒绝, 备份任务会记录失败原因
3. 增加 MCPets `Pets` 目录索引, 会扫描当前宠物定义文件并建立可用 `petId` 集合
4. 回滚预览与正式回滚增加宠物定义安全预检, 若快照内引用的 `petId` 在现行 `Pets` 目录中不存在, 则阻断回滚
5. 若快照载荷非空但当前版本无法安全识别其中的 `petId`, 也会阻断回滚, 避免把不可验证的 MCPets 数据强行写回生产库
6. `status` 输出增加 `sourceGuard` 与 `petDefs` 两条状态, 可直接看到当前是否处于只读保护、当前已索引宠物数量与目录位置
7. 回滚异常路径补充 `FAILED` 状态写回, 便于用 `/bpet rollback status <jobUuid>` 追踪失败结果

## 安全逻辑说明

1. `0.3.0` 不做自动修复, 只做检测、阻断、记录
2. 只读保护的触发条件主要是源表结构异常、元数据检查失败、数据库连接检查失败
3. 宠物定义风险检查的触发条件主要是: 快照内引用了已删除/改名的 `petId`, 或当前无法确认快照里到底引用了哪些 `petId`
4. 只要命中上述风险, 回滚不会落库到 MCPets MySQL

## 对本地 SQLite 的影响

1. 手动回滚成功时, 本地 SQLite 会新增一条 `rollback_jobs` 记录
2. 如果开启 `create-pre-rollback-snapshot`, 回滚前会额外写入一条 `pre_rollback` 快照到本地 SQLite
3. 既有历史快照不会被覆盖, 本地 SQLite 仍然保留原备份链
4. 如果回滚被 `0.3.0` 安全层拒绝, 不会改写 MCPets MySQL 主数据; 只可能新增审计日志或失败任务记录

## 本次保留的边界

1. 仍只支持 MCPets MySQL 主数据源
2. 仍不直接接管 MCPets 插件内部缓存刷新逻辑
3. 仍坚持 `names / inventories / data` 原值桥接, 不重序列化玩家物品 NBT
4. 仍不把 `NBTAPI` 作为硬依赖, 兼容思路继续以“原值快照 + 原值回写”为主

## 典型命令

- `/bpet status`
- `/bpet backup now`
- `/bpet snapshot list <player> [page] [pageSize]`
- `/bpet snapshot info <snapshotId>`
- `/bpet rollback preview <player> <snapshotId>`
- `/bpet rollback confirm <token>`
- `/bpet rollback status <jobUuid>`
- `/bpet cleanup run`
