package com.puter.unofficial;

import android.content.Context;
import android.graphics.Bitmap;
import android.webkit.CookieManager;
import android.webkit.WebResourceRequest;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.util.Log;

/**
 * Custom WebViewClient for Puter Unofficial.
 * This class intercepts URL loads to detect successful authentication redirects
 * and ensures that Puter.js session cookies are persisted correctly.
 */
public class PuterWebViewClient extends WebViewClient {

    private static final String TAG = "PuterWebViewClient";
    private final Context context;
    private final AuthManager authManager;

    public PuterWebViewClient(Context context) {
        this.context = context;
        this.authManager = AuthManager.getInstance(context);
    }

    /**
     * Intercepts URL loading to handle authentication success.
     */
    @Override
    public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
        String url = request.getUrl().toString();
        Log.d(TAG, "Loading URL: " + url);

        // Check if this URL is a Puter authentication success callback
        if (authManager.isAuthCallback(url)) {
            Log.i(TAG, "Authentication successful redirect detected.");
            
            // Persist the login state
            authManager.setLoggedIn(true);
            
            // Sync cookies immediately to ensure Puter.js stays authenticated
            CookieManager.getInstance().flush();
            
            /* 
             * Instead of letting the WebView load the callback URL, 
             * we redirect the user back to our local index.html 
             * so the UI can update to the "Signed In" state.
             */
            view.loadUrl("file:///android_asset/index.html");
            return true;
        }

        // Return false to let the WebView handle the URL normally
        return false;
    }

    @Override
    public void onPageStarted(WebView view, String url, Bitmap favicon) {
        super.onPageStarted(view, url, favicon);
        Log.d(TAG, "Page load started: " + url);
    }

    @Override
    public void onPageFinished(WebView view, String url) {
        super.onPageFinished(view, url);
        Log.d(TAG, "Page load finished: " + url);

        // Ensure cookies are saved for persistence
        CookieManager.getInstance().flush();

        /*
         * After our index.html is loaded, we can trigger a JS function 
         * to update the UI based on the latest Auth state.
         */
        if (url.startsWith("file:///android_asset/index.html")) {
            boolean status = authManager.isLoggedIn();
            view.evaluateJavascript("if(window.updateAuthUI) { window.updateAuthUI(" + status + "); }", null);
        }
    }

    /**
     * Handles SSL or network errors if necessary to ensure 
     * the Puter interface remains stable.
     */
    @Override
    public void onReceivedError(WebView view, int errorCode, String description, String failingUrl) {
        super.onReceivedError(view, errorCode, description, failingUrl);
        Log.e(TAG, "WebView Error (" + errorCode + "): " + description);
    }
}