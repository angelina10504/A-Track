package com.example.a_track.utils;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Matrix;
import android.media.ExifInterface;
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
            // Step 1: Read EXIF orientation BEFORE processing
            int orientation = getExifOrientation(sourceFile.getAbsolutePath());

            // Step 2: Decode with size check
            BitmapFactory.Options options = new BitmapFactory.Options();
            options.inJustDecodeBounds = true; // Just get dimensions, don't load bitmap
            BitmapFactory.decodeFile(sourceFile.getAbsolutePath(), options);

            int originalWidth = options.outWidth;
            int originalHeight = options.outHeight;

            Log.d(TAG, "Original image: " + originalWidth + "x" + originalHeight +
                    ", Size: " + (sourceFile.length() / 1024) + " KB, Orientation: " + orientation);

            // Step 3: Calculate sample size
            options.inSampleSize = calculateInSampleSize(options, MAX_WIDTH, MAX_HEIGHT);
            options.inJustDecodeBounds = false; // Now actually load the bitmap

            Bitmap bitmap = BitmapFactory.decodeFile(sourceFile.getAbsolutePath(), options);

            if (bitmap == null) {
                Log.e(TAG, "Failed to decode bitmap");
                return false;
            }

            Log.d(TAG, "Sampled image: " + bitmap.getWidth() + "x" + bitmap.getHeight());

            // Step 4: Rotate bitmap according to EXIF orientation
            bitmap = rotateImageIfRequired(bitmap, orientation);

            Log.d(TAG, "After rotation: " + bitmap.getWidth() + "x" + bitmap.getHeight());

            // Step 5: Compress with quality adjustment to reach target size
            int quality = 90; // Start with 90% quality
            FileOutputStream fos = new FileOutputStream(targetFile);

            bitmap.compress(Bitmap.CompressFormat.JPEG, quality, fos);
            fos.close();

            // Step 6: Check if we need further compression
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

            // Step 7: Copy EXIF data to compressed file (preserves GPS, date, etc.)
            copyExifData(sourceFile.getAbsolutePath(), targetFile.getAbsolutePath());

            bitmap.recycle(); // Free memory

            Log.d(TAG, "✓ Final compressed image: " + fileSizeKB + " KB (Quality: " + quality + "%)");

            return true;

        } catch (IOException e) {
            Log.e(TAG, "Error compressing image: " + e.getMessage());
            return false;
        }
    }

    // Read EXIF orientation from image file
    private static int getExifOrientation(String imagePath) {
        try {
            ExifInterface exif = new ExifInterface(imagePath);
            return exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL);
        } catch (IOException e) {
            Log.e(TAG, "Error reading EXIF: " + e.getMessage());
            return ExifInterface.ORIENTATION_NORMAL;
        }
    }

    // Rotate bitmap based on EXIF orientation
    private static Bitmap rotateImageIfRequired(Bitmap bitmap, int orientation) {
        Matrix matrix = new Matrix();

        switch (orientation) {
            case ExifInterface.ORIENTATION_ROTATE_90:
                matrix.postRotate(90);
                break;
            case ExifInterface.ORIENTATION_ROTATE_180:
                matrix.postRotate(180);
                break;
            case ExifInterface.ORIENTATION_ROTATE_270:
                matrix.postRotate(270);
                break;
            case ExifInterface.ORIENTATION_FLIP_HORIZONTAL:
                matrix.postScale(-1, 1);
                break;
            case ExifInterface.ORIENTATION_FLIP_VERTICAL:
                matrix.postScale(1, -1);
                break;
            case ExifInterface.ORIENTATION_TRANSPOSE:
                matrix.postRotate(90);
                matrix.postScale(-1, 1);
                break;
            case ExifInterface.ORIENTATION_TRANSVERSE:
                matrix.postRotate(270);
                matrix.postScale(-1, 1);
                break;
            case ExifInterface.ORIENTATION_NORMAL:
            case ExifInterface.ORIENTATION_UNDEFINED:
            default:
                return bitmap; // No rotation needed
        }

        try {
            Bitmap rotated = Bitmap.createBitmap(bitmap, 0, 0, bitmap.getWidth(), bitmap.getHeight(), matrix, true);
            if (rotated != bitmap) {
                bitmap.recycle(); // Free original bitmap
            }
            return rotated;
        } catch (OutOfMemoryError e) {
            Log.e(TAG, "Out of memory rotating image");
            return bitmap;
        }
    }

    // Copy EXIF data from source to target (preserves GPS, date, etc.)
    private static void copyExifData(String sourcePath, String targetPath) {
        try {
            ExifInterface sourceExif = new ExifInterface(sourcePath);
            ExifInterface targetExif = new ExifInterface(targetPath);

            // Copy important EXIF tags
            String[] tags = {
                    ExifInterface.TAG_DATETIME,
                    ExifInterface.TAG_DATETIME_DIGITIZED,
                    ExifInterface.TAG_GPS_LATITUDE,
                    ExifInterface.TAG_GPS_LATITUDE_REF,
                    ExifInterface.TAG_GPS_LONGITUDE,
                    ExifInterface.TAG_GPS_LONGITUDE_REF,
                    ExifInterface.TAG_GPS_ALTITUDE,
                    ExifInterface.TAG_GPS_ALTITUDE_REF,
                    ExifInterface.TAG_MAKE,
                    ExifInterface.TAG_MODEL,
                    ExifInterface.TAG_ORIENTATION
            };

            for (String tag : tags) {
                String value = sourceExif.getAttribute(tag);
                if (value != null) {
                    targetExif.setAttribute(tag, value);
                }
            }

            targetExif.saveAttributes();
            Log.d(TAG, "✓ EXIF data copied to compressed image");

        } catch (IOException e) {
            Log.e(TAG, "Error copying EXIF data: " + e.getMessage());
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