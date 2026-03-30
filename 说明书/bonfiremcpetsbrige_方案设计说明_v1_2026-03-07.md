# BonfirePets 方案设计说明

位置: bonfirepets 项目内说明书目录  
适用范围: BonfirePets 第一阶段预研方案

## 1. 设计目标

第一阶段方案遵循 4 个原则:

1. 不侵入 MCPets 核心代码
2. 不替换 MCPets 主存档
3. 先保证“可找回”, 再考虑“更细粒度”
4. 先保证回滚安全, 再考虑自动化恢复

## 2. 目标环境假设

基于当前线上环境, 先按以下前提设计:

1. MCPets 主数据源为 MySQL
2. MCPets 当前实际持久化结构为玩家级记录
3. 需要恢复的核心内容是 `uuid / names / inventories / data`
4. 本桥接插件自己的备份仓库使用 SQLite

## 3. 总体架构

建议模块拆分如下:

### 3.1 ConfigModule

负责读取桥接插件配置, 包括:

1. SQLite 路径
2. 备份频率
3. 保留时间
4. MCPets 主数据源连接配置或读取方式
5. 是否只备份变更数据
6. 回滚是否强制离线

### 3.2 MCPetsSourceGateway

负责访问 MCPets 当前主数据源。

第一阶段只实现 MySQL 读写网关:

1. 读取 MCPets 玩家记录
2. 按 UUID 查询指定玩家数据
3. 将指定快照写回 MCPets 主表

这里的关键点是:

- 桥接插件只操作 MCPets 的玩家级数据表
- 不直接修改 MCPets 插件内部内存对象

### 3.3 SnapshotService

负责:

1. 采集快照
2. 计算哈希
3. 判断是否有变化
4. 写入 SQLite
5. 查询历史快照

### 3.4 RetentionService

负责定期清理超过 7 天的历史快照。

### 3.5 RollbackService

负责:

1. 回滚前校验
2. 生成回滚前快照
3. 写回 MCPets 主数据源
4. 二次读取校验
5. 更新回滚任务状态
6. 写审计日志

### 3.6 CommandService

负责提供管理命令入口。

### 3.7 AuditService

负责记录:

1. 定时备份摘要
2. 手动备份行为
3. 回滚任务全过程
4. 异常与失败原因

## 4. 为什么第一阶段不用“只监听事件做备份”

原因有 4 个:

1. 你的核心诉求是先建立稳定的独立保险层
2. 定时快照更简单, 更稳定, 更容易审计
3. 只靠事件监听容易漏掉“服务重启前未触发特定事件”的边角场景
4. 当前 MCPets 虽然有 API 和部分事件, 但真正可靠的恢复对象仍然是主存储记录本身

因此第一阶段采用:

- 定时扫描主数据源为主
- 事件增强为辅

后续如果需要更高精度, 再增加 `InventoryCloseEvent` 级别的增量快照。

## 5. MCPets 对接策略

根据 MCPets 当前公开源码, 其 MySQL 核心数据表可以抽象为:

1. `uuid`
2. `names`
3. `inventories`
4. `data`

桥接插件第一阶段不依赖反射篡改 MCPets 内部缓存。

建议流程是:

1. 读取主表当前玩家记录
2. 落本地 SQLite 快照
3. 需要回滚时, 直接把目标记录写回主表
4. 完成后提示目标玩家重新上线, 或在可控条件下再做缓存刷新策略

这样做的好处是:

1. 与 MCPets 内部实现耦合更低
2. 更容易排查问题
3. 即使 MCPets 内部类名变动, 只要主存储结构不变, 桥接插件仍有较高可维护性

### 5.1 NBT 数据保真策略

你当前生产环境里存在大量高复杂物品:

1. `ItemsAdder` 自定义物品
2. `MMOCore` / MMO 生态物品
3. `MythicMobs` + `ModelEngine` 配套掉落物
4. `CMI` 指令生成并带自定义 NBT 的物品
5. 其他通过 `NBTAPI` 或原始 NBT 逻辑识别的物品

因此桥接插件第一阶段必须采用“原始载荷直通”策略。

核心原则是:

1. 从 MCPets 主数据源读到的 `names / inventories / data` 视为原始业务载荷
2. 快照时直接保存原始值, 不做语义解析
3. 回滚时直接把快照中的原始值写回源表, 不做二次构造
4. 主链路不以 Bukkit `ItemStack` 为中间格式

这样设计的原因是:

1. `inventories` 本质上已经是 MCPets 序列化后的持久化结果
2. 一旦桥接层再次做反序列化和重序列化, 就有机会引入额外的不兼容或字段丢失
3. 对于 `ItemsAdder / MMOCore / MythicMobs / ModelEngine / CMI NBT` 这类复杂生态, “原样保存, 原样回写”是风险最低的方案

### 5.2 与 NBTAPI 的兼容策略

第一阶段建议把 `NBTAPI` 视为“环境兼容对象”, 而不是“核心硬依赖”。

也就是说:

1. 桥接插件需要兼容生产环境已安装 `NBTAPI` 的情况
2. 但第一阶段不要求桥接插件依赖 `NBTAPI` 去修改或重写物品 NBT
3. 如果后续要做更细的诊断命令, 可以把 `NBTAPI` 作为可选 `softdepend`

第一阶段不把 `NBTAPI` 设为硬依赖的原因:

1. 备份与回滚的关键对象是 MCPets 已持久化的原始字段, 不是桥接插件自己构造的物品对象
2. 只要桥接层不重写 NBT, 就不需要在主流程里深入操作 NBT 结构
3. 这样可以减少依赖, 降低维护和版本耦合风险

### 5.3 兼容性边界声明

桥接插件要保证的是:

1. 不额外引入新的 NBT 丢失
2. 不在快照和回滚过程中清洗第三方插件写入的自定义标记
3. 不因为桥接层的二次编码导致 `ItemsAdder / MMOCore / MythicMobs / ModelEngine / CMI NBT` 物品失效

但桥接插件不能保证的是:

1. 上游插件在物品进入 MCPets 之前造成的损坏
2. MCPets 自己在原始写库前若已丢失的字段
3. 某个历史时刻根本没有被写进 MCPets 主数据源的数据

所以第一阶段的承诺应该表述为:

- “BonfirePets 保证桥接层数据保真, 不保证修复桥接层之外已经发生的数据损坏。”

## 6. SQLite 数据模型建议

### 6.1 快照表 `snapshots`

建议字段:

1. `id` INTEGER PRIMARY KEY AUTOINCREMENT
2. `player_uuid` TEXT NOT NULL
3. `player_name` TEXT
4. `source_type` TEXT NOT NULL
5. `snapshot_type` TEXT NOT NULL
6. `created_at` TEXT NOT NULL
7. `names_raw` TEXT NOT NULL
8. `inventories_raw` TEXT NOT NULL
9. `data_raw` TEXT NOT NULL
10. `content_hash` TEXT NOT NULL
11. `note` TEXT

### 6.2 回滚任务表 `rollback_jobs`

建议字段:

1. `id` INTEGER PRIMARY KEY AUTOINCREMENT
2. `job_uuid` TEXT NOT NULL
3. `player_uuid` TEXT NOT NULL
4. `player_name` TEXT
5. `target_snapshot_id` INTEGER NOT NULL
6. `pre_snapshot_id` INTEGER
7. `operator_name` TEXT NOT NULL
8. `status` TEXT NOT NULL
9. `message` TEXT
10. `created_at` TEXT NOT NULL
11. `updated_at` TEXT NOT NULL

### 6.3 审计表 `audit_logs`

建议字段:

1. `id` INTEGER PRIMARY KEY AUTOINCREMENT
2. `event_type` TEXT NOT NULL
3. `player_uuid` TEXT
4. `operator_name` TEXT
5. `ref_id` TEXT
6. `message` TEXT NOT NULL
7. `created_at` TEXT NOT NULL

### 6.4 任务运行表 `backup_runs`

建议字段:

1. `id` INTEGER PRIMARY KEY AUTOINCREMENT
2. `run_type` TEXT NOT NULL
3. `started_at` TEXT NOT NULL
4. `finished_at` TEXT
5. `scanned_count` INTEGER NOT NULL DEFAULT 0
6. `inserted_count` INTEGER NOT NULL DEFAULT 0
7. `skipped_count` INTEGER NOT NULL DEFAULT 0
8. `status` TEXT NOT NULL
9. `message` TEXT

## 7. 备份流程设计

### 7.1 定时备份主流程

1. 定时任务启动
2. 建立一次 `backup_runs` 记录
3. 获取待备份玩家集合
4. 逐个从 MCPets 主数据源读取玩家记录
5. 对记录做标准化拼接
6. 计算 `content_hash`
7. 查询该玩家最近一份快照哈希
8. 相同则跳过
9. 不同则写入 `snapshots`
10. 更新运行摘要
11. 结束后写入审计日志

### 7.2 为什么建议“相同数据跳过”

因为 1 小时备份一次, 7 天内总轮次不少。

如果完全不去重:

1. SQLite 体积会更快膨胀
2. 回滚查找时噪音快照过多
3. 实际信息增量不高

因此第一阶段就应该支持 `only-when-changed`。

## 8. 回滚流程设计

### 8.1 流程总览

1. 管理员发起 `/bpet rollback <player> <snapshotId>`
2. 创建 `rollback_jobs` 记录, 状态为 `PENDING`
3. 校验玩家 UUID 与目标快照匹配
4. 校验当前主数据源类型是否受支持
5. 校验目标玩家是否离线
6. 生成“回滚前快照”
7. 状态推进为 `VALIDATED`
8. 开始写回 MCPets 主数据源, 状态为 `APPLYING`
9. 写回成功后状态变为 `APPLIED`
10. 再读取一次主数据源记录并做哈希校验
11. 校验一致则状态变为 `VERIFIED`
12. 若任一环节失败则状态改为 `FAILED`

### 8.2 为什么强制玩家离线

这是第一阶段最重要的安全策略之一。

原因:

1. 在线玩家可能仍被 MCPets 内存缓存持有旧数据
2. 在线状态下, MCPets 可能再次自动保存, 把刚回滚的数据覆盖掉
3. 离线回滚更容易定义结果边界

### 8.3 回滚后是否立即刷新 MCPets 缓存

第一阶段建议不做复杂的热刷新。

建议策略:

1. 玩家离线时执行回滚
2. 写回主表
3. 校验成功后提示管理员让玩家重新登录

原因:

1. 风险低
2. 实现简单
3. 不依赖 MCPets 内部缓存刷新细节

## 9. 回滚对数据的影响边界

### 9.1 会被修改的内容

1. MCPets 主数据源中该玩家当前记录
2. BonfirePets 的 SQLite 审计记录
3. BonfirePets 的“回滚前快照”记录

### 9.2 不会被修改的内容

1. 其他玩家的 MCPets 数据
2. 非 MCPets 插件的数据
3. 服务器世界存档
4. 当前线上环境中的 `plugins/MCPets/PlayerData/*.yml`

### 9.3 关于“是否影响本地数据库”的准确定义

如果“本地数据库”指的是桥接插件自己的 SQLite:

- 会写入任务状态、审计记录和回滚前快照

如果“本地数据库”指的是 MCPets 当前主 MySQL:

- 会写入目标玩家的回滚结果, 这是回滚动作本身的目的

如果“本地数据库”指的是 MCPets 本地 YAML:

- 当前第一阶段不会写它

## 10. 故障与保护策略

### 10.1 常见故障点

1. SQLite 文件被占用
2. MCPets 主 MySQL 连接失败
3. 目标快照不存在
4. 玩家在线导致拒绝回滚
5. 写回成功但校验哈希不一致

### 10.2 保护策略

1. 任何回滚失败都不得静默吞掉
2. 失败后必须保留任务记录与错误信息
3. 失败后不得自动删除回滚前快照
4. 对不支持的数据源类型直接拒绝执行

## 11. 第一阶段推荐配置

```yaml
storage:
  type: sqlite
  path: plugins/BonfirePets/backup.db

backup:
  interval-minutes: 60
  retention-days: 7
  only-when-changed: true
  include-online-players-only: false

rollback:
  manual-only: true
  require-player-offline: true
  create-pre-rollback-snapshot: true
  verify-after-apply: true

source:
  type: mysql
  table: <从 MCPets 配置推导或显式填写>
```

## 12. 迭代建议

### 12.1 第一阶段

1. 建 SQLite
2. 实现定时快照
3. 实现快照查询
4. 实现手动回滚
5. 实现回滚状态查询
6. 完成 `NBTAPI / ItemsAdder / MMOCore / MythicMobs / ModelEngine / CMI NBT` 兼容性基线验证

### 12.2 第二阶段

1. 增加 InventoryCloseEvent 级别的补充快照
2. 增加 Discord 或控制台告警
3. 增加异常空白检测

### 12.3 第三阶段

1. 研究是否在安全前提下支持在线热恢复
2. 研究是否支持 Flat YAML 模式写回

## 13. 当前结论

对你当前环境来说, 这套方案是可实施的。

原因是:

1. MCPets 当前主数据源明确是 MySQL
2. 桥接插件可以完全独立使用 SQLite 做快照仓库
3. 手动回滚的影响边界清晰可控
4. 第一阶段不用碰 MCPets 本地 YAML, 风险更低

因此可以进入下一步: 在该独立目录下开始搭工程骨架并实现第一阶段最小可用版本。

补充说明:

- 真正决定这些复杂物品能否被完整找回的关键, 不是桥接插件是否理解每一种 NBT, 而是桥接插件是否做到“对 MCPets 原始载荷零改写”。
