package com.pitchcareer.trial;

import android.app.Activity;
import android.content.ContentValues;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.MediaStore;
import android.webkit.JavascriptInterface;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

// 게임(assets/index.html)을 띄우는 화면. 인터넷 없이 동작하고, 저장 데이터는 앱 안(localStorage)에 남는다.
public class MainActivity extends Activity {
    private static final int PICK_FILE = 41;
    private WebView web;
    private ValueCallback<Uri[]> pending;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        web = new WebView(this);
        web.setBackgroundColor(0xFF0A0F1F);
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setAllowFileAccess(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setTextZoom(100);
        web.addJavascriptInterface(new Bridge(this), "AndroidBridge");
        web.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest r) {
                return !"file".equals(r.getUrl().getScheme()); // 게임 밖으로 나가는 링크는 막는다
            }
        });
        web.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onShowFileChooser(WebView v, ValueCallback<Uri[]> cb, FileChooserParams p) {
                if (pending != null) pending.onReceiveValue(null);
                pending = cb;
                Intent i = new Intent(Intent.ACTION_GET_CONTENT);
                i.addCategory(Intent.CATEGORY_OPENABLE);
                i.setType("*/*");
                try { startActivityForResult(Intent.createChooser(i, "백업 파일 선택"), PICK_FILE); }
                catch (Exception e) { pending = null; return false; }
                return true;
            }
        });
        setContentView(web);
        if (state != null) web.restoreState(state);
        else web.loadUrl("file:///android_asset/index.html");
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (req != PICK_FILE || pending == null) return;
        Uri u = (res == RESULT_OK && data != null) ? data.getData() : null;
        pending.onReceiveValue(u != null ? new Uri[]{u} : null);
        pending = null;
    }

    @Override
    protected void onSaveInstanceState(Bundle out) { super.onSaveInstanceState(out); web.saveState(out); }

    @Override
    public void onBackPressed() { if (web.canGoBack()) web.goBack(); else super.onBackPressed(); }

    @Override
    protected void onPause() { super.onPause(); web.onPause(); }

    @Override
    protected void onResume() { super.onResume(); web.onResume(); }

}
