package com.puter.unofficial;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ServiceInfo;
import android.net.Uri;
import android.os.Build;
import android.os.IBinder;
import android.util.Log;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;

import org.json.JSONArray;
import org.json.JSONObject;

import java.net.URLEncoder;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;

/**
 * QueryWatcherService: Foreground service that silently monitors a Nostr relay
 * for incoming search queries and launches Kiwi Browser in the background task stack
 * if the extension is currently dormant.
 */
public class QueryWatcherService extends Service {

    private static final String TAG = "QueryWatcherService";
    
    private SharedPreferences prefs;
    private OkHttpClient client;
    private WebSocket webSocket;
    private ScheduledExecutorService executor;
    
    private long serviceStartTimestamp;
    private boolean isRunning = false;
    private String nostrRelayUrl;
    private String hexPublicKey;

    @Override
    public void onCreate() {
        super.onCreate();
        prefs = getSharedPreferences(AppConstants.PREF_NAME, Context.MODE_PRIVATE);
        client = new OkHttpClient.Builder()
                .pingInterval(20, TimeUnit.SECONDS) // Keeps the raw socket connection alive
                .build();
        executor = Executors.newSingleThreadScheduledExecutor();
        serviceStartTimestamp = System.currentTimeMillis() / 1000;
        Log.d(TAG, "Service onCreate. Monitoring query events created after timestamp: " + serviceStartTimestamp);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (!isRunning) {
            isRunning = true;
            
            // 1. Immediately establish Foreground status to comply with Android regulations
            startServiceInForeground();
            
            // 2. Fetch connection configurations
            loadIdentityAndRelay();
            
            // 3. Initiate the background WebSocket thread loop
            startNostrMonitor();
        }
        return START_STICKY; // Request system recreation if killed
    }

    /**
     * Builds the persistent notification card and promotes the process to a Foreground Service.
     */
    private void startServiceInForeground() {
        NotificationCompat.Builder builder = new NotificationCompat.Builder(this, AppConstants.WATCHER_CHANNEL_ID)
                .setContentTitle("Puter Query Watcher")
                .setContentText("Silently monitoring background search queries...")
                .setSmallIcon(R.drawable.puter) // Fallback standard application icon
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setOngoing(true);

        Notification notification = builder.build();

        // Android 14 (API 34) requires declaring FOREGROUND_SERVICE_TYPE_DATA_SYNC in startForeground
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                    AppConstants.WATCHER_NOTIFICATION_ID, 
                    notification, 
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            );
        } else {
            startForeground(AppConstants.WATCHER_NOTIFICATION_ID, notification);
        }
    }

    /**
     * Resolves cryptographic identity parameters and target relay coordinates from SharedPreferences.
     */
    private void loadIdentityAndRelay() {
        nostrRelayUrl = prefs.getString(AppConstants.KEY_NOSTR_RELAY_URL, "wss://relay.damus.io");
        String rawPubKey = prefs.getString(AppConstants.KEY_NOSTR_PUBLIC_KEY, "");

        // Decode public key if present in Bech32 npub format, otherwise fallback to raw hex
        if (rawPubKey.startsWith("npub1")) {
            try {
                byte[] decoded = NostrKeyManager.bech32Decode(rawPubKey.trim(), "npub");
                hexPublicKey = NostrKeyManager.bytesToHex(decoded);
            } catch (Exception e) {
                Log.e(TAG, "Failed to decode Bech32 npub public key.", e);
                hexPublicKey = rawPubKey; // Fallback to raw value
            }
        } else {
            hexPublicKey = rawPubKey;
        }

        Log.d(TAG, "Configurations Loaded. Relay: " + nostrRelayUrl + " | Hex Public Key: " + hexPublicKey);
    }

    /**
     * Connects or re-connects the Nostr WebSocket monitor.
     */
    private void startNostrMonitor() {
        if (hexPublicKey == null || hexPublicKey.isEmpty()) {
            Log.w(TAG, "Aborting connection. Native identity public key is not configured yet.");
            scheduleReconnect();
            return;
        }

        Log.d(TAG, "Attempting connection to target relay: " + nostrRelayUrl);
        Request request = new Request.Builder()
                .url(nostrRelayUrl)
                .build();

        webSocket = client.newWebSocket(request, new WebSocketListener() {
            @Override
            public void onOpen(WebSocket webSocket, Response response) {
                Log.d(TAG, "WebSocket Connection Established. Registering NIP-01 Subscription.");
                sendSubscriptionRequest(webSocket);
            }

            @Override
            public void onMessage(WebSocket webSocket, String text) {
                Log.d(TAG, "Raw WebSocket Event Received: " + text);
                parseAndProcessNostrMessage(text);
            }

            @Override
            public void onClosing(WebSocket webSocket, int code, String reason) {
                Log.w(TAG, "WebSocket Connection Closing. Code: " + code + " | Reason: " + reason);
            }

            @Override
            public void onClosed(WebSocket webSocket, int code, String reason) {
                Log.w(TAG, "WebSocket Connection Closed. Code: " + code + " | Reason: " + reason);
                if (isRunning) {
                    scheduleReconnect();
                }
            }

            @Override
            public void onFailure(WebSocket webSocket, Throwable t, @Nullable Response response) {
                Log.e(TAG, "WebSocket Communication Failure: " + t.getMessage(), t);
                if (isRunning) {
                    scheduleReconnect();
                }
            }
        });
    }

    /**
     * Schedules a task to reconnect the WebSocket monitor after a safety delay.
     */
    private void scheduleReconnect() {
        if (executor != null && !executor.isShutdown()) {
            Log.d(TAG, "Scheduling WebSocket connection retry in 10 seconds...");
            executor.schedule(this::startNostrMonitor, 10, TimeUnit.SECONDS);
        }
    }

    /**
     * Dispatches subscription requirements filtering for the active user's generated queries.
     */
    private void sendSubscriptionRequest(WebSocket ws) {
        try {
            // Subscription Array Structure: ["REQ", "watcher_sub", { "kinds": [1], "authors": [pubkey], "#t": ["puter_unofficial_query"], "since": timestamp }]
            JSONArray subscription = new JSONArray();
            subscription.put("REQ");
            subscription.put("watcher_sub");

            JSONObject filter = new JSONObject();
            
            JSONArray kinds = new JSONArray();
            kinds.put(1);
            filter.put("kinds", kinds);

            JSONArray authors = new JSONArray();
            authors.put(hexPublicKey);
            filter.put("authors", authors);

            JSONArray tags = new JSONArray();
            tags.put("puter_unofficial_query");
            filter.put("#t", tags);

            filter.put("since", serviceStartTimestamp);

            subscription.put(filter);

            String subFrame = subscription.toString();
            ws.send(subFrame);
            Log.d(TAG, "Subscription Frame Delivered: " + subFrame);

        } catch (Exception e) {
            Log.e(TAG, "Failed to compile subscription JSON frame.", e);
        }
    }

    /**
     * Parses the text from incoming WebSocket messages and validates the structure.
     */
    private void parseAndProcessNostrMessage(String jsonString) {
        try {
            JSONArray rootArray = new JSONArray(jsonString);
            if (rootArray.length() >= 3 && "EVENT".equals(rootArray.getString(0))) {
                JSONObject event = rootArray.getJSONObject(2);
                
                long createdAt = event.getLong("created_at");
                String queryText = event.optString("content");

                // Validate event age strictly to avoid re-triggering historical events
                if (createdAt > serviceStartTimestamp && queryText != null && !queryText.trim().isEmpty()) {
                    Log.d(TAG, "Intercepted New Valid Search Query: " + queryText);
                    
                    // Update tracking timestamp to prevent executing duplicate triggers on socket reconnects
                    serviceStartTimestamp = createdAt;
                    
                    triggerBackgroundExploration(queryText);
                } else {
                    Log.d(TAG, "Discarded event ID: " + event.optString("id") + " (timestamp check failed or content empty)");
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Error parsing WebSocket message content: ", e);
        }
    }

    /**
     * Fires an explicit intent to spawn Kiwi Browser headlessly inside the device's task stack.
     */
    private void triggerBackgroundExploration(String queryText) {
        try {
            Log.d(TAG, "Waking up Kiwi Browser silently to run the background RAG crawl loop.");
            
            // REQUIREMENT: Target the local browser portal URL to wake up the extension cleanly.
            // Opening browser.html directly triggers the extension's background sockets to start.
            String targetUrl = AppConstants.LOCAL_BROWSER_URL;

            Intent intent = new Intent(Intent.ACTION_VIEW);
            intent.setData(Uri.parse(targetUrl));
            intent.setPackage("com.kiwibrowser.browser"); // Target Kiwi Browser package explicitly
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK); // Allow separate task stack instantiation

            // Trigger background activity activation
            startActivity(intent);
            
            // Log action natively
            ActionReportLogger.logAction("WATCHER_WAKEUP", "Explicit background intent fired for query: " + queryText);

        } catch (Exception e) {
            Log.e(TAG, "Failed to dispatch explicit wake-up Intent: ", e);
            ActionReportLogger.logError("WATCHER_INTENT_FAIL", "Error firing explicit wake-up intent: " + e.getMessage());
        }
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null; // Bound access is not utilized
    }

    @Override
    public void onDestroy() {
        Log.d(TAG, "QueryWatcherService onDestroy. Releasing active background threads and sockets.");
        isRunning = false;
        
        if (webSocket != null) {
            try {
                webSocket.close(1000, "Service Shutdown cleanly");
            } catch (Exception ignored) {}
        }
        if (executor != null) {
            executor.shutdownNow();
        }
        
        ActionReportLogger.logAction("WATCHER_SHUTDOWN", "Watcher Service terminated and threads cleanly disposed.");
        super.onDestroy();
    }
}