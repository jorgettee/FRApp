package com.sd.facultyfacialrecognition;

import android.content.Context;
import android.content.res.AssetFileDescriptor;
import android.graphics.Bitmap;
import android.util.Log;

import org.tensorflow.lite.Interpreter;

import java.io.FileInputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;

public class FaceNet {
    private static final String TAG = "FaceNet";
    private Interpreter tflite;
    private static final int INPUT_SIZE = 160;
    private static final int EMBEDDING_SIZE = 128;

    public FaceNet(Context context, String modelPath) throws IOException {
        try {
            tflite = new Interpreter(loadModelFile(context, modelPath));
            Log.d(TAG, "FaceNet model loaded successfully from file path.");
        } catch (Exception e) {
            Log.w(TAG, "Could not load model from file, trying assets...");
            tflite = new Interpreter(loadModelFromAssets(context, "facenet.tflite"));
            Log.d(TAG, "FaceNet model loaded successfully from assets.");
        }
    }

    private MappedByteBuffer loadModelFile(Context context, String modelPath) throws IOException {
        FileInputStream fis = new FileInputStream(modelPath);
        FileChannel fc = fis.getChannel();
        long declaredLength = fc.size();
        return fc.map(FileChannel.MapMode.READ_ONLY, 0, declaredLength);
    }

    private MappedByteBuffer loadModelFromAssets(Context context, String assetName) throws IOException {
        AssetFileDescriptor fileDescriptor = context.getAssets().openFd(assetName);
        FileInputStream inputStream = new FileInputStream(fileDescriptor.getFileDescriptor());
        FileChannel fileChannel = inputStream.getChannel();
        long startOffset = fileDescriptor.getStartOffset();
        long declaredLength = fileDescriptor.getDeclaredLength();
        return fileChannel.map(FileChannel.MapMode.READ_ONLY, startOffset, declaredLength);
    }

    public float[] getEmbedding(Bitmap bitmap) {
        if (bitmap == null) {
            Log.e(TAG, "Bitmap is null");
            return null;
        }

        // Scaling (from InputImageUtils.getBitmapFromInputImage or alignAndCropFace)
        // must result in a face that can be scaled to 160x160 here
        Bitmap scaled = Bitmap.createScaledBitmap(bitmap, INPUT_SIZE, INPUT_SIZE, true);

        float[][][][] input = new float[1][INPUT_SIZE][INPUT_SIZE][3];
        for (int y = 0; y < INPUT_SIZE; y++) {
            for (int x = 0; x < INPUT_SIZE; x++) {
                int pixel = scaled.getPixel(x, y);
                float r = ((pixel >> 16) & 0xFF) / 255.0f;
                float g = ((pixel >> 8) & 0xFF) / 255.0f;
                float b = (pixel & 0xFF) / 255.0f;
                // **Pre-processing (Normalization to [-1, 1]):**
                // This must exactly match the model's training!
                input[0][y][x][0] = (r - 0.5f) * 2.0f;
                input[0][y][x][1] = (g - 0.5f) * 2.0f;
                input[0][y][x][2] = (b - 0.5f) * 2.0f;
            }
        }

        float[][] embedding = new float[1][EMBEDDING_SIZE];
        try {
            tflite.run(input, embedding);
        } catch (Exception e) {
            Log.e(TAG, "Error running inference", e);
            return null;
        }

        float[] emb = embedding[0];
        // **L2 Normalization (often required for FaceNet embeddings):**
        l2Normalize(emb);

        return emb;
    }

    private void l2Normalize(float[] emb) {
        double sum = 0.0;
        for (float v : emb) sum += v * v;
        double norm = Math.sqrt(sum);
        if (norm == 0) return;
        for (int i = 0; i < emb.length; i++) emb[i] /= norm;
    }

    public static float distance(float[] emb1, float[] emb2) {
        if (emb1 == null || emb2 == null || emb1.length != emb2.length)
            return Float.MAX_VALUE;

        // **Euclidean Distance:**
        float sum = 0f;
        for (int i = 0; i < emb1.length; i++) {
            float diff = emb1[i] - emb2[i];
            sum += diff * diff;
        }
        return (float) Math.sqrt(sum);
    }

    public void close() {
        if (tflite != null) {
            tflite.close();
            tflite = null;
        }
    }
}