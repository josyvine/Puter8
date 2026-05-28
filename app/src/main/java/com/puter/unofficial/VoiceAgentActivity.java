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
 * BARGE-IN UPDATE: Gated onBeginningOfSpeech STT resets with a state tracking flag to prevent infinite loops and client-side STT errors.
 * PERFORMANCE FIXES: Shielded STT onError callbacks from processing programmatic cancellations, and added safe hardware re-initialization.
 * WEBRTC HANDOFF FIX: Releases native STT hardware lock during Live WebSocket sessions to unblock browser-level capture and Acoustic Echo Cancellation (AEC).
 * PROACTIVE HANDOFF FIX: Recommends destroying and releasing the SpeechRecognizer immediately inside processUserQuery() to bypass 100ms hardware release race conditions.
 * TIMING & LIFECYCLE FIXES: Implements layered STT teardowns, audio focus release, a 1200ms WebRTC delay buffer, and removes redundant cancellations.
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

    // State tracking variable to gate the barge-in cancellation and prevent false STT resets
    private boolean isAiSpeakingOrPlaying = false;

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
        WebAppInterface.DiagnosticLogger.log("VoiceAgentActivity onCreate: Initializing native managers. Hardware context: " + this.toString());

        // Click status text directly to reopen or show the diagnostic logs if minimized
        tvStatus.setOnClickListener(v -> {
            if (logOverlayContainer != null) {
                logOverlayContainer.setVisibility(View.VISIBLE);
                WebAppInterface.DiagnosticLogger.log("Diagnostic Log HUD revealed manually by user.");
            }
        });

        // 1. Initialize Native TTS
        WebAppInterface.DiagnosticLogger.log("VoiceAgentActivity onCreate: Initializing native TextToSpeech engine...");
        tts = new TextToSpeech(this, status -> {
            WebAppInterface.DiagnosticLogger.log("VoiceAgentActivity onCreate: TTS Initialization callback returned status=" + status);
            if (status == TextToSUCCESS()) {
                tts.setLanguage(Locale.US);
                setupTtsListener();
                
                // Only start native microphone listening if there isn't an active WebSockets Live session.
                // If a Live session is already running, the WebView's WebRTC stream holds the lock.
                WebAppInterface.DiagnosticLogger.log("VoiceAgentActivity onCreate: TTS Engine ready. Checking active Live socket state: " + WebAppInterface.isLiveSocketActive);
                startListening();
            } else {
                WebAppInterface.DiagnosticLogger.log("[FATAL ERROR] Native TTS initialization failed with code: " + status);
            }
        });

        // 2. Initialize Native STT
        WebAppInterface.DiagnosticLogger.log("VoiceAgentActivity onCreate: Constructing native RecognizerIntent.");
        recognizerIntent = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
        recognizerIntent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
        recognizerIntent.putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault());
        // Required for barge-in: allows the recognizer to process sound while speakers are active
        recognizerIntent.putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true);

        // STT instance will be dynamically constructed inside startListening()
        // to guarantee a clean hardware state.

        // 3. Setup Receiver to catch AI responses and Web Audio lifecycle triggers from WebAppInterface
        setupAiResponseReceiver();

        // UI Listeners
        fabMic.setOnClickListener(v -> {
            WebAppInterface.DiagnosticLogger.log("VoiceAgentActivity: Manual microphone FAB trigger clicked by user.");
            toggleListening();
        });
        btnClose.setOnClickListener(v -> {
            WebAppInterface.DiagnosticLogger.log("VoiceAgentActivity: Closing voice dashboard via exit button.");
            finish();
        });
    }

    private int TextToSUCCESS() {
        return TextToSpeech.SUCCESS;
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
        if (tts == null) return;
        tts.setOnUtteranceProgressListener(new UtteranceProgressListener() {
            @Override
            public void onStart(String utteranceId) {
                isAIspeaking = true;
                isAiSpeakingOrPlaying = true; // State-gate: AI native TTS playback active
                runOnUiThread(() -> tvStatus.setText("Puter is speaking..."));
                WebAppInterface.DiagnosticLogger.log("[TTS] Speaking AI response sequence initialized. UtteranceId: " + utteranceId);

                // REQUIREMENT #2: Start listening IMMEDIATELY when AI starts talking
                // This allows the user to interrupt (Barge-in) at any time.
                runOnUiThread(() -> startListening());
            }

            @Override
            public void onDone(String utteranceId) {
                isAIspeaking = false;
                isAiSpeakingOrPlaying = false; // State-gate: AI native TTS playback finished
                WebAppInterface.DiagnosticLogger.log("[TTS] Speech playback successfully finalized. UtteranceId: " + utteranceId);
                // CONTINUOUS FLOW: Re-open mic to wait for next user command
                runOnUiThread(() -> startListening());
            }

            @Override
            public void onError(String utteranceId) {
                isAIspeaking = false;
                isAiSpeakingOrPlaying = false; // State-gate: native TTS playback completed with error
                WebAppInterface.DiagnosticLogger.log("[ERROR] Native TTS playback error occurred. UtteranceId: " + utteranceId);
                runOnUiThread(() -> startListening());
            }
        });
    }

    private void setupSTTListener() {
        if (speechRecognizer == null) return;

        speechRecognizer.setRecognitionListener(new RecognitionListener() {
            @Override
            public void onReadyForSpeech(Bundle params) {
                tvStatus.setText("Listening...");
                isListening = true;
                WebAppInterface.DiagnosticLogger.log("[STT] Mic channel initialized. Awaiting user speech... [isListening: true]");
            }

            @Override
            public void onBeginningOfSpeech() {
                WebAppInterface.DiagnosticLogger.log("[STT] Voice activity / beginning of speech detected. State check -> isAiSpeakingOrPlaying: " + isAiSpeakingOrPlaying);
                
                // BARGE-IN RESOLUTION GATING: Only execute the reset and WebView stop sequence 
                // if the AI is actively speaking or playing audio chunks (Native TTS or Web Audio).
                if (isAiSpeakingOrPlaying) {
                    Log.d(TAG, "Barge-in: Voice detected during active AI playback - performing hardware reset.");
                    WebAppInterface.DiagnosticLogger.log("[BARGE_IN] Speech detected while AI speaking. Silencing outputs & triggering hardware reset...");

                    if (tts != null) {
                        tts.stop();
                    }
                    isAIspeaking = false;
                    isAiSpeakingOrPlaying = false; // Reset state tracking

                    // Broadcast intent to WebAppInterface to clear custom audio players and Live WebSocket play queues
                    Intent stopIntent = new Intent("PUTER_STOP_SPEAKING");
                    sendBroadcast(stopIntent);

                    /* 
                     * REQUIREMENT: Deep Hardware Reset
                     * Destroys and recreates the recognizer instead of just canceling it. 
                     * This prevents ERROR_CLIENT (5) ghost-locks that kill the microphone after barge-ins.
                     */
                    hardwareHandler.post(() -> {
                        WebAppInterface.DiagnosticLogger.log("[BARGE_IN] Executing physical native STT release loop.");
                        if (speechRecognizer != null) {
                            try { 
                                speechRecognizer.cancel(); 
                                WebAppInterface.DiagnosticLogger.log("[BARGE_IN] speechRecognizer.cancel() invoked.");
                            } catch (Exception e) {
                                WebAppInterface.DiagnosticLogger.log("[BARGE_IN] cancel() error: " + e.getMessage());
                            }
                            try { 
                                speechRecognizer.destroy(); 
                                WebAppInterface.DiagnosticLogger.log("[BARGE_IN] speechRecognizer.destroy() invoked.");
                            } catch (Exception e) {
                                WebAppInterface.DiagnosticLogger.log("[BARGE_IN] destroy() error: " + e.getMessage());
                            }
                            speechRecognizer = null;
                        }
                        isListening = false;
                        WebAppInterface.DiagnosticLogger.log("[BARGE_IN] STT hardware fully destroyed. Scheduling re-init with 150ms buffer.");
                        hardwareHandler.postDelayed(() -> startListening(), 150); // Buffer for hardware unlock
                    });
                } else {
                    // STT remains un-cancelled, letting transcription run cleanly
                    WebAppInterface.DiagnosticLogger.log("[STT] AI is silent. Letting user's voice transcribe normally.");
                }
            }

            @Override
            public void onRmsChanged(float rmsdB) {}

            @Override
            public void onBufferReceived(byte[] buffer) {}

            @Override
            public void onEndOfSpeech() {
                isListening = false;
                WebAppInterface.DiagnosticLogger.log("[STT] User completed utterance. onEndOfSpeech event triggered.");
            }

            @Override
            public void onError(int error) {
                // EXTREME STABILITY SHIELD: If isListening is false, the cancellation 
                // was requested programmatically. Ignore the error to bypass self-destructive loops.
                if (!isListening) {
                    WebAppInterface.DiagnosticLogger.log("[STT ERROR] Ignored intentional cancellation error. Code: " + error);
                    return;
                }

                isListening = false;
                
                // Translate the raw error code into clear diagnostic descriptions
                String errorDescription;
                boolean shouldAutoRestart = true; // Default to true to ensure Always-On capabilities

                switch (error) {
                    case SpeechRecognizer.ERROR_SPEECH_TIMEOUT:
                        errorDescription = "ERROR_SPEECH_TIMEOUT (3)";
                        break;
                    case SpeechRecognizer.ERROR_NO_MATCH:
                        errorDescription = "ERROR_NO_MATCH (7)";
                        break;
                    case SpeechRecognizer.ERROR_RECOGNIZER_BUSY:
                        errorDescription = "ERROR_RECOGNIZER_BUSY (8)";
                        break;
                    case SpeechRecognizer.ERROR_AUDIO:
                        errorDescription = "ERROR_AUDIO_RECORD (5)";
                        break;
                    case SpeechRecognizer.ERROR_CLIENT:
                        errorDescription = "ERROR_CLIENT_SIDE (5)";
                        // This error happens when cancel() is called. Restarting is mandatory.
                        break;
                    case SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS:
                        errorDescription = "ERROR_MIC_PERMISSIONS_DENIED (9)";
                        shouldAutoRestart = false; // Cannot recover from lack of permissions
                        break;
                    default:
                        errorDescription = "UNKNOWN_ERROR_CODE_" + error;
                        break;
                }

                WebAppInterface.DiagnosticLogger.log("[STT ERROR] Mic receiver reported fault: " + errorDescription);

                // REQUIREMENT: Deep Re-initialization on Errors to flush corrupted OS states
                if (shouldAutoRestart) {
                    WebAppInterface.DiagnosticLogger.log("[RECOVERY] Hard-Restarting microphone interface to clear error " + error);
                    hardwareHandler.post(() -> {
                        if (speechRecognizer != null) {
                            try { 
                                speechRecognizer.cancel(); 
                                WebAppInterface.DiagnosticLogger.log("[RECOVERY] speechRecognizer.cancel() invoked.");
                            } catch (Exception e) {}
                            try { 
                                speechRecognizer.destroy(); 
                                WebAppInterface.DiagnosticLogger.log("[RECOVERY] speechRecognizer.destroy() invoked.");
                            } catch (Exception e) {}
                            speechRecognizer = null;
                        }
                        hardwareHandler.postDelayed(() -> startListening(), 150);
                    });
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
                } else {
                    WebAppInterface.DiagnosticLogger.log("[STT] onResults received but matches list was empty or NULL.");
                }
            }

            @Override
            public void onPartialResults(Bundle partialResults) {
                ArrayList<String> matches = partialResults.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
                if (matches != null && !matches.isEmpty()) {
                    String partial = matches.get(0);
                    tvTranscript.setText(partial);
                    WebAppInterface.DiagnosticLogger.log("[STT] Partial transcription captured: \"" + partial + "\"");

                    // Live Barge-in: Stop AI as soon as user starts speaking first few words
                    if (tts != null && tts.isSpeaking() && partial.trim().length() > 0) {
                        Log.d(TAG, "Partial voice detected - silencing AI.");
                        WebAppInterface.DiagnosticLogger.log("[BARGE_IN] Partial transcription captured. Muting TTS output immediately.");
                        tts.stop();
                        isAIspeaking = false;
                        isAiSpeakingOrPlaying = false;
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
                WebAppInterface.DiagnosticLogger.log("[INTENT] setupAiResponseReceiver received action: " + action);
                
                if ("PUTER_AI_RESPONSE".equals(action)) {
                    String aiText = intent.getStringExtra("RESPONSE_TEXT");
                    if (aiText != null) {
                        WebAppInterface.DiagnosticLogger.log("[INTENT] PUTER_AI_RESPONSE extracted. Content length: " + aiText.length());
                        isAiSpeakingOrPlaying = true; // State-gate: AI native TTS output starting
                        speakAIResponse(aiText);
                    } else {
                        WebAppInterface.DiagnosticLogger.log("[INTENT WARNING] PUTER_AI_RESPONSE content extra parameter was NULL.");
                    }
                } else if ("PUTER_START_LISTENING".equals(action)) {
                    WebAppInterface.DiagnosticLogger.log("[INTENT] Received PUTER_START_LISTENING. Evaluating mic allocation...");
                    isAiSpeakingOrPlaying = false; // State-gate: AI speaking sequence completed
                    runOnUiThread(() -> {
                        // Only perform dynamic hardware resets if the WebView WebRTC capture is NOT active
                        WebAppInterface.DiagnosticLogger.log("[INTENT PUTER_START_LISTENING] checking isLiveSocketActive: " + WebAppInterface.isLiveSocketActive);
                        if (!WebAppInterface.isLiveSocketActive) {
                            if (speechRecognizer != null) {
                                try { 
                                    speechRecognizer.cancel(); 
                                    WebAppInterface.DiagnosticLogger.log("[INTENT PUTER_START_LISTENING] Cancelled existing SpeechRecognizer.");
                                } catch (Exception e) {}
                                try { 
                                    speechRecognizer.destroy(); 
                                    WebAppInterface.DiagnosticLogger.log("[INTENT PUTER_START_LISTENING] Destroyed existing SpeechRecognizer.");
                                } catch (Exception e) {}
                                speechRecognizer = null;
                            }
                            isListening = false;
                            hardwareHandler.postDelayed(() -> startListening(), 100);
                        } else {
                            // If WebSockets Live API is handling the mic, we simply transition the status back to Ready
                            WebAppInterface.DiagnosticLogger.log("[INTENT PUTER_START_LISTENING] Live WebSockets active. Bypassing native mic re-initialization.");
                            tvStatus.setText("Listening...");
                        }
                    });
                } else if ("PUTER_PAUSE_LISTENING".equals(action)) {
                    WebAppInterface.DiagnosticLogger.log("[INTENT] Received PUTER_PAUSE_LISTENING. Suspending native microphone.");
                    isAiSpeakingOrPlaying = true; // State-gate: AI Web Audio streaming active
                    runOnUiThread(() -> {
                        tvStatus.setText("Puter is speaking...");
                        // Only close the native hardware STT if WebRTC is not active (to avoid double locks)
                        WebAppInterface.DiagnosticLogger.log("[INTENT PUTER_PAUSE_LISTENING] checking isLiveSocketActive: " + WebAppInterface.isLiveSocketActive);
                        if (!WebAppInterface.isLiveSocketActive && speechRecognizer != null) {
                            try { 
                                speechRecognizer.cancel(); 
                                WebAppInterface.DiagnosticLogger.log("[INTENT PUTER_PAUSE_LISTENING] Cancelled SpeechRecognizer for pause request.");
                            } catch (Exception e) {}
                            isListening = false;
                        }
                    });
                } else if ("PUTER_LIVE_SOCKET_STATE".equals(action)) {
                    boolean active = intent.getBooleanExtra("ACTIVE", false);
                    WebAppInterface.DiagnosticLogger.log("[INTENT] Received PUTER_LIVE_SOCKET_STATE. Active: " + active);
                    runOnUiThread(() -> {
                        if (active) {
                            // Live WebSocket connection established. Completely shutdown and release the native 
                            // SpeechRecognizer with layered teardown (Fix 2) to free the hardware microphone lock.
                            WebAppInterface.DiagnosticLogger.log("[LIFECYCLE] active=true. Initiating total native SpeechRecognizer destruction...");
                            if (speechRecognizer != null) {
                                try { 
                                    speechRecognizer.stopListening(); 
                                    WebAppInterface.DiagnosticLogger.log("[LIFECYCLE] speechRecognizer.stopListening() executed.");
                                } catch (Exception ignored) {}
                                try { 
                                    speechRecognizer.cancel(); 
                                    WebAppInterface.DiagnosticLogger.log("[LIFECYCLE] speechRecognizer.cancel() executed.");
                                } catch (Exception ignored) {}
                                try { 
                                    speechRecognizer.destroy(); 
                                    WebAppInterface.DiagnosticLogger.log("[LIFECYCLE] speechRecognizer.destroy() executed.");
                                } catch (Exception ignored) {}
                                speechRecognizer = null;
                            }
                            isListening = false;
                            tvStatus.setText("Puter is speaking...");
                            WebAppInterface.DiagnosticLogger.log("[LIFECYCLE] Native SpeechRecognizer fully released for WebRTC handoff.");
                        } else {
                            // WebSocket closed. Re-initialize and restore native microphone listening
                            WebAppInterface.DiagnosticLogger.log("[LIFECYCLE] active=false. Restoring native SpeechRecognizer listening.");
                            startListening();
                        }
                    });
                } else if ("PUTER_USER_TRANSCRIPT".equals(action)) {
                    String text = intent.getStringExtra("TEXT");
                    if (text != null) {
                        WebAppInterface.DiagnosticLogger.log("[INTENT] Received PUTER_USER_TRANSCRIPT. Transcript: " + text);
                        runOnUiThread(() -> {
                            tvTranscript.setText(text);
                            tvStatus.setText("Puter is thinking...");
                        });
                    }
                }
            }
        };

        IntentFilter filter = new IntentFilter();
        filter.addAction("PUTER_AI_RESPONSE");
        filter.addAction("PUTER_START_LISTENING");
        filter.addAction("PUTER_PAUSE_LISTENING");
        filter.addAction("PUTER_LIVE_SOCKET_STATE");
        filter.addAction("PUTER_USER_TRANSCRIPT");

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(aiResponseReceiver, filter, Context.RECEIVER_EXPORTED);
        } else {
            registerReceiver(aiResponseReceiver, filter);
        }
        WebAppInterface.DiagnosticLogger.log("setupAiResponseReceiver: Receiver registered successfully.");
    }

    /**
     * Sends the user's spoken transcription text directly to the WebView client.
     * 
     * CRITICAL WEBRTC HANDOFF FIX: Completely shuts down and destroys the native SpeechRecognizer 
     * instance immediately inside processUserQuery(). 
     * TIMING UPDATE (Fix 1): Releasing audio focus and applying a 1200ms delay buffer on sending 
     * the WebView query ensures standard background speech services have fully cleared the 
     * hardware microphone lock before WebRTC begins getUserMedia() initialization.
     */
    private void processUserQuery(final String text) {
        runOnUiThread(() -> tvStatus.setText("Puter is thinking..."));
        WebAppInterface.DiagnosticLogger.log("[DISPATCH] Forwarding user query text to local web client. Text: \"" + text + "\"");
        
        // STEP 1: FULL MIC RELEASE (Fix 1 Layered Teardown)
        if (speechRecognizer != null) {
            try { 
                speechRecognizer.stopListening(); 
                WebAppInterface.DiagnosticLogger.log("[LIFECYCLE] processUserQuery: speechRecognizer.stopListening() completed.");
            } catch (Exception ignored) {}
            try { 
                speechRecognizer.cancel(); 
                WebAppInterface.DiagnosticLogger.log("[LIFECYCLE] processUserQuery: speechRecognizer.cancel() completed.");
            } catch (Exception ignored) {}
            try {
                speechRecognizer.destroy();
                WebAppInterface.DiagnosticLogger.log("[LIFECYCLE] processUserQuery: Destroyed active native SpeechRecognizer context on dispatch.");
            } catch (Exception e) {
                Log.e(TAG, "Error releasing SpeechRecognizer: " + e.getMessage());
            }
            speechRecognizer = null;
        }
        isListening = false;

        // STEP 2: RELEASE AUDIO FOCUS (Fix 1)
        try {
            android.media.AudioManager audioManager = 
                    (android.media.AudioManager) getSystemService(Context.AUDIO_SERVICE);

            if (audioManager != null) {
                audioManager.abandonAudioFocus(null);
                WebAppInterface.DiagnosticLogger.log("[AUDIO] processUserQuery: Audio focus abandoned successfully.");
            }
        } catch (Exception e) {
            Log.e(TAG, "Audio focus release failed: " + e.getMessage());
        }

        // STEP 3: WAIT FOR HARDWARE RELEASE (Fix 1 - 1200ms Delay Buffer)
        WebAppInterface.DiagnosticLogger.log("[WEBRTC] Scheduling delayed WebRTC stream dispatch with 1200ms buffer.");
        hardwareHandler.postDelayed(() -> {
            WebAppInterface.DiagnosticLogger.log("[WEBRTC] Delayed Live session dispatch started after mic release buffer. Broadcasting query.");

            Intent intent = new Intent("PUTER_VOICE_INPUT");
            intent.putExtra("QUERY", text);
            sendBroadcast(intent);
        }, 1200);
    }

    public void speakAIResponse(String response) {
        if (tts != null) {
            WebAppInterface.DiagnosticLogger.log("[TTS] Invoking speak for AI response. Length: " + response.length() + " chars.");
            // Flush ensures modern "Barge-in" interruption logic
            tts.speak(response, TextToSpeech.QUEUE_FLUSH, null, "VOICE_AGENT_ID");
        } else {
            WebAppInterface.DiagnosticLogger.log("[TTS WARNING] speakAIResponse skipped: tts engine is NULL.");
        }
    }

    /**
     * Starts listening. 
     * Requirement: Robust reset to avoid collisions with TTS audio focus.
     */
    private void startListening() {
        // Bypasses native initialization entirely if WebRTC is actively streaming 
        // the microphone inside the WebView context (to prevent hardware lock conflicts).
        WebAppInterface.DiagnosticLogger.log("startListening requested. checking active state -> isLiveSocketActive: " + WebAppInterface.isLiveSocketActive + " | isListening: " + isListening);
        if (WebAppInterface.isLiveSocketActive) {
            WebAppInterface.DiagnosticLogger.log("[STT] Bypassing native SpeechRecognizer initialization: WebView WebRTC stream is active.");
            return;
        }

        if (!isListening) {
            try {
                // Programmatic Guard: Safe re-initialization of the hardware if destroyed
                if (speechRecognizer == null) {
                    speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this);
                    setupSTTListener();
                    WebAppInterface.DiagnosticLogger.log("[STT] Re-constructed active SpeechRecognizer context.");
                }
                speechRecognizer.startListening(recognizerIntent);
                isListening = true;
                WebAppInterface.DiagnosticLogger.log("[STT] SpeechRecognizer startListening() successfully initiated.");
            } catch (Exception e) {
                Log.e(TAG, "Error starting recognizer: " + e.getMessage());
                WebAppInterface.DiagnosticLogger.log("[ERROR] Speech recognizer initialization failed: " + e.getMessage());
                isListening = false;
                
                // If the hardware failed to bind, destroy the corrupted instance and retry
                if (speechRecognizer != null) {
                    try { speechRecognizer.destroy(); } catch (Exception ex) {}
                    speechRecognizer = null;
                }
                // Retry after a short delay if hardware is locked
                WebAppInterface.DiagnosticLogger.log("[RECOVERY] Initialization failure caught. Rescheduling startListening in 500ms.");
                hardwareHandler.postDelayed(() -> startListening(), 500);
            }
        }
    }

    private void toggleListening() {
        WebAppInterface.DiagnosticLogger.log("toggleListening triggered. isListening currently: " + isListening);
        if (isListening) {
            if (speechRecognizer != null) {
                try { 
                    speechRecognizer.cancel(); 
                    WebAppInterface.DiagnosticLogger.log("toggleListening: speechRecognizer.cancel() complete.");
                } catch (Exception e) {}
                try { 
                    speechRecognizer.destroy(); 
                    WebAppInterface.DiagnosticLogger.log("toggleListening: speechRecognizer.destroy() complete.");
                } catch (Exception e) {}
                speechRecognizer = null;
            }
            isListening = false;
            WebAppInterface.DiagnosticLogger.log("[STT] Listening manually paused.");
            tvStatus.setText("Tap mic to speak");
        } else {
            if (tts != null && tts.isSpeaking()) {
                tts.stop();
                isAIspeaking = false;
                isAiSpeakingOrPlaying = false;
                WebAppInterface.DiagnosticLogger.log("toggleListening: Stopped active TTS playback prior to listing start.");
            }
            startListening();
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        WebAppInterface.DiagnosticLogger.log("VoiceAgentActivity: onPause event triggered.");
        // Prevent mic hanging in background using isolated teardown blocks (Fix 4)
        if (speechRecognizer != null) {
            WebAppInterface.DiagnosticLogger.log("onPause: Disposing native SpeechRecognizer instance...");
            try { 
                speechRecognizer.stopListening(); 
                WebAppInterface.DiagnosticLogger.log("onPause: speechRecognizer.stopListening() complete.");
            } catch (Exception ignored) {}
            try { 
                speechRecognizer.cancel(); 
                WebAppInterface.DiagnosticLogger.log("onPause: speechRecognizer.cancel() complete.");
            } catch (Exception ignored) {}
            try { 
                speechRecognizer.destroy(); 
                WebAppInterface.DiagnosticLogger.log("onPause: speechRecognizer.destroy() complete.");
            } catch (Exception ignored) {}
            speechRecognizer = null;
            isListening = false;
            WebAppInterface.DiagnosticLogger.log("onPause: STT fully released.");
        }
    }

    @Override
    protected void onDestroy() {
        WebAppInterface.DiagnosticLogger.log("VoiceAgentActivity: onDestroy event triggered. Dashboard closing.");
        // MODIFIED: Remove static telemetry hook to manage references
        WebAppInterface.DiagnosticLogger.setListener(null);

        if (aiResponseReceiver != null) {
            try {
                unregisterReceiver(aiResponseReceiver);
                WebAppInterface.DiagnosticLogger.log("onDestroy: Unregistered aiResponseReceiver.");
            } catch (Exception e) {
                Log.e(TAG, "Error unregistering receiver: " + e.getMessage());
            }
        }
        if (speechRecognizer != null) {
            WebAppInterface.DiagnosticLogger.log("onDestroy: speechRecognizer cleanup starting...");
            try { speechRecognizer.cancel(); } catch (Exception e) {}
            try { speechRecognizer.destroy(); } catch (Exception e) {}
            speechRecognizer = null;
        }
        if (tts != null) {
            WebAppInterface.DiagnosticLogger.log("onDestroy: tts engine cleanup starting...");
            tts.stop();
            tts.shutdown();
        }
        hardwareHandler.removeCallbacksAndMessages(null);
        super.onDestroy();
    }
}