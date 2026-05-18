/*
 * Copyright (c) 2018-present, 美团点评
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 */

#import <Foundation/Foundation.h>

typedef void (^LoganUploadResultBlock)(BOOL success,
                                       NSString *_Nullable fileUrl,
                                       NSString *_Nullable filePath);

/**
 上传拦截：返回 YES 表示由调用方自行完成本次上传，并必须在适当时机调用 resultBlock；返回 NO 表示走 Logan 内置 NSURLSession 上传。
 @param date 本次上传的日志日期（yyyy-MM-dd）
 @param retrievalId 与本次上传链相同的检索标识（与 loganUpload / loganAllUpload 入参一致，可为 nil）
 @param url 上传服务端地址
 @param localFilePath 待上传的本地文件路径（当天日志可能为 Temp 下临时拷贝路径）
 @param subFolderName 主通道上传时为 nil；子目录上传时为子目录名（如 Tech、Biz）
 */
typedef BOOL (^LoganUploadInterceptionBlock)(NSString *_Nonnull date,
                                              NSString *_Nullable retrievalId,
                                              NSString *_Nonnull url,
                                              NSString *_Nonnull localFilePath,
                                              NSString *_Nullable subFolderName,
                                              LoganUploadResultBlock _Nonnull resultBlock);

typedef void (^LoganSyncCloudUploadCompletionBlock)(BOOL success);

/**
 logan 初始化

 @param aes_key16 16 位 AES 加密 key
 @param aes_iv16  16 位 AES 加密 iv
 @param max_file  日志文件最大大小，超过该大小后日志将不再被写入，单位：byte。
 */
extern void loganInit(NSData *_Nonnull aes_key16, NSData *_Nonnull aes_iv16, uint64_t max_file);

/**
 设置本地保存最大文件天数

 @param max_reversed_date 超过该文件天数的文件会被 Logan 删除，默认 7 天
 */
extern void loganSetMaxReversedDate(int max_reversed_date);

/**
 记录 Logan 日志

 @param type 日志类型
 @param log  日志字符串
 */
extern void logan(NSUInteger type, NSString *_Nonnull log);

/**
 Tech 通道：文件落在 loganLogDirectory/Tech/ 下。
 */
extern void loganTech(NSUInteger type, NSString *_Nonnull log);

/**
 Biz 通道：文件落在 loganLogDirectory/Biz/ 下。
 */
extern void loganBiz(NSUInteger type, NSString *_Nonnull log);

/**
 将日志全部输出到控制台的开关，默认 NO

 @param b 开关
 */
extern void loganUseASL(BOOL b);

/**
 立即写入日志文件
 */
extern void loganFlush(void);

/**
 日志信息输出开关，默认 NO

 @param b 开关
 */
extern void loganPrintClibLog(BOOL b);

/**
 清除本地所有日志
 */
extern void loganClearAllLogs(void);

extern void loganClearLogOfDate(NSString *_Nonnull date);

/**
 返回根目录下主通道日志文件大小（单位 byte），不包含 Tech、Biz、Temp。

 @return @{@"2018-11-21":@"110"}
 */
extern NSDictionary *_Nullable loganAllFilesInfo(void);

/**
 返回 Tech、Biz 子目录下同 pathname（日期）下的文件大小（单位 byte）。

 @return @{@"2018-11-21":@{@"Tech": @"110", @"Biz": @"110"}}
 */
extern NSDictionary *_Nullable loganAllSubFilesInfo(void);

/**
 返回今天日期

 @return @"2018-11-21"
 */
extern NSString *_Nonnull loganTodaysDate(void);

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

/**
 上传指定日期的主通道、Tech、Biz 全部存在的日志文件。

 @param url 接受日志的服务器地址
 @param date 日志日期，格式 yyyy-MM-dd
 @param retrievalId 检索标识
 @param interceptionBlock 可为 nil；每个文件上传前调用一次
 @param resultBlock 全部文件处理完成后的最终结果（主线程回调）
 */
extern void loganUploadWithInterceptionBlock(NSString *_Nonnull url,
                        NSString *_Nonnull date,
                        NSString *_Nullable retrievalId,
                        NSString *_Nullable appId,
                        NSString *_Nullable unionId,
                        NSString *_Nullable deviceId,
                        LoganUploadInterceptionBlock _Nullable interceptionBlock,
                        LoganUploadResultBlock _Nullable resultBlock);

/**
 上传指定日期的主通道、Tech、Biz 全部存在的日志文件。

 @param url 接受日志的服务器地址
 @param date 日志日期，格式 yyyy-MM-dd
 @param retrievalId 检索标识
 @param resultBlock 全部文件处理完成后的最终结果（主线程回调）
 */
extern void loganUpload(NSString *_Nonnull url,
                        NSString *_Nonnull date,
                        NSString *_Nullable retrievalId,
                        NSString *_Nullable appId,
                        NSString *_Nullable unionId,
                        NSString *_Nullable deviceId,
                        LoganUploadResultBlock _Nullable resultBlock);
/**
 上传本地全部合法日期日志（主通道、Tech、Biz），按日期升序；同一日期内顺序为主通道、Tech、Biz。
 */
extern void loganAllUpload(NSString *_Nonnull url,
                           NSString *_Nullable retrievalId,
                           NSString *_Nullable appId,
                           NSString *_Nullable unionId,
                           NSString *_Nullable deviceId,
                           LoganUploadInterceptionBlock _Nullable interceptionBlock,
                           LoganUploadResultBlock _Nullable resultBlock);

/**
 将已上传至可访问 URL 的日志信息登记到云端（POST，Content-Type: application/json）。
 请求体 JSON 中的 logStartTime / logEndTime 由实现根据 fileDate（yyyy-MM-dd，系统时区日界）与当前时间自动计算，无需调用方传入。
 */
extern void syncCloudUploadUrl(NSString *_Nonnull urlString,
                               NSString *_Nonnull logFileUrl,
                               NSString *_Nonnull fileDate,
                               NSString *_Nullable logFileType,
                               NSString *_Nullable retrievalId,
                               NSString *_Nullable appId,
                               NSString *_Nullable unionId,
                               NSString *_Nullable deviceId,
                               LoganSyncCloudUploadCompletionBlock _Nullable completion);
