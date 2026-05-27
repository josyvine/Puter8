package com.puter.unofficial;

import android.os.Build;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import com.puter.unofficial.databinding.FragmentHomeBinding;

/**
 * The Primary Dashboard Fragment for Puter Unofficial.
 * This fragment hosts the WebView that displays the Puter AI chat interface.
 * It initializes the JavaScript bridge and handles native feature integration.
 * 
 * UPDATED: Integrated WebViewAssetLoader support and Secure Origin migration.
 * CRITICAL FIX: Explicitly stops the native background standard VoiceManager's microphone capture 
 * inside the fragment lifecycle to eliminate hardware contention with the active foreground voice agent.
 */
public class HomeFragment extends Fragment {

    private FragmentHomeBinding binding;
    private WebView webView;
    private WebAppInterface webAppInterface;
    private VoiceManager voiceManager;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        binding = FragmentHomeBinding.inflate(inflater, container, false);
        return binding.getRoot();
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        // Reference the WebView defined in fragment_home.xml
        webView = binding.homeWebView;

        // FIX: Enable Remote Debugging for the fragment's WebView
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
            WebView.setWebContentsDebuggingEnabled(true);
        }

        // 1. Configure WebView Settings for Puter.js compatibility
        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setAllowFileAccess(true);
        settings.setAllowContentAccess(true);
        settings.setDatabaseEnabled(true);
        settings.setMediaPlaybackRequiresUserGesture(false); // For TTS/Audio

        // FIX: Enable universal access to allow debug_console.js to function
        settings.setAllowFileAccessFromFileURLs(true);
        settings.setAllowUniversalAccessFromFileURLs(true);

        // FIX: Bypass SDK initialization hangs by removing the WebView identifier ("; wv").
        // Matches the logic in MainActivity for session and model-loading consistency.
        String userAgent = settings.getUserAgentString();
        userAgent = userAgent.replace("; wv", "");
        settings.setUserAgentString(userAgent);

        // Ensure standard mobile viewport behavior
        settings.setUseWideViewPort(true);
        settings.setLoadWithOverviewMode(true);

        // 2. Initialize the Native Managers
        // Note: Using getActivity() because the bridge requires an Activity context for UI operations
        voiceManager = new VoiceManager(requireActivity(), webView);
        webAppInterface = new WebAppInterface(requireActivity(), webView);

        // Link the managers to enable Barge-in and Microphone control
        webAppInterface.setVoiceManager(voiceManager);
        voiceManager.setBridge(webAppInterface);

        // 3. Set the Custom Puter WebView Client and Web Chrome Client
        // This handles authentication redirects, AssetLoader routing, file pickers, popups, and device permissions
        webView.setWebViewClient(new PuterWebViewClient(requireContext()));
        webView.setWebChromeClient(new MyWebChromeClient(requireActivity()));

        // 4. Register the JavaScript Bridge
        // This exposes 'window.AndroidInterface' to the HTML/JS frontend
        webView.addJavascriptInterface(webAppInterface, AppConstants.JS_BRIDGE_NAME);

        // 5. Load the local Puter frontend via the secure HTTPS virtual origin
        webView.loadUrl(AppConstants.LOCAL_INDEX_URL);
    }

    /**
     * Refreshes the chat interface. 
     * Can be called by the Activity when returning from settings.
     */
    public void refreshChat() {
        if (webView != null) {
            webView.reload();
        }
    }

    @Override
    public void onResume() {
        super.onResume();
        if (webView != null) {
            webView.onResume();
            webView.resumeTimers();
        }
    }

    /**
     * Lifecycle hook called when the fragment is paused.
     * 
     * CRITICAL FIX: Stops the background standard STT engine to prevent hardware mic lockouts 
     * when VoiceAgentActivity takes foreground priority, and selectively preserves the active 
     * WebKit execution engine if voice session streams are running.
     */
    @Override
    public void onPause() {
        super.onPause();
        
        // De-register background STT recognizers in MainActivity to prevent hardware conflicts
        if (voiceManager != null) {
            try {
                voiceManager.stopListening();
                WebAppInterface.DiagnosticLogger.log("[LIFECYCLE] HomeFragment onPause: Suspended native background microphone.");
            } catch (Exception e) {
                Log.e("HomeFragment", "Failed to suspend standard background VoiceManager: " + e.getMessage());
            }
        }

        if (webView != null) {
            // Prevent pausing background socket streams and JavaScript loops
            // if the hands-free continuous voice conversation loop is running.
            if (!isVoiceModeActive()) {
                webView.onPause();
                webView.pauseTimers();
            }
        }
    }

    @Override
    public void onDestroyView() {
        // Cleanup to prevent memory leaks
        if (webAppInterface != null) {
            webAppInterface.destroy();
        }
        if (voiceManager != null) {
            voiceManager.destroy();
        }
        if (webView != null) {
            webView.stopLoading();
            webView.destroy();
        }
        super.onDestroyView();
        binding = null;
    }

    /**
     * Helper method to check the static voice mode state from WebAppInterface.
     */
    private boolean isVoiceModeActive() {
        try {
            return WebAppInterface.isVoiceModeActiveStatic;
        } catch (Exception e) {
            return false;
        }
    }
}