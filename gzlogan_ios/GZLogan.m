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

#import "Logan.h"
#import <sys/time.h>
#include <sys/mount.h>
#include "clogan_core.h"

#if TARGET_OS_IPHONE
#import <UIKit/UIKit.h>
#else
#import <Cocoa/Cocoa.h>
#endif

BOOL LOGANUSEASL = NO;
NSData *__AES_KEY;
NSData *__AES_IV;
uint64_t __max_file;
uint32_t __max_reversed_date;

#define VALID_LOG_STR(s) ((s && [s isKindOfClass:[NSString class]] && s.length) ? s : @"-")

static NSString *const kLoganTechSubfolder = @"Tech";
static NSString *const kLoganBizSubfolder = @"Biz";
static NSString *const kLoganTempSubfolder = @"Temp";

/// dispatch_queue_set_specific / dispatch_get_specific 用于 loganQueue 可重入的 dispatch_sync
static const char kLoganQueueSpecificKey;

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
                            NSString *_Nullable message) {
    NSMutableArray *logData = [NSMutableArray array];
    [logData addObject:[NSString stringWithFormat:@"%@:%ld", VALID_LOG_STR(currentName), (long)threadCount]];
    [logData addObject:VALID_LOG_STR(level)];
    [logData addObject:VALID_LOG_STR(userId)];
    [logData addObject:[NSString stringWithFormat:@"%ld", (long)category]];
    [logData addObject:[NSString stringWithFormat:@"[%@, %@, %@, %@,%@]",
                        VALID_LOG_STR(bizModule),
                        VALID_LOG_STR(flowId),
                        VALID_LOG_STR(stage),
                        VALID_LOG_STR(functionName),
                        VALID_LOG_STR(location)]];
    [logData addObject:VALID_LOG_STR(message)];
    return [logData componentsJoinedByString:@"|"];
}

static void loganInvokeOnMainQueue(dispatch_block_t block) {
    if (!block) {
        return;
    }
    if ([NSThread isMainThread]) {
        block();
    } else {
        dispatch_async(dispatch_get_main_queue(), block);
    }
}

/// \p ymd 为 \c yyyy-MM-dd：该日 0 点（系统时区）的 Unix 毫秒时间戳。
static long long loganDayStartMillisecondsFromYYYYMMDD(NSString *ymd) {
    if (![ymd isKindOfClass:[NSString class]] || ymd.length == 0) {
        return 0;
    }
    NSDateFormatter *df = [[NSDateFormatter alloc] init];
    df.dateFormat = @"yyyy-MM-dd";
    df.locale = [NSLocale localeWithLocaleIdentifier:@"en_US_POSIX"];
    NSDate *dayStart = [df dateFromString:ymd];
    if (!dayStart) {
        return 0;
    }
    long long startMs = (long long)([dayStart timeIntervalSince1970] * 1000.0);
    return startMs;
}

static void loganSyncCloudNotifyCompletion(LoganSyncCloudUploadCompletionBlock _Nullable completion, BOOL success) {
    if (!completion) {
        return;
    }
    loganInvokeOnMainQueue(^{
        completion(success);
    });
}

/// 为 `syncCloudUploadUrl` 设置除 `Content-Type` / body 外的公共请求头。
static void loganSyncCloudApplyRequestHeaders(NSMutableURLRequest *req,
                                              NSString *fileDate,
                                              NSString *_Nullable appId,
                                              NSString *_Nullable unionId,
                                              NSString *_Nullable deviceId) {
    [req addValue:fileDate forHTTPHeaderField:@"fileDate"];
    if (appId.length > 0) {
        [req addValue:appId forHTTPHeaderField:@"appId"];
    }
    if (unionId.length > 0) {
        [req addValue:unionId forHTTPHeaderField:@"unionId"];
    }
    NSString *bundleVersion = [[[NSBundle mainBundle] infoDictionary] objectForKey:@"CFBundleVersion"];
    if (bundleVersion.length > 0) {
        [req addValue:bundleVersion forHTTPHeaderField:@"bundleVersion"];
    }
    if (deviceId.length > 0) {
        [req addValue:deviceId forHTTPHeaderField:@"deviceId"];
    }
    NSString *appVersion = [[NSBundle mainBundle] objectForInfoDictionaryKey:@"CFBundleShortVersionString"];
    if (appVersion.length > 0) {
        [req addValue:appVersion forHTTPHeaderField:@"appVersion"];
    }
}

void syncCloudUploadUrl(NSString *_Nonnull urlString,
                        NSString *_Nonnull logFileUrl,
                        NSString *_Nonnull fileDate,
                        NSString *_Nullable logFileType,
                        NSString *_Nullable retrievalId,
                        NSString *_Nullable appId,
                        NSString *_Nullable unionId,
                        NSString *_Nullable deviceId,
                        LoganSyncCloudUploadCompletionBlock _Nullable completion) {
    if (urlString.length == 0 || logFileUrl.length == 0 || fileDate.length == 0) {
        loganSyncCloudNotifyCompletion(completion, NO);
        return;
    }

    long long dateInt = loganDayStartMillisecondsFromYYYYMMDD(fileDate);
    if (dateInt == 0) {
        loganSyncCloudNotifyCompletion(completion, NO);
        return;
    }
    long long logEndTimeInt = dateInt + 86400000LL - 1000;
    long long curTime = (long long)([[NSDate date] timeIntervalSince1970] * 1000.0);
    long long logEndTimeCapped = MIN(logEndTimeInt, curTime);

    NSString *typeStr = ([logFileType isKindOfClass:[NSString class]] && logFileType.length) ? logFileType : @"";
    NSMutableDictionary *payload = [NSMutableDictionary dictionaryWithDictionary:@{
        @"logFileUrl" : logFileUrl,
        @"logFileType" : typeStr,
        @"logStartTime" : @(dateInt),
        @"logEndTime" : @(logEndTimeCapped),
    }];
    if (retrievalId.length > 0) {
        payload[@"retrievalId"] = retrievalId;
    }

    NSError *jsonError = nil;
    NSData *bodyData = [NSJSONSerialization dataWithJSONObject:payload options:0 error:&jsonError];
    if (!bodyData) {
        loganSyncCloudNotifyCompletion(completion, NO);
        return;
    }

    NSURL *url = [NSURL URLWithString:urlString];
    if (!url) {
        loganSyncCloudNotifyCompletion(completion, NO);
        return;
    }

    NSMutableURLRequest *req = [[NSMutableURLRequest alloc] initWithURL:url cachePolicy:NSURLRequestReloadIgnoringCacheData timeoutInterval:30];
    [req setHTTPMethod:@"POST"];
    [req setHTTPBody:bodyData];
    [req setValue:@"application/json" forHTTPHeaderField:@"Content-Type"];
    loganSyncCloudApplyRequestHeaders(req, fileDate, appId, unionId, deviceId);

    NSURLSessionDataTask *task = [[NSURLSession sharedSession] dataTaskWithRequest:req completionHandler:^(NSData *_Nullable data, NSURLResponse *_Nullable response, NSError *_Nullable error) {
        BOOL ok = NO;
        if (!error && data.length > 0) {
            NSDictionary *obj = [NSJSONSerialization JSONObjectWithData:data options:0 error:nil];
            if ([obj isKindOfClass:[NSDictionary class]]) {
                NSNumber *code = obj[@"code"];
                ok = (code && [code integerValue] == 200);
            }
        }
        loganSyncCloudNotifyCompletion(completion, ok);
    }];
    [task resume];
}

@interface GZLogan : NSObject {
    NSTimeInterval _lastCheckFreeSpace;
}

@property (nonatomic, copy) NSString *lastLogDate;

#if OS_OBJECT_USE_OBJC
@property (nonatomic, strong) dispatch_queue_t loganQueue;
#else
@property (nonatomic, assign) dispatch_queue_t loganQueue;
#endif

/// 仅在 loganQueue 上读写；防止多路上传与写日志/删文件交错
@property (nonatomic, assign) BOOL uploadChainActive;

+ (instancetype)logan;

- (void)writeLog:(NSString *)log logType:(NSUInteger)type;
- (void)writeLog:(NSString *)log logType:(NSUInteger)type sideChannelSubfolder:(NSString *_Nullable)subfolder;
- (void)clearLogs;
- (void)clearLogsOfDate:(NSString *)date;
+ (NSDictionary *)allFilesInfo;
+ (NSDictionary *)allSubFilesInfo;
+ (NSString *)currentDate;
- (void)flush;
- (void)flushInQueue;

+ (void)callResultBlockOnMain:(LoganUploadResultBlock _Nullable)block
                      success:(BOOL)success
                      fileUrl:(NSString *_Nullable)fileUrl
                     filePath:(NSString *_Nullable)filePath
             onMainAfterUserCb:(dispatch_block_t _Nullable)onMainAfterUserCb;

+ (void)performSyncOnLoganQueue:(dispatch_block_t)block;
+ (void)ensureAllDirectories;
+ (void)emptyDirectoryContentsAtFullPath:(NSString *)fullPath;
+ (void)reTemFile;
+ (BOOL)isValidDateFilename:(NSString *)name;
+ (NSArray<NSString *> *)collectAllUploadDates;
+ (void)deleteAfterSuccessfulUploadForDate:(NSString *)date subFolderName:(NSString *_Nullable)subFolderName;
+ (void)deleteLoganFilesForDatePrefix:(NSString *)datePrefix;
+ (NSURLSessionUploadTask *)uploadTaskWithURL:(NSString *)urlStr
                                     filePath:(NSString *)filePath
                                         date:(NSString *)date
                                retrievalId:(NSString *_Nullable)retrievalId
                                        appId:(NSString *)appId
                                      unionId:(NSString *)unionId
                                     deviceId:(NSString *)deviceId
                                subFolderName:(NSString *_Nullable)subFolderName
                            completionHandler:(void (^)(NSData *_Nullable data, NSURLResponse *_Nullable response, NSError *_Nullable error))completionHandler;

- (NSArray<NSDictionary *> *_Nullable)buildUploadJobsForDate:(NSString *)date;
- (void)runUploadJobs:(NSArray<NSDictionary *> *)jobs
      startingAtIndex:(NSUInteger)idx
                 date:(NSString *)date
                  url:(NSString *)url
        retrievalId:(NSString *_Nullable)retrievalId
                appId:(NSString *_Nullable)appId
              unionId:(NSString *_Nullable)unionId
             deviceId:(NSString *_Nullable)deviceId
    interceptionBlock:(LoganUploadInterceptionBlock _Nullable)interceptionBlock
          finalResult:(LoganUploadResultBlock _Nullable)finalResult;

- (void)runAllUploadForDates:(NSArray<NSString *> *)dates
            startingAtDateIndex:(NSUInteger)dateIdx
                          url:(NSString *)url
                retrievalId:(NSString *_Nullable)retrievalId
                        appId:(NSString *_Nullable)appId
                      unionId:(NSString *_Nullable)unionId
                     deviceId:(NSString *_Nullable)deviceId
          interceptionBlock:(LoganUploadInterceptionBlock _Nullable)interceptionBlock
                finalResult:(LoganUploadResultBlock _Nullable)finalResult;

@end

void loganInit(NSData *_Nonnull aes_key16, NSData *_Nonnull aes_iv16, uint64_t max_file) {
    __AES_KEY = aes_key16;
    __AES_IV = aes_iv16;
    __max_file = max_file;
    if (__max_reversed_date == 0) {
        __max_reversed_date = 7;
    }
}

void loganSetMaxReversedDate(int max_reversed_date) {
    if (max_reversed_date <= 0) {
        return;
    }
    dispatch_async([Logan logan].loganQueue, ^{
        __max_reversed_date = (uint32_t)max_reversed_date;
    });
}

void logan(NSUInteger type, NSString *_Nonnull log) {
    [[Logan logan] writeLog:log logType:type];
}

void loganTech(NSUInteger type, NSString *_Nonnull log) {
    [[Logan logan] writeLog:log logType:type sideChannelSubfolder:kLoganTechSubfolder];
}

void loganBiz(NSUInteger type, NSString *_Nonnull log) {
    [[Logan logan] writeLog:log logType:type sideChannelSubfolder:kLoganBizSubfolder];
}

void loganUseASL(BOOL b) {
    dispatch_async([Logan logan].loganQueue, ^{
        LOGANUSEASL = b;
    });
}

void loganPrintClibLog(BOOL b) {
    dispatch_async([Logan logan].loganQueue, ^{
        clogan_debug(!!b);
    });
}

void loganClearAllLogs(void) {
    [[Logan logan] clearLogs];
}

void loganClearLogOfDate(NSString *_Nonnull date) {
    [[Logan logan] clearLogsOfDate:date];
}

NSDictionary *_Nullable loganAllFilesInfo(void) {
    __block NSDictionary *result = nil;
    [Logan performSyncOnLoganQueue:^{
        result = [Logan allFilesInfo];
    }];
    return result;
}

NSDictionary *_Nullable loganAllSubFilesInfo(void) {
    __block NSDictionary *result = nil;
    [Logan performSyncOnLoganQueue:^{
        result = [Logan allSubFilesInfo];
    }];
    return result;
}

void loganFlush(void) {
    [[Logan logan] flush];
}

NSString *_Nonnull loganTodaysDate(void) {
    return [Logan currentDate];
}

void loganUpload(NSString *_Nonnull url,
                 NSString *_Nonnull date,
                 NSString *_Nullable retrievalId,
                 NSString *_Nullable appId,
                 NSString *_Nullable unionId,
                 NSString *_Nullable deviceId,
                 LoganUploadResultBlock _Nullable resultBlock) {
    loganUploadWithInterceptionBlock(url, date, retrievalId, appId, unionId, deviceId, nil, resultBlock);

 }

void loganUploadWithInterceptionBlock(NSString *_Nonnull url,
                 NSString *_Nonnull date,
                 NSString *_Nullable retrievalId,
                 NSString *_Nullable appId,
                 NSString *_Nullable unionId,
                 NSString *_Nullable deviceId,
                 LoganUploadInterceptionBlock _Nullable interceptionBlock,
                 LoganUploadResultBlock _Nullable resultBlock) {
    dispatch_async([Logan logan].loganQueue, ^{
        Logan *instance = [Logan logan];
        if (instance.uploadChainActive) {
            [Logan callResultBlockOnMain:resultBlock success:NO fileUrl:nil filePath:nil onMainAfterUserCb:nil];
            return;
        }
        if (url.length == 0 || date.length == 0) {
            [Logan callResultBlockOnMain:resultBlock success:NO fileUrl:nil filePath:nil onMainAfterUserCb:nil];
            return;
        }
        NSArray *jobs = [instance buildUploadJobsForDate:date];
        if (!jobs) {
            [Logan callResultBlockOnMain:resultBlock success:NO fileUrl:nil filePath:nil onMainAfterUserCb:nil];
            return;
        }
        if (jobs.count == 0) {
            [Logan callResultBlockOnMain:resultBlock success:NO fileUrl:nil filePath:nil onMainAfterUserCb:nil];
            return;
        }
        if (![NSURL URLWithString:url]) {
            [Logan callResultBlockOnMain:resultBlock success:NO fileUrl:nil filePath:nil onMainAfterUserCb:nil];
            return;
        }
        instance.uploadChainActive = YES;
        LoganUploadResultBlock userResult = resultBlock;
        LoganUploadResultBlock wrappedFinal = ^(BOOL success, NSString *fileUrl, NSString *filePath) {
            [Logan callResultBlockOnMain:userResult success:success fileUrl:fileUrl filePath:filePath onMainAfterUserCb:^{
                dispatch_async(instance.loganQueue, ^{
                    instance.uploadChainActive = NO;
                });
            }];
        };
        [instance runUploadJobs:jobs startingAtIndex:0 date:date url:url retrievalId:retrievalId appId:appId unionId:unionId deviceId:deviceId interceptionBlock:interceptionBlock finalResult:wrappedFinal];
    });
}

void loganAllUpload(NSString *_Nonnull url,
                    NSString *_Nullable retrievalId,
                    NSString *_Nullable appId,
                    NSString *_Nullable unionId,
                    NSString *_Nullable deviceId,
                    LoganUploadInterceptionBlock _Nullable interceptionBlock,
                    LoganUploadResultBlock _Nullable resultBlock) {
    dispatch_async([Logan logan].loganQueue, ^{
        Logan *instance = [Logan logan];
        if (instance.uploadChainActive) {
            [Logan callResultBlockOnMain:resultBlock success:NO fileUrl:nil filePath:nil onMainAfterUserCb:nil];
            return;
        }
        if (url.length == 0) {
            [Logan callResultBlockOnMain:resultBlock success:NO fileUrl:nil filePath:nil onMainAfterUserCb:nil];
            return;
        }
        if (![NSURL URLWithString:url]) {
            [Logan callResultBlockOnMain:resultBlock success:NO fileUrl:nil filePath:nil onMainAfterUserCb:nil];
            return;
        }
        NSArray<NSString *> *dates = [Logan collectAllUploadDates];
        if (dates.count == 0) {
            [Logan callResultBlockOnMain:resultBlock success:YES fileUrl:nil filePath:nil onMainAfterUserCb:nil];
            return;
        }
        instance.uploadChainActive = YES;
        LoganUploadResultBlock userResult = resultBlock;
        LoganUploadResultBlock wrappedFinal = ^(BOOL success, NSString *fileUrl, NSString *filePath) {
            [Logan callResultBlockOnMain:userResult success:success fileUrl:fileUrl filePath:filePath onMainAfterUserCb:^{
                dispatch_async(instance.loganQueue, ^{
                    instance.uploadChainActive = NO;
                });
            }];
        };
        [instance runAllUploadForDates:dates startingAtDateIndex:0 url:url retrievalId:retrievalId appId:appId unionId:unionId deviceId:deviceId interceptionBlock:interceptionBlock finalResult:wrappedFinal];
    });
}

@implementation GZLogan

+ (long long)dayTimeMillisecondsFromYYYYMMDD:(NSString *)ymd {
    return loganDayStartMillisecondsFromYYYYMMDD(ymd);
}

+ (void)callResultBlockOnMain:(LoganUploadResultBlock)block
                      success:(BOOL)success
                      fileUrl:(NSString *)fileUrl
                     filePath:(NSString *)filePath
            onMainAfterUserCb:(dispatch_block_t)onMainAfterUserCb {
    if (!block && !onMainAfterUserCb) {
        return;
    }
    loganInvokeOnMainQueue(^{
        if (block) {
            block(success, fileUrl, filePath);
        }
        if (onMainAfterUserCb) {
            onMainAfterUserCb();
        }
    });
}

+ (void)performSyncOnLoganQueue:(dispatch_block_t)block {
    if (!block) {
        return;
    }
    Logan *instance = [Logan logan];
    if (dispatch_get_specific(&kLoganQueueSpecificKey)) {
        block();
    } else {
        dispatch_sync(instance.loganQueue, block);
    }
}

+ (instancetype)logan {
    static Logan *instance = nil;
    static dispatch_once_t pred;
    dispatch_once(&pred, ^{
        instance = [[Logan alloc] init];
    });
    return instance;
}

- (instancetype)init {
    if (self = [super init]) {
        _loganQueue = dispatch_queue_create("com.guazi.logan", DISPATCH_QUEUE_SERIAL);
        dispatch_queue_set_specific(_loganQueue, &kLoganQueueSpecificKey, (void *)1, NULL);
        dispatch_async(self.loganQueue, ^{
            [self initAndOpenCLib];
            [self addNotification];
            [Logan reTemFile];
            [Logan deleteOutdatedFiles];
        });
    }
    return self;
}

- (void)initAndOpenCLib {
    NSAssert(__AES_KEY, @"aes_key is nil!!!, Please use loganInit() to set the key.");
    NSAssert(__AES_IV, @"aes_iv is nil!!!, Please use loganInit() to set the iv.");
    const char *path = [Logan loganLogDirectory].UTF8String;

    const char *aeskey = (const char *)[__AES_KEY bytes];
    const char *aesiv = (const char *)[__AES_IV bytes];
    clogan_init(path, path, (int)__max_file, aeskey, aesiv);
    [Logan ensureAllDirectories];
    NSString *today = [Logan currentDate];
    clogan_open((char *)today.UTF8String);
    __AES_KEY = nil;
    __AES_IV = nil;
}

+ (void)ensureAllDirectories {
    NSFileManager *fm = [NSFileManager defaultManager];
    NSString *root = [self loganLogDirectory];
    NSArray *subpaths = @[
        kLoganTechSubfolder,
        kLoganBizSubfolder,
        kLoganTempSubfolder,
        [kLoganTempSubfolder stringByAppendingPathComponent:kLoganTechSubfolder],
        [kLoganTempSubfolder stringByAppendingPathComponent:kLoganBizSubfolder],
    ];
    for (NSString *sub in subpaths) {
        NSString *p = [root stringByAppendingPathComponent:sub];
        [fm createDirectoryAtPath:p withIntermediateDirectories:YES attributes:nil error:nil];
    }
}

+ (void)emptyDirectoryContentsAtFullPath:(NSString *)fullPath {
    NSFileManager *fm = [NSFileManager defaultManager];
    BOOL isDir = NO;
    if (![fm fileExistsAtPath:fullPath isDirectory:&isDir] || !isDir) {
        return;
    }
    for (NSString *name in [fm contentsOfDirectoryAtPath:fullPath error:nil] ?: @[]) {
        [fm removeItemAtPath:[fullPath stringByAppendingPathComponent:name] error:nil];
    }
}

+ (void)reTemFile {
    NSString *root = [self loganLogDirectory];
    NSString *tempTech = [root stringByAppendingPathComponent:[kLoganTempSubfolder stringByAppendingPathComponent:kLoganTechSubfolder]];
    NSString *tempBiz = [root stringByAppendingPathComponent:[kLoganTempSubfolder stringByAppendingPathComponent:kLoganBizSubfolder]];
    NSString *tempRoot = [root stringByAppendingPathComponent:kLoganTempSubfolder];
    [self emptyDirectoryContentsAtFullPath:tempTech];
    [self emptyDirectoryContentsAtFullPath:tempBiz];
    [self emptyDirectoryContentsAtFullPath:tempRoot];
    [self ensureAllDirectories];
}

+ (BOOL)isValidDateFilename:(NSString *)name {
    static const NSUInteger fmtLen = 10;
    static NSDateFormatter *formatter;
    static dispatch_once_t onceToken;
    dispatch_once(&onceToken, ^{
        formatter = [[NSDateFormatter alloc] init];
        formatter.locale = [NSLocale localeWithLocaleIdentifier:@"en_US_POSIX"];
        formatter.dateFormat = @"yyyy-MM-dd";
    });
    if (name.length != fmtLen) {
        return NO;
    }
    if ([name pathExtension].length > 0) {
        return NO;
    }
    return [formatter dateFromString:name] != nil;
}

+ (void)collectDateFilenamesFromDirectoryRelative:(NSString *)rel into:(NSMutableSet<NSString *> *)set {
    NSFileManager *fm = [NSFileManager defaultManager];
    NSString *dir = [[self loganLogDirectory] stringByAppendingPathComponent:rel];
    BOOL isDir = NO;
    if (![fm fileExistsAtPath:dir isDirectory:&isDir] || !isDir) {
        return;
    }
    for (NSString *name in [fm contentsOfDirectoryAtPath:dir error:nil] ?: @[]) {
        NSString *full = [dir stringByAppendingPathComponent:name];
        BOOL isSubDir = NO;
        if (![fm fileExistsAtPath:full isDirectory:&isSubDir] || isSubDir) {
            continue;
        }
        if ([self isValidDateFilename:name]) {
            [set addObject:name];
        }
    }
}

+ (NSArray<NSString *> *)collectAllUploadDates {
    NSMutableSet<NSString *> *all = [NSMutableSet set];
    NSFileManager *fm = [NSFileManager defaultManager];
    NSString *root = [self loganLogDirectory];
    for (NSString *name in [fm contentsOfDirectoryAtPath:root error:nil] ?: @[]) {
        if ([name isEqualToString:kLoganTechSubfolder] || [name isEqualToString:kLoganBizSubfolder] || [name isEqualToString:kLoganTempSubfolder]) {
            continue;
        }
        NSString *full = [root stringByAppendingPathComponent:name];
        BOOL isSubDir = NO;
        if (![fm fileExistsAtPath:full isDirectory:&isSubDir] || isSubDir) {
            continue;
        }
        if ([self isValidDateFilename:name]) {
            [all addObject:name];
        }
    }
    [self collectDateFilenamesFromDirectoryRelative:kLoganTechSubfolder into:all];
    [self collectDateFilenamesFromDirectoryRelative:kLoganBizSubfolder into:all];
    return [[all allObjects] sortedArrayUsingSelector:@selector(compare:)];
}

+ (void)deleteAfterSuccessfulUploadForDate:(NSString *)date subFolderName:(NSString *)subFolderName {
    if (date.length == 0) {
        return;
    }
    NSFileManager *fm = [NSFileManager defaultManager];
    NSString *root = [self loganLogDirectory];
    if (subFolderName.length == 0) {
        [fm removeItemAtPath:[root stringByAppendingPathComponent:date] error:nil];
        [fm removeItemAtPath:[[root stringByAppendingPathComponent:kLoganTempSubfolder] stringByAppendingPathComponent:date] error:nil];
    } else if ([subFolderName isEqualToString:kLoganTechSubfolder]) {
        [fm removeItemAtPath:[[root stringByAppendingPathComponent:kLoganTechSubfolder] stringByAppendingPathComponent:date] error:nil];
        [fm removeItemAtPath:[[[root stringByAppendingPathComponent:kLoganTempSubfolder] stringByAppendingPathComponent:kLoganTechSubfolder] stringByAppendingPathComponent:date] error:nil];
    } else if ([subFolderName isEqualToString:kLoganBizSubfolder]) {
        [fm removeItemAtPath:[[root stringByAppendingPathComponent:kLoganBizSubfolder] stringByAppendingPathComponent:date] error:nil];
        [fm removeItemAtPath:[[[root stringByAppendingPathComponent:kLoganTempSubfolder] stringByAppendingPathComponent:kLoganBizSubfolder] stringByAppendingPathComponent:date] error:nil];
    }
}

- (void)writeLog:(NSString *)log logType:(NSUInteger)type {
    [self writeLog:log logType:type sideChannelSubfolder:nil];
}

- (void)writeLog:(NSString *)log logType:(NSUInteger)type sideChannelSubfolder:(NSString *)subfolder {
    if (log.length == 0) {
        return;
    }

    NSTimeInterval localTime = [[NSDate date] timeIntervalSince1970] * 1000;
    NSString *threadName = [[NSThread currentThread] name];
    NSInteger threadNum = 1;
    BOOL threadIsMain = [[NSThread currentThread] isMainThread];
    if (!threadIsMain) {
        threadNum = [self getThreadNum];
    }
    char *threadNameC = threadName ? (char *)threadName.UTF8String : "";

    if (subfolder.length == 0) {
        subfolder = nil;
    }

    dispatch_async(self.loganQueue, ^{
        if (LOGANUSEASL && type > 100) {
            [self printfLog:log type:type];
        }
        if (![self hasFreeSpece]) {
            return;
        }

        NSString *today = [Logan currentDate];
        if (self.lastLogDate && ![self.lastLogDate isEqualToString:today]) {
            clogan_flush();
            clogan_open((char *)today.UTF8String);
        }
        self.lastLogDate = today;

        if (!subfolder) {
            clogan_write((int)type, (char *)log.UTF8String, (long long)localTime, threadNameC, (long long)threadNum, (int)threadIsMain);
            return;
        }

        [Logan ensureAllDirectories];
        NSString *relativeOpenPath = [subfolder stringByAppendingPathComponent:today];
        clogan_flush();
        clogan_open((char *)relativeOpenPath.UTF8String);
        clogan_write((int)type, (char *)log.UTF8String, (long long)localTime, threadNameC, (long long)threadNum, (int)threadIsMain);
        clogan_flush();
        clogan_open((char *)today.UTF8String);
    });
}

- (void)flush {
    dispatch_async(self.loganQueue, ^{
        [self flushInQueue];
    });
}

- (void)flushInQueue {
    clogan_flush();
}

- (NSArray<NSDictionary *> *_Nullable)buildUploadJobsForDate:(NSString *)date {
    NSFileManager *fm = [NSFileManager defaultManager];
    NSString *root = [Logan loganLogDirectory];
    NSString *today = [Logan currentDate];
    BOOL isToday = [date isEqualToString:today];
    if (isToday) {
        [self flushInQueue];
    }

    NSMutableArray<NSDictionary *> *jobs = [NSMutableArray array];

    BOOL (^appendJob)(NSString *srcPath, NSString *tempPath, NSString *_Nullable subName) = ^BOOL(NSString *srcPath, NSString *tempPath, NSString *_Nullable subName) {
        if (![fm fileExistsAtPath:srcPath]) {
            return YES;
        }
        NSString *uploadPath = srcPath;
        if (isToday) {
            [fm removeItemAtPath:tempPath error:nil];
            NSError *err = nil;
            if (![fm copyItemAtPath:srcPath toPath:tempPath error:&err]) {
                return NO;
            }
            uploadPath = tempPath;
        }
        id subKey = subName.length ? subName : [NSNull null];
        [jobs addObject:@{@"sub": subKey, @"path": uploadPath}];
        return YES;
    };

    NSString *mainSrc = [root stringByAppendingPathComponent:date];
    NSString *mainTemp = [[root stringByAppendingPathComponent:kLoganTempSubfolder] stringByAppendingPathComponent:date];
    if (!appendJob(mainSrc, mainTemp, nil)) {
        return nil;
    }

    NSString *techSrc = [[root stringByAppendingPathComponent:kLoganTechSubfolder] stringByAppendingPathComponent:date];
    NSString *techTemp = [[[root stringByAppendingPathComponent:kLoganTempSubfolder] stringByAppendingPathComponent:kLoganTechSubfolder] stringByAppendingPathComponent:date];
    if (!appendJob(techSrc, techTemp, kLoganTechSubfolder)) {
        return nil;
    }

    NSString *bizSrc = [[root stringByAppendingPathComponent:kLoganBizSubfolder] stringByAppendingPathComponent:date];
    NSString *bizTemp = [[[root stringByAppendingPathComponent:kLoganTempSubfolder] stringByAppendingPathComponent:kLoganBizSubfolder] stringByAppendingPathComponent:date];
    if (!appendJob(bizSrc, bizTemp, kLoganBizSubfolder)) {
        return nil;
    }

    return jobs;
}

- (void)runUploadJobs:(NSArray<NSDictionary *> *)jobs
      startingAtIndex:(NSUInteger)idx
                 date:(NSString *)date
                  url:(NSString *)url
        retrievalId:(NSString *)retrievalId
                appId:(NSString *)appId
              unionId:(NSString *)unionId
             deviceId:(NSString *)deviceId
    interceptionBlock:(LoganUploadInterceptionBlock)interceptionBlock
          finalResult:(LoganUploadResultBlock)finalResult {
    if (idx >= jobs.count) {
        [Logan callResultBlockOnMain:finalResult success:YES fileUrl:nil filePath:nil onMainAfterUserCb:nil];
        return;
    }

    NSDictionary *job = jobs[idx];
    id subObj = job[@"sub"];
    NSString *subFolderName = (subObj == [NSNull null]) ? nil : (NSString *)subObj;
    NSString *localPath = job[@"path"];

    __weak Logan *weakSelf = self;
    LoganUploadResultBlock wrappedPerFile = ^(BOOL success, NSString *fileUrl, NSString *filePath) {
        __strong Logan *strongSelf = weakSelf;
        if (!strongSelf) {
            [Logan callResultBlockOnMain:finalResult success:NO fileUrl:nil filePath:localPath onMainAfterUserCb:nil];
            return;
        }
        if (!success) {
            [Logan callResultBlockOnMain:finalResult success:NO fileUrl:fileUrl filePath:filePath ?: localPath onMainAfterUserCb:nil];
            return;
        }
        dispatch_async(strongSelf.loganQueue, ^{
            [Logan deleteAfterSuccessfulUploadForDate:date subFolderName:subFolderName];
            [strongSelf runUploadJobs:jobs startingAtIndex:idx + 1 date:date url:url retrievalId:retrievalId appId:appId unionId:unionId deviceId:deviceId interceptionBlock:interceptionBlock finalResult:finalResult];
        });
    };

    if (interceptionBlock && interceptionBlock(date, retrievalId, url, localPath, subFolderName, wrappedPerFile)) {
        return;
    }

    NSURLSessionUploadTask *task = [Logan uploadTaskWithURL:url filePath:localPath date:date retrievalId:retrievalId appId:appId unionId:unionId deviceId:deviceId subFolderName:subFolderName completionHandler:^(NSData *data, NSURLResponse *response, NSError *error) {
        if (error) {
            wrappedPerFile(NO, nil, localPath);
            return;
        }
        if (![response isKindOfClass:[NSHTTPURLResponse class]]) {
            wrappedPerFile(NO, nil, localPath);
            return;
        }
        NSInteger code = [(NSHTTPURLResponse *)response statusCode];
        if (code < 200 || code >= 300) {
            wrappedPerFile(NO, nil, localPath);
            return;
        }
        wrappedPerFile(YES, nil, localPath);
    }];
    if (!task) {
        wrappedPerFile(NO, nil, localPath);
        return;
    }
    [task resume];
}

- (void)runAllUploadForDates:(NSArray<NSString *> *)dates
            startingAtDateIndex:(NSUInteger)dateIdx
                          url:(NSString *)url
                retrievalId:(NSString *)retrievalId
                        appId:(NSString *)appId
                      unionId:(NSString *)unionId
                     deviceId:(NSString *)deviceId
          interceptionBlock:(LoganUploadInterceptionBlock)interceptionBlock
                finalResult:(LoganUploadResultBlock)finalResult {
    if (dateIdx >= dates.count) {
        [Logan ensureAllDirectories];
        [Logan callResultBlockOnMain:finalResult success:YES fileUrl:nil filePath:nil onMainAfterUserCb:nil];
        return;
    }

    NSString *date = dates[dateIdx];
    NSArray *jobs = [self buildUploadJobsForDate:date];
    if (!jobs) {
        [Logan callResultBlockOnMain:finalResult success:NO fileUrl:nil filePath:nil onMainAfterUserCb:nil];
        return;
    }
    if (jobs.count == 0) {
        [self runAllUploadForDates:dates startingAtDateIndex:dateIdx + 1 url:url retrievalId:retrievalId appId:appId unionId:unionId deviceId:deviceId interceptionBlock:interceptionBlock finalResult:finalResult];
        return;
    }

    __weak Logan *weakSelf = self;
    LoganUploadResultBlock dateFinished = ^(BOOL success, NSString *fileUrl, NSString *filePath) {
        if (!success) {
            [Logan callResultBlockOnMain:finalResult success:NO fileUrl:fileUrl filePath:filePath onMainAfterUserCb:nil];
            return;
        }
        Logan *strong = weakSelf;
        if (!strong) {
            [Logan callResultBlockOnMain:finalResult success:NO fileUrl:nil filePath:nil onMainAfterUserCb:nil];
            return;
        }
        dispatch_async(strong.loganQueue, ^{
            [strong runAllUploadForDates:dates startingAtDateIndex:dateIdx + 1 url:url retrievalId:retrievalId appId:appId unionId:unionId deviceId:deviceId interceptionBlock:interceptionBlock finalResult:finalResult];
        });
    };

    [self runUploadJobs:jobs startingAtIndex:0 date:date url:url retrievalId:retrievalId appId:appId unionId:unionId deviceId:deviceId interceptionBlock:interceptionBlock finalResult:dateFinished];
}

- (void)clearLogs {
    dispatch_async(self.loganQueue, ^{
        NSFileManager *fm = [NSFileManager defaultManager];
        NSString *root = [Logan loganLogDirectory];
        for (NSString *rel in [Logan allLogFileRelativePathsForClear]) {
            [fm removeItemAtPath:[root stringByAppendingPathComponent:rel] error:nil];
        }
        [Logan ensureAllDirectories];
    });
}

- (void)clearLogsOfDate:(NSString *)date {
    dispatch_async(self.loganQueue, ^{
        [Logan deleteLoganFilesForDatePrefix:date];
    });
}

- (BOOL)hasFreeSpece {
    NSTimeInterval now = [[NSDate date] timeIntervalSince1970];
    if (now > (_lastCheckFreeSpace + 60)) {
        _lastCheckFreeSpace = now;
        long long freeDiskSpace = [self freeDiskSpaceInBytes];
        if (freeDiskSpace <= 5 * 1024 * 1024) {
            return NO;
        }
    }
    return YES;
}

- (long long)freeDiskSpaceInBytes {
    struct statfs buf;
    long long freespace = -1;
    if (statfs("/var", &buf) >= 0) {
        freespace = (long long)(buf.f_bsize * buf.f_bfree);
    }
    return freespace;
}

- (NSInteger)getThreadNum {
    NSString *description = [[NSThread currentThread] description];
    NSRange beginRange = [description rangeOfString:@"{"];
    NSRange endRange = [description rangeOfString:@"}"];

    if (beginRange.location == NSNotFound || endRange.location == NSNotFound) {
        return -1;
    }

    NSInteger length = endRange.location - beginRange.location - 1;
    if (length < 1) {
        return -1;
    }

    NSRange keyRange = NSMakeRange(beginRange.location + 1, length);

    if (description.length > (keyRange.location + keyRange.length)) {
        NSString *keyPairs = [description substringWithRange:keyRange];
        NSArray *keyValuePairs = [keyPairs componentsSeparatedByString:@","];
        for (NSString *keyValuePair in keyValuePairs) {
            NSArray *components = [keyValuePair componentsSeparatedByString:@"="];
            if (components.count) {
                NSString *key = components[0];
                key = [key stringByTrimmingCharactersInSet:[NSCharacterSet whitespaceCharacterSet]];
                if (([key isEqualToString:@"num"] || [key isEqualToString:@"number"]) && components.count > 1) {
                    return [components[1] integerValue];
                }
            }
        }
    }
    return -1;
}

- (void)printfLog:(NSString *)log type:(NSUInteger)type {
    static time_t dtime = -1;
    if (dtime == -1) {
        time_t tm;
        time(&tm);
        struct tm *t_tm = localtime(&tm);
        dtime = t_tm->tm_gmtoff;
    }
    struct timeval time;
    gettimeofday(&time, NULL);
    int secOfDay = (int)((time.tv_sec + dtime) % (3600 * 24));
    int hour = secOfDay / 3600;
    int minute = secOfDay % 3600 / 60;
    int second = secOfDay % 60;
    int millis = (int)(time.tv_usec / 1000);
    NSString *str = [[NSString alloc] initWithFormat:@"%02d:%02d:%02d.%03d [%lu] %@\n", hour, minute, second, millis, (unsigned long)type, log];
    const char *buf = [str cStringUsingEncoding:NSUTF8StringEncoding];
    printf("%s", buf);
}

#pragma mark - notification

- (void)addNotification {
    if ([[[NSBundle mainBundle] bundlePath] hasSuffix:@".appex"]) {
        return;
    }
#if TARGET_OS_IPHONE
    [[NSNotificationCenter defaultCenter] addObserver:self selector:@selector(appWillEnterForeground) name:UIApplicationWillEnterForegroundNotification object:nil];
    [[NSNotificationCenter defaultCenter] addObserver:self selector:@selector(appDidEnterBackground) name:UIApplicationDidEnterBackgroundNotification object:nil];
    [[NSNotificationCenter defaultCenter] addObserver:self selector:@selector(appWillTerminate) name:UIApplicationWillTerminateNotification object:nil];
#else
    [[NSNotificationCenter defaultCenter] addObserver:self selector:@selector(appWillEnterForeground) name:NSApplicationWillBecomeActiveNotification object:nil];
    [[NSNotificationCenter defaultCenter] addObserver:self selector:@selector(appDidEnterBackground) name:NSApplicationDidResignActiveNotification object:nil];
    [[NSNotificationCenter defaultCenter] addObserver:self selector:@selector(appWillTerminate) name:NSApplicationWillTerminateNotification object:nil];
#endif
}

- (void)appDidEnterBackground {
    [self flush];
}

- (void)appWillEnterForeground {
    [self flush];
}

- (void)appWillTerminate {
    [self flush];
}

- (void)dealloc {
    [[NSNotificationCenter defaultCenter] removeObserver:self];
}

+ (NSDictionary *)allFilesInfo {
    NSArray *allFiles = [Logan localFilesArray];
    NSString *dateFormatString = @"yyyy-MM-dd";
    NSUInteger fmtLen = dateFormatString.length;
    NSDateFormatter *formatter = [[NSDateFormatter alloc] init];
    [formatter setLocale:[NSLocale localeWithLocaleIdentifier:@"en_US_POSIX"]];
    [formatter setDateFormat:dateFormatString];
    NSMutableDictionary *infoDic = [NSMutableDictionary new];
    for (NSString *file in allFiles) {
        if ([file pathExtension].length > 0) {
            continue;
        }
        if (file.length != fmtLen) {
            continue;
        }
        if (![formatter dateFromString:file]) {
            continue;
        }
        unsigned long long sz = [Logan fileSizeAtPath:[self logFilePath:file]];
        infoDic[file] = [NSString stringWithFormat:@"%llu", sz];
    }
    return infoDic;
}

+ (NSDictionary *)allSubFilesInfo {
    NSString *dateFormatString = @"yyyy-MM-dd";
    NSUInteger fmtLen = dateFormatString.length;
    NSDateFormatter *formatter = [[NSDateFormatter alloc] init];
    [formatter setLocale:[NSLocale localeWithLocaleIdentifier:@"en_US_POSIX"]];
    [formatter setDateFormat:dateFormatString];
    NSFileManager *fm = [NSFileManager defaultManager];
    NSString *root = [self loganLogDirectory];
    NSMutableSet *dateSet = [NSMutableSet set];
    void (^collectDates)(NSString *) = ^(NSString *subfolder) {
        NSString *dir = [root stringByAppendingPathComponent:subfolder];
        BOOL isDir = NO;
        if (![fm fileExistsAtPath:dir isDirectory:&isDir] || !isDir) {
            return;
        }
        for (NSString *name in [fm contentsOfDirectoryAtPath:dir error:nil] ?: @[]) {
            if ([name pathExtension].length > 0) {
                continue;
            }
            if (name.length != fmtLen) {
                continue;
            }
            if ([formatter dateFromString:name]) {
                [dateSet addObject:name];
            }
        }
    };
    collectDates(kLoganTechSubfolder);
    collectDates(kLoganBizSubfolder);
    NSArray *sortedDates = [[dateSet allObjects] sortedArrayUsingSelector:@selector(compare:)];
    NSMutableDictionary *out = [NSMutableDictionary dictionary];
    for (NSString *date in sortedDates) {
        unsigned long long techSize = [Logan fileSizeAtPath:[[root stringByAppendingPathComponent:kLoganTechSubfolder] stringByAppendingPathComponent:date]];
        unsigned long long bizSize = [Logan fileSizeAtPath:[[root stringByAppendingPathComponent:kLoganBizSubfolder] stringByAppendingPathComponent:date]];
        out[date] = @{
            kLoganTechSubfolder : [NSString stringWithFormat:@"%llu", techSize],
            kLoganBizSubfolder : [NSString stringWithFormat:@"%llu", bizSize],
        };
    }
    return [out copy];
}

+ (void)deleteOutdatedFiles {
    NSFileManager *fm = [NSFileManager defaultManager];
    NSString *root = [self loganLogDirectory];
    NSArray *rootContents = [fm contentsOfDirectoryAtPath:root error:nil] ?: @[];
    NSDateFormatter *formatter = [[NSDateFormatter alloc] init];
    NSString *dateFormatString = @"yyyy-MM-dd";
    NSUInteger fmtLen = dateFormatString.length;
    [formatter setLocale:[NSLocale localeWithLocaleIdentifier:@"en_US_POSIX"]];
    [formatter setDateFormat:dateFormatString];
    NSString *todayStr = [Logan currentDate];
    NSDate *todayDate = [formatter dateFromString:todayStr];

    for (NSString *name in rootContents) {
        NSString *full = [root stringByAppendingPathComponent:name];
        BOOL isDir = NO;
        [fm fileExistsAtPath:full isDirectory:&isDir];
        if ([name isEqualToString:kLoganTechSubfolder] || [name isEqualToString:kLoganBizSubfolder]) {
            if (isDir) {
                [self deleteOutdatedFilesInSubfolder:name dateFormatter:formatter todayDate:todayDate formatLength:fmtLen];
            }
            continue;
        }
        if ([name isEqualToString:kLoganTempSubfolder]) {
            if (isDir) {
                [self deleteOutdatedFilesInTempTree:formatter todayDate:todayDate formatLength:fmtLen];
            }
            continue;
        }
        if ([name pathExtension].length > 0) {
            [self deleteLoganFile:name];
            continue;
        }
        if (name.length != fmtLen) {
            [self deleteLoganFile:name];
            continue;
        }
        NSDate *date = [formatter dateFromString:name];
        if (!date || [self getDaysFrom:date To:todayDate] >= __max_reversed_date) {
            [self deleteLoganFilesForDatePrefix:name];
        }
    }
}

+ (void)deleteOutdatedFilesInSubfolder:(NSString *)subfolder dateFormatter:(NSDateFormatter *)formatter todayDate:(NSDate *)todayDate formatLength:(NSUInteger)fmtLen {
    NSFileManager *fm = [NSFileManager defaultManager];
    NSString *dir = [[self loganLogDirectory] stringByAppendingPathComponent:subfolder];
    NSArray *inner = [fm contentsOfDirectoryAtPath:dir error:nil] ?: @[];
    for (NSString *name in inner) {
        NSString *rel = [subfolder stringByAppendingPathComponent:name];
        if ([name pathExtension].length > 0) {
            [self deleteLoganFile:rel];
            continue;
        }
        if (name.length != fmtLen) {
            [self deleteLoganFile:rel];
            continue;
        }
        NSDate *date = [formatter dateFromString:name];
        if (!date || [self getDaysFrom:date To:todayDate] >= __max_reversed_date) {
            [self deleteLoganFilesForDatePrefix:name];
        }
    }
}

+ (void)deleteOutdatedFilesInTempTreeRelDir:(NSString *)relDir
                                 formatter:(NSDateFormatter *)formatter
                                  todayDate:(NSDate *)todayDate
                                formatLength:(NSUInteger)fmtLen {
    NSFileManager *fm = [NSFileManager defaultManager];
    NSString *full = [[self loganLogDirectory] stringByAppendingPathComponent:relDir];
    for (NSString *name in [fm contentsOfDirectoryAtPath:full error:nil] ?: @[]) {
        NSString *child = [relDir stringByAppendingPathComponent:name];
        BOOL isDir = NO;
        NSString *childFull = [full stringByAppendingPathComponent:name];
        if (![fm fileExistsAtPath:childFull isDirectory:&isDir]) {
            continue;
        }
        if (isDir && ([name isEqualToString:kLoganTechSubfolder] || [name isEqualToString:kLoganBizSubfolder])) {
            [self deleteOutdatedFilesInTempTreeRelDir:child formatter:formatter todayDate:todayDate formatLength:fmtLen];
            continue;
        }
        if (isDir) {
            continue;
        }
        if ([name pathExtension].length > 0) {
            [self deleteLoganFile:child];
            continue;
        }
        if (name.length != fmtLen) {
            [self deleteLoganFile:child];
            continue;
        }
        NSDate *date = [formatter dateFromString:name];
        if (!date || [self getDaysFrom:date To:todayDate] >= __max_reversed_date) {
            [self deleteLoganFilesForDatePrefix:name];
        }
    }
}

+ (void)deleteOutdatedFilesInTempTree:(NSDateFormatter *)formatter todayDate:(NSDate *)todayDate formatLength:(NSUInteger)fmtLen {
    [self deleteOutdatedFilesInTempTreeRelDir:kLoganTempSubfolder formatter:formatter todayDate:todayDate formatLength:fmtLen];
}

+ (void)deleteLoganFilesForDatePrefix:(NSString *)datePrefix {
    if (datePrefix.length == 0) {
        return;
    }
    [self deleteLoganFile:datePrefix];
    [self deleteLoganFile:[kLoganTechSubfolder stringByAppendingPathComponent:datePrefix]];
    [self deleteLoganFile:[kLoganBizSubfolder stringByAppendingPathComponent:datePrefix]];
    [self deleteLoganFile:[kLoganTempSubfolder stringByAppendingPathComponent:datePrefix]];
    [self deleteLoganFile:[[kLoganTempSubfolder stringByAppendingPathComponent:kLoganTechSubfolder] stringByAppendingPathComponent:datePrefix]];
    [self deleteLoganFile:[[kLoganTempSubfolder stringByAppendingPathComponent:kLoganBizSubfolder] stringByAppendingPathComponent:datePrefix]];
}

+ (void)deleteLoganFile:(NSString *)relativePath {
    if (relativePath.length == 0) {
        return;
    }
    [[NSFileManager defaultManager] removeItemAtPath:[[self loganLogDirectory] stringByAppendingPathComponent:relativePath] error:nil];
}

+ (void)collectRelativeFilePathsUnderDirectory:(NSString *)relativeDir into:(NSMutableArray *)paths {
    NSFileManager *fm = [NSFileManager defaultManager];
    NSString *full = [[self loganLogDirectory] stringByAppendingPathComponent:relativeDir];
    BOOL isDir = NO;
    if (![fm fileExistsAtPath:full isDirectory:&isDir] || !isDir) {
        return;
    }
    for (NSString *name in [fm contentsOfDirectoryAtPath:full error:nil] ?: @[]) {
        NSString *childRel = relativeDir.length ? [relativeDir stringByAppendingPathComponent:name] : name;
        NSString *childFull = [full stringByAppendingPathComponent:name];
        BOOL childIsDir = NO;
        if (![fm fileExistsAtPath:childFull isDirectory:&childIsDir]) {
            continue;
        }
        if (childIsDir) {
            [self collectRelativeFilePathsUnderDirectory:childRel into:paths];
        } else {
            [paths addObject:childRel];
        }
    }
}

+ (NSArray *)allLogFileRelativePathsForClear {
    NSMutableArray *paths = [NSMutableArray array];
    NSFileManager *fm = [NSFileManager defaultManager];
    NSString *root = [self loganLogDirectory];
    NSArray *rootContents = [fm contentsOfDirectoryAtPath:root error:nil] ?: @[];
    NSPredicate *hyphenPred = [NSPredicate predicateWithFormat:@"SELF CONTAINS[cd] '-'"];
    for (NSString *name in rootContents) {
        if ([name isEqualToString:kLoganTechSubfolder] || [name isEqualToString:kLoganBizSubfolder] || [name isEqualToString:kLoganTempSubfolder]) {
            NSString *sub = [root stringByAppendingPathComponent:name];
            BOOL isDir = NO;
            if ([fm fileExistsAtPath:sub isDirectory:&isDir] && isDir) {
                if ([name isEqualToString:kLoganTempSubfolder]) {
                    [self collectRelativeFilePathsUnderDirectory:kLoganTempSubfolder into:paths];
                } else {
                    for (NSString *inner in [fm contentsOfDirectoryAtPath:sub error:nil] ?: @[]) {
                        [paths addObject:[name stringByAppendingPathComponent:inner]];
                    }
                }
            }
            continue;
        }
        if ([hyphenPred evaluateWithObject:name] || [name hasSuffix:@".temp"]) {
            [paths addObject:name];
        }
    }
    return [paths sortedArrayUsingSelector:@selector(compare:)];
}

+ (NSInteger)getDaysFrom:(NSDate *)serverDate To:(NSDate *)endDate {
    NSCalendar *gregorian = [[NSCalendar alloc] initWithCalendarIdentifier:NSCalendarIdentifierGregorian];
    NSDate *fromDate;
    NSDate *toDate;
    [gregorian rangeOfUnit:NSCalendarUnitDay startDate:&fromDate interval:NULL forDate:serverDate];
    [gregorian rangeOfUnit:NSCalendarUnitDay startDate:&toDate interval:NULL forDate:endDate];
    NSDateComponents *dayComponents = [gregorian components:NSCalendarUnitDay fromDate:fromDate toDate:toDate options:0];
    return dayComponents.day;
}

+ (NSString *)logFilePath:(NSString *)date {
    return [[self loganLogDirectory] stringByAppendingPathComponent:date];
}

+ (unsigned long long)fileSizeAtPath:(NSString *)filePath {
    if (filePath.length == 0) {
        return 0;
    }
    NSFileManager *fileManager = [NSFileManager defaultManager];
    if (![fileManager fileExistsAtPath:filePath]) {
        return 0;
    }
    return (unsigned long long)[[fileManager attributesOfItemAtPath:filePath error:nil] fileSize];
}

+ (NSArray *)localFilesArray {
    NSArray *names = [[NSFileManager defaultManager] contentsOfDirectoryAtPath:[self loganLogDirectory] error:nil] ?: @[];
    NSPredicate *hyphenPred = [NSPredicate predicateWithFormat:@"SELF CONTAINS[cd] '-'"];
    NSMutableArray *dates = [NSMutableArray array];
    for (NSString *name in names) {
        if ([name isEqualToString:kLoganTechSubfolder] || [name isEqualToString:kLoganBizSubfolder] || [name isEqualToString:kLoganTempSubfolder]) {
            continue;
        }
        if ([hyphenPred evaluateWithObject:name]) {
            [dates addObject:name];
        }
    }
    return [dates sortedArrayUsingSelector:@selector(compare:)];
}

+ (NSString *)currentDate {
    NSString *key = @"LOGAN_CURRENTDATE";
    NSMutableDictionary *dictionary = [[NSThread currentThread] threadDictionary];
    NSDateFormatter *dateFormatter = dictionary[key];
    if (!dateFormatter) {
        dateFormatter = [[NSDateFormatter alloc] init];
        dateFormatter.locale = [NSLocale localeWithLocaleIdentifier:@"en_US_POSIX"];
        dateFormatter.dateFormat = @"yyyy-MM-dd";
        dictionary[key] = dateFormatter;
    }
    return [dateFormatter stringFromDate:[NSDate date]];
}

+ (NSString *)loganLogDirectory {
    static NSString *dir = nil;
    static dispatch_once_t onceToken;
    dispatch_once(&onceToken, ^{
        dir = [NSSearchPathForDirectoriesInDomains(NSDocumentDirectory, NSUserDomainMask, YES)[0] stringByAppendingPathComponent:@"LoganLoggerv3"];
    });
    return dir;
}

+ (NSURLSessionUploadTask *)uploadTaskWithURL:(NSString *)urlStr
                                     filePath:(NSString *)filePath
                                         date:(NSString *)date
                                retrievalId:(NSString *)retrievalId
                                        appId:(NSString *)appId
                                      unionId:(NSString *)unionId
                                     deviceId:(NSString *)deviceId
                                subFolderName:(NSString *)subFolderName
                            completionHandler:(void (^)(NSData *_Nullable data, NSURLResponse *_Nullable response, NSError *_Nullable error))completionHandler {
    NSURL *url = [NSURL URLWithString:urlStr];
    if (!url || filePath.length == 0) {
        return nil;
    }
    NSMutableURLRequest *req = [[NSMutableURLRequest alloc] initWithURL:url cachePolicy:NSURLRequestReloadIgnoringCacheData timeoutInterval:60];
    [req setHTTPMethod:@"POST"];
    [req addValue:@"binary/octet-stream" forHTTPHeaderField:@"Content-Type"];
    if (retrievalId.length > 0) {
        [req addValue:retrievalId forHTTPHeaderField:@"retrievalId"];
    }
    if (appId.length > 0) {
        [req addValue:appId forHTTPHeaderField:@"appId"];
    }
    if (unionId.length > 0) {
        [req addValue:unionId forHTTPHeaderField:@"unionId"];
    }
    NSString *bundleVersion = [[[NSBundle mainBundle] infoDictionary] objectForKey:@"CFBundleVersion"];
    if (bundleVersion.length > 0) {
        [req addValue:bundleVersion forHTTPHeaderField:@"bundleVersion"];
    }
    if (deviceId.length > 0) {
        [req addValue:deviceId forHTTPHeaderField:@"deviceId"];
    }
    NSString *appVersion = [[NSBundle mainBundle] objectForInfoDictionaryKey:@"CFBundleShortVersionString"];
    if (appVersion.length > 0) {
        [req addValue:appVersion forHTTPHeaderField:@"appVersion"];
    }
    [req addValue:@"2" forHTTPHeaderField:@"platform"];
    [req addValue:date forHTTPHeaderField:@"fileDate"];
    if (subFolderName.length > 0) {
        [req addValue:subFolderName forHTTPHeaderField:@"loganSubFolder"];
    }
    NSURL *fileUrl = [NSURL fileURLWithPath:filePath];
    return [[NSURLSession sharedSession] uploadTaskWithRequest:req fromFile:fileUrl completionHandler:completionHandler];
}

@end
