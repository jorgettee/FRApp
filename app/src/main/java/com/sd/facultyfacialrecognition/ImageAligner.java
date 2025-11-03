package com.sd.facultyfacialrecognition;

import android.graphics.Bitmap;
import android.graphics.Matrix;
import android.graphics.PointF;
import android.graphics.Rect;
import android.util.Log;

public class ImageAligner {
    private static final String TAG = "ImageAligner";
    private static final int TARGET_SIZE = 160;
    private static final float PADDING_FACTOR = 0.15f;

    public Bitmap alignAndCropFace(Bitmap fullBmp, Rect box, PointF leftEye, PointF rightEye) {
        if (fullBmp == null || box == null) return null;

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

        if (leftEye != null && rightEye != null) {
            try {
                float leftEyeX = leftEye.x - left;
                float leftEyeY = leftEye.y - top;
                float rightEyeX = rightEye.x - left;
                float rightEyeY = rightEye.y - top;

                float dy = rightEyeY - leftEyeY;
                float dx = rightEyeX - leftEyeX;
                float angle = (float) Math.toDegrees(Math.atan2(dy, dx));

                float centerX = croppedBmp.getWidth() / 2f;
                float centerY = croppedBmp.getHeight() / 2f;

                Matrix matrix = new Matrix();
                matrix.postRotate(angle, centerX, centerY);

                Bitmap alignedBmp = Bitmap.createBitmap(
                        croppedBmp, 0, 0, croppedBmp.getWidth(), croppedBmp.getHeight(), matrix, true);

                if (croppedBmp != alignedBmp) {
                    croppedBmp.recycle();
                }
                return alignedBmp;

            } catch (Exception e) {
                Log.e(TAG, "Alignment failed: " + e.getMessage());
                return croppedBmp;
            }
        }

        return croppedBmp;
    }
}