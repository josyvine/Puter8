package com.puter.unofficial;

import android.content.Context;
import android.speech.tts.TextToSpeech;
import android.util.Log;

import java.util.Locale;

/**
 * Native Android Text-To-Speech Manager.
 * Handles the speech synthesis and barge-in (interruption) logic.
 */
public class TTSManager implements TextToSpeech.OnInitListener {

    private static final String TAG = "PuterTTSManager";
    private TextToSpeech tts;
    private boolean isInitialized = false;

    public TTSManager(Context context) {
        tts = new TextToSpeech(context, this);
    }

    @Override
    public void onInit(int status) {
        if (status == TextToSpeech.SUCCESS) {
            int result = tts.setLanguage(Locale.US);
            if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
                Log.e(TAG, "Language not supported");
            } else {
                isInitialized = true;
            }
        } else {
            Log.e(TAG, "TTS Initialization failed");
        }
    }

    /**
     * Speaks the given text. 
     * If the AI is already speaking, this stops the current speech (Barge-in) 
     * and immediately begins the new text.
     */
    public void speak(String text) {
        if (isInitialized && tts != null) {
            // Barge-in Interruption: Stop whatever is currently playing
            tts.stop();
            // Queue Flush ensures it starts immediately
            tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "PuterTTS");
        }
    }

    public void stop() {
        if (tts != null) {
            tts.stop();
        }
    }

    public void shutdown() {
        if (tts != null) {
            tts.stop();
            tts.shutdown();
        }
    }
}