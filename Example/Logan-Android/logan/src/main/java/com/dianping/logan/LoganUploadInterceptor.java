package com.dianping.logan;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

public interface LoganUploadInterceptor {

    boolean interceptUpload(@NonNull String date,
            @Nullable String retrievalId,
            @NonNull String url,
            @NonNull String localFilePath,
            @Nullable String subFolderName,
            long localCreateTime,
            @NonNull LoganUploadFileResult fileResult);
}
