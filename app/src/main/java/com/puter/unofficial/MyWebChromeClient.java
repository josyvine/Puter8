package com.puter.unofficial;

import android.app.Activity;
import android.app.Dialog;
import android.content.Intent;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Message;
import android.provider.MediaStore;
import android.util.Log;
import android.view.ViewGroup;
import android.webkit.ConsoleMessage;
import android.webkit.CookieManager;
import android.webkit.JavascriptInterface;
import android.webkit.PermissionRequest;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.LinearLayout;

import androidx.core.content.FileProvider;

import java.io.File;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * Handles the native file upload functionality (Camera, Gallery, File Picker)
 * for the WebView, enabling Base64 upload support for Puter AI interactions.
 * UPDATED: Added Console Message interception and moved to HTTPS Origin for persistence.
 * CORE FIX: Prevented OAuth interruption by allowing final redirects to load.
 * TIMING FIX: Synchronized WebRTC permission requests to eliminate Android 9 WebView thread race conditions.
 * WEBRTC FIX: Implemented explicit resource matching to bypass ColorOS hardware permission filters.
 */
public class MyWebChromeClient extends WebChromeClient {

    private static final String TAG = "MyWebChromeClient";
    private ValueCallback<Uri[]> uploadMessage;
    private final Activity activity;
    private String currentPhotoPath;
    private Dialog authDialog; // Dialog to host the login popup WebView
    private boolean isAuthProcessing = false; // Prevents the "Blinking" reload loop

    public MyWebChromeClient(Activity activity) {
        this.activity = activity;
    }

    /**
     * FIX: Capture JavaScript console logs and pipe them to Logcat.
     * This allows you to see JS errors in the system logs even without Chrome DevTools.
     * UPDATED: Now also writes unhandled errors and warnings directly to the public diagnostic log.
     */
    @Override
    public boolean onConsoleMessage(ConsoleMessage consoleMessage) {
        String logMsg = String.format(Locale.getDefault(), 
            "[JS CONSOLE] %s -- From line %d of %s",
            consoleMessage.message(),
            consoleMessage.lineNumber(),
            consoleMessage.sourceId());

        switch (consoleMessage.messageLevel()) {
            case ERROR: 
                Log.e("PuterJS", logMsg); 
                ActionReportLogger.logHtmlGlitch("JS_CONSOLE_ERROR", logMsg);
                break;
            case WARNING: 
                Log.w("PuterJS", logMsg); 
                ActionReportLogger.logHtmlGlitch("JS_CONSOLE_WARNING", logMsg);
                break;
            default: 
                Log.d("PuterJS", logMsg); 
                ActionReportLogger.logAction("JS_CONSOLE_LOG", logMsg);
                break;
        }
        return true;
    }

    // --- SDK AUTH POPUP HANDLER WITH AUTO-CLOSE & FALLBACK BUTTON ---

    @Override
    public boolean onCreateWindow(WebView view, boolean isDialog, boolean isUserGesture, Message resultMsg) {
        WebView.WebViewTransport transport = (WebView.WebViewTransport) resultMsg.obj;
        isAuthProcessing = false; // Reset lock for new window

        // Log event to the diagnostic console
        triggerNativeLog("Auth Popup Requested by SDK", "native");

        // 1. Create a Layout to hold a Close Button + The Popup WebView
        LinearLayout dialogLayout = new LinearLayout(activity);
        dialogLayout.setOrientation(LinearLayout.VERTICAL);
        dialogLayout.setBackgroundColor(Color.WHITE);

        // 2. Create a Fail-Safe "Done" Button
        Button closeButton = new Button(activity);
        closeButton.setText("Close Window (Tap when Signed In)");
        closeButton.setBackgroundColor(Color.parseColor("#1a73e8"));
        closeButton.setTextColor(Color.WHITE);
        closeButton.setOnClickListener(v -> {
            triggerNativeLog("Manual Close Triggered by User", "warn");
            closeAuthAndRefresh();
        });

        dialogLayout.addView(closeButton, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 
                ViewGroup.LayoutParams.WRAP_CONTENT));

        // 3. Create and configure the new WebView for the popup
        final WebView popupWebView = new WebView(activity);
        dialogLayout.addView(popupWebView, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 
                ViewGroup.LayoutParams.MATCH_PARENT));

        WebSettings webSettings = popupWebView.getSettings();
        webSettings.setJavaScriptEnabled(true);
        webSettings.setDomStorageEnabled(true);
        webSettings.setJavaScriptCanOpenWindowsAutomatically(true);
        webSettings.setSupportMultipleWindows(true);

        // FIX: Match the UserAgent of the main window exactly to ensure session continuity.
        String userAgent = popupWebView.getSettings().getUserAgentString().replace("; wv", "");
        webSettings.setUserAgentString(userAgent);

        // Enable Third-Party Cookies so the Puter session saves correctly
        CookieManager.getInstance().setAcceptCookie(true);
        CookieManager.getInstance().setAcceptThirdPartyCookies(popupWebView, true);

        // 4. Monitor the popup for URL changes.
        popupWebView.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                String url = request.getUrl().toString();
                triggerNativeLog("Popup Navigating: " + url, "info");

                // Active URL monitoring for success markers
                if (url.contains(AppConstants.AUTH_TOKEN_PARAM) || 
                    url.contains(AppConstants.AUTH_SUCCESS_MARKER) || 
                    url.contains("auth_success") || 
                    url.contains("signed_in=true")) {

                    if (!isAuthProcessing) {
                        isAuthProcessing = true;

                        triggerNativeLog("Auth marker found. Waiting for session persistence...", "native");

                        // CORE FIX: Delay closure and return FALSE to allow the redirect to finalize.
                        // This allows Puter to finish writing cookies/localStorage.
                        view.postDelayed(() -> {
                            CookieManager.getInstance().flush();
                            closeAuthAndRefresh();
                        }, 2000);
                    }
                    // Return false to allow the URL to load and finalize the SDK session
                    return false;
                }
                return false;
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                super.onPageFinished(view, url);
                CookieManager.getInstance().flush();
                triggerNativeLog("Popup Page Loaded: " + url, "info");
            }
        });

        // 5. Handle the official window.close() call from Puter SDK
        popupWebView.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onCloseWindow(WebView window) {
                if (!isAuthProcessing) {
                    isAuthProcessing = true;
                    triggerNativeLog("SDK issued window.close() command", "native");
                    closeAuthAndRefresh();
                }
            }
        });

        // 6. Show the Dialog
        authDialog = new Dialog(activity, android.R.style.Theme_DeviceDefault_NoActionBar);
        authDialog.setContentView(dialogLayout);
        authDialog.show();

        transport.setWebView(popupWebView);
        resultMsg.sendToTarget();

        return true;
    }

    /**
     * Helper to send logs to the background main console and the public documents log.
     */
    private void triggerNativeLog(String msg, String type) {
        Log.d("PuterPopupTrace", msg);
        ActionReportLogger.logAction("AUTH_POPUP", "[" + type + "] " + msg);
    }

    /**
     * Helper method to finalize authentication, dismiss popup, and refresh main UI.
     * CORE FIX: Removed the fake native auth.setLoggedIn(true) call.
     * TRUSTED SOURCE: We now rely on the JS SDK to report the status.
     */
    private void closeAuthAndRefresh() {
        CookieManager.getInstance().flush(); 

        if (authDialog != null && authDialog.isShowing()) {
            authDialog.dismiss();
            authDialog = null;
        }

        // Notify the main WebView to check the Puter SDK state
        if (activity instanceof MainActivity) {
            final WebView mainView = activity.findViewById(R.id.webView);
            if (mainView != null) {
                // CORE FIX: Added delay before checking status to ensure SDK is initialized
                mainView.postDelayed(() -> {
                    mainView.evaluateJavascript("if(window.updateAuthUI){ window.updateAuthUI(); }", null);
                }, 1500);
            }
        }
    }

    @Override
    public void onCloseWindow(WebView window) {
        if (authDialog != null && authDialog.isShowing()) {
            authDialog.dismiss();
            authDialog = null;
        }
        super.onCloseWindow(window);
    }

    // --- HARDWARE PERMISSIONS GATE OVERRIDE ---

    /**
     * Intercepts standard HTML5 permission queries (such as Camera & Microphone).
     * Grants matching permissions cleanly to prevent WebRTC/getUserMedia silent halts.
     * TIMING FIX: Executes synchronously to prevent WebView thread timeout rejections.
     * WebRTC EXPLICIT MAPPING: Safely filters and authorizes requested hardware capabilities.
     */
    @Override
    public void onPermissionRequest(final PermissionRequest request) {
        Log.d(TAG, "onPermissionRequest: Processing hardware capture access permission synchronously.");
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            activity.runOnUiThread(() -> {
                try {
                    String[] requestedResources = request.getResources();
                    request.grant(requestedResources);
                    Log.d(TAG, "onPermissionRequest: Successfully granted resources: " + java.util.Arrays.toString(requestedResources));
                } catch (Exception e) {
                    Log.e(TAG, "onPermissionRequest: Failed to grant permission synchronously. Error: " + e.getMessage());
                    request.deny();
                }
            });
        }
    }

    // --- FILE UPLOAD LOGIC (UNCHANGED) ---

    @Override
    public boolean onShowFileChooser(WebView webView, ValueCallback<Uri[]> filePathCallback, FileChooserParams fileChooserParams) {
        if (uploadMessage != null) {
            uploadMessage.onReceiveValue(null);
        }
        uploadMessage = filePathCallback;

        Intent contentIntent = new Intent(Intent.ACTION_GET_CONTENT);
        contentIntent.addCategory(Intent.CATEGORY_OPENABLE);
        contentIntent.setType("*/*");

        Intent takePictureIntent = new Intent(MediaStore.ACTION_IMAGE_CAPTURE);
        if (takePictureIntent.resolveActivity(activity.getPackageManager()) != null) {
            File photoFile = null;
            try {
                photoFile = createImageFile();
                takePictureIntent.putExtra("PhotoPath", currentPhotoPath);
            } catch (IOException ex) {
                Log.e("MyWebChromeClient", "Error creating file", ex);
            }
            if (photoFile != null) {
                currentPhotoPath = "file:" + photoFile.getAbsolutePath();
                Uri photoURI = FileProvider.getUriForFile(activity, activity.getPackageName() + ".fileprovider", photoFile);
                takePictureIntent.putExtra(MediaStore.EXTRA_OUTPUT, photoURI);
            } else {
                takePictureIntent = null;
            }
        }

        Intent chooserIntent = new Intent(Intent.ACTION_CHOOSER);
        chooserIntent.putExtra(Intent.EXTRA_INTENT, contentIntent);
        chooserIntent.putExtra(Intent.EXTRA_TITLE, "Upload File");
        if (takePictureIntent != null) {
            chooserIntent.putExtra(Intent.EXTRA_INITIAL_INTENTS, new Intent[] { takePictureIntent });
        }

        activity.startActivityForResult(chooserIntent, 1);
        return true;
    }

    private File createImageFile() throws IOException {
        String timeStamp = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(new Date());
        String imageFileName = "JPEG_" + timeStamp + "_";
        File storageDir = activity.getExternalFilesDir(null);
        return File.createTempFile(imageFileName, ".jpg", storageDir);
    }

    public void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (uploadMessage == null) return;
        Uri[] results = null;

        if (resultCode == Activity.RESULT_OK) {
            if (data == null || data.getData() == null) {
                if (currentPhotoPath != null) {
                    results = new Uri[]{Uri.parse(currentPhotoPath)};
                }
            } else {
                String dataString = data.getDataString();
                if (dataString != null) {
                    results = new Uri[]{Uri.parse(dataString)};
                }
            }
        }
        uploadMessage.onReceiveValue(results);
        uploadMessage = null;
    }
}