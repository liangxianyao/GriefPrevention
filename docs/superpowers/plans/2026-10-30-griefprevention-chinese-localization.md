# GriefPrevention 简体中文汉化 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox syntax for tracking.

**Goal:** 汉化目标项目的全部玩家可见运行时消息和命令帮助文字。

**Architecture:** 保持消息键、参数、命令注册和权限不变，只替换文案。沿用项目现有的 `DataStore` 默认消息机制和 `plugin.yml` 描述字段。

**Tech Stack:** Java 21, Maven, Bukkit `plugin.yml` YAML.

**Spec:** `docs/superpowers/specs/2026-10-30-griefprevention-chinese-localization-design.md`

## Global Constraints

- 翻译只改变文案，不改变圈地行为和插件接口。
- 保留命令名称、别名、权限、配置键、占位符、颜色和链接。
- 正式服部署仅在用户明确要求后执行；先备份 JAR 与 `messages.yml`，不自动启动或重启服务器。
- 不翻译配置注释和 README。

## Review Focus

- `{0}` 等格式参数、调用参数顺序保持一致；核对翻译前后的占位符集合。
- URL、玩家名、世界名、物品名和命令标记保持可用；逐条扫描对应消息。
- `plugin.yml` YAML 字符串仍可解析；对资源文件做语法检查。
- 命令、别名、权限节点、版本和依赖声明不变；对比修改前后的结构字段。
- 直接发送给玩家的硬编码英文没有遗漏；扫描 `sendMessage`、踢出提示和配置重载提示。

---

### Task 1: 汉化运行时消息

**Files:**
- Modify: `src/main/java/me/ryanhamshire/GriefPrevention/DataStore.java`
- Modify: `src/main/java/me/ryanhamshire/GriefPrevention/GriefPrevention.java`
- Modify: `src/main/java/me/ryanhamshire/GriefPrevention/PlayerEventHandler.java`

**Interfaces:**
- Consumes: 现有 `Messages` 常量、`addDefault` 注册和 `{0}` 参数。
- Produces: 中文默认消息，消息键与参数契约保持不变。

- [x] 翻译 233 条面向玩家的默认消息及选定文件中的直接玩家可见英文提示；保留 3 条功能关键字/正则/作者名、玩家名、URL、命令、物品名、格式标记和参数顺序。
- [x] 对照修改前版本，确认每个消息键和 `{n}` 参数集合一致，并检查剩余玩家可见英文。

### Task 2: 汉化命令说明

**Files:**
- Modify: `src/main/resources/plugin.yml`

**Interfaces:**
- Consumes: 当前命令注册表。
- Produces: 中文 `description` 和 `usage` 文案；所有注册字段保持不变。

- [x] 翻译命令描述与用法中的自然语言，保留命令名、别名、权限、参数占位符和依赖字段。
- [x] 解析 YAML，并比较命令名、别名、权限、主类、版本和依赖列表与修改前一致。

### Task 3: 构建检查

**Files:**
- Inspect: 上述 Java 与 YAML 资源；不新增测试代码。

- [x] 检查最终差异仅含指定文案和本计划/设计文档。
- [x] 执行 Maven 编译打包并用 `jar tf` 检查 JAR 包含 `plugin.yml`；不运行测试套件。

> 重新打包后 `mvn -Dmaven.test.skip=true package` 成功。已确认 JAR 包含 `plugin.yml` 和中文命令说明；测试套件跳过，正式服部署见 Task 4。
### Task 4: 部署正式服

**Files:**
- Replace: `E:\MYSQL\正式服V2.0\plugins\圈地系统GriefPrevention.jar`
- Update: `E:\MYSQL\正式服V2.0\plugins\GriefPreventionData\messages.yml`
- Backup: 两个原文件的时间戳备份

- [x] 确认服务器未运行；检查 233 条消息与源码原始默认值一致，保留 3 条功能项。
- [x] 备份原 JAR 与消息文件，部署新 JAR 和汉化消息；核对 JAR 哈希、`plugin.yml`、YAML 键数与截图对应消息；不启动或重启服务器。