package com.k1af.ft8af;
/**
 * WebView for collecting questions/feedback.
 * @author BGY70Z
 * @date 2023-03-20
 */

import android.annotation.SuppressLint;
import android.os.Bundle;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import androidx.appcompat.app.AppCompatActivity;

public class FAQActivity extends AppCompatActivity {

    @SuppressLint("SetJavaScriptEnabled")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_faqactivity);


        WebView webView = (WebView) findViewById(R.id.faqWebView);
        WebSettings faqSettings = webView.getSettings();
        faqSettings.setJavaScriptEnabled(true);
        faqSettings.setDomStorageEnabled(true);       // This needs to be enabled
        // Block local file / content URI access so a compromised support.qq.com page
        // can't read app cache or FileProvider contents via the WebView.
        faqSettings.setAllowFileAccess(false);
        faqSettings.setAllowContentAccess(false);
        faqSettings.setAllowFileAccessFromFileURLs(false);
        faqSettings.setAllowUniversalAccessFromFileURLs(false);

        /* Get the webview url. Note the word is "product" not "products"; "products" is an old parameter and using the wrong address will prevent successful submission */
        //String url = "https://www.qrz.com/db/BG7YOZ";
        String url = "https://support.qq.com/product/415890";

        /* Embedded WebViewClient allows opening web pages within the app instead of launching an external browser */
        WebViewClient webViewClient = new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, String url) {
                super.shouldOverrideUrlLoading(view, url);
                // HTTPS only: the app-wide cleartext allowance exists for the user's LAN
                // logbook server (issue #756), not for pages this WebView follows links to.
                if (com.k1af.ft8af.ui.WebNavigationPolicy.isAllowed(url)) {
                    view.loadUrl(url);
                }
                return true;
            }
        };
        webView.setWebViewClient(webViewClient);

        webView.loadUrl(url);
    }

}