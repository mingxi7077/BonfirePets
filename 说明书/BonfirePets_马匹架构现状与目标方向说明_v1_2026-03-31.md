# BonfirePets 马匹架构现状与目标方向说明 v1

更新时间：2026-03-31  
适用范围：LittleRoom Horse Pack、MCPets 现网试点、BetterModel 迁移方向、BonfirePets 后续架构收敛  
文档目的：沉淀当前马匹/骑宠体系的真实现状、已经验证过的结论、为什么此前会出现大量低效试错，以及未来应当遵循的总方向。  

---

## 1. 文档结论摘要

当前 `MCPets + MythicMobs + ModelEngine` 这条马匹试点链路，**已经证明可以做出“稳定可召唤、稳定可骑、可切速”的临时可用版本**，但**不适合作为最终产品形态继续精修**。  

核心原因不是单个参数没调好，而是：

1. `MCPets`、`ModelEngine`、`MythicMobs` 都会以不同方式影响骑乘行为。  
2. 我们过去几十轮试错，本质上都在“多个系统共同控制同一个状态”的架构缝隙上修症状。  
3. 这类问题继续靠配置层微调，收益越来越低，且每次改动都可能回归到“飞天、假骑乘、右键错乱、倾斜误判、侧移失控”等问题。  

因此，未来正确方向不是“继续把 MCPets 修到像原版 MM 马”，而是：

> **以 BonfirePets 作为唯一权威的宠物/坐骑控制层，BetterModel 作为表现层，MythicMobs 作为内容/技能层。**

一句话原则：

> **单一权威、逻辑先行、表现跟随。**

---

## 2. 当前真实稳定基线是什么

### 2.1 当前稳定基线的定义

当前被验证为“相对稳定、至少不继续破坏骑乘链”的版本，是一个**最小 MCPets 试点版**：

- 使用 `MCPets` 负责宠物生命周期、菜单、骑乘入口、重连恢复。
- 使用 `MythicMobs` 里的 `Horse_Bay_MCPets` / `LRD_horse_bay_MCPets` 作为试点实体定义。
- 使用 `ModelEngine` 仅负责模型显示与已有动画表现。
- 当前 carrier 已回到 **`Type: WOLF`**。
- 已移除高风险的：
  - mount controller 强接管
  - 右键交互强制挂载/卸载
  - carrier 改成 HORSE 后导致的假骑乘/飞天链
  - 通过 mountmodel/controller 直接重写 MCPets walking 行为的实验性逻辑

### 2.2 当前稳定基线的关键文件

#### MythicMobs MCPets 试点实体
`E:\Minecraft\12121\purpur第四版\一、服务器核心区\4.0正式服核心\server\plugins\MythicMobs\packs\littleroom Horse Pack\mobs\horse_bay_mcpets.yml`

当前该文件的特征：

- `Type: WOLF`
- 保留 `model{m=lrd_horse_bay; ... ride=true}` on spawn/load
- 保留 `BodyRotation`
- 保留 `LRD_horse_bay_init` / `LRD_horse_bay_load`
- 保留 `~onSignal:DASH -> LRD_horse_bay_toggle_speed_MCPets`
- 目前 lean 相关仅作为**非侵入式 timer 逻辑**存在
- 没有任何 `~onInteract` 的强制 mount/dismount 行为
- 没有任何 `mountcontroller_MCPets` / `apply_controller_MCPets` 继续参与运行

#### MCPets 宠物入口
`E:\Minecraft\12121\purpur第四版\一、服务器核心区\4.0正式服核心\server\plugins\MCPets\Pets\Pet_mount_vol2\horse_bay_mcpets.yml`

当前该文件的特征：

- `Id: Horse_Bay_MCPets`
- `MythicMob: Horse_Bay_MCPets`
- `Mountable: true`
- 使用 `Signals: DASH`
- 当前没有额外复杂的 MountType 或 controller 声明

### 2.3 当前稳定基线已经做到什么

在最近一轮收敛后，当前基线已经能够做到：

- 菜单可正常打开
- 骑乘入口可正常使用
- 不再出现“右键直接飞天”
- 不再出现“右键瞬间 TP 到马中心然后掉地”
- 不再出现“提示已经骑乘，但实际上站在地上走”
- 不再出现“下马后原地追着玩家转圈”
- `DASH`（信羽）已经可正常切速

这说明：

> 当前基线已经可以作为**现网临时可用版**，但并不代表它是“正确终态”。

---

## 3. 用户真实目标是什么

从多轮沟通中，真实目标并不是“能骑上去就行”，而是更接近原始 LittleRoom 马包 / 原 MM 马的手感与语义：

1. **A / D 的语义应更接近“倾斜与转向”而不是侧移。**  
2. **W 前进时不能因为鼠标转头而误触反向倾斜。**  
3. **走路（trot）与奔跑（gallop）应有不同但稳定的动画与手感。**  
4. **速度回落、减速、倒退等行为需要更接近原 MM 状态机。**  
5. **宠物生命周期应平滑、可恢复、可扩展。**  
6. 最终方向不是 MCPets，而是：
   - BetterModel 替代 ModelEngine
   - 继续用 MythicMobs 承载内容与技能
   - 自己做 BonfirePets，仿照 MCPets 承担宠物/骑宠功能

---

## 4. 我们已经验证过的失败路径

下面这些方向都已经在实际测试中被证明：**继续走下去只会低效甚至回归**。

### 4.1 试图通过 MCPets + ModelEngine + MythicMobs 三边配置，硬修 A/D 侧移

做过的尝试包括但不限于：

- `velocity{m=set}` / `velocity{m=MULTIPLY}` 压侧移
- `relative=true` 下对 x/z 分量做消除
- `directionalVelocity` 判定后再补偿速度
- 通过 mountmodel 的 `move{front=...;side=0}` 强制 side 归零

结果：

- 要么没有效果
- 要么短期看似有效，但下一 tick 被 walking controller 写回
- 要么直接破坏骑乘稳定性（飞天、假骑乘、掉地）

### 4.2 试图通过 mount controller / mountmodel 重新接管 MCPets 的 walking 行为

做过的尝试包括但不限于：

- `mountcontroller_MCPets`
- `apply_controller_MCPets`
- 直接 `mountmodel ... @ModelDriver / @self / @owner`
- 强制 `p=pilot / passenger / pbone=p_seat`
- `driver=false / force_walking / Meta Controller` 类实验

结果：

- 极易破坏 MCPets 原有骑乘逻辑
- 典型症状包括：
  - 右键上马后飞天
  - 假骑乘（插件判已骑，但玩家掉地）
  - 飞到空中后被 MCPets 销毁重召
  - 右键交互与菜单逻辑冲突

### 4.3 试图通过 `skill.meg:side` / `skill.meg:front` 在 MCPets 场景稳定获取输入语义

做过的尝试包括但不限于：

- 用 `skill.meg:side` 直接控制 lean left/right
- 用 `skill.meg:sneak` 控制下马
- 以 `skill.meg:front` 区分 trot/gallop 动作切换

结果：

- 在原始 MM 马逻辑里这些变量是有意义的
- 但在 MCPets 接管 walking/controller 的前提下，输入语义并不稳定
- 最终表现为：
  - A/D 倾斜不触发
  - 鼠标转头误触发反向倾斜
  - 同一个技能在不同 carrier / controller 组合下完全不同

### 4.4 试图通过即时 directionalVelocity + yaw 阈值来反推“玩家到底在按 A 还是 D”

做过的尝试包括但不限于：

- `directionalVelocity{x / z}` 直接判左右/前进
- `prevYaw` / `dYaw` 差分过滤鼠标转头
- 通过计数器 / aura / hysteresis 去掉闪烁

结果：

- 步行状态信号太弱，不稳定
- 奔跑状态信号太强，且会混入转头导致的假侧向
- A 与 D 在不同组合下甚至可能出现符号和方向不直观的情况
- 本质问题仍然是：**我们在从“结果”倒推“输入”**

这类方案最多能做成“部分看起来更像”，但很难稳定、长期、低回归。

---

## 5. 为什么会测试几十次却没有真实进度

### 5.1 根因不是“没找到正确参数”，而是“控制权没有唯一 owner”

当前旧链路里，至少有三层都在影响骑乘行为：

- `MCPets`：宠物生命周期、挂载入口、重连恢复、walking mount 抽象
- `ModelEngine`：模型、座位、默认 strafe/walking controller 语义
- `MythicMobs`：mob、技能、状态、触发器、状态机

我们后来追加的所有 patch，本质上都在做一件事：

> 从多个系统共同作用后的“结果”，反推玩家真正的输入和意图。

这就是持续低效的根因。

### 5.2 这是典型的“集成缝隙修补”而不是“架构推进”

过去很多改动表面看是在修某个问题：

- 去掉 strafe
- 加 lean
- 稳定下马
- 修好 speed toggle

但实际上都在 integration seam 上打补丁：

- 修一个点，另一个系统写回去
- 再修另一个点，骑乘本体又坏掉
- 最后看似有动作变化，但稳定性全面下降

这就是为什么测试很多，但没有真实累积。

---

## 6. 当前最该接受的现实判断

### 6.1 当前 MCPets 线的正确定位

当前 `MCPets + MythicMobs + ModelEngine` 马试点，最合适的定位只有一个：

> **临时可用基线 / 回退基线**

它可以：

- 作为现网暂时能骑、能切速、能重连恢复的版本
- 作为后续新架构的行为参考
- 作为回退方案，防止开发期把服内坐骑体验直接打坏

但它不应该再被当成“最终方案”去无限精修。

### 6.2 继续纯配置硬修，边际收益已经很低

现在继续修这条线，最多做到：

- 手感“有时更像”
- 某几个场景看起来改善
- 但整体依然脆弱

而你真正想要的是：

- 明确的输入语义
- 稳定的动作状态机
- 不依赖猜测的动画切换
- 可维护、可扩展的宠物/骑宠架构

这四件事，当前这条线已经不适合作为主战场。

---

## 7. 正确的大方向是什么

最终方向已经很明确，而且本地也已经有基础：

> **BetterModel + MythicMobs + BonfirePets**

但关键不只是“把 ModelEngine 换成 BetterModel”，而是：

> **把“谁拥有骑宠逻辑”重新定义清楚。**

### 7.1 单一权威原则

以后必须遵守：

> **谁负责逻辑，谁就负责状态；表现层只消费状态，不再猜状态。**

在新架构里：

- `BonfirePets`：唯一权威控制层
- `BetterModel`：表现层
- `MythicMobs`：内容与技能层

### 7.2 BonfirePets 应负责什么

BonfirePets 必须成为唯一的宠物/坐骑运行时 owner，负责：

- 召唤 / 回收 / 存档 / 重登恢复
- 主人归属
- 坐骑进入 / 退出
- 玩家输入解释
- 核心移动状态机

例如：

- idle
- trot
- gallop
- brake
- backpedal
- lean_left
- lean_right
- jump
- dismount_ready

也就是说，未来的系统不该再通过 velocity/yaw 去“猜 lean_left”，而应该由 BonfirePets 直接判定：

> 现在就是 lean_left。

### 7.3 BetterModel 应负责什么

BetterModel 只负责：

- 模型挂载
- 骨骼与动画播放
- 座位 / model part / 动画表现

它不应该再作为“输入解释层”。

BetterModel 收到的应该是明确状态，例如：

- play `walk`
- play `gallop`
- set state `lean_left`
- clear `lean`

### 7.4 MythicMobs 应负责什么

MythicMobs 应继续保留为：

- 内容层
- 数值层
- 技能层
- 事件层

也就是：

- Horse Pack 的动作、特效、技能、喂养/刷毛/互动逻辑
- mob 定义与状态配置

但不再作为“骑乘输入解释器”。

---

## 8. 本地已经有哪些现成基础可以证明这条方向不是空想

### 8.1 BonfirePets 源码工程已经存在

路径：  
`E:\Minecraft\12121\purpur第四版\二、插件开发区\源码工程\BonfirePets`

该工程目录下已经有：

- `src/`
- `pom.xml`
- `README.md`
- `说明书/`

并且本地探索已确认：

- `BonfirePetsService` 等 runtime 结构已经存在
- 项目内部已经有 `BetterModelAdapter` 与 `MythicMobsRuntimeAdapter` 方向

这说明：

> 你们不是“准备做 BonfirePets”，而是已经有了明确的工程雏形。

### 8.2 5.0 正式服已经有 BetterModel 与 BonfirePets 运行时

已确认路径：

- `E:\Minecraft\12121\purpur第四版\一、服务器核心区\5.0正式服核心\server\plugins\BonfirePets\config.yml`
- `E:\Minecraft\12121\purpur第四版\一、服务器核心区\5.0正式服核心\server\plugins\BetterModel-2.2.0-paper.jar`

这意味着：

> BetterModel + BonfirePets 不是纸上路线，而是你们环境里已经具备落地基础的主线。

### 8.3 当前 BonfirePets 文档目录本身就是合适的沉淀位置

路径：  
`E:\Minecraft\12121\purpur第四版\二、插件开发区\源码工程\BonfirePets\说明书`

已存在同类文档：

- `bonfiremcpetsbrige_功能需求说明_v1_2026-03-07.md`
- `bonfiremcpetsbrige_方案设计说明_v1_2026-03-07.md`
- `bonfiremcpetsbrige_开发更新日志_v0.3.0_2026-03-08.md`

说明这套项目本来就在用中文说明书沉淀方案、需求与阶段性结论。

---

## 9. 未来真正应该怎么避免“几十次测试没进度”

重点不是“少测试”，而是**换测试对象**。

### 9.1 以前的错误做法

过去测试在做的是：

- 改一个阈值
- 上服试骑
- 看现象
- 再猜一个阈值

这叫：

> **现象驱动调参**

在多插件耦合系统里，这几乎一定陷入死循环。

### 9.2 以后应该验证的是“状态流”

未来应该验证：

1. BonfirePets 输出的状态对不对？
2. BetterModel 是否正确消费状态？
3. MythicMobs 是否正确在这些状态上执行技能？

也就是：

- 输入 -> 状态
- 状态 -> 动画
- 状态 -> 技能/逻辑

而不是：

- 看最终动画表现 -> 猜是不是按了 A

### 9.3 这是“猜”与“控制”的根本区别

目前这条旧链一直在：

- 看 velocity 猜输入
- 看 yaw 猜 lean
- 看 mounted/driver 猜当前是否算 mounted

未来正确的方式应该是：

- BonfirePets 直接拥有输入
- BonfirePets 直接产出状态
- BetterModel / MythicMobs 只消费状态

---

## 10. 当前工作建议（不是实现计划，而是原则判断）

### 10.1 当前 MCPets 线的建议

保持当前稳定基线，不再继续进行大量高风险精修：

- 不再碰 mount controller
- 不再碰交互链
- 不再碰 carrier 类型切换
- 不再尝试用配置层彻底抹平 strafe

如果必须继续用当前线，仅限于：

- 做小幅、可回退、非侵入式优化
- 以“不破坏可骑/可召/可菜单”为第一优先级

### 10.2 主线建议

主线应转到：

> **BonfirePets 作为唯一控制层，BetterModel 作为唯一表现层，MythicMobs 作为内容层。**

真正有价值的后续工作，不再是调 MCPets 参数，而是：

- 先明确 BonfirePets 将来输出哪些状态
- 明确 BetterModel 如何消费这些状态
- 明确 MythicMobs 哪些旧技能可以复用，哪些逻辑要从配置迁到 Java/runtime

---

## 11. 最后一段最重要的话

你现在遇到的问题，不是“还差最后几个参数”。  
而是：

> **当前这条线的抽象层级就不适合继续承担你最终想要的马匹手感。**

所以真正有效的进展，不是“再试 20 次倾斜阈值”，而是：

1. 接受当前 MCPets 线只是临时可用版  
2. 用它作为回退基线  
3. 把真正的控制权迁移到 BonfirePets 这层  
4. 让 BetterModel 去表现，让 MythicMobs 去承载内容  

只有这样，后面的每一次开发，才会从“症状修补”变成“架构推进”。

---

## 12. 本文涉及的关键本地路径

### 当前 4.0 服稳定试点相关
- `E:\Minecraft\12121\purpur第四版\一、服务器核心区\4.0正式服核心\server\plugins\MythicMobs\packs\littleroom Horse Pack\mobs\horse_bay_mcpets.yml`
- `E:\Minecraft\12121\purpur第四版\一、服务器核心区\4.0正式服核心\server\plugins\MythicMobs\packs\littleroom Horse Pack\skills\horse_bay.yml`
- `E:\Minecraft\12121\purpur第四版\一、服务器核心区\4.0正式服核心\server\plugins\MCPets\Pets\Pet_mount_vol2\horse_bay_mcpets.yml`
- `E:\Minecraft\12121\purpur第四版\一、服务器核心区\4.0正式服核心\server\plugins\MCPets\config.yml`
- `E:\Minecraft\12121\purpur第四版\一、服务器核心区\4.0正式服核心\server\plugins\ModelEngine\config.yml`

### BonfirePets / BetterModel 主线相关
- `E:\Minecraft\12121\purpur第四版\二、插件开发区\源码工程\BonfirePets`
- `E:\Minecraft\12121\purpur第四版\二、插件开发区\源码工程\BonfirePets\说明书`
- `E:\Minecraft\12121\purpur第四版\一、服务器核心区\5.0正式服核心\server\plugins\BonfirePets\config.yml`
- `E:\Minecraft\12121\purpur第四版\一、服务器核心区\5.0正式服核心\server\plugins\BetterModel-2.2.0-paper.jar`
