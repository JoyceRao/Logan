# GZLogan iOS Spec

本文档定义 `Logan.h` / `Logan.m` 的目标行为。若当前实现与本文档存在冲突，以本文档为准。

**多线程安全**：除一次性初始化（见 **8.5**）外，对外 API 设计为可从任意线程调用；写路径、查询与上传编排通过串行 `loganQueue` 与上传互斥保证无数据竞争；业务回调的线程约定见 **8.4**。完整约定见 **第 8 章（线程模型与多线程安全）**。

## 1. 目录结构

Logan 所有日志都位于 `loganLogDirectory` 根目录下。初始化后必须保证以下目录存在：

```text
loganLogDirectory/
├── {date}
├── Tech/
│   └── {date}
├── Biz/
│   └── {date}
└── Temp/
    ├── {date}
    ├── Tech/
    │   └── {date}
    └── Biz/
        └── {date}
```

目录语义：

- `loganLogDirectory/`：主通道目录，`logan` 直接写入该目录。
- `loganLogDirectory/Tech/`：Tech 通道目录，`loganTech` 写入该目录。
- `loganLogDirectory/Biz/`：Biz 通道目录，`loganBiz` 写入该目录。
- `loganLogDirectory/Temp/`：主通道上传临时目录，保存主通道上传前复制出的 `{date}` 临时文件。
- `loganLogDirectory/Temp/Tech/`：Tech 通道上传临时目录，保存 Tech 上传前复制出的 `{date}` 临时文件。
- `loganLogDirectory/Temp/Biz/`：Biz 通道上传临时目录，保存 Biz 上传前复制出的 `{date}` 临时文件。

规范要求：

- 子目录名称固定为 `Tech`、`Biz`、`Temp`。
- 除上述目录外，不再定义额外的日志持久化目录或上传临时目录。
- `date` 固定使用 `yyyy-MM-dd`，例如 `2026-05-08`。
- 日志文件名只使用 `date`，不同通道只通过目录区分。

## 2. 初始化与写入

### 2.1 初始化

`loganInit` 负责设置 AES key、AES iv 和单文件最大大小，并初始化 C 层日志库。

初始化时需要：

- 创建 `loganLogDirectory`。
- 创建 `Tech`、`Biz`、`Temp` 三个子目录。
- 创建 `Temp/Tech`、`Temp/Biz` 两个上传临时子目录。
- 打开当天主通道日志文件 `{date}`。
- 设置默认日志保留天数，未显式设置时默认为 7 天。

### 2.2 写入 API

| API | 写入路径 | `clogan_open` pathname |
| --- | --- | --- |
| `logan(type, log)` | `loganLogDirectory/{date}` | `{date}` |
| `loganTech(type, log)` | `loganLogDirectory/Tech/{date}` | `Tech/{date}` |
| `loganBiz(type, log)` | `loganLogDirectory/Biz/{date}` | `Biz/{date}` |

写入规则：

- `log.length == 0` 时不写入。
- 写入前需要检查剩余磁盘空间；空间不足时丢弃本次日志。
- **多线程安全**：`logan`、`loganTech`、`loganBiz`、`loganFlush` 可从任意线程并发调用；实现须将实际写盘与 `clogan_*` 调用派发到同一条串行队列（**8.1** 的 `loganQueue`），与下一条「同一串行队列」要求一致。
- 所有写入、flush、切换文件句柄必须在同一个串行队列中执行。
- 当日期变化时，需要先 flush 当前文件，再打开新日期文件。
- 写入 Tech / Biz 时，需要先 flush 当前句柄，打开对应子目录文件，写入并 flush 后再切回主通道当天文件。
- `Temp` 目录不允许参与日志写入。

### 2.3 标准日志格式

提供 `createStandardLog` 方法，将业务字段组装成 Logan 写入所需的标准字符串。

```objc
NSString *createStandardLog(NSString *_Nullable currentName,
                            NSInteger threadCount,
                            NSString *_Nullable level,
                            NSString *_Nullable userId,
                            NSInteger category,
                            NSString *_Nullable bizModule,
                            NSString *_Nullable flowId,
                            NSString *_Nullable stage,
                            NSString *_Nullable functionName,
                            NSString *_Nullable location,
                            NSString *_Nullable message);
```

字段顺序固定为：

```text
{currentName}:{threadCount}|{level}|{userId}|{category}|[{bizModule}, {flowId}, {stage}, {functionName},{location}]|{message}
```

实现规则：

- `VALID_LOG_STR` 宏定义固定为：

```objc
#define VALID_LOG_STR(s) ((s&&[s isKindOfClass:[NSString class]]&&s.length)?s:@"-")
```

- `VALID_LOG_STR` 仅当入参为非空 `NSString` 且 `length > 0` 时返回原值，否则返回 `@"-"`。
- 使用 `NSMutableArray` 按字段顺序追加日志片段。
- 所有字符串字段都必须先经过 `VALID_LOG_STR(...)` 处理，避免 `nil` 或非法值进入日志。
- `threadCount` 使用 `%ld` 转成字符串，并拼接在 `currentName` 后，格式为 `{currentName}:{threadCount}`。
- `category` 使用 `%ld` 转成字符串，对应日志中的 `type` 字段。
- 第 5 段为业务定位数组字符串，格式固定为 `[{bizModule}, {flowId}, {stage}, {functionName},{location}]`。
- 最终使用 `|` 连接全部字段。

参考实现：

```objc
NSMutableArray *logData = [NSMutableArray array];
[logData addObject:[NSString stringWithFormat:@"%@:%ld", VALID_LOG_STR(currentName), threadCount]];
[logData addObject:VALID_LOG_STR(level)];
[logData addObject:VALID_LOG_STR(userId)];
[logData addObject:[NSString stringWithFormat:@"%ld", category]];
[logData addObject:[NSString stringWithFormat:@"[%@, %@, %@, %@,%@]", VALID_LOG_STR(bizModule), VALID_LOG_STR(flowId), VALID_LOG_STR(stage), VALID_LOG_STR(functionName), VALID_LOG_STR(location)]];
[logData addObject:VALID_LOG_STR(message)];

NSString *log = [logData componentsJoinedByString:@"|"];
```

## 3. 上传模型

对外上传相关 API：

- `loganUpload`：无拦截器；实现上等价于 `loganUploadWithInterceptionBlock(..., interceptionBlock = nil, ...)`。
- `loganUploadWithInterceptionBlock`：可选 `LoganUploadInterceptionBlock`，每个待上传文件上传前调用一次拦截。
- `loganAllUpload`：扫描本地全部合法日期日志并上传。

旧上传接口从规格中删除，不再作为对外 API 或内部推荐路径。

### 3.1 上传文件集合

一个日期 `date` 对应最多三个待上传日志文件：

```text
loganLogDirectory/{date}
loganLogDirectory/Tech/{date}
loganLogDirectory/Biz/{date}
```

上传时的 `subFolderName`：

- 主通道：`nil`
- Tech 通道：`Tech`
- Biz 通道：`Biz`

不存在的文件直接跳过。指定日期下三个通道均不存在时，本次上传失败并回调错误。

### 3.2 上传前准备

上传当天日志时，必须先执行 `flush`，保证待上传内容已落盘。

为避免上传过程中源日志继续变化，上传前应复制文件到 `Temp` 目录：

```text
loganLogDirectory/Temp/{date}
loganLogDirectory/Temp/Tech/{date}
loganLogDirectory/Temp/Biz/{date}
```

临时文件命名要求：

- 主通道临时文件固定为 `Temp/{date}`。
- Tech 临时文件固定为 `Temp/Tech/{date}`。
- Biz 临时文件固定为 `Temp/Biz/{date}`。
- 临时文件名仍只使用 `date`，通道通过 `Temp` 下的子目录区分。

非当天日志允许直接上传源文件；如为了统一实现，也可以先复制到 `Temp` 再上传。

### 3.3 请求头

内置上传使用 `POST`，`Content-Type` 为 `binary/octet-stream`。

请求头：

| Header | 规则 |
| --- | --- |
| `fileDate` | 必填，日志日期 |
| `retrievalId` | 存在时写入 |
| `appId` | 存在时写入 |
| `unionId` | 存在时写入 |
| `deviceId` | 存在时写入 |
| `bundleVersion` | 存在时写入 |
| `appVersion` | 存在时写入 |
| `platform` | 固定为 `2` |
| `loganSubFolder` | 主通道不写；Tech / Biz 写对应目录名 |

### 3.4 上传拦截

每个本地文件上传前都必须支持拦截：

```objc
interceptionBlock(date, retrievalId, url, localFilePath, subFolderName, resultBlock)
```

规则：

- `retrievalId` 与本次 `loganUploadWithInterceptionBlock` / `loganUpload` / `loganAllUpload` 调用传入的检索标识相同，可为 `nil`。
- `localFilePath` 为本次实际上传的本地路径，可能是源日志文件，也可能是 `Temp` 下临时文件。
- `subFolderName` 主通道为 `nil`，Tech / Biz 为对应子目录名。
- 拦截器返回 `YES` 表示调用方自行上传该文件，并必须在上传完成后调用传入的 `resultBlock`。
- 拦截器返回 `NO` 表示继续使用 Logan 内置上传。
- 无论使用内置上传还是拦截上传，后续串联、失败中止、成功删除都必须保持一致。

### 3.5 云端 URL 同步（`syncCloudUploadUrl`）

与内置二进制直传不同：当日志文件已由业务侧上传至对象存储等位置后，通过 `syncCloudUploadUrl` 将 `logFileUrl`、类型与时间范围等信息以 **`application/json`** 请求体上报给云端接口。请求完整 URL 由调用方通过参数 `urlString` 传入（例如由业务环境解析出的 host 与固定 path 拼接）。请求体、请求头与成功判定规则见 **9.4.1**。

## 4. `loganUploadWithInterceptionBlock` 与 `loganUpload`

### 4.1 `loganUploadWithInterceptionBlock`

上传指定 `date` 下主通道、Tech、Biz 中**实际存在**的日志文件（见 **3.1**）。在 `loganQueue` 上编排上传链，行为与 **5**、**8.3** 一致。

```objc
extern void loganUploadWithInterceptionBlock(NSString *_Nonnull url,
                        NSString *_Nonnull date,
                        NSString *_Nullable retrievalId,
                        NSString *_Nullable appId,
                        NSString *_Nullable unionId,
                        NSString *_Nullable deviceId,
                        LoganUploadInterceptionBlock _Nullable interceptionBlock,
                        LoganUploadResultBlock _Nullable resultBlock);
```

参数：

- `url`：上传服务端地址。
- `date`：日志日期，格式为 `yyyy-MM-dd`。
- `retrievalId`：检索标识，可为 `nil`；非空时参与内置上传请求头等（见 **3.3**），并传入 **3.4** 拦截器第二参。
- `appId` / `unionId` / `deviceId`：可选；非空时写入内置上传请求头（见 **3.3**）。
- `interceptionBlock`：上传拦截器，可为 `nil`（等价于全程走内置 `NSURLSession` 上传）。
- `resultBlock`：整次上传最终结果（主线程回调，见 **8.4**）。

### 4.2 `loganUpload`

不提供拦截器时的便捷封装：**必须**实现为调用 `loganUploadWithInterceptionBlock`，且 `interceptionBlock` 固定为 `nil`。

```objc
extern void loganUpload(NSString *_Nonnull url,
                        NSString *_Nonnull date,
                        NSString *_Nullable retrievalId,
                        NSString *_Nullable appId,
                        NSString *_Nullable unionId,
                        NSString *_Nullable deviceId,
                        LoganUploadResultBlock _Nullable resultBlock);
```

参数：与 **4.1** 相同，但不包含 `interceptionBlock`。

**4.1** 与 **4.2** 共用下列上传顺序与结果规则。

上传顺序：

1. 主通道：`loganLogDirectory/{date}`
2. Tech：`loganLogDirectory/Tech/{date}`
3. Biz：`loganLogDirectory/Biz/{date}`

结果规则：

- 任一文件上传失败，本次上传立即失败并回调该错误。
- 失败时不删除未成功上传的源日志文件。
- 每成功上传一个 `date` 文件，立即删除该文件对应的源日志文件和临时文件。
- 该 `date` 下所有存在的日志文件都上传成功后，回调成功。

单文件成功后的删除规则：

```text
主通道成功：删除 loganLogDirectory/{date} 和 loganLogDirectory/Temp/{date}
Tech 成功：删除 loganLogDirectory/Tech/{date} 和 loganLogDirectory/Temp/Tech/{date}
Biz 成功：删除 loganLogDirectory/Biz/{date} 和 loganLogDirectory/Temp/Biz/{date}
```

如果某个通道不存在日志文件，则跳过该通道，不执行删除。

## 5. `loganAllUpload`

`loganAllUpload` 上传本地全部日志文件。

```objc
extern void loganAllUpload(NSString *_Nonnull url,
                           NSString *_Nullable retrievalId,
                           NSString *_Nullable appId,
                           NSString *_Nullable unionId,
                           NSString *_Nullable deviceId,
                           LoganUploadInterceptionBlock _Nullable interceptionBlock,
                           LoganUploadResultBlock _Nullable resultBlock);
```

参数：

- `url`：上传服务端地址。
- `retrievalId`：检索标识，可为 `nil`；非空时用于内置上传与拦截器（与 **4.1** 语义一致）。
- `appId` / `unionId` / `deviceId`：可选；非空时写入内置上传请求头（见 **3.3**）。
- `interceptionBlock`：上传拦截器，可为 `nil`。
- `resultBlock`：整次全部日期上传最终结果（主线程回调，见 **8.4**）。

上传范围：

- `loganLogDirectory/` 下所有合法日期文件。
- `loganLogDirectory/Tech/` 下所有合法日期文件。
- `loganLogDirectory/Biz/` 下所有合法日期文件。

上传顺序：

- 先收集所有合法日期，按日期升序上传。
- 同一日期内按主通道、Tech、Biz 顺序上传。
- 任一文件失败时，`loganAllUpload` 立即失败并回调该错误。
- 每成功上传一个 `date` 文件，立即删除该文件对应的源日志文件和临时文件。

单文件成功后的删除规则：

```text
主通道成功：删除 loganLogDirectory/{date} 和 loganLogDirectory/Temp/{date}
Tech 成功：删除 loganLogDirectory/Tech/{date} 和 loganLogDirectory/Temp/Tech/{date}
Biz 成功：删除 loganLogDirectory/Biz/{date} 和 loganLogDirectory/Temp/Biz/{date}
```

全部成功后的目录规则：

- 所有文件全部上传成功后，`loganLogDirectory`、`Tech`、`Biz`、`Temp`、`Temp/Tech`、`Temp/Biz` 下不应再残留本次上传涉及的日志或临时文件。
- 保留 `loganLogDirectory`、`Tech`、`Biz`、`Temp` 目录本身。
- 清理后再次确保 `Tech`、`Biz`、`Temp`、`Temp/Tech`、`Temp/Biz` 目录存在。

## 6. 清理规则

### 6.1 清理指定日期

`loganClearLogOfDate(date)` 需要删除：

```text
loganLogDirectory/{date}
loganLogDirectory/Tech/{date}
loganLogDirectory/Biz/{date}
loganLogDirectory/Temp/{date}
loganLogDirectory/Temp/Tech/{date}
loganLogDirectory/Temp/Biz/{date}
```

### 6.2 清理全部

`loganClearAllLogs()` 需要清理全部日志和上传临时文件：

- 根目录下所有合法日期日志文件。
- `Tech` 下所有合法日期日志文件。
- `Biz` 下所有合法日期日志文件。
- `Temp` 下所有临时文件。
- `Temp/Tech` 下所有临时文件。
- `Temp/Biz` 下所有临时文件。

清理完成后保留目录结构：

```text
loganLogDirectory/
├── Tech/
├── Biz/
└── Temp/
    ├── Tech/
    └── Biz/
```

### 6.3 启动时临时文件清理

启动初始化时需要清理历史上传临时文件：

- 清空 `Temp` 目录。
- 清空 `Temp/Tech`、`Temp/Biz` 目录。
- 清理根目录或子目录中历史遗留的临时文件。
- 清理完成后保留目录本身。

### 6.4 过期日志清理

`loganSetMaxReversedDate(max_reversed_date)` 设置本地最大保留天数。

过期清理时，如果某个 `date` 超出保留天数，需要删除：

```text
loganLogDirectory/{date}
loganLogDirectory/Tech/{date}
loganLogDirectory/Biz/{date}
loganLogDirectory/Temp/{date}
loganLogDirectory/Temp/Tech/{date}
loganLogDirectory/Temp/Biz/{date}
```

无效文件、非法日期文件和历史临时文件可以在过期清理中一并删除。

## 7. 查询 API

```objc
extern NSDictionary *_Nullable loganAllFilesInfo(void);
```

返回主通道日志大小，示例：

```objc
@{
    @"2026-05-08": @"1024"
}
```

```objc
extern NSDictionary *_Nullable loganAllSubFilesInfo(void);
```

返回 Tech、Biz 通道日志大小，示例：

```objc
@{
    @"2026-05-08": @{
        @"Tech": @"1024",
        @"Biz": @"2048"
    }
}
```

查询规则：

- 只统计合法日期文件。
- 不统计 `Temp` 临时文件。
- 不统计非法文件和目录。

```objc
extern NSString *_Nonnull loganTodaysDate(void);
```

返回当天日期，格式为 `yyyy-MM-dd`。

## 8. 线程模型与多线程安全

本章约定 Logan 在任意线程调用对外 C API 时的并发语义，便于宿主与实现保持一致，即对外保证的 **多线程安全** 契约。

**总览**：

- **写与 C 层**：所有 `clogan_*` 及依赖其的文件句柄、flush、侧写切回等，仅在 `loganQueue` 上执行，多线程调用写入 API 时由队列串行化，调用方无需自加锁。
- **读目录（查询）**：`loganAllFilesInfo` / `loganAllSubFilesInfo` 与写删路径互斥，在 `loganQueue` 上同步执行并支持队列内可重入（**8.2**）。
- **上传**：`loganUploadWithInterceptionBlock`（及 `loganUpload`）、`loganAllUpload` 与写路径共享 `loganQueue` 编排，且全局仅一条上传链（**8.3**）；最终 `LoganUploadResultBlock` 在主队列（**8.4**）。
- **初始化**：`loganInit` 须在并发使用其它 API 之前完成；业务不应多线程并发调用 `loganInit`（**8.5**）。

### 8.1 串行日志队列（`loganQueue`）

- 所有与 C 层 `clogan_*` 相关的操作（主通道 / Tech / Biz 写入、`flush`、按日期切换文件句柄、侧写通道打开与切回）、以及启动时 Temp 清理、过期删除、`buildUploadJobs`、上传成功后的源文件与 Temp 删除、上传链在队列上的「下一段」编排，**必须**在同一条**串行** `dispatch_queue_t`（下文记为 `loganQueue`）上执行，与 **2.2**「所有写入、flush、切换文件句柄必须在同一个串行队列中执行」一致。
- `logan`、`loganTech`、`loganBiz`、`loganFlush`、`loganClearAllLogs`、`loganClearLogOfDate` 可从任意线程调用；实现将实际工作**异步**派发到 `loganQueue`（调用方不阻塞等待写盘完成，除非实现另有同步 API）。
- 与写路径相关的状态（例如磁盘剩余空间节流、`loganUseASL` 开关、`loganPrintClibLog` 对 C 层调试开关）**必须**仅在 `loganQueue` 上与写路径一起读写，避免与多线程调用方之间的数据竞争。

### 8.2 查询与保留天数

- `loganAllFilesInfo`、`loganAllSubFilesInfo` 会枚举 `loganLogDirectory` 下文件；必须与 **8.1** 中的写日志、删文件、上传删文件互斥。实现上应在 `loganQueue` 上**同步**执行目录枚举与统计；若当前线程已在 `loganQueue` 上（例如从队列内触发的嵌套调用），须**可重入**（同线程再次进入时直接执行，避免 `dispatch_sync` 自身死锁）。
- `loganSetMaxReversedDate` 对保留天数的更新**必须**与过期清理读取该值在同一条 `loganQueue` 上串行化，避免与 **6.4** 清理逻辑并发读写未定义行为。

### 8.3 上传编排与互斥

- `loganUploadWithInterceptionBlock`（含 `loganUpload` 对其的封装）、`loganAllUpload` 在 `loganQueue` 上完成「flush（当天）→ 准备 Temp 拷贝 → 逐文件内置上传或拦截」的编排；`NSURLSession` 回调在系统线程上触发后，再将「成功删文件 / 失败结束链」等工作派回 `loganQueue`。
- **同一时间仅允许一条上传链**（`loganUploadWithInterceptionBlock` / `loganUpload` 与 `loganAllUpload` 彼此互斥）：若上一链尚未完全结束（含最终 `LoganUploadResultBlock` 已回调）又发起新链，新链**必须**立即以失败结束（例如 `LoganUploadResultBlock` 中 `success == NO`），不得与未结束链并行准备同一日期的 Temp/源文件。业务若需严格排队，应在业务层串行调用上传 API。
- 当上传 URL 无法解析为合法 `NSURL` 或无法创建上传任务时，**必须**走失败路径并结束上传链，不得出现无回调、互斥标志无法释放的情况。

### 8.4 回调线程

- 对业务暴露的 **`LoganUploadResultBlock`（整次上传最终结果）**：**必须**在**主队列**上调用；若调用时已在主线程，允许同步调用以避免顺序不确定。拦截器传入的**单次文件** `resultBlock` 可由拦截方在任意线程调用；实现收到后**必须**切回 `loganQueue` 执行删除与后续文件上传，再通过上述主队列规则通知最终 `LoganUploadResultBlock`。
- **`syncCloudUploadUrl` 的 `completion`**：网络结束后**必须**在主队列调用；前置参数不合法、未发起请求时，若提供 `completion`，也应在主队列调用（与 **9.4.1** 一致）。
- **`loganTodaysDate`**：实现可使用每线程 `NSDateFormatter` 等缓存，**必须**保证多线程并发调用结果一致为当日 `yyyy-MM-dd`（日历与系统时区策略与实现一致即可）。
- **`createStandardLog`**：无共享可变状态时，**必须**为线程安全（仅基于入参生成不可变 `NSString`）。

### 8.5 调用方注意

- **初始化顺序与 `loganInit` 线程语义**：须在首次依赖 C 层日志库的行为（如写入）之前完成 `loganInit`；若单例初始化与 `loganInit` 存在异步先后，以不违反「先 `clogan_init` 再写」为准。**不得**假定多线程同时调用 `loganInit` 是安全的；宿主应在启动路径上单次调用，或自行对 `loganInit` 串行化。
- **在 `LoganUploadResultBlock` 内同步调用其它 Logan API**：实现应避免主线程与 `loganQueue` 之间**循环互相 `dispatch_sync`** 导致死锁（例如：先派主回调、再在主回调之后异步回 `loganQueue` 释放上传占用，而不是在队列内阻塞等待主线程上的用户代码）。

## 9. API 清单

### 9.1 回调类型

```objc
typedef void (^LoganUploadResultBlock)(BOOL success,
                                       NSString *_Nullable fileUrl,
                                       NSString *_Nullable filePath);
```

上传结果回调。

- `success`：是否上传成功。
- `fileUrl`：成功时服务端返回或可关联的文件 URL（若有）；失败或未返回时为 `nil`。
- `filePath`：本次上传对应的本地文件路径；失败时可为 `nil`。

```objc
typedef BOOL (^LoganUploadInterceptionBlock)(NSString *_Nonnull date,
                                             NSString *_Nullable retrievalId,
                                             NSString *_Nonnull url,
                                             NSString *_Nonnull localFilePath,
                                             NSString *_Nullable subFolderName,
                                             LoganUploadResultBlock _Nonnull resultBlock);
```

上传拦截回调。

参数：

- `date`：本次上传的日志日期。
- `retrievalId`：本次上传链的检索标识，与 `loganUploadWithInterceptionBlock` / `loganUpload` / `loganAllUpload` 入参一致，可为 `nil`。
- `url`：上传服务端地址。
- `localFilePath`：上传的本地文件路径。
- `subFolderName`：主通道上传时为 `nil`；子目录上传时为子目录名，例如 `Tech`、`Biz`。
- `resultBlock`：本次文件上传完成后的结果回调。

```objc
typedef void (^LoganSyncCloudUploadCompletionBlock)(BOOL success);
```

云端 URL 同步上报完成回调。`success == YES` 表示 HTTP 成功且响应 JSON 中 `code` 整型值为 `200`（见 9.4.1）。

### 9.2 初始化与配置

```objc
extern void loganInit(NSData *_Nonnull aes_key16,
                      NSData *_Nonnull aes_iv16,
                      uint64_t max_file);
```

```objc
extern void loganSetMaxReversedDate(int max_reversed_date);
```

```objc
extern void loganUseASL(BOOL b);
```

```objc
extern void loganPrintClibLog(BOOL b);
```

### 9.3 写入与刷新

```objc
extern void logan(NSUInteger type, NSString *_Nonnull log);
```

```objc
extern void loganTech(NSUInteger type, NSString *_Nonnull log);
```

```objc
extern void loganBiz(NSUInteger type, NSString *_Nonnull log);
```

```objc
extern NSString *_Nonnull createStandardLog(NSString *_Nullable currentName,
                                   NSInteger threadCount,
                                   NSString *_Nullable level,
                                   NSString *_Nullable userId,
                                   NSInteger category,
                                   NSString *_Nullable bizModule,
                                   NSString *_Nullable flowId,
                                   NSString *_Nullable stage,
                                   NSString *_Nullable functionName,
                                   NSString *_Nullable location,
                                   NSString *_Nullable message);
```

```objc
extern void loganFlush(void);
```

### 9.4 上传

```objc
extern void loganUploadWithInterceptionBlock(NSString *_Nonnull url,
                        NSString *_Nonnull date,
                        NSString *_Nullable retrievalId,
                        NSString *_Nullable appId,
                        NSString *_Nullable unionId,
                        NSString *_Nullable deviceId,
                        LoganUploadInterceptionBlock _Nullable interceptionBlock,
                        LoganUploadResultBlock _Nullable resultBlock);
```

```objc
extern void loganUpload(NSString *_Nonnull url,
                        NSString *_Nonnull date,
                        NSString *_Nullable retrievalId,
                        NSString *_Nullable appId,
                        NSString *_Nullable unionId,
                        NSString *_Nullable deviceId,
                        LoganUploadResultBlock _Nullable resultBlock);
```

```objc
extern void loganAllUpload(NSString *_Nonnull url,
                           NSString *_Nullable retrievalId,
                           NSString *_Nullable appId,
                           NSString *_Nullable unionId,
                           NSString *_Nullable deviceId,
                        LoganUploadInterceptionBlock _Nullable interceptionBlock,
                        LoganUploadResultBlock _Nullable resultBlock);
```

#### 9.4.1 `syncCloudUploadUrl`

在日志文件已存在于可访问 URL（如对象存储）时，向云端登记该 URL 及元数据。

```objc
extern void syncCloudUploadUrl(NSString *_Nonnull urlString,
                              NSString *_Nonnull logFileUrl,
                              NSString *_Nonnull fileDate,
                              NSString *_Nullable logFileType,
                              NSString *_Nullable retrievalId,
                              NSString *_Nullable appId,
                              NSString *_Nullable unionId,
                              NSString *_Nullable deviceId,
                              LoganSyncCloudUploadCompletionBlock _Nullable completion);
```

参数：

- `urlString`：本次请求的完整 URL（含 scheme、host、path、query 如需由调用方自行拼接），对应实现中原先由环境 host 与固定 path 拼出的地址；**不再**在 Logan 内解析业务环境 host。
- `logFileUrl`：日志文件的可访问 URL，必填。
- `fileDate`：日志日期，格式 `yyyy-MM-dd`，必填；写入请求头 `fileDate`；同时用于在实现内计算 JSON 体中的 `logStartTime` / `logEndTime`（见下「请求体时间字段」）。
- `logFileType`：日志类型；为空或 `nil` 时在 JSON 中按空字符串 `""` 写入 `logFileType`。
- `retrievalId`：可选；长度大于 0 时在 JSON 体中写入字符串字段 `retrievalId`。
- `appId` / `unionId` / `deviceId`：可选；非空时写入对应 HTTP 头（见下表）。
- `completion`：可选；在完成判定后**主线程**回调，参数为是否成功。

请求体时间字段（由实现根据 `fileDate` 与当前时间生成，不再由调用方传入）：

- `dateInt`：`fileDate` 在**系统时区**下该日 0 点的 Unix 毫秒时间戳（与实现内 `loganDayStartMillisecondsFromYYYYMMDD` / `Logan` 的 `+dayTimeMillisecondsFromYYYYMMDD:` 语义一致；无法解析 `fileDate` 时视为参数不合法）。
- `logStartTime`：JSON 中为**数字**，取值 `dateInt`。
- `logEndTimeInt`：`dateInt + 86400000 - 1000`（即该日历日最后一秒对应的毫秒时刻）。
- `logEndTime`：JSON 中为**数字**，取值 `MIN(logEndTimeInt, curTime)`，其中 `curTime` 为当前 `NSDate` 的 Unix 毫秒时间戳。

前置校验：

- 若 `urlString.length == 0` 或 `logFileUrl.length == 0` 或 `fileDate.length == 0`，不得发起网络请求；若 `completion` 非空则回调 `NO` 并返回。
- 若 `fileDate` 无法按 `yyyy-MM-dd` 解析为合法日期（无法得到有效的日界毫秒），不得发起网络请求；若 `completion` 非空则回调 `NO` 并返回。
- 若 `urlString` 无法解析为合法 `NSURL`，或 JSON 序列化失败，不得发起网络请求；若 `completion` 非空则回调 `NO` 并返回。

HTTP 请求：

- 方法：`POST`。
- URL：`urlString`（须为可解析的 URL；否则失败）。
- `cachePolicy`：`NSURLRequestReloadIgnoringCacheData`；`timeoutInterval`：`30`。
- `Content-Type`：`application/json`（使用 `setValue:forHTTPHeaderField:` 设置）。
- Body：`UTF-8` 编码的 **JSON 对象**（`NSJSONSerialization`），顶层键固定包含：
  - `logFileUrl`：`NSString`，取参数 `logFileUrl` 原值。
  - `logFileType`：`NSString`，缺省为 `""`。
  - `logStartTime`、`logEndTime`：`NSNumber` 整型，毫秒时间戳（见上「请求体时间字段」）。
  - `retrievalId`：仅当 `retrievalId.length > 0` 时存在，值为 `NSString`。

请求头（除 `Content-Type` 与 `fileDate` 外，均为「有值才写」）：

| Header | 规则 |
| --- | --- |
| `fileDate` | 必填，取参数 `fileDate` 原样 |
| `appId` | `appId.length > 0` 时写入 |
| `unionId` | `unionId.length > 0` 时写入 |
| `deviceId` | `deviceId.length > 0` 时写入 |
| `bundleVersion` | 来自主 bundle `CFBundleVersion`，长度大于 0 时写入 |
| `appVersion` | 来自主 bundle `CFBundleShortVersionString`，长度大于 0 时写入 |

网络与会话：

- 使用 `NSURLSession` 的 `sharedSession` 发起 `dataTask`。
- 完成处理中：无 `error` 且 `data.length > 0` 时，将 `data` 按 JSON 反序列化为字典；若存在键 `code` 且其 `integerValue == 200`，则视为成功，否则失败。其余情况（含网络错误、空数据、非字典 JSON）均为失败。
- 若 `completion` 非空，在得到 `ok` 后在**主队列**调用 `completion(ok)`（已在主线程时允许同步调用以避免额外延迟）。

### 9.5 查询与清理

```objc
extern NSDictionary *_Nullable loganAllFilesInfo(void);
```

```objc
extern NSDictionary *_Nullable loganAllSubFilesInfo(void);
```

```objc
extern NSString *_Nonnull loganTodaysDate(void);
```

```objc
extern void loganClearAllLogs(void);
```

```objc
extern void loganClearLogOfDate(NSString *_Nonnull date);
```

## 10. 与当前实现的对齐要求

当前 `Logan.m` 已具备部分相关能力，例如串行 `loganQueue`、`flushInQueue`、Tech/Biz 子目录写入、`uploadTaskWithURL`、过期清理、按日期删除和临时文件清理。后续实现应在这些能力基础上按本文档调整。

必须对齐的差异：

- 目录结构以本文档为准，只保留 `Tech`、`Biz`、`Temp`、`Temp/Tech`、`Temp/Biz`。
- Biz 持久化目录固定为 `loganLogDirectory/Biz/{date}`。
- 指定日期上传须覆盖主通道、Tech、Biz 中存在的文件（见 **3.1** / **4.1**），而不是只上传主通道。
- 提供 `loganUploadWithInterceptionBlock` 与便捷封装 `loganUpload`（`interceptionBlock == nil`）。
- 提供 `loganAllUpload`，上传本地全部合法日期日志。
- 上传 API 须包含可空的 `retrievalId` 与 **3.4** / **9.1** 中的 `LoganUploadInterceptionBlock` 签名（含 `retrievalId` 参数）。
- 上传成功后的源文件删除规则必须按“上传完成一个 `date` 文件，删除一个 `date` 文件”执行。
- 需要新增 `syncCloudUploadUrl`：完整请求 URL 由参数 `urlString` 传入；JSON 请求体、`Content-Type: application/json`、请求头、响应 JSON `code == 200` 判定与主线程回调行为以 **9.4.1** 为准。
- 多线程与回调线程语义以 **第 8 章** 为准（串行 `loganQueue`、上传互斥、主队列结果回调等）。
