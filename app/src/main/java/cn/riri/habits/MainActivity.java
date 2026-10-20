package cn.riri.habits;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.webkit.JavascriptInterface;
import android.webkit.JsResult;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import org.json.JSONObject;

public class MainActivity extends Activity {
    private static final String ORIGIN = "https://appassets.androidplatform.net";
    private static final int EXPORT = 101, IMPORT = 102;
    private WebView web;
    private String pendingBackup;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        web = new WebView(this);
        web.setBackgroundColor(0xfff7f8f2);
        // Keep content clear of status/navigation bars, including Android 15.
        web.setOnApplyWindowInsetsListener((v, insets) -> {
            v.setPadding(insets.getSystemWindowInsetLeft(), insets.getSystemWindowInsetTop(),
                insets.getSystemWindowInsetRight(), insets.getSystemWindowInsetBottom());
            return insets;
        });
        setContentView(web);
        web.getSettings().setJavaScriptEnabled(true);
        web.getSettings().setDomStorageEnabled(true);
        web.getSettings().setAllowFileAccess(false);
        web.getSettings().setAllowContentAccess(false);
        web.getSettings().setMixedContentMode(android.webkit.WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        web.addJavascriptInterface(new BackupBridge(), "AndroidBackup");
        web.setWebViewClient(new WebViewClient() {
            @Override public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                return !isAppUrl(request.getUrl());
            }
            @Override public boolean shouldOverrideUrlLoading(WebView view, String url) {
                return !isAppUrl(Uri.parse(url));
            }
            @Override public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
                if (isAppUrl(request.getUrl())) {
                    try {
                        return new WebResourceResponse("text/html", "UTF-8",
                            getAssets().open("index.html"));
                    } catch (Exception e) { /* Return closed response below. */ }
                }
                return new WebResourceResponse("text/plain", "UTF-8", 404, "Not Found",
                    java.util.Collections.emptyMap(), new ByteArrayInputStream(new byte[0]));
            }
        });
        web.setWebChromeClient(new WebChromeClient() {
            @Override public boolean onJsConfirm(WebView view, String url, String message, JsResult result) {
                new AlertDialog.Builder(MainActivity.this).setTitle("日日打卡").setMessage(message)
                    .setPositiveButton("确认", (d,w) -> result.confirm())
                    .setNegativeButton("取消", (d,w) -> result.cancel())
                    .setOnCancelListener(d -> result.cancel()).show();
                return true;
            }
        });
        web.loadUrl(ORIGIN + "/index.html");
    }

    private boolean isAppUrl(Uri uri) {
        return "https".equals(uri.getScheme())
            && "appassets.androidplatform.net".equals(uri.getHost())
            && uri.getPort() == -1
            && "/index.html".equals(uri.getPath());
    }
    private void toast(String text) {
        Toast.makeText(this, text, Toast.LENGTH_LONG).show();
    }
    public final class BackupBridge {
        @JavascriptInterface public void exportBackup(String json) {
            runOnUiThread(() -> {
                if (json == null || json.length() > 5_000_000) {
                    toast("备份过大，无法导出"); return;
                }
                if (pendingBackup != null) { toast("请先完成当前备份保存"); return; }
                pendingBackup = json;
                Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
                intent.addCategory(Intent.CATEGORY_OPENABLE);
                intent.setType("application/json");
                intent.putExtra(Intent.EXTRA_TITLE, "日日习惯备份-" +
                    new SimpleDateFormat("yyyy-MM-dd", Locale.ROOT).format(new Date()) + ".json");
                try { startActivityForResult(intent, EXPORT); }
                catch (Exception e) { pendingBackup = null; toast("无法打开系统文件保存器"); }
            });
        }
        @JavascriptInterface public void importBackup() {
            runOnUiThread(() -> {
                Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
                intent.addCategory(Intent.CATEGORY_OPENABLE);
                intent.setType("*/*");
                try { startActivityForResult(intent, IMPORT); }
                catch (Exception e) { toast("无法打开系统文件选择器"); }
            });
        }
    }
    @Override protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (result != RESULT_OK || data == null || data.getData() == null) {
            if (request == EXPORT) pendingBackup = null;
            return;
        }
        Uri uri = data.getData();
        if (request == EXPORT) {
            String json = pendingBackup;
            pendingBackup = null;
            if (json == null) { toast("备份已失效，请重新导出"); return; }
            new Thread(() -> {
                try (OutputStream out = getContentResolver().openOutputStream(uri, "wt")) {
                    if (out == null) throw new Exception();
                    out.write(json.getBytes(StandardCharsets.UTF_8));
                    runOnUiThread(() -> toast("备份已保存"));
                } catch (Exception e) { runOnUiThread(() -> toast("保存失败，请重新导出")); }
            }).start();
        } else if (request == IMPORT) {
            new Thread(() -> {
                try (InputStream in = getContentResolver().openInputStream(uri);
                     ByteArrayOutputStream out = new ByteArrayOutputStream()) {
                    if (in == null) throw new Exception();
                    byte[] buffer = new byte[8192];
                    int count;
                    while ((count = in.read(buffer)) != -1) {
                        if (out.size() + count > 5_000_000) throw new Exception();
                        out.write(buffer, 0, count);
                    }
                    String json = out.toString("UTF-8");
                    runOnUiThread(() -> {
                        if (!isFinishing()) web.evaluateJavascript(
                            "window.receiveAndroidBackup(" + JSONObject.quote(json) + ")", null);
                    });
                } catch (Exception e) {
                    runOnUiThread(() -> toast("无法读取备份，请选择小于 5 MB 的 JSON 文件"));
                }
            }).start();
        }
    }
    @Override public void onBackPressed() {
        web.evaluateJavascript("(function(){const d=document.querySelector('dialog[open]');"
            + "if(d){d.close();return true;}const b=document.querySelector('[data-action=back]');"
            + "if(b){b.click();return true;}return false;})()", handled -> {
                if (!"true".equals(handled)) finish();
            });
    }
    @Override protected void onDestroy() {
        web.removeJavascriptInterface("AndroidBackup");
        web.destroy();
        super.onDestroy();
    }
}
