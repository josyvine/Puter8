package com.puter.unofficial;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * Manages the persistent authentication state for Puter Unofficial.
 * This class ensures that once a user signs in via the browser, the app 
 * remembers that state across restarts using SharedPreferences.
 */
public class AuthManager {

    private static final String PREF_NAME = "PuterPrefs";
    private static final String KEY_IS_LOGGED_IN = "is_logged_in";
    private static AuthManager instance;
    private final SharedPreferences prefs;

    /**
     * Private constructor for Singleton pattern.
     * Accesses the shared preference file dedicated to Puter settings.
     */
    private AuthManager(Context context) {
        prefs = context.getApplicationContext().getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
    }

    /**
     * Gets the global instance of the AuthManager.
     * Uses synchronized block to ensure thread safety.
     */
    public static synchronized AuthManager getInstance(Context context) {
        if (instance == null) {
            instance = new AuthManager(context);
        }
        return instance;
    }

    /**
     * Saves the current authentication status.
     * This is called by the MainActivity or the Bridge when a successful 
     * login is detected or intercepted from the browser redirect.
     * 
     * @param status true if the user is authenticated, false otherwise.
     */
    public void setLoggedIn(boolean status) {
        prefs.edit().putBoolean(KEY_IS_LOGGED_IN, status).apply();
    }

    /**
     * Checks if the user is currently considered logged in.
     * The SplashActivity uses this to skip the sign-in flow, 
     * and the HTML frontend uses this to show "Sign Out".
     * 
     * @return true if status is saved as logged in.
     */
    public boolean isLoggedIn() {
        return prefs.getBoolean(KEY_IS_LOGGED_IN, false);
    }

    /**
     * Clears the authentication state.
     * Triggered when the user selects "Sign Out" from the HTML dropdown menu.
     */
    public void logout() {
        prefs.edit().putBoolean(KEY_IS_LOGGED_IN, false).apply();
    }

    /**
     * Helper to verify if a specific URL is a Puter authentication success callback.
     * This logic is used by the WebViewClient to detect when the user has 
     * finished signing in on the browser and has been redirected back.
     * 
     * @param url The URL being intercepted in the WebView.
     * @return true if the URL indicates a successful authentication.
     */
    public boolean isAuthCallback(String url) {
        if (url == null) return false;
        
        /* 
         * Puter typically redirects back to the main domain or a custom 
         * callback URL after login. We check for success markers.
         */
        return (url.contains("puter.com") && url.contains("token=")) || 
               url.contains("auth_success") || 
               url.contains("signed_in=true");
    }
}