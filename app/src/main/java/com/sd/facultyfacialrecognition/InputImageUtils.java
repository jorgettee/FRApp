package com.sd.facultyfacialrecognition;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.ImageFormat;
import android.graphics.Matrix;
import android.media.Image;
import android.util.Log;

import com.google.mlkit.vision.common.InputImage;

import java.nio.ByteBuffer;

public class InputImageUtils {

    public static Bitmap getBitmapFromInputImage(Context context, InputImage inputImage) {
        try {
            Bitmap bmp = inputImage.getBitmapInternal();
            if (bmp != null) return bmp;
        } catch (Exception ignored) {}

        try {
            Image mediaImage = inputImage.getMediaImage();
            if (mediaImage == null) return null;

            YuvToRgbConverter converter = new YuvToRgbConverter(context);
            Bitmap bitmap = Bitmap.createBitmap(
                    mediaImage.getWidth(), mediaImage.getHeight(), Bitmap.Config.ARGB_8888);
            converter.yuvToRgb(mediaImage, bitmap);

            Matrix matrix = new Matrix();
            matrix.postRotate(inputImage.getRotationDegrees());
            Bitmap rotated = Bitmap.createBitmap(bitmap, 0, 0,
                    bitmap.getWidth(), bitmap.getHeight(), matrix, true);

            return rotated;
        } catch (Exception e) {
            Log.e("InputImageUtils", "Error converting InputImage to Bitmap", e);
            return null;
        }
    }
}