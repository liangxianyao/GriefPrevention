# GriefPrevention 领地金币费用 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 创建或扩建私人顶层领地时，同时消耗领地格和金币，正式服按每新增一格 10 金币计价。

**Architecture:** 在 `DataStore` 的有效创建和扩建路径扣款，复用现有 Vault Economy 服务。创建结果携带经济失败原因和已扣金额，让命令与金铲子路径给出统一中文反馈。

**Tech Stack:** Java 21、Bukkit/Paper 1.21.4、Maven、Vault Economy API。

**Spec:** `docs/superpowers/specs/2026-10-30-griefprevention-claim-cost-design.md`

## Global Constraints

- 新配置项：`GriefPrevention.Economy.ClaimCreationCostPerBlock`；代码默认值 `0`，正式服值 `10.0`。
- 自动新手箱子圈地、管理员领地、管理员代为扩建、子领地免费。
- 私人顶层领地扩建只按新增二维面积扣费；缩小和放弃不退金币。
- 失败、事件取消、余额不足或经济服务不可用时，不扣费且不创建/扩建领地。
- 不更改现有领地格购买/出售价格；不添加经济插件依赖。
- 只构建本 Paper 1.21.4 Maven 工程；不添加或运行测试。
- 正式服 JAR、`config.yml` 和 `messages.yml` 修改前先备份；部署前检查目标服务器进程，不自动启动或重启。

## Review Focus

- Vault Economy 缺失、禁用或扣款返回失败时，领地不得免费创建；Task 2、3 逐条检查失败结果和扣款顺序。
- 余额恰好等于费用、低于费用或为无效数值时，检查统一扣费助手的比较和失败结果；Task 2。
- 领地重叠、WorldGuard 拒绝或创建/修改事件取消时，检查扣款发生在所有拒绝条件之后；Task 2、3。
- 自动新手箱子半径为 0 和大于 0 时均不扣金币；Task 2 检查两个自动创建调用路径。
- 扩建、等面积重画、收缩、子领地和管理员操作分别检查新增面积与免费边界；Task 3。

---

### Task 1: 经济配置与 Vault 加载

**Files:**
- Modify: `src/main/java/me/ryanhamshire/GriefPrevention/GriefPrevention.java`
- Modify: `src/main/java/me/ryanhamshire/GriefPrevention/EconomyHandler.java`

**Interfaces:**
- Produces: `config_economy_claimCreationCostPerBlock`，从新配置项读取，默认 `0`，并写回配置。
- `EconomyHandler.loadEconomy` 在该单价大于零时加载 Vault Economy。

- [ ] 在经济字段区增加单价字段，并在配置读取/写回处使用 `GriefPrevention.Economy.ClaimCreationCostPerBlock`。
- [ ] 将新字段加入 `EconomyHandler` 的启用条件；购买、出售配置继续保持原逻辑。
- [ ] 用 `rg -n "ClaimCreationCostPerBlock|config_economy_claimCreationCostPerBlock" src/main/java` 核对声明、读取、写回和加载条件均存在。

### Task 2: 新建顶层领地扣费与玩家反馈

**Files:**
- Modify: `src/main/java/me/ryanhamshire/GriefPrevention/DataStore.java`
- Modify: `src/main/java/me/ryanhamshire/GriefPrevention/CreateClaimResult.java`
- Modify: `src/main/java/me/ryanhamshire/GriefPrevention/Messages.java`
- Modify: `src/main/java/me/ryanhamshire/GriefPrevention/BlockEventHandler.java`
- Modify: `src/main/java/me/ryanhamshire/GriefPrevention/GriefPrevention.java`
- Modify: `src/main/java/me/ryanhamshire/GriefPrevention/PlayerEventHandler.java`

**Interfaces:**
- `CreateClaimResult` 增加可选经济失败消息及参数、成功扣费金额。
- `DataStore.createClaim(..., boolean dryRun, boolean chargeCreationCost)` 控制自动新手箱子免费；现有普通调用仍启用计费，`dryRun` 永不扣费。
- `DataStore` 使用统一的 Vault 扣费逻辑，费用为 `area × config_economy_claimCreationCostPerBlock`。

- [ ] 扩展 `CreateClaimResult`，保留现有 `succeeded`/`claim` 字段，增加经济失败消息、消息参数和扣费金额。
- [ ] 在 `Messages` 与 `DataStore` 默认消息中增加“金币已扣除”和“经济服务不可用”中文消息；余额不足复用已有 `InsufficientFunds`。
- [ ] 为 `DataStore.createClaim` 增加显式费用开关重载；校验、WorldGuard 和 `ClaimCreatedEvent` 均通过后，且保存前，才处理私人顶层领地扣款。
- [ ] 经济服务不可用、余额不足或 Vault 扣款失败时返回带原因的失败结果，不添加领地；扣款成功后再保存领地并记录实际费用。
- [ ] `BlockEventHandler` 中半径为 0 与大于 0 的自动新手箱子保护区都显式传入免费开关。
- [ ] `/claim` 和金铲子顶层领地流程优先显示经济失败原因；成功时显示扣费金额；子领地维持免费。
- [ ] 用 `rg -n -C 2 "createClaim\\(" src/main/java/me/ryanhamshire/GriefPrevention` 检查所有创建调用，确认自动箱子跳过费用、普通顶层路径默认计费、试算不扣款。

### Task 3: 扩建按新增面积扣费

**Files:**
- Modify: `src/main/java/me/ryanhamshire/GriefPrevention/DataStore.java`
- Modify: `src/main/java/me/ryanhamshire/GriefPrevention/CreateClaimResult.java`

**Interfaces:**
- `DataStore.resizeClaim(...)` 复用 Task 2 的扣费逻辑；只对玩家扩建自己的私人顶层领地计费。
- `CreateClaimResult` 继续承载失败消息和实际扣款金额，供 `resizeClaimWithChecks` 显示。

- [ ] 在 `resizeClaim` 成功完成 dry-run 后、修改原边界前计算 `max(0, newArea - oldArea)`，按差额扣费。
- [ ] 余额不足或扣款失败时不修改边界，并把经济失败原因放入结果。
- [ ] 对缩小、等面积重画、子领地和管理员代操作保持免费；扩建成功时提示实际扣费。
- [ ] 检查 `resizeClaimWithChecks` 对经济失败先显示专用消息，不再发送冲突区域类通用提示；检查成功后原有边界和领地格余量反馈仍保留。

### Task 4: 玩法记录、构建与正式服部署

**Files:**
- Create: `docs/玩法跟踪台账.md`（当前仓库没有该文件；按上级 `AGENTS.md` 的游戏玩法记录要求建立本插件台账）
- Modify: `E:\MYSQL\正式服V2.0\plugins\GriefPreventionData\config.yml`
- Modify: `E:\MYSQL\正式服V2.0\plugins\GriefPreventionData\messages.yml`
- Replace after backup: `E:\MYSQL\正式服V2.0\plugins\圈地系统GriefPrevention.jar`

- [ ] 台账记录本次玩法规则、模块/配置和验证结果。
- [ ] 将正式服 `ClaimCreationCostPerBlock` 设为 `10.0`，保留现有 `ClaimBlocksPurchaseCost`、`ClaimBlocksSellValue` 和世界模式；为新增消息合入中文文本。
- [ ] 在源码仓库运行 `mvn -Dmaven.test.skip=true package`；预期 Maven 返回 `BUILD SUCCESS`，测试不运行。
- [ ] 用 `jar tf target/圈地系统GriefPrevention.jar` 检查 JAR 包含插件描述符与更新后的类；读取 SHA-256。
- [ ] 部署前再次核对没有 `E:\MYSQL\正式服V2.0` 的 Minecraft Java 进程；备份正式服 JAR 和两个 YAML 文件，再复制候选 JAR、更新配置与消息。
- [ ] 对比候选 JAR 与正式服 JAR 的 SHA-256，并解析/核对 YAML 关键值；不启动或重启服务器。

