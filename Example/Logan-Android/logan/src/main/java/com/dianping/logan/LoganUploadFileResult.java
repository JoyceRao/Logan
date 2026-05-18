package com.dianping.logan;

import androidx.annotation.Nullable;

public interface LoganUploadFileResult {

    void complete(boolean success, @Nullable String fileUrl, @Nullable String filePath);
}
