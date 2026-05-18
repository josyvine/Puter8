package com.puter.unofficial;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.util.Base64;
import android.util.Log;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;

/**
 * Utility class to handle image processing.
 * Specifically converts selected images/photos into Base64 strings
 * so they can be injected into the Puter.js frontend for vision analysis.
 */
public class ImageUtils {

    private static final String TAG = "PuterImageUtils";

    /**
     * Converts an image URI to a Base64 encoded string.
     * Includes the Data URI scheme (e.g., data:image/jpeg;base64,...)
     * 
     * @param context App context to access ContentResolver
     * @param imageUri The URI of the image from Gallery or Camera
     * @return Base64 string of the image
     */
    public static String uriToBase64(Context context, Uri imageUri) {
        try {
            InputStream inputStream = context.getContentResolver().openInputStream(imageUri);
            Bitmap bitmap = BitmapFactory.decodeStream(inputStream);
            if (inputStream != null) {
                inputStream.close();
            }
            return bitmapToBase64(bitmap);
        } catch (Exception e) {
            Log.e(TAG, "Error converting URI to Base64: " + e.getMessage());
            return null;
        }
    }

    /**
     * Helper method to compress bitmap and encode to Base64.
     */
    private static String bitmapToBase64(Bitmap bitmap) {
        ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
        
        /* 
         * Compress to JPEG with 80% quality to reduce payload size 
         * while maintaining enough detail for AI Vision models.
         */
        bitmap.compress(Bitmap.CompressFormat.JPEG, 80, outputStream);
        byte[] byteArray = outputStream.toByteArray();
        
        String base64String = Base64.encodeToString(byteArray, Base64.DEFAULT);
        
        // Cleanup memory
        bitmap.recycle();
        
        return "data:image/jpeg;base64," + base64String.replace("\n", "");
    }
}