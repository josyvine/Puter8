package com.puter.unofficial;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.speech.tts.TextToSpeech;
import android.webkit.JavascriptInterface;
import android.webkit.WebView;
import android.widget.Toast;

import java.util.Locale;

/**
 * The core bridge class between the HTML JavaScript and Native Android code.
 * Fulfills all requirements for native TTS, barge-in, full-screen voice agent,
 * and authentication persistence.
 */
public class WebAppInterface {

    private final Context context;
    private final WebView webView;
    private TextToSpeech tts;
    private final SharedPreferences prefs;
    private VoiceManager voiceManager;
    private boolean isTtsInitialized = false;

    /**
     * Constructor for the interface.
     * @param context Activity context required for launching intents and UI updates.
     * @param webView Reference to the WebView for running JavaScript callbacks.
     */
    public WebAppInterface(Context context, WebView webView) {
        this.context = context;
        this.webView = webView;
        this.prefs = context.getSharedPreferences("PuterPrefs", Context.MODE_PRIVATE);

        // Initialize Native Android Text-To-Speech Engine (Requirement #4)
        this.tts = new TextToSpeech(context, status -> {
            if (status == TextToSpeech.SUCCESS) {
                int result = tts.setLanguage(Locale.US);
                if (result != TextToSpeech.LANG_MISSING_DATA && result != TextToSpeech.LANG_NOT_SUPPORTED) {
                    isTtsInitialized = true;
                }
            }
        });
    }

    /**
     * Links the VoiceManager for background STT operations.
     */
    public void setVoiceManager(VoiceManager voiceManager) {
        this.voiceManager = voiceManager;
    }

    // 1. NATIVE TEXT-TO-SPEECH (TTS)
    // Supports Barge-in: stops current speech and starts new text immediately.
    @JavascriptInterface
    public void speak(String text) {
        if (isTtsInitialized && tts != null) {
            // Barge-in logic: QUEUE_FLUSH clears previous speech and interrupts immediately (Requirement #4)
            tts.stop();
            tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "PuterTTS_ID");
        }
    }

    // 2. STOP TTS
    // Interrupts the AI speaker immediately.
    @JavascriptInterface
    public void stopSpeaking() {
        if (tts != null) {
            tts.stop();
        }
    }

    // 3. NATIVE SPEECH RECOGNITION (Standard)
    // Triggers background microphone for the search input.
    @JavascriptInterface
    public void startListening() {
        if (voiceManager != null) {
            stopSpeaking();
            ((Activity) context).runOnUiThread(() -> voiceManager.startListening());
        }
    }

    // 4. FULL-SCREEN VOICE AGENT (Requirement #4)
    // Launches the native full-screen Activity for continuous conversation (Gemini Live style).
    @JavascriptInterface
    public void startVoiceAgent() {
        stopSpeaking();
        Intent intent = new Intent(context, VoiceAgentActivity.class);
        context.startActivity(intent);
    }

    // 5. NATIVE FILE / CAMERA PICKER (Requirement #3)
    // Triggers the system chooser for Gallery, Camera, and Files.
    @JavascriptInterface
    public void openFilePicker() {
        ((Activity) context).runOnUiThread(() -> {
            // This is handled via onShowFileChooser in MainActivity's WebChromeClient.
            // We invoke the picker via Intent to ensure compatibility with Puter.js Base64 needs.
            Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
            intent.addCategory(Intent.CATEGORY_OPENABLE);
            intent.setType("*/*");
            ((Activity) context).startActivityForResult(Intent.createChooser(intent, "Upload to Puter"), 1);
        });
    }

    // 6. SIGN IN
    // Opens browser for Puter authentication (Requirement #10).
    @JavascriptInterface
    public void signIn() {
        Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse("https://puter.com/login"));
        context.startActivity(intent);
    }

    // 7. SIGN OUT
    // Clears persistence and refreshes the UI (Requirement #10).
    @JavascriptInterface
    public void signOut() {
        prefs.edit().putBoolean("is_logged_in", false).apply();
        ((Activity) context).runOnUiThread(() -> {
            Toast.makeText(context, "Signed out", Toast.LENGTH_SHORT).show();
            webView.reload(); 
        });
    }

    // 8. LOGIN PERSISTENCE CHECK
    @JavascriptInterface
    public boolean isLoggedIn() {
        return prefs.getBoolean("is_logged_in", false);
    }

    /**
     * Java-side method to update login status after browser redirect.
     */
    public void setLoggedIn(boolean status) {
        prefs.edit().putBoolean("is_logged_in", status).apply();
    }

    /**
     * Cleanup resources when the Activity is destroyed.
     */
    public void destroy() {
        if (tts != null) {
            tts.stop();
            tts.shutdown();
        }
    }
}