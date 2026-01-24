package com.example.a_track.utils;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;

public class ImageCompressor {

    private static final String TAG = "ImageCompressor";
    private static final int TARGET_SIZE_KB = 200; // Target size: 200KB
    private static final int MAX_WIDTH = 720;     // Max width in pixels
    private static final int MAX_HEIGHT = 1280;     // Max height in pixels

    public static boolean compressImage(File sourceFile, File targetFile) {
        try {
            // Step 1: Decode with size check
            BitmapFactory.Options options = new BitmapFactory.Options();
            options.inJustDecodeBounds = true; // Just get dimensions, don't load bitmap
            BitmapFactory.decodeFile(sourceFile.getAbsolutePath(), options);

            int originalWidth = options.outWidth;
            int originalHeight = options.outHeight;

            Log.d(TAG, "Original image: " + originalWidth + "x" + originalHeight +
                    ", Size: " + (sourceFile.length() / 1024) + " KB");

            // Step 2: Calculate sample size
            options.inSampleSize = calculateInSampleSize(options, MAX_WIDTH, MAX_HEIGHT);
            options.inJustDecodeBounds = false; // Now actually load the bitmap

            Bitmap bitmap = BitmapFactory.decodeFile(sourceFile.getAbsolutePath(), options);

            if (bitmap == null) {
                Log.e(TAG, "Failed to decode bitmap");
                return false;
            }

            Log.d(TAG, "Sampled image: " + bitmap.getWidth() + "x" + bitmap.getHeight());

            // Step 3: Compress with quality adjustment to reach target size
            int quality = 90; // Start with 90% quality
            FileOutputStream fos = new FileOutputStream(targetFile);

            bitmap.compress(Bitmap.CompressFormat.JPEG, quality, fos);
            fos.close();

            // Step 4: Check if we need further compression
            long fileSizeKB = targetFile.length() / 1024;

            while (fileSizeKB > TARGET_SIZE_KB && quality > 10) {
                // Reduce quality and try again
                quality -= 10;

                fos = new FileOutputStream(targetFile);
                bitmap.compress(Bitmap.CompressFormat.JPEG, quality, fos);
                fos.close();

                fileSizeKB = targetFile.length() / 1024;
                Log.d(TAG, "Compressed with quality " + quality + "% → " + fileSizeKB + " KB");
            }

            bitmap.recycle(); // Free memory

            Log.d(TAG, "✓ Final compressed image: " + fileSizeKB + " KB (Quality: " + quality + "%)");

            return true;

        } catch (IOException e) {
            Log.e(TAG, "Error compressing image: " + e.getMessage());
            return false;
        }
    }

    private static int calculateInSampleSize(BitmapFactory.Options options,
                                             int reqWidth, int reqHeight) {
        final int height = options.outHeight;
        final int width = options.outWidth;
        int inSampleSize = 1;

        if (height > reqHeight || width > reqWidth) {
            final int halfHeight = height / 2;
            final int halfWidth = width / 2;

            // Calculate the largest inSampleSize that keeps dimensions larger than requested
            while ((halfHeight / inSampleSize) >= reqHeight &&
                    (halfWidth / inSampleSize) >= reqWidth) {
                inSampleSize *= 2;
            }
        }

        return inSampleSize;
    }

    public static String getReadableFileSize(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return (bytes / 1024) + " KB";
        return (bytes / (1024 * 1024)) + " MB";
    }
}