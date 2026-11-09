package com.pitchcareer.trial;

import android.content.ContentValues;
import android.content.Context;
import android.net.Uri;
import android.os.Build;
import android.provider.MediaStore;
import android.webkit.JavascriptInterface;
import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;

// 백업 파일을 휴대폰의 다운로드 폴더에 저장 (게임의 window.AndroidBridge.saveFile)
public class Bridge {
    private final Context ctx;
    public Bridge(Context c) { ctx = c; }

    @JavascriptInterface
    public boolean saveFile(String name, String text) {
        if (name == null || text == null) return false;
        String safe = name.replaceAll("[^A-Za-z0-9._-]", "_");
        try {
            byte[] bytes = text.getBytes("UTF-8");
            if (Build.VERSION.SDK_INT >= 29) {
                ContentValues cv = new ContentValues();
                cv.put(MediaStore.Downloads.DISPLAY_NAME, safe);
                cv.put(MediaStore.Downloads.MIME_TYPE, "application/json");
                Uri uri = ctx.getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, cv);
                if (uri == null) return false;
                OutputStream o = ctx.getContentResolver().openOutputStream(uri);
                if (o == null) return false;
                o.write(bytes); o.close();
            } else {
                File dir = ctx.getExternalFilesDir(null);
                FileOutputStream o = new FileOutputStream(new File(dir, safe));
                o.write(bytes); o.close();
            }
            return true;
        } catch (Exception e) {
            return false;
        }
    }
}
