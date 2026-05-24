package com.puter.unofficial;

import android.app.Application;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.os.Build;
import android.util.Log;

import java.io.PrintWriter;
import java.io.StringWriter;

/**
 * Global Application class for Puter Unofficial.
 * Initializes necessary notification channels for background voice/chat services.
 * UPDATED: Integrated local ActionReportLogger and UIHangDetector watchdog.
 * UPDATED: Implemented a robust global uncaught exception handler to capture all crash telemetry.
 * UPDATED: Registered a low-importance notification channel specifically for the QueryWatcherService.
 */
public class PuterApplication extends Application {

    // Unique ID for the AI voice and interaction notification channel
    public static final String CHANNEL_ID = "puter_voice_chat_channel";
    private static final String TAG = "PuterApplication";

    @Override
    public void onCreate() {
        super.onCreate();

        // 1. Initialize Total Surveillance Asynchronous Logging Engine
        // Creates the public "puter report" directory under /Documents
        ActionReportLogger.init(this);
        ActionReportLogger.logAction("APP_STARTUP", "Application onCreate initiated.");

        // 2. Start UI Hang Watchdog
        // Monitors Main Looper queues for delays, rendering blocks, and thread freezes
        UIHangDetector.startWatchdog();

        // 3. Register the Global Exception Interceptor
        // Captures any unhandled logical or system crashes in background logs
        setupGlobalExceptionHandler();

        // 4. Initialize the notification channel required for Foreground Services
        createPuterNotificationChannel();

        ActionReportLogger.logAction("APP_READY", "Puter Unofficial Application successfully initialized.");
    }

    /**
     * Intercepts all uncaught application-level exceptions.
     * Extracts the complete stack trace and flushes it to the daily public log file before exit.
     */
    private void setupGlobalExceptionHandler() {
        final Thread.UncaughtExceptionHandler defaultHandler = Thread.getDefaultUncaughtExceptionHandler();
        
        Thread.setDefaultUncaughtExceptionHandler(new Thread.UncaughtExceptionHandler() {
            @Override
            public void uncaughtException(Thread thread, Throwable throwable) {
                // Extract the detailed stack trace to a string
                StringWriter sw = new StringWriter();
                PrintWriter pw = new PrintWriter(sw);
                throwable.printStackTrace(pw);
                String stackTrace = sw.toString();

                Log.e(TAG, "CRITICAL PROCESS FAILURE CAUGHT: " + stackTrace);

                // --- FORENSIC SURVEILLANCE LOG ---
                // Silently write the crash dump to our public .txt log file
                ActionReportLogger.logError("UNCAUGHT_EXCEPTION", "Process Crashed. Thread: " + thread.getName() + "\nStack Trace:\n" + stackTrace);

                // Give the executor thread a brief moment to finish writing to disk before process termination
                try {
                    Thread.sleep(500);
                } catch (InterruptedException ignored) {}

                // Delegate back to the default OS handler to complete the crash gracefully
                if (defaultHandler != null) {
                    defaultHandler.uncaughtException(thread, throwable);
                } else {
                    android.os.Process.killProcess(android.os.Process.myPid());
                    System.exit(10);
                }
            }
        });
    }

    /**
     * Creates Notification Channels for Puter AI interactions and background query watching.
     * Required for Android 8.0 (API 26) and above.
     */
    private void createPuterNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationManager notificationManager = getSystemService(NotificationManager.class);
            if (notificationManager == null) {
                return;
            }

            // 1. Puter AI Agent Channel (Voice and interactions)
            // User-visible name for the channel (shown in system settings)
            CharSequence name = "Puter AI Agent";
            
            // Description of what this channel does
            String description = "Ensures Puter AI voice interactions remain active.";
            
            /* 
             * IMPORTANCE_LOW: The notification is shown in the tray 
             * but does not make an intrusive sound or pop up.
             */
            int importance = NotificationManager.IMPORTANCE_LOW;
            
            NotificationChannel channel = new NotificationChannel(CHANNEL_ID, name, importance);
            channel.setDescription(description);
            notificationManager.createNotificationChannel(channel);

            // 2. NEW: Background Query Watcher Channel
            // User-visible name for the watcher channel (retrieved from AppConstants)
            CharSequence watcherChannelName = AppConstants.WATCHER_CHANNEL_NAME;
            
            // Description of what this channel does
            String watcherDescription = "Silently monitors background Nostr search queries.";
            
            /*
             * IMPORTANCE_LOW: Ensures the persistent background notification card
             * displays quietly in the tray without making intrusive system sounds.
             */
            int watcherImportance = NotificationManager.IMPORTANCE_LOW;

            NotificationChannel watcherChannel = new NotificationChannel(
                    AppConstants.WATCHER_CHANNEL_ID,
                    watcherChannelName,
                    watcherImportance
            );
            watcherChannel.setDescription(watcherDescription);
            notificationManager.createNotificationChannel(watcherChannel);
        }
    }
}