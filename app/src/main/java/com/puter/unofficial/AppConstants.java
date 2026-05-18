package com.puter.unofficial;

/**
 * Centralized constants for the Puter Unofficial application.
 * Manages configuration keys, URLs, and shared preference identifiers 
 * to ensure consistency across Java and JavaScript components.
 */
public class AppConstants {

    // --- SHARED PREFERENCES ---
    public static final String PREF_NAME = "PuterPrefs";
    public static final String KEY_IS_LOGGED_IN = "is_logged_in";
    public static final String KEY_CUSTOM_INSTRUCTION = "puter_custom_instruction";

    // --- PUTER URLs ---
    // The main entry point for Puter.com authentication via browser redirect.
    public static final String PUTER_LOGIN_URL = "https://puter.com/login";
    
    // The local asset path for the app frontend.
    public static final String LOCAL_INDEX_URL = "file:///android_asset/index.html";

    // --- PERMISSION REQUEST CODES ---
    public static final int REQUEST_CODE_PERMISSIONS = 100;
    public static final int FILE_CHOOSER_RESULT_CODE = 1;

    // --- BRIDGE NAMES ---
    // This MUST match the name used in JavaScript: window.AndroidInterface
    public static final String JS_BRIDGE_NAME = "AndroidInterface";

    // --- TTS CONFIGURATION ---
    public static final String TTS_UTTERANCE_ID = "PuterTTS_ID";

    // --- LOG TAGS ---
    public static final String TAG_AUTH = "PuterAuth";
    public static final String TAG_VOICE = "PuterVoice";
    public static final String TAG_WEBVIEW = "PuterWebView";

    /**
     * Private constructor to prevent instantiation of a constant utility class.
     */
    private AppConstants() {
        // No-op
    }
} 