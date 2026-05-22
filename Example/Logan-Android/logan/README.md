# Logan Android Library

本文档说明 `logan` 模块中 `Logan.java` 的主要能力，以及当前仓库可追溯的版本记录信息。

## Logan.java 功能说明

`Logan.java` 是 Android 侧日志 SDK 的统一入口，负责对外暴露初始化、写日志、上传、查询和清理等能力，并将底层实现委托给 `LoganEngine` 与 native `CLogan` 协议层。

### 1) 初始化与运行状态

- `init(LoganConfig)`：初始化日志系统，校验配置并构建 `LoganInitConfig`。
- 初始化成功后，SDK 才允许写入、上传、查询、清理。
- `setDebug(boolean)` / `printClibLog(boolean)`：控制日志调试输出。
- `setMaxReversedDate(int)`：设置日志最大保留天数。

### 2) 日志写入

- `log(int type, String log)`：默认日志写入入口（当前默认写入 `TECH` 通道）。
- `logTech(int type, String log)`：写入技术通道。
- `logBiz(int type, String log)`：写入业务通道。
- `flush()`：立即将内存日志刷盘。
- `createStandardLog(...)`：按统一格式组装日志字符串，便于业务侧结构化输出。

### 3) 日志上传

- `uploadWithInterception(...)`：上传指定日期日志，支持上传拦截器。
- `upload(...)`：上传指定日期日志（不传拦截器）。
- `uploadAll(...)`：上传全部日期日志，支持扩展元信息与拦截器。
- 上传过程由 `LoganUploadCoordinator` 协调，支持 `TECH` / `BIZ` 通道。

### 4) 本地日志信息与清理

- `allSubFilesInfo()`：获取各日期下的子通道文件信息（`TECH`、`BIZ`）。
- `todaysDate()`：返回当天日期（`yyyy-MM-dd`）。
- `clearLogOfDate(String date)`：清理指定日期日志。
- `clearAllLogs()`：清理全部日志。

### 5) 当前通道模型（重要）

当前实现为双通道：

- `TECH`
- `BIZ`

`MAIN` 通道已移除，默认写入行为已调整为 `TECH`。

## 本次改动记录

本次改动围绕“移除 `MAIN` 通道并简化日志模型”展开，核心变化如下：

- 通道模型调整：
  - `LoganChannel` 移除 `MAIN`，仅保留 `TECH`、`BIZ`。
  - `log(int type, String log)` 默认写入 `TECH` 通道。

- 写入与默认打开策略调整：
  - `LoganEngine` 启动后默认打开 `TECH` 当日日志文件。
  - `BIZ` 写入后会恢复到 `TECH` 文件，作为默认写入状态。
  - 与 `MAIN` 相关的创建/写入路径逻辑已删除。

- 上传与清理调整：
  - `LoganUploadCoordinator` 仅上传 `TECH`、`BIZ` 两个通道文件。
  - 清理逻辑仅处理 `TECH`、`BIZ` 及对应临时文件。
  - 清理时额外兼容删除主目录残留的 `date` 文件（若存在）。
  - 根目录主日志文件（原 `MAIN` 文件）不再参与上传。

- API 与示例同步：
  - 移除 `allFilesInfo()`，保留 `allSubFilesInfo()` 作为通道文件信息查询入口。
  - 示例 `MainActivity` 已同步移除 `allFilesInfo()` 调用，改为仅展示子通道信息。

## 版本记录（仓库可追溯）

以下记录来自当前仓库中的构建配置与文档内容。

- 当前发布脚本版本：`1.3.1`
  - 来源：`Example/Logan-Android/gradle.properties` 的 `versionCode=1.3.1`
  - 说明：`publishLoganAAr.sh` 发布时读取该值作为产物版本号

- 当前模块版本名：`1.1.2`
  - 来源：`Example/Logan-Android/logan/build.gradle` 的 `versionName "1.1.2"`

- 历史文档示例版本：`1.2.4`
  - 来源：`Example/Logan-Android/README.md` 安装示例中的依赖坐标

## 说明

- 由于仓库内未提供完整的历史变更日志文件（如 `CHANGELOG.md`），以上版本记录为“当前仓库可直接确认”的版本信息快照。
- 如需完整版本演进（时间线、功能变更点、兼容性说明），建议补充统一的 `CHANGELOG.md` 并在发布流程中自动更新。
