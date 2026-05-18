package com.dianping.logan;

import androidx.annotation.Nullable;

public interface LoganUploadResultCallback {

    void onUploadFinished(boolean success, @Nullable String fileUrl, @Nullable String filePath);
}
