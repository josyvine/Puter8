package com.puter.unofficial;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.speech.RecognitionListener;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageButton;
import android.widget.TextView;
import androidx.appcompat.app.AppCompatActivity;
import com.google.android.material.floatingactionbutton.FloatingActionButton;
import java.util.ArrayList;
import java.util.Locale;

/**
 * Full-screen Native Voice Agent Activity for Puter Unofficial.
 * This implementation enables a continuous, hands-free conversation loop.
 * UPDATED: Optimized for Barge-in and Always-on listening during AI speech.
 * REFINED: Fixed hardware reset logic to ensure user speech is captured during barge-in.
 * ENHANCED: Full-screen hands-free voice loop fully supports both Puter and Gemini conversational engines.
 * MODIFIED: Integrated on-screen dynamic log terminal HUD with copy-to-clipboard functionality.
 * CRITICAL FIXES: Added intent filters to support zero-click hands-free turn transitions and live Web Audio barge-ins.
 */
public class VoiceAgentActivity extends AppCompatActivity {

    private static final String TAG = "PuterVoiceAgent";

    private TextView tvStatus, tvTranscript;
    private FloatingActionButton fabMic;
    private ImageButton btnClose;

    private SpeechRecognizer speechRecognizer;
    private TextToSpeech tts;
    private Intent recognizerIntent;
    private BroadcastReceiver aiResponseReceiver;

    private boolean isListening = false;
    private boolean isAIspeaking = false;

    // Handler for managing hardware sync delays
    private final Handler hardwareHandler = new Handler(Looper.getMainLooper());

    // MODIFIED: Visual logging HUD components built dynamically to prevent resource errors
    private android.widget.LinearLayout logOverlayContainer;
    private android.widget.TextView tvLogs;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_voice_agent);

        // Initialize UI Elements
        tvStatus = findViewById(R.id.tvVoiceStatus);
        tvTranscript = findViewById(R.id.tvTranscript);
        fabMic = findViewById(R.id.fabMicControl);
        btnClose = findViewById(R.id.btnCloseVoice);

        // MODIFIED: Initialize dynamic background console logging HUD
        setupDiagnosticLogOverlay();
        WebAppInterface.DiagnosticLogger.log("VoiceAgentActivity: Immersive dashboard session initialized.");

        // Click status text directly to reopen or show the diagnostic logs if minimized
        tvStatus.setOnClickListener(v -> {
            if (logOverlayContainer != null) {
                logOverlayContainer.setVisibility(View.VISIBLE);
                WebAppInterface.DiagnosticLogger.log("Diagnostic Log HUD revealed manually by user.");
            }
        });

        // 1. Initialize Native TTS
        tts = new TextToSpeech(this, status -> {
            if (status == TextToSpeech.SUCCESS) {
                tts.setLanguage(Locale.US);
                setupTtsListener();
                // Start by listening for the user
                startListening();
            } else {
                WebAppInterface.DiagnosticLogger.log("[FATAL ERROR] Native TTS initialization failed with code: " + status);
            }
        });

        // 2. Initialize Native STT
        speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this);
        recognizerIntent = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
        recognizerIntent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
        recognizerIntent.putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault());
        // Required for barge-in: allows the recognizer to process sound while speakers are active
        recognizerIntent.putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true);

        setupSTTListener();

        // 3. Setup Receiver to catch AI responses and Web Audio lifecycle triggers from WebAppInterface
        setupAiResponseReceiver();

        // UI Listeners
        fabMic.setOnClickListener(v -> toggleListening());
        btnClose.setOnClickListener(v -> finish());
    }

    /**
     * MODIFIED: Creates the green-on-black semi-transparent monospace console.
     * Hooks up the native Copy to Clipboard and Close/Minimize buttons directly.
     */
    private void setupDiagnosticLogOverlay() {
        ViewGroup rootLayout = (ViewGroup) findViewById(android.R.id.content);
        if (rootLayout == null) return;

        logOverlayContainer = new android.widget.LinearLayout(this);
        logOverlayContainer.setOrientation(android.widget.LinearLayout.VERTICAL);
        logOverlayContainer.setBackgroundColor(android.graphics.Color.parseColor("#EC000000")); // Monolithic black
        logOverlayContainer.setPadding(24, 24, 24, 24);

        // Header controls layout
        android.widget.LinearLayout ctrlHeader = new android.widget.LinearLayout(this);
        ctrlHeader.setOrientation(android.widget.LinearLayout.HORIZONTAL);
        ctrlHeader.setPadding(0, 0, 0, 16);

        android.widget.TextView tvTitle = new android.widget.TextView(this);
        tvTitle.setText("FORENSIC LOG HUD (Tap status to reopen)");
        tvTitle.setTextColor(android.graphics.Color.parseColor("#00FF00"));
        tvTitle.setTypeface(android.graphics.Typeface.MONOSPACE);
        tvTitle.setTextSize(11);
        android.widget.LinearLayout.LayoutParams titleParams = new android.widget.LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.0f);
        ctrlHeader.addView(tvTitle, titleParams);

        // Clipboard Copy Button
        android.widget.Button btnCopy = new android.widget.Button(this);
        btnCopy.setText("Copy Log");
        btnCopy.setBackgroundColor(android.graphics.Color.parseColor("#1A73E8"));
        btnCopy.setTextColor(android.graphics.Color.WHITE);
        btnCopy.setTextSize(11);
        btnCopy.setOnClickListener(v -> {
            ClipboardManager clipboard = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            ClipData clip = ClipData.newPlainText("DiagnosticLogs", WebAppInterface.DiagnosticLogger.getLogs());
            if (clipboard != null) {
                clipboard.setPrimaryClip(clip);
                android.widget.Toast.makeText(this, "Logs Copied to Clipboard", android.widget.Toast.LENGTH_SHORT).show();
                WebAppInterface.DiagnosticLogger.log("Diagnostic logs exported successfully to system clipboard.");
            }
        });
        ctrlHeader.addView(btnCopy);

        // Dismiss Console Button
        android.widget.Button btnMinimize = new android.widget.Button(this);
        btnMinimize.setText("Close");
        btnMinimize.setBackgroundColor(android.graphics.Color.parseColor("#D93025"));
        btnMinimize.setTextColor(android.graphics.Color.WHITE);
        btnMinimize.setTextSize(11);
        btnMinimize.setOnClickListener(v -> {
            logOverlayContainer.setVisibility(View.GONE);
            WebAppInterface.DiagnosticLogger.log("Diagnostic Log HUD minimized to background.");
        });
        ctrlHeader.addView(btnMinimize);

        logOverlayContainer.addView(ctrlHeader);

        // Monospace Log Output Window
        android.widget.ScrollView scrollContainer = new android.widget.ScrollView(this);
        android.widget.LinearLayout.LayoutParams scrollParams = new android.widget.LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1.0f);

        tvLogs = new android.widget.TextView(this);
        tvLogs.setTextColor(android.graphics.Color.parseColor("#00FF00"));
        tvLogs.setTypeface(android.graphics.Typeface.MONOSPACE);
        tvLogs.setTextSize(10);
        tvLogs.setText(WebAppInterface.DiagnosticLogger.getLogs());

        scrollContainer.addView(tvLogs);
        logOverlayContainer.addView(scrollContainer, scrollParams);

        rootLayout.addView(logOverlayContainer, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        // Establish the static listener link so logs update in real-time
        WebAppInterface.DiagnosticLogger.setListener(newLog -> {
            runOnUiThread(() -> {
                if (tvLogs != null) {
                    tvLogs.setText(WebAppInterface.DiagnosticLogger.getLogs());
                }
            });
        });
    }

    private void setupTtsListener() {
        tts.setOnUtteranceProgressListener(new UtteranceProgressListener() {
            @Override
            public void onStart(String utteranceId) {
                isAIspeaking = true;
                runOnUiThread(() -> tvStatus.setText("Puter is speaking..."));
                WebAppInterface.DiagnosticLogger.log("[TTS] Speaking AI response sequence initialized.");

                // REQUIREMENT #2: Start listening IMMEDIATELY when AI starts talking
                // This allows the user to interrupt (Barge-in) at any time.
                runOnUiThread(() -> startListening());
            }

            @Override
            public void onDone(String utteranceId) {
                isAIspeaking = false;
                WebAppInterface.DiagnosticLogger.log("[TTS] Speech playback successfully finalized.");
                // CONTINUOUS FLOW: Re-open mic to wait for next user command
                runOnUiThread(() -> startListening());
            }

            @Override
            public void onError(String utteranceId) {
                isAIspeaking = false;
                WebAppInterface.DiagnosticLogger.log("[ERROR] Native TTS playback error occurred.");
                runOnUiThread(() -> startListening());
            }
        });
    }

    private void setupSTTListener() {
        speechRecognizer.setRecognitionListener(new RecognitionListener() {
            @Override
            public void onReadyForSpeech(Bundle params) {
                tvStatus.setText("Listening...");
                isListening = true;
                WebAppInterface.DiagnosticLogger.log("[STT] Mic channel initialized. Awaiting user speech...");
            }

            @Override
            public void onBeginningOfSpeech() {
                WebAppInterface.DiagnosticLogger.log("[STT] Voice activity detected.");
                // FIX: BARGE-IN COMPREHENSION RECOVERY
                // If user talks while AI is speaking (Native TTS or Web Audio), kill the AI speech immediately.
                Log.d(TAG, "Barge-in: Voice detected - performing hardware reset.");
                WebAppInterface.DiagnosticLogger.log("[BARGE_IN] Speech detected. Silencing output queues.");

                if (tts != null) {
                    tts.stop();
                }
                isAIspeaking = false;

                // Broadcast intent to WebAppInterface to clear custom audio players and Live WebSocket play queues
                Intent stopIntent = new Intent("PUTER_STOP_SPEAKING");
                sendBroadcast(stopIntent);

                /* 
                 * REQUIREMENT: Reset the audio buffer immediately.
                 * We cycle the recognizer to ensure that the AI's audio residue 
                 * is purged and the user's first words are captured clearly.
                 */
                hardwareHandler.postDelayed(() -> {
                    if (isListening) {
                        speechRecognizer.cancel();
                        isListening = false;
                        startListening();
                    }
                }, 50); // Minimal delay to allow audio focus release
            }

            @Override
            public void onRmsChanged(float rmsdB) {}

            @Override
            public void onBufferReceived(byte[] buffer) {}

            @Override
            public void onEndOfSpeech() {
                isListening = false;
                WebAppInterface.DiagnosticLogger.log("[STT] User completed utterance.");
            }

            @Override
            public void onError(int error) {
                isListening = false;
                
                // Translate the raw error code into clear diagnostic descriptions
                String errorDescription;
                boolean shouldAutoRestart = false;

                switch (error) {
                    case SpeechRecognizer.ERROR_SPEECH_TIMEOUT:
                        errorDescription = "ERROR_SPEECH_TIMEOUT";
                        shouldAutoRestart = true;
                        break;
                    case SpeechRecognizer.ERROR_NO_MATCH:
                        errorDescription = "ERROR_NO_MATCH";
                        shouldAutoRestart = true;
                        break;
                    case SpeechRecognizer.ERROR_RECOGNIZER_BUSY:
                        errorDescription = "ERROR_RECOGNIZER_BUSY";
                        shouldAutoRestart = true;
                        break;
                    case SpeechRecognizer.ERROR_AUDIO:
                        errorDescription = "ERROR_AUDIO_RECORD";
                        break;
                    case SpeechRecognizer.ERROR_CLIENT:
                        errorDescription = "ERROR_CLIENT_SIDE";
                        break;
                    case SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS:
                        errorDescription = "ERROR_MIC_PERMISSIONS_DENIED";
                        break;
                    default:
                        errorDescription = "UNKNOWN_ERROR_CODE_" + error;
                        break;
                }

                WebAppInterface.DiagnosticLogger.log("[STT ERROR] Mic receiver reported: " + errorDescription);

                // REQUIREMENT: Auto-restart listening on timeouts/silence to maintain "Always On"
                if (shouldAutoRestart) {
                    WebAppInterface.DiagnosticLogger.log("[RECOVERY] Restarting microphone interface...");
                    speechRecognizer.cancel();
                    hardwareHandler.postDelayed(() -> startListening(), 100);
                } else {
                    tvStatus.setText("Tap mic to try again");
                    Log.e(TAG, "STT Critical Error: " + error);
                }
            }

            @Override
            public void onResults(Bundle results) {
                ArrayList<String> matches = results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
                if (matches != null && !matches.isEmpty()) {
                    String userText = matches.get(0);
                    tvTranscript.setText(userText);
                    WebAppInterface.DiagnosticLogger.log("[STT] Transcription complete: \"" + userText + "\"");
                    // Send to MainActivity to hit Puter.js
                    processUserQuery(userText);
                }
            }

            @Override
            public void onPartialResults(Bundle partialResults) {
                ArrayList<String> matches = partialResults.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
                if (matches != null && !matches.isEmpty()) {
                    String partial = matches.get(0);
                    tvTranscript.setText(partial);

                    // Live Barge-in: Stop AI as soon as user starts speaking first few words
                    if (tts != null && tts.isSpeaking() && partial.trim().length() > 0) {
                        Log.d(TAG, "Partial voice detected - silencing AI.");
                        WebAppInterface.DiagnosticLogger.log("[BARGE_IN] Partial transcription captured. Muting TTS output.");
                        tts.stop();
                        isAIspeaking = false;
                    }
                }
            }

            @Override
            public void onEvent(int eventType, Bundle params) {}
        });
    }

    /**
     * Set up Intent Receivers to handle zero-click turn transitions.
     * Integrates direct communication channels with WebAppInterface.
     */
    private void setupAiResponseReceiver() {
        aiResponseReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                String action = intent.getAction();
                if ("PUTER_AI_RESPONSE".equals(action)) {
                    String aiText = intent.getStringExtra("RESPONSE_TEXT");
                    if (aiText != null) {
                        WebAppInterface.DiagnosticLogger.log("[INTENT] Received PUTER_AI_RESPONSE payload. Synthesizing.");
                        speakAIResponse(aiText);
                    }
                } else if ("PUTER_START_LISTENING".equals(action)) {
                    WebAppInterface.DiagnosticLogger.log("[INTENT] Received PUTER_START_LISTENING. Restarting native microphone.");
                    runOnUiThread(() -> {
                        if (speechRecognizer != null) {
                            speechRecognizer.cancel();
                            isListening = false;
                        }
                        startListening();
                    });
                } else if ("PUTER_PAUSE_LISTENING".equals(action)) {
                    WebAppInterface.DiagnosticLogger.log("[INTENT] Received PUTER_PAUSE_LISTENING. Suspending native microphone.");
                    runOnUiThread(() -> {
                        if (speechRecognizer != null) {
                            speechRecognizer.cancel();
                            isListening = false;
                            tvStatus.setText("Puter is speaking...");
                        }
                    });
                }
            }
        };

        IntentFilter filter = new IntentFilter();
        filter.addAction("PUTER_AI_RESPONSE");
        filter.addAction("PUTER_START_LISTENING");
        filter.addAction("PUTER_PAUSE_LISTENING");

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(aiResponseReceiver, filter, Context.RECEIVER_EXPORTED);
        } else {
            registerReceiver(aiResponseReceiver, filter);
        }
    }

    private void processUserQuery(String text) {
        runOnUiThread(() -> tvStatus.setText("Puter is thinking..."));
        WebAppInterface.DiagnosticLogger.log("[DISPATCH] Forwarding user query text to local web client.");
        // Send Broadcast to MainActivity
        Intent intent = new Intent("PUTER_VOICE_INPUT");
        intent.putExtra("QUERY", text);
        sendBroadcast(intent);
    }

    public void speakAIResponse(String response) {
        if (tts != null) {
            // Flush ensures modern "Barge-in" interruption logic
            tts.speak(response, TextToSpeech.QUEUE_FLUSH, null, "VOICE_AGENT_ID");
        }
    }

    /**
     * Starts listening. 
     * Requirement: Robust reset to avoid collisions with TTS audio focus.
     */
    private void startListening() {
        if (!isListening) {
            try {
                // Ensure no previous session is lingering
                speechRecognizer.cancel();
                speechRecognizer.startListening(recognizerIntent);
                isListening = true;
            } catch (Exception e) {
                Log.e(TAG, "Error starting recognizer: " + e.getMessage());
                WebAppInterface.DiagnosticLogger.log("[ERROR] Speech recognizer initialization failed: " + e.getMessage());
                isListening = false;
                // Retry after a short delay if hardware is locked
                hardwareHandler.postDelayed(() -> startListening(), 500);
            }
        }
    }

    private void toggleListening() {
        if (isListening) {
            speechRecognizer.stopListening();
            WebAppInterface.DiagnosticLogger.log("[STT] Listening manually paused.");
        } else {
            if (tts.isSpeaking()) {
                tts.stop();
                isAIspeaking = false;
            }
            startListening();
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        // Prevent mic hanging in background
        if (speechRecognizer != null) {
            speechRecognizer.cancel();
            isListening = false;
        }
    }

    @Override
    protected void onDestroy() {
        WebAppInterface.DiagnosticLogger.log("VoiceAgentActivity: Dashboard closed.");
        // MODIFIED: Remove static telemetry hook to manage references
        WebAppInterface.DiagnosticLogger.setListener(null);

        if (aiResponseReceiver != null) unregisterReceiver(aiResponseReceiver);
        if (speechRecognizer != null) {
            speechRecognizer.cancel();
            speechRecognizer.destroy();
        }
        if (tts != null) {
            tts.stop();
            tts.shutdown();
        }
        hardwareHandler.removeCallbacksAndMessages(null);
        super.onDestroy();
    }
}