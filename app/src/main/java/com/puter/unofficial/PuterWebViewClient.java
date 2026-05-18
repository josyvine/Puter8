package com.puter.unofficial;

import android.content.Context;
import android.graphics.Bitmap;
import android.webkit.CookieManager;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.util.Log;

import androidx.webkit.WebViewAssetLoader;

/**
 * Custom WebViewClient for Puter Unofficial.
 * This class intercepts URL loads to detect successful authentication redirects,
 * manages the virtual HTTPS asset routing via WebViewAssetLoader,
 * and ensures that Puter.js session cookies are persisted correctly.
 */
public class PuterWebViewClient extends WebViewClient {

    private static final String TAG = "PuterWebViewClient";
    private final Context context;
    private final AuthManager authManager;
    private final WebViewAssetLoader assetLoader;
    
    // Guard variable to prevent the "Blinking Loop"
    private String lastHandledAuthUrl = "";

    /**
     * Constructor for the PuterWebViewClient.
     * Initializes the WebViewAssetLoader to handle the virtual HTTPS origin.
     */
    public PuterWebViewClient(Context context) {
        this.context = context;
        this.authManager = AuthManager.getInstance(context);

        // FIX: Configure the AssetLoader to serve local assets over a virtual HTTPS domain.
        // This is the key fix for Puter.js authentication persistence.
        this.assetLoader = new WebViewAssetLoader.Builder()
                .setDomain("appassets.androidplatform.net")
                .addPathHandler("/assets/", new WebViewAssetLoader.AssetsPathHandler(context))
                .build();
    }

    /**
     * Intercepts requests to the virtual domain and serves them from local assets.
     */
    @Override
    public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
        // This routes https://appassets.androidplatform.net/assets/... to the assets folder
        return assetLoader.shouldInterceptRequest(request.getUrl());
    }

    /**
     * Intercepts URL loading to handle authentication success.
     */
    @Override
    public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
        String url = request.getUrl().toString();
        Log.d(TAG, "Loading URL: " + url);

        // Check if this URL is a Puter authentication success callback
        // Added a check to see if we've already handled this specific callback to stop reload loops
        if (authManager.isAuthCallback(url) && !url.equals(lastHandledAuthUrl)) {
            Log.i(TAG, "Authentication successful redirect detected.");
            
            // Mark this URL as handled to prevent the "blinking" recursive loop
            lastHandledAuthUrl = url;

            // Persist the login state immediately
            authManager.setLoggedIn(true);
            
            // Sync cookies immediately to ensure Puter.js stays authenticated
            CookieManager.getInstance().flush();
            
            /* 
             * Let the WebView handle the final redirect, but our onPageFinished
             * will ensure the UI updates correctly. This is more stable than
             * forcing a loadUrl here. The SDK needs to finish its internal state.
             */
        }

        // Return false to let the WebView handle all URL loads normally.
        // This is crucial for the SDK's popup/redirect flow to work correctly.
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

        // Ensure cookies are saved for persistence after any page load
        CookieManager.getInstance().flush();

        /*
         * After our index.html is loaded, we can trigger a JS function 
         * to update the UI based on the latest Auth state.
         * This now also handles the refresh after a successful login redirect.
         */
        if (url.startsWith(AppConstants.LOCAL_INDEX_URL)) {
            // Use evaluateJavascript for better performance and error handling
            view.post(() -> {
                // Ensure updateAuthUI is called only when the page is fully ready
                view.evaluateJavascript("if(window.updateAuthUI) { window.updateAuthUI(); }", null);
            });
            
            // Reset the auth URL guard once we are back at the home index
            lastHandledAuthUrl = "";
        }
    }

    /**
     * Handles SSL or network errors if necessary to ensure 
     * the Puter interface remains stable.
     */
    @Override
    public void onReceivedError(WebView view, int errorCode, String description, String failingUrl) {
        super.onReceivedError(view, errorCode, description, failingUrl);
        Log.e(TAG, "WebView Error (" + errorCode + "): " + description + " at " + failingUrl);
    }
}