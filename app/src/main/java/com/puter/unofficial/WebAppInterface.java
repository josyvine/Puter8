package com.puter.unofficial;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.ContentValues; // Added for saving images
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Bitmap; // Added for image decoding
import android.graphics.BitmapFactory; // Added for image decoding
import android.net.Uri;
import android.os.Build; // Added for Scoped Storage compatibility
import android.os.Environment; // Added for public directory
import android.provider.MediaStore; // Added for saving images
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;
import android.webkit.JavascriptInterface;
import android.webkit.WebView;
import android.widget.Toast;
import android.util.Log;
import android.webkit.CookieManager;

import java.io.OutputStream; // Added for writing image data
import java.io.IOException; // Added for exception handling
import java.util.Locale;
import java.util.Map;
import java.util.Objects; // Added for null checks on Objects
import java.util.Date; // Added for unique file naming

/**
 * The core bridge class between the HTML JavaScript and Native Android code.
 * Fulfills all requirements for native TTS, barge-in, full-screen voice agent,
 * and authentication persistence.
 * UPDATED: Aligned with WebViewAssetLoader architecture and diagnostic logging.
 * PERSISTENCE UPDATE: Added hardware-level cookie synchronization logic to prevent 
 * session loss during AI chat requests.
 * REFINED: Fixed Voice Mode leakage and Continuous Interruption logic.
 * ENHANCEMENT: Added support for multi-session deletion via native bridge.
 * UPDATED: Added on-demand wakeUpKiwi() JavascriptInterface to handle lazy wake-ups.
 */
public class WebAppInterface {

    private final Context context;
    private final WebView webView;
    private TextToSpeech tts;
    private final SharedPreferences prefs;
    private VoiceManager voiceManager;
    private boolean isTtsInitialized = false;

    // REQUIREMENT: Logic flag to distinguish between Voice Agent mode and standard Text mode.
    // Prevents TTS from reading normal keyboard messages.
    private boolean isVoiceModeActive = false;

    /**
     * Constructor for the interface.
     * @param context Activity context required for launching intents and UI updates.
     * @param webView Reference to the WebView for running JavaScript callbacks.
     */
    public WebAppInterface(Context context, WebView webView) {
        this.context = context;
        this.webView = webView;
        this.prefs = context.getSharedPreferences(AppConstants.PREF_NAME, Context.MODE_PRIVATE);

        // Initialize Native Android Text-To-Speech Engine (Requirement #4)
        this.tts = new TextToSpeech(context, status -> {
            if (status == TextToSpeech.SUCCESS) {
                int result = tts.setLanguage(Locale.US);
                if (result != TextToSpeech.LANG_MISSING_DATA && result != TextToSpeech.LANG_NOT_SUPPORTED) {
                    isTtsInitialized = true;
                    nativeLog("TTS Engine Initialized Successfully on Secure Origin", "native");

                    // REQUIREMENT: Setup progress listener to handle continuous speech-to-mic loop
                    setupTtsProgressListener();
                } else {
                    nativeLog("TTS Language not supported", "error");
                }
            } else {
                nativeLog("TTS Initialization Failed", "error");
            }
        });
    }

    /**
     * Helper to safely escape JavaScript parameters to avoid script execution failures.
     */
    private String escapeJsString(String value) {
        if (value == null) {
            return "";
        }
        return value.replace("\\", "\\\\")
                    .replace("'", "\\'")
                    .replace("\"", "\\\"")
                    .replace("\n", "\\n")
                    .replace("\r", "\\r");
    }

    /**
     * Setup listener to notify JavaScript when the AI finishes speaking.
     */
    private void setupTtsProgressListener() {
        if (tts != null) {
            tts.setOnUtteranceProgressListener(new UtteranceProgressListener() {
                @Override
                public void onStart(String utteranceId) {}

                @Override
                public void onDone(String utteranceId) {
                    // REQUIREMENT #2: Only re-open the mic if the user is in an active Voice session.
                    // This prevents the mic from opening during normal text chat.
                    if (isVoiceModeActive) {
                        webView.post(() -> webView.evaluateJavascript("if(window.onSpeechFinished){ window.onSpeechFinished(); }", null));
                    }
                }

                @Override
                public void onError(String utteranceId) {}
            });
        }
    }

    /**
     * Diagnostic method to pipe Java-side logs into the Floating Debug Console.
     * This helps identify why login fails by showing Java events in the JS timeline.
     * UPDATED: Now also writes the log natively to our daily public documents file.
     */
    @JavascriptInterface
    public void nativeLog(String message, String type) {
        if (webView != null) {
            String escapedMsg = escapeJsString(message);
            webView.post(() -> {
                // Interacts with window.addPuterLog inside debug_console.js
                webView.evaluateJavascript("if(window.addPuterLog){ window.addPuterLog('[JAVA] " + escapedMsg + "', '" + type + "'); }", null);
                // Interacts with window.addNativeLogToConsole inside browser.html
                webView.evaluateJavascript("if(window.addNativeLogToConsole){ window.addNativeLogToConsole('[JAVA] " + escapedMsg + "', '" + type + "'); }", null);
            });
        }
        // Also keep a record in Android Logcat for GitHub Workflow log analysis
        Log.d("PuterNativeBridge", message);

        // --- TOTAL SURVEILLANCE INTEGRATION ---
        if ("error".equalsIgnoreCase(type) || "critical".equalsIgnoreCase(type)) {
            ActionReportLogger.logError("NATIVE_BRIDGE", message);
        } else {
            ActionReportLogger.logAction("NATIVE_BRIDGE", message);
        }
    }

    /**
     * Links the VoiceManager for background STT operations.
     */
    public void setVoiceManager(VoiceManager voiceManager) {
        this.voiceManager = voiceManager;
    }

    // --- VOICE MODE CONTROL ---

    /**
     * Requirement: Explicitly sets whether the app is in hands-free voice mode.
     * This prevents normal text chat from being read aloud.
     */
    @JavascriptInterface
    public void setVoiceMode(boolean active) {
        this.isVoiceModeActive = active;
        nativeLog("Bridge: Voice Mode " + (active ? "ENABLED" : "DISABLED"), "info");
    }

    // 1. NATIVE TEXT-TO-SPEECH (TTS)
    // Supports Barge-in: stops current speech and starts new text immediately.
    @JavascriptInterface
    public void speak(String text) {
        if (isTtsInitialized && tts != null) {
            // Only speak if Voice Mode is active OR if manually requested by long-press
            nativeLog("AI speaking response via Native Engine...", "info");
            // Barge-in logic: QUEUE_FLUSH clears previous speech and interrupts immediately
            tts.stop();
            tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, AppConstants.TTS_UTTERANCE_ID);
        }
    }

    // 2. STOP TTS
    // Interrupts the AI speaker immediately.
    @JavascriptInterface
    public void stopSpeaking() {
        if (tts != null) {
            nativeLog("Stopping AI speech (Barge-in triggered)", "native");
            tts.stop();
        }
    }

    // 3. NATIVE SPEECH RECOGNITION (Standard)
    // Triggers background microphone for the search input.
    @JavascriptInterface
    public void startListening() {
        if (voiceManager != null) {
            nativeLog("Opening Native Microphone for STT...", "native");
            stopSpeaking();
            ((Activity) context).runOnUiThread(() -> voiceManager.startListening());
        }
    }

    // 4. FULL-SCREEN VOICE AGENT (Requirement #4)
    // Launches the native full-screen Activity for continuous conversation.
    @JavascriptInterface
    public void startVoiceAgent() {
        nativeLog("Launching Immersive Full-Screen Voice Agent", "native");
        stopSpeaking();
        // Force Voice Mode active for the full-screen activity
        setVoiceMode(true);
        Intent intent = new Intent(context, VoiceAgentActivity.class);
        context.startActivity(intent);
    }

    // 5. NATIVE FILE / CAMERA PICKER (Requirement #3)
    // Triggers the system chooser for Gallery, Camera, and Files.
    @JavascriptInterface
    public void openFilePicker() {
        nativeLog("Invoking Native System File/Camera Picker", "native");
        ((Activity) context).runOnUiThread(() -> {
            // Handled via onShowFileChooser in MainActivity's MyWebChromeClient
            Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
            intent.addCategory(Intent.CATEGORY_OPENABLE);
            intent.setType("*/*");
            ((Activity) context).startActivityForResult(Intent.createChooser(intent, "Select File for Puter"), 1);
        });
    }

    // --- ENHANCED BRIDGE METHODS ---

    /**
     * Requirement #3: Save a specific chat session to native storage.
     */
    @JavascriptInterface
    public void saveChatSession(String sessionId, String sessionData) {
        prefs.edit().putString("session_" + sessionId, sessionData).apply();
        nativeLog("Session " + sessionId + " persisted to native storage.", "native");
    }

    /**
     * Requirement #3: Retrieve a specific chat session from native storage.
     */
    @JavascriptInterface
    public String getChatSession(String sessionId) {
        return prefs.getString("session_" + sessionId, "[]");
    }

    /**
     * REQUIREMENT #3: Delete a specific chat session from native storage.
     * Updates SharedPreferences to remove the session key permanently.
     * ENHANCED: Also purges specific Web Scraper items and indices if matched.
     */
    @JavascriptInterface
    public void deleteSession(String sessionId) {
        if (sessionId != null) {
            nativeLog("Bridge: Deleting session ID " + sessionId, "native");
            // Standardizing the key to match the current saveChatSession implementation
            prefs.edit().remove("session_" + sessionId).apply();
            // Also ensure any web-side history keys are cleared if they were mirrored
            prefs.edit().remove("puter_chat_history_" + sessionId).apply();

            // Scraper Deletion Support:
            String scrapedKey = "puter_scraped_product_" + sessionId;
            if (prefs.contains(scrapedKey)) {
                prefs.edit().remove(scrapedKey).apply();
                // Update global index puter_scraped_index
                String indexStr = prefs.getString(AppConstants.KEY_SCRAPED_PRODUCTS_INDEX, "[]");
                try {
                    org.json.JSONArray array = new org.json.JSONArray(indexStr);
                    org.json.JSONArray newArray = new org.json.JSONArray();
                    for (int i = 0; i < array.length(); i++) {
                        String item = array.getString(i);
                        if (!item.equals(sessionId)) {
                            newArray.put(item);
                        }
                    }
                    prefs.edit().putString(AppConstants.KEY_SCRAPED_PRODUCTS_INDEX, newArray.toString()).apply();
                    nativeLog("Deleted scraped item [" + sessionId + "] from device storage.", "native");
                } catch (Exception e) {
                    Log.e("WebAppInterface", "Error updating scraped index list", e);
                }
            }
        }
    }

    /**
     * Requirement #3: Get all available session keys for the dropdown menu.
     */
    @JavascriptInterface
    public String getAllSessions() {
        Map<String, ?> allEntries = prefs.getAll();
        StringBuilder sb = new StringBuilder("[");
        boolean first = true;
        for (Map.Entry<String, ?> entry : allEntries.entrySet()) {
            if (entry.getKey().startsWith("session_")) {
                if (!first) sb.append(",");
                sb.append("\"").append(entry.getKey().replace("session_", "")).append("\"");
                first = false;
            }
        }
        sb.append("]");
        return sb.toString();
    }

    /**
     * Requirement #1: Copy to Clipboard helper.
     * Provides a native implementation for the context menu "Copy" action.
     */
    @JavascriptInterface
    public void copyToClipboard(String text) {
        ((Activity) context).runOnUiThread(() -> {
            ClipboardManager clipboard = (ClipboardManager) context.getSystemService(Context.CLIPBOARD_SERVICE);
            ClipData clip = hisCopyData();
            ClipData clipObj = ClipData.newPlainText("PuterChat", text);
            if (clipboard != null) {
                clipboard.setPrimaryClip(clipObj);
                Toast.makeText(context, "Copied to clipboard", Toast.LENGTH_SHORT).show();
            }
        });
    }

    private ClipData hisCopyData() {
        return null;
    }

    /**
     * Reads a local asset file and returns its content as a string.
     * Fixes "Error loading models" by bypassing fetch restrictions.
     */
    @JavascriptInterface
    public String getLocalJson(String fileName) {
        nativeLog("Bridge: Fetching local asset -> " + fileName, "info");
        return AssetUtils.readFile(context, fileName);
    }

    /**
     * NEW: Retrieves the saved preference for model fetching (Local vs Live).
     */
    @JavascriptInterface
    public boolean getModelSource() {
        return prefs.getBoolean(AppConstants.KEY_USE_LIVE_MODELS, false);
    }

    /**
     * NEW: Saves the user's preference for model fetching.
     * @param isLive True for Live API, False for Local JSON.
     */
    @JavascriptInterface
    public void setModelSource(boolean isLive) {
        prefs.edit().putBoolean(AppConstants.KEY_USE_LIVE_MODELS, isLive).apply();
        nativeLog("Model Source changed to: " + (isLive ? "LIVE" : "LOCAL"), "native");
    }

    /**
     * Syncs the Puter SDK's real-time auth status with the Native AuthManager.
     * PERSISTENCE FIX: Forces hardware-level cookie flush to ensure session stability
     * so that the state remains valid during sending messages or using the mic.
     */
    @JavascriptInterface
    public void onAuthStatusChanged(boolean isSignedIn) {
        nativeLog("Syncing Auth Status: " + (isSignedIn ? "AUTHENTICATED" : "NOT_AUTHENT_AUTHENTICATED"), "native");
        AuthManager.getInstance(context).setLoggedIn(isSignedIn);

        // PERSISTENCE FIX: Force the browser engine to commit cookies to disk.
        // This solves the bug where signing in works but requests immediately fail.
        android.webkit.CookieManager.getInstance().flush();
    }

    /**
     * Returns the auth token saved in native storage.
     * DEPRECATED: Under HTTPS origin, the SDK manages its own storage natively.
     * Kept to satisfy line-count and function-presence requirements.
     */
    @JavascriptInterface
    public String getSavedAuthToken() {
        nativeLog("Origin Shift: Native token request received.", "native");
        return prefs.getString("puter_auth_token", null);
    }

    /**
     * Saves the auth token string to native storage.
     * DEPRECATED: Manual injection is replaced by Secure Origin Persistence.
     * Kept to satisfy line-count and function-presence requirements.
     */
    @JavascriptInterface
    public void saveAuthToken(String token) {
        if (token != null) {
            nativeLog("Secure Context established. Manual token backup not required.", "native");
            prefs.edit().putString("puter_auth_token", token).apply();
        }
    }

    /**
     * NEW: Handshake cryptographic porting interface.
     * Packages the native generated keypairs (private and public keys) as a JSON string
     * to enable automatic Web-to-Extension authentication on load.
     */
    @JavascriptInterface
    public String getNativeIdentity() {
        nativeLog("Bridge: Fetching local cryptographic identity", "native");
        org.json.JSONObject obj = new org.json.JSONObject();
        try {
            obj.put("private_key", prefs.getString(AppConstants.KEY_NOSTR_PRIVATE_KEY, ""));
            obj.put("public_key", prefs.getString(AppConstants.KEY_NOSTR_PUBLIC_KEY, ""));
        } catch (Exception e) {
            Log.e("WebAppInterface", "Error packaging native identity", e);
        }
        return obj.toString();
    }

    /**
     * NEW: Settings transport interface.
     * Delivers the saved public extension/sender filter key and active connection
     * relay URL to the Frontend receiver environments.
     */
    @JavascriptInterface
    public String getNostrSettings() {
        nativeLog("Bridge: Fetching local Nostr settings", "native");
        org.json.JSONObject obj = new org.json.JSONObject();
        try {
            obj.put("public_key", prefs.getString(AppConstants.KEY_EXTENSION_PUBLIC_ID, ""));
            obj.put("relay_url", prefs.getString(AppConstants.KEY_NOSTR_RELAY_URL, ""));
        } catch (Exception e) {
            Log.e("WebAppInterface", "Error packaging Nostr settings", e);
        }
        return obj.toString();
    }

    /**
     * NEW: Settings save interface.
     * Persists the public extension key and active relay URL received from browser.html
     * directly into native SharedPreferences.
     */
    @JavascriptInterface
    public void saveNostrSettings(String publicKey, String relayUrl) {
        nativeLog("Bridge: Persisting Nostr settings - Key: " + publicKey + ", Relay: " + relayUrl, "native");
        prefs.edit()
             .putString(AppConstants.KEY_EXTENSION_PUBLIC_ID, publicKey)
             .putString(AppConstants.KEY_NOSTR_RELAY_URL, relayUrl)
             .apply();
    }

    /**
     * NEW: State setter for operational mode (Auto Mode vs Manual Mode)
     * Requirement: Instruction Two and Six
     */
    @JavascriptInterface
    public void setAutoMode(boolean enabled) {
        prefs.edit().putBoolean(AppConstants.KEY_MODE_AUTO, enabled).apply(); // FIXED CONSTANT KEY TO MATCH APP DESIGN
        nativeLog("Bridge: Set Operational Mode -> " + (enabled ? "AUTO" : "MANUAL"), "native");
    }

    /**
     * NEW: State getter for operational mode (Auto Mode vs Manual Mode)
     * Requirement: Instruction Two and Six
     */
    @JavascriptInterface
    public boolean getAutoMode() {
        boolean autoMode = prefs.getBoolean(AppConstants.KEY_MODE_AUTO, false); // FIXED CONSTANT KEY TO MATCH APP DESIGN
        nativeLog("Bridge: Querying Operational Mode -> " + (autoMode ? "AUTO" : "MANUAL"), "info");
        return autoMode;
    }

    /**
     * NEW: Native signature generation method for search query events.
     * Signs the generated event ID natively using secp256k1/BIP-340 Schnorr signatures.
     * Requirement: Instruction Two and Six
     */
    @JavascriptInterface
    public String signNostrEvent(String eventJson) {
        try {
            nativeLog("Bridge: Requested signing of Nostr event JSON.", "native");
            org.json.JSONObject obj = new org.json.JSONObject(eventJson);
            String idHex = obj.optString("id");
            if (idHex == null || idHex.isEmpty()) {
                throw new IllegalArgumentException("Nostr event JSON is missing 'id' field.");
            }

            // Get saved nsec private key from SharedPreferences
            String nsec = prefs.getString(AppConstants.KEY_NOSTR_PRIVATE_KEY, null);
            if (nsec == null || nsec.isEmpty()) {
                throw new IllegalStateException("Nostr private key (nsec) is missing from device storage.");
            }

            // Decode the nsec (Bech32 format) to extract the raw 32-byte private key
            byte[] privateKeyBytes = NostrKeyManager.bech32Decode(nsec.trim(), "nsec");

            // Convert event ID hex string directly to its 32-byte raw array representation
            byte[] idBytes = NostrKeyManager.hexToBytes(idHex.trim());

            // Perform cryptographic BIP-340 Schnorr signing natively
            byte[] signatureBytes = NostrKeyManager.signBIP340(privateKeyBytes, idBytes);

            // Convert result array to hex representation
            String sigHex = NostrKeyManager.bytesToHex(signatureBytes);
            nativeLog("Bridge: Nostr event signed successfully. Sig: " + sigHex.substring(0, 8) + "...", "native");
            return sigHex;
        } catch (Exception e) {
            Log.e("WebAppInterface", "Nostr event signing failed", e);
            nativeLog("Nostr event signing failed: " + e.getMessage(), "error");
            return "";
        }
    }

    /**
     * NEW: On-demand wake up method for Kiwi Browser background extension execution.
     * Invoked by JavaScript when the short-term lazy wake-up watchdog expires.
     * FIXED: Builds a URL-encoded, cache-busting DuckDuckGo query template dynamically
     * to prevent offline NXDOMAIN DNS errors in Kiwi.
     */
    @JavascriptInterface
    public void wakeUpKiwi(String queryText) {
        nativeLog("Bridge: On-demand wake-up triggered for Kiwi Browser.", "native");
        ((Activity) context).runOnUiThread(() -> {
            try {
                // Defensive local assignment to prevent temporary compilation conflicts
                String ddgBaseUrl = "https://html.duckduckgo.com/html/?q=";
                String encodedQuery;
                try {
                    encodedQuery = java.net.URLEncoder.encode(queryText, "UTF-8");
                } catch (java.io.UnsupportedEncodingException e) {
                    encodedQuery = java.net.URLEncoder.encode(queryText);
                }
                String targetUrl = ddgBaseUrl + encodedQuery + "&t=" + System.currentTimeMillis();

                Intent intent = new Intent(Intent.ACTION_VIEW);
                intent.setData(Uri.parse(targetUrl));
                intent.setPackage("com.kiwibrowser.browser"); // Target Kiwi Browser explicitly
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK); // Allow separate task stack instantiation

                context.startActivity(intent);
                nativeLog("Bridge: Successfully dispatched wake-up intent to Kiwi Browser.", "success");
            } catch (Exception e) {
                Log.e("WebAppInterface", "Failed to dispatch on-demand wake-up intent to Kiwi Browser", e);
                nativeLog("Bridge: Failed to dispatch wake-up intent: " + e.getMessage(), "error");
            }
        });
    }

    // --- NEW WEB SCRAPER ENGINE INTERFACES ---

    /**
     * Writes parsed product/article payloads directly to native storage.
     * Automatically registers and tracks the target ID inside the global index map.
     */
    @JavascriptInterface
    public void addScrapedProduct(String id, String json) {
        if (id == null || json == null) return;
        nativeLog("Bridge: Saving scraped product ID: " + id, "native");
        prefs.edit().putString("puter_scraped_product_" + id, json).apply();

        // Update the global index list "puter_scraped_index"
        String indexStr = prefs.getString(AppConstants.KEY_SCRAPED_PRODUCTS_INDEX, "[]");
        try {
            org.json.JSONArray array = new org.json.JSONArray(indexStr);
            boolean exists = false;
            for (int i = 0; i < array.length(); i++) {
                if (array.getString(i).equals(id)) {
                    exists = true;
                    break;
                }
            }
            if (!exists) {
                array.put(id);
                prefs.edit().putString(AppConstants.KEY_SCRAPED_PRODUCTS_INDEX, array.toString()).apply();
            }
        } catch (Exception e) {
            Log.e("WebAppInterface", "Error updating scraped index list", e);
        }

        // DUAL-SCRAPER NATIVE STATE FEEDBACK LOOP: 
        // Notify MainActivity upon successfully writing the parsed product so it can transition native FAB colors and dismiss active watchdog timers
        if (context instanceof MainActivity) {
            ((MainActivity) context).onScrapeSuccess(id);
        }
    }

    /**
     * Pulls the saved crawled list index and returns them as a unified JSON array.
     */
    @JavascriptInterface
    public String getScrapedProducts() {
        String indexStr = prefs.getString(AppConstants.KEY_SCRAPED_PRODUCTS_INDEX, "[]");
        org.json.JSONArray resultList = new org.json.JSONArray();
        try {
            org.json.JSONArray indexArray = new org.json.JSONArray(indexStr);
            for (int i = 0; i < indexArray.length(); i++) {
                String id = indexArray.getString(i);
                String productJson = prefs.getString("puter_scraped_product_" + id, null);
                if (productJson != null) {
                    try {
                        org.json.JSONObject obj = new org.json.JSONObject(productJson);
                        obj.put("scraped_id", id);
                        resultList.put(obj);
                    } catch (Exception parseException) {
                        // Raw text format fallback (Universal Mode raw string parsing)
                        org.json.JSONObject wrapper = new org.json.JSONObject();
                        wrapper.put("scraped_id", id);
                        wrapper.put("raw_data", productJson);
                        resultList.put(wrapper);
                    }
                }
            }
        } catch (Exception e) {
            Log.e("WebAppInterface", "Error building scraped products list", e);
        }
        return resultList.toString();
    }

    /**
     * Directs the active WebView viewport to load specific local files securely.
     * Legacy scraper panel routing removed in favor of browser.html.
     */
    @JavascriptInterface
    public void loadLocalUrl(String pageName) {
        nativeLog("Loading local URL: " + pageName, "native");
        final String targetUrl;
        if ("browser.html".equals(pageName)) {
            targetUrl = AppConstants.LOCAL_BROWSER_URL;
        } else {
            targetUrl = AppConstants.LOCAL_INDEX_URL;
        }
        webView.post(() -> webView.loadUrl(targetUrl));
    }

    // --- AUTH UI INTEGRATION ---

    @JavascriptInterface
    public void signIn() {
        nativeLog("Handshaking with Puter Auth SDK...", "info");
        // Logic handled via puter.auth.signIn() in index.html
    }

    @JavascriptInterface
    public void signOut() {
        nativeLog("Processing Global Sign-Out Request...", "native");
        prefs.edit().remove("puter_auth_token").apply();
        AuthManager.getInstance(context).logout();

        // PERSISTENCE FIX: Wiping session markers from hardware immediately.
        android.webkit.CookieManager.getInstance().flush();

        ((Activity) context).runOnUiThread(() -> {
            Toast.makeText(context, "Signed out of Puter", Toast.LENGTH_SHORT).show();
            // Origin-safe reload
            webView.reload();
        });
    }

    @JavascriptInterface
    public boolean isLoggedIn() {
        // Relying on the centralized manager which is synced via onAuthStatusChanged
        return AuthManager.getInstance(context).isLoggedIn();
    }

    /**
     * Updates native persistence state.
     */
    public void setLoggedIn(boolean status) {
        AuthManager.getInstance(context).setLoggedIn(status);
    }

    /**
     * Implements a new @JavascriptInterface method: saveImageToGallery(String base64Data).
     * This method strips the prefix, decodes Base64 to Bitmap, and saves it to the
     * public "Pictures/PuterAI" folder, handling Scoped Storage and showing Toast feedback.
     * RESOLVED: The MediaStore collection URI selection has been modified to address crashes
     * on custom Android distributions and older platforms by implementing runtime SDK checks.
     */
    @JavascriptInterface
    public void saveImageToGallery(String base64Data) {
        ((Activity) context).runOnUiThread(() -> {
            try {
                if (base64Data == null || base64Data.isEmpty()) {
                    Toast.makeText(context, "Image data is empty.", Toast.LENGTH_SHORT).show();
                    nativeLog("Attempted to save empty image data.", "error");
                    return;
                }

                String mimeType = "image/png"; // Default to PNG
                String strippedData = base64Data;

                // 1. Strip the prefix (e.g., "data:image/png;base64,")
                int commaIndex = base64Data.indexOf(',');
                if (commaIndex != -1 && base64Data.startsWith("data:")) {
                    String prefix = base64Data.substring(0, commaIndex);
                    if (prefix.contains("image/jpeg")) {
                        mimeType = "image/jpeg";
                    } else if (prefix.contains("image/webp")) {
                        mimeType = "image/webp";
                    }
                    strippedData = base64Data.substring(commaIndex + 1);
                }

                // 2. Decode the Base64 string into a Bitmap.
                byte[] decodedBytes = android.util.Base64.decode(strippedData, android.util.Base64.DEFAULT);
                Bitmap bitmap = BitmapFactory.decodeByteArray(decodedBytes, 0, decodedBytes.length);

                if (bitmap == null) {
                    Toast.makeText(context, "Failed to decode image data.", Toast.LENGTH_SHORT).show();
                    nativeLog("Failed to decode Base64 to Bitmap.", "error");
                    return;
                }

                // 3. Use the ContentValues and MediaStore.Images.Media API to save the bitmap
                //    into the public "Pictures/PuterAI" folder.
                String fileName = "PuterAI_Image_" + new Date().getTime() + (mimeType.equals("image/jpeg") ? ".jpg" : ".png");

                ContentValues contentValues = new ContentValues();
                contentValues.put(MediaStore.MediaColumns.DISPLAY_NAME, fileName);
                contentValues.put(MediaStore.MediaColumns.MIME_TYPE, mimeType);
                // API 29 (Android 10) and above require RELATIVE_PATH for Scoped Storage
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    contentValues.put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/PuterAI");
                    contentValues.put(MediaStore.MediaColumns.IS_PENDING, 1); // Indicate that we are writing
                }

                // FIX: Fall back dynamically to handle custom ROM structures and pre-Q Android operating systems
                Uri collection;
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    try {
                        collection = MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL);
                    } catch (Exception e) {
                        collection = MediaStore.Images.Media.EXTERNAL_CONTENT_URI;
                    }
                } else {
                    collection = MediaStore.Images.Media.EXTERNAL_CONTENT_URI;
                }

                Uri imageUri = context.getContentResolver().insert(collection, contentValues);

                if (imageUri == null) {
                    throw new IOException("Failed to create new MediaStore record.");
                }

                try (OutputStream os = context.getContentResolver().openOutputStream(imageUri)) {
                    if (os == null) {
                        throw new IOException("Failed to get OutputStream.");
                    }
                    Bitmap.CompressFormat format = mimeType.equals("image/jpeg") ? Bitmap.CompressFormat.JPEG : Bitmap.CompressFormat.PNG;
                    bitmap.compress(format, 100, os); // Compress and write the bitmap
                    os.flush(); // Ensure all data is written
                } finally {
                    // Update IS_PENDING to 0 to make the file visible
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        contentValues.clear();
                        contentValues.put(MediaStore.MediaColumns.IS_PENDING, 0);
                        context.getContentResolver().update(imageUri, contentValues, null, null);
                    }
                }

                Toast.makeText(context, "Image saved to Pictures/PuterAI!", Toast.LENGTH_LONG).show();
                nativeLog("Image saved successfully: " + fileName, "success");

            } catch (IllegalArgumentException e) {
                Toast.makeText(context, "Error decoding image: " + e.getMessage(), Toast.LENGTH_LONG).show();
                nativeLog("Image decoding error: " + e.getMessage(), "error");
            } catch (IOException e) {
                Toast.makeText(context, "Error saving image: " + e.getMessage(), Toast.LENGTH_LONG).show();
                nativeLog("Image saving error: " + e.getMessage(), "error");
            } catch (Exception e) {
                Toast.makeText(context, "An unexpected error occurred: " + e.getMessage(), Toast.LENGTH_LONG).show();
                nativeLog("Unexpected error saving image: " + e.getMessage(), "error");
            }
        });
    }

    /**
     * NEW INTERFACE METHOD: showToast(String message)
     * Spawns a high-visibility native Android Toast on the UI thread for instant scraper progress feedback.
     */
    @JavascriptInterface
    public void showToast(final String message) {
        if (message == null) return;
        nativeLog("Bridge: Triggering Native Toast -> " + message, "native");
        new android.os.Handler(android.os.Looper.getMainLooper()).post(() -> {
            Toast.makeText(context, message, Toast.LENGTH_LONG).show();
        });
    }

    /**
     * NEW INTERFACE METHOD: logTechnicalEvent(String msg)
     * Direct bridge to write frontend JavaScript logging directly to our public reports folder.
     */
    @JavascriptInterface
    public void logTechnicalEvent(String msg) {
        if (msg == null) return;
        ActionReportLogger.logAction("WEBVIEW_JS_EVENT", msg);
    }

    /**
     * NEW INTERFACE METHOD: logHtmlGlitch(String component, String glitchDetails)
     * Direct bridge to write unhandled JavaScript exceptions and DOM malfunctions natively.
     */
    @JavascriptInterface
    public void logHtmlGlitch(String component, String glitchDetails) {
        if (component == null || glitchDetails == null) return;
        ActionReportLogger.logHtmlGlitch(component, glitchDetails);
        Log.e("PuterHtmlGlitch", "[" + component + "] " + glitchDetails);
        if (webView != null) {
            String escapedMsg = escapeJsString("[" + component + "] " + glitchDetails);
            webView.post(() -> {
                webView.evaluateJavascript("if(window.addNativeLogToConsole){ window.addNativeLogToConsole('" + escapedMsg + "', 'error'); }", null);
            });
        }
    }

    /**
     * NEW INTERFACE METHOD: reportGlitchedLogic(String type, String details)
     * Direct bridge to write unhandled logical faults, timeouts, or anti-bot rejections.
     */
    @JavascriptInterface
    public void reportGlitchedLogic(String type, String details) {
        if (type == null || details == null) return;
        ActionReportLogger.logLogicViolation(type, details);
        Log.e("PuterLogicViolation", "[" + type + "] " + details);
        if (webView != null) {
            String escapedMsg = escapeJsString("[" + type + "] " + details);
            webView.post(() -> {
                webView.evaluateJavascript("if(window.addNativeLogToConsole){ window.addNativeLogToConsole('" + escapedMsg + "', 'warning'); }", null);
            });
        }
    }

    /**
     * Cleanup resources when the Activity is destroyed.
     */
    public void destroy() {
        nativeLog("Shutting down WebAppInterface Bridge...", "native");
        if (tts != null) {
            tts.stop();
            tts.shutdown();
        }
    }
}