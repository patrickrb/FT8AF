package com.k1af.ft8af.ui;
/**
 * QRZ lookup web view.
 * @author BGY70Z
 * @date 2023-03-20
 */

import android.annotation.SuppressLint;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import androidx.fragment.app.Fragment;

import com.k1af.ft8af.databinding.FragmentQrzBinding;


public class QRZ_Fragment extends Fragment {
    private FragmentQrzBinding binding;

    public static final String CALLSIGN_PARAM = "callsign";

    private String qrzParam;




    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (getArguments() != null) {
            qrzParam = getArguments().getString(CALLSIGN_PARAM);
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container,
                             Bundle savedInstanceState) {
        binding=FragmentQrzBinding.inflate(inflater, container, false);
        WebSettings qrzSettings = binding.qrzWebView.getSettings();
        qrzSettings.setJavaScriptEnabled(true);
        // Block access to local files and content:// URIs from inside this WebView so a
        // compromised qrz.com page (or a stored XSS) can't pivot to reading our cache or
        // FileProvider contents. setAllowFileAccessFromFileURLs / Universal default false on
        // API 30+ but set them explicitly for older devices we still support (minSdk 23).
        qrzSettings.setAllowFileAccess(false);
        qrzSettings.setAllowContentAccess(false);
        qrzSettings.setAllowFileAccessFromFileURLs(false);
        qrzSettings.setAllowUniversalAccessFromFileURLs(false);
        //binding.qrzWebView.getSettings().setDomStorageEnabled(true);       // This needs to be added
        qrzSettings.setLayoutAlgorithm(WebSettings.LayoutAlgorithm.SINGLE_COLUMN);
        qrzSettings.setUseWideViewPort(true);

        binding.qrzWebView.getSettings().setLoadWithOverviewMode(true);
        binding.qrzWebView.getSettings().setSupportZoom(true);
        binding.qrzWebView.getSettings().setBuiltInZoomControls(true);
        String url = String.format("https://www.qrz.com/db/%s",qrzParam);
        WebViewClient webViewClient = new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, String url) {
                super.shouldOverrideUrlLoading(view, url);
                // HTTPS only: the app-wide cleartext allowance exists for the user's LAN
                // logbook server (issue #756), not for pages this WebView follows links to.
                if (WebNavigationPolicy.isAllowed(url)) {
                    view.loadUrl(url);
                }
                return true;
            }
        };
        binding.qrzWebView.setWebViewClient(webViewClient);


        binding.qrzWebView.loadUrl(url);
        return binding.getRoot();
    }
}