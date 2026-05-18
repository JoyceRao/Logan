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

package test.logan.dianping.com.logan;

import static com.dianping.logan.CSPLogan.*;

import android.app.Activity;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.text.TextUtils;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;

import com.dianping.logan.CSPLogan;
import com.dianping.logan.SendLogCallback;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Map;

public class MainActivity extends Activity {

    private static final String TAG = MainActivity.class.getName();
    private static final String FILE_NAME = "logan_v1";

    private TextView mTvInfo;
    private EditText mEditIp;
    private RealSendLogRunnable mSendLogRunnable;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        initView();
        mSendLogRunnable = new RealSendLogRunnable();
    }

    private void initView() {
        Button button = (Button) findViewById(R.id.write_btn);
        Button batchBtn = (Button) findViewById(R.id.write_batch_btn);
        Button sendBtn = (Button) findViewById(R.id.send_btn);
        Button logFileBtn = (Button) findViewById(R.id.show_log_file_btn);
        mTvInfo = (TextView) findViewById(R.id.info);
        mEditIp = (EditText) findViewById(R.id.send_ip);

        button.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                log(2, "啊哈哈哈哈66666");
                logTech(3, "啊哈哈哈哈66666");
                logBiz(4, "啊哈哈哈哈66666");
            }
        });
        batchBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                loganTest();
            }
        });
        sendBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                loganSend();
            }
        });
        logFileBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                loganFilesInfo();
            }
        });
        findViewById(R.id.send_btn_default).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                loganSendByDefault();
            }
        });
    }

    private void loganTest() {
        new Thread() {
            @Override
            public void run() {
                super.run();
                try {
                    for (int i = 0; i < 9; i++) {
                        Log.d(TAG, "times : " + i);
                        logTech(1, String.valueOf(i));
                        Thread.sleep(5);
                    }
                    Log.d(TAG, "write log end");
                } catch (InterruptedException e) {
                    e.printStackTrace();
                }
            }
        }.start();
    }

    private void loganSend() {
        SimpleDateFormat dataFormat = new SimpleDateFormat("yyyy-MM-dd");
        String d = dataFormat.format(new Date(System.currentTimeMillis()));
        String[] temp = new String[1];
        temp[0] = d;
        String ip = mEditIp.getText().toString().trim();
        if (!TextUtils.isEmpty(ip)) {
            mSendLogRunnable.setIp(ip);
        }
//        CSPLogan.s(temp, mSendLogRunnable);
    }

    private void loganFilesInfo() {
        Map<String, String> map = allFilesInfo();
        Map<String, Map<String, String>> subMap = allSubFilesInfo();
        StringBuilder info = new StringBuilder();
        if (map != null) {
            for (Map.Entry<String, String> entry : map.entrySet()) {
                info.append("文件日期：").append(entry.getKey()).append("  文件大小（bytes）：").append(
                        entry.getValue()).append("\n");
                String line = "文件日期：" + entry.getKey()
                        + "  文件大小（bytes）：" + entry.getValue();
                Log.d(TAG, line);
            }
        }
        if (subMap != null) {
            File logRoot = new File(getApplicationContext().getExternalFilesDir(null), FILE_NAME);
            for (Map.Entry<String, Map<String, String>> dateEntry : subMap.entrySet()) {
                String date = dateEntry.getKey();
                Map<String, String> channelMap = dateEntry.getValue();
                if (channelMap == null) {
                    continue;
                }
                for (Map.Entry<String, String> channelEntry : channelMap.entrySet()) {
                    String channel = channelEntry.getKey();
                    String size = channelEntry.getValue();
                    File logFile = new File(new File(logRoot, channel), date);
                    String line = "子文件日期：" + date
                            + "  通道：" + channel
                            + "  文件大小（bytes）：" + size
                            + "  路径：" + logFile.getAbsolutePath();
                    info.append(line).append("\n");
                    Log.d(TAG, line);
                }
            }
        }
        mTvInfo.setText(info.toString());
    }

    private void loganSendByDefault() {
        String buildVersion = "";
        String appVersion = "";
        try {
            PackageInfo pInfo = getPackageManager().getPackageInfo(getPackageName(), 0);
            appVersion = pInfo.versionName;
            buildVersion = String.valueOf(pInfo.versionCode);
        } catch (PackageManager.NameNotFoundException e) {
            e.printStackTrace();
        }
        final String url = "https://openlogan.inf.test.sankuai.com/logan/upload.json";
        SimpleDateFormat dataFormat = new SimpleDateFormat("yyyy-MM-dd");
        final String date = dataFormat.format(new Date(System.currentTimeMillis()));

//        CSPLogan.s(url, date, "1", "logan-test-unionid", "deviceId", buildVersion, appVersion, new SendLogCallback() {
//            @Override
//            public void onLogSendCompleted(int statusCode, byte[] data) {
//                final String resultData = data != null ? new String(data) : "";
//                Log.d(TAG, "日志上传结果, http状态码: " + statusCode + ", 详细: " + resultData);
//            }
//        });
    }
}
