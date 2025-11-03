package com.sd.facultyfacialrecognition;

import android.graphics.Bitmap;
import android.graphics.Matrix;
import android.graphics.PointF;
import android.graphics.Rect;
import android.util.Log;

public class ImageAligner {
    private static final String TAG = "ImageAligner";
    // Target face size for FaceNet, often 160x160. Not strictly used for cropping
    // but useful for calculating padding/target alignment.
    private static final int TARGET_SIZE = 160;

    // Padding factor to include forehead/chin/ears
    private static final float PADDING_FACTOR = 0.15f;

    /**
     * Aligns, rotates, and crops the face from the full image.
     * @param fullBmp The full image Bitmap.
     * @param box The bounding box of the face.
     * @param leftEye The position of the left eye landmark.
     * @param rightEye The position of the right eye landmark.
     * @return The aligned and cropped face Bitmap, or null if alignment fails.
     */
    public Bitmap alignAndCropFace(Bitmap fullBmp, Rect box, PointF leftEye, PointF rightEye) {
        if (fullBmp == null || box == null) return null;

        // 1. Calculate Padding
        int paddingX = (int) (box.width() * PADDING_FACTOR);
        int paddingY = (int) (box.height() * PADDING_FACTOR);

        int left = Math.max(0, box.left - paddingX);
        int top = Math.max(0, box.top - paddingY);
        int right = Math.min(fullBmp.getWidth(), box.right + paddingX);
        int bottom = Math.min(fullBmp.getHeight(), box.bottom + paddingY);

        int cropWidth = right - left;
        int cropHeight = bottom - top;

        if (cropWidth <= 0 || cropHeight <= 0) return null;

        Bitmap croppedBmp;
        try {
            croppedBmp = Bitmap.createBitmap(fullBmp, left, top, cropWidth, cropHeight);
        } catch (IllegalArgumentException e) {
            Log.e(TAG, "Crop failed: " + e.getMessage());
            return null;
        }

        // 2. Perform Alignment (Rotation)
        if (leftEye != null && rightEye != null) {
            try {
                // Adjust landmark coordinates to be relative to the cropped image
                float leftEyeX = leftEye.x - left;
                float leftEyeY = leftEye.y - top;
                float rightEyeX = rightEye.x - left;
                float rightEyeY = rightEye.y - top;

                // Calculate the angle of rotation
                float dy = rightEyeY - leftEyeY;
                float dx = rightEyeX - leftEyeX;
                float angle = (float) Math.toDegrees(Math.atan2(dy, dx));

                // Center of the cropped image for rotation
                float centerX = croppedBmp.getWidth() / 2f;
                float centerY = croppedBmp.getHeight() / 2f;

                Matrix matrix = new Matrix();
                matrix.postRotate(angle, centerX, centerY);

                Bitmap alignedBmp = Bitmap.createBitmap(
                        croppedBmp, 0, 0, croppedBmp.getWidth(), croppedBmp.getHeight(), matrix, true);

                // Recycle the intermediate cropped Bitmap
                if (croppedBmp != alignedBmp) {
                    croppedBmp.recycle();
                }
                return alignedBmp;

            } catch (Exception e) {
                Log.e(TAG, "Alignment failed: " + e.getMessage());
                // Fallback: return the unaligned but padded crop if alignment fails
                return croppedBmp;
            }
        }

        // Return the padded, unaligned crop if landmarks aren't available
        return croppedBmp;
    }
}