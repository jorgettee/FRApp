package com.sd.facultyfacialrecognition;

import android.Manifest;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.os.Bundle;
import android.util.Log;
import android.util.Size;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.ImageAnalysis;
import androidx.camera.core.ImageProxy;
import androidx.camera.core.Preview;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.camera.view.PreviewView;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import com.google.common.util.concurrent.ListenableFuture;
import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.face.Face;
import com.google.mlkit.vision.face.FaceDetection;
import com.google.mlkit.vision.face.FaceDetector;
import com.google.mlkit.vision.face.FaceDetectorOptions;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends AppCompatActivity {
    private static final String TAG = "MainActivity";
    private static final int REQUEST_CAMERA_PERMISSION = 1001;

    private PreviewView previewView;
    private FaceOverlayView overlayView;
    private FaceNet faceNet;
    private ExecutorService cameraExecutor;

    private final Map<String, float[]> KNOWN_FACE_EMBEDDINGS = new HashMap<>();
    private static final float THRESHOLD = 1.3f;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        previewView = findViewById(R.id.previewView);
        overlayView = findViewById(R.id.faceOverlayView);
        cameraExecutor = Executors.newSingleThreadExecutor();

        try {
            faceNet = new FaceNet(this, "facenet.tflite");
            loadEmbeddingsFromAssets();
            Log.d(TAG, "FaceNet model and embeddings loaded successfully");
        } catch (Exception e) {
            Log.e(TAG, "Error initializing FaceNet or embeddings", e);
        }

        if (allPermissionsGranted()) {
            startCamera();
        } else {
            ActivityCompat.requestPermissions(
                    this,
                    new String[]{Manifest.permission.CAMERA},
                    REQUEST_CAMERA_PERMISSION
            );
        }
    }

    private boolean allPermissionsGranted() {
        return ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
                == PackageManager.PERMISSION_GRANTED;
    }

    private void startCamera() {
        ListenableFuture<ProcessCameraProvider> cameraProviderFuture = ProcessCameraProvider.getInstance(this);
        cameraProviderFuture.addListener(() -> {
            try {
                ProcessCameraProvider cameraProvider = cameraProviderFuture.get();
                bindPreviewAndAnalyzer(cameraProvider);
            } catch (ExecutionException | InterruptedException e) {
                Log.e(TAG, "Camera provider error", e);
            }
        }, ContextCompat.getMainExecutor(this));
    }

    private void bindPreviewAndAnalyzer(ProcessCameraProvider cameraProvider) {
        cameraProvider.unbindAll();

        Preview preview = new Preview.Builder().build();
        CameraSelector selector = CameraSelector.DEFAULT_FRONT_CAMERA;
        preview.setSurfaceProvider(previewView.getSurfaceProvider());

        ImageAnalysis imageAnalysis = new ImageAnalysis.Builder()
                .setTargetResolution(new Size(640, 480))
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build();

        FaceDetectorOptions options = new FaceDetectorOptions.Builder()
                .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_ACCURATE)
                .setLandmarkMode(FaceDetectorOptions.LANDMARK_MODE_NONE)
                .setClassificationMode(FaceDetectorOptions.CLASSIFICATION_MODE_NONE)
                .enableTracking()
                .build();

        FaceDetector detector = FaceDetection.getClient(options);

        imageAnalysis.setAnalyzer(cameraExecutor, image -> {
            try {
                final android.media.Image mediaImage = image.getImage();
                if (mediaImage != null) {
                    InputImage inputImage = InputImage.fromMediaImage(mediaImage, image.getImageInfo().getRotationDegrees());
                    detector.process(inputImage)
                            .addOnSuccessListener(faces -> handleFaces(faces, inputImage))
                            .addOnFailureListener(e -> Log.e(TAG, "Face detection failed", e))
                            .addOnCompleteListener(task -> image.close());
                } else {
                    image.close();
                }
            } catch (Exception e) {
                Log.e(TAG, "Analyzer error", e);
                image.close();
            }
        });

        cameraProvider.bindToLifecycle(this, selector, preview, imageAnalysis);
    }

    private void handleFaces(List<Face> faces, InputImage inputImage) {
        List<FaceOverlayView.FaceGraphic> graphics = new ArrayList<>();
        Bitmap fullBmp = InputImageUtils.getBitmapFromInputImage(this, inputImage);
        if (fullBmp == null) return;

        for (Face face : faces) {
            android.graphics.Rect box = face.getBoundingBox();

            int left = Math.max(0, box.left);
            int top = Math.max(0, box.top);
            int right = Math.min(fullBmp.getWidth(), box.right);
            int bottom = Math.min(fullBmp.getHeight(), box.bottom);

            if (right - left <= 0 || bottom - top <= 0) continue;

            Bitmap faceBmp = Bitmap.createBitmap(fullBmp, left, top, right - left, bottom - top);
            float[] emb = faceNet.getEmbedding(faceBmp);
            if (emb == null) continue;

            normalizeEmbedding(emb);

            String bestMatch = "Unknown";
            float bestDist = Float.MAX_VALUE;

            for (Map.Entry<String, float[]> entry : KNOWN_FACE_EMBEDDINGS.entrySet()) {
                float d = FaceNet.distance(emb, entry.getValue());
                if (d < bestDist) {
                    bestDist = d;
                    bestMatch = entry.getKey();
                }
            }

            if (bestDist > THRESHOLD) {
                bestMatch = "Unknown";
            }

            Log.d(TAG, "Best match: " + bestMatch + " (distance=" + bestDist + ")");
            graphics.add(new FaceOverlayView.FaceGraphic(box, bestMatch, bestDist));
        }

        overlayView.setImageSourceInfo(inputImage.getWidth(), inputImage.getHeight(), true);
        runOnUiThread(() -> overlayView.setFaces(graphics));
    }

    private void normalizeEmbedding(float[] emb) {
        float norm = 0;
        for (float v : emb) norm += v * v;
        norm = (float) Math.sqrt(norm);
        if (norm > 0) {
            for (int i = 0; i < emb.length; i++) emb[i] /= norm;
        }
    }

    private void loadEmbeddingsFromAssets() {
        try {
            InputStream is = getAssets().open("embeddings.json");
            byte[] buffer = new byte[is.available()];
            is.read(buffer);
            is.close();

            String json = new String(buffer, StandardCharsets.UTF_8);
            JSONObject obj = new JSONObject(json);

            Iterator<String> keys = obj.keys();
            while (keys.hasNext()) {
                String name = keys.next();
                JSONArray arr = obj.getJSONArray(name);
                float[] emb = new float[arr.length()];
                for (int i = 0; i < arr.length(); i++) emb[i] = (float) arr.getDouble(i);

                normalizeEmbedding(emb);
                KNOWN_FACE_EMBEDDINGS.put(name, emb);
            }

            Log.d(TAG, "Loaded " + KNOWN_FACE_EMBEDDINGS.size() + " embeddings");
        } catch (Exception e) {
            Log.e(TAG, "Failed to load embeddings.json", e);
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (cameraExecutor != null) cameraExecutor.shutdown();
        if (faceNet != null) faceNet.close();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions,
                                           @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQUEST_CAMERA_PERMISSION) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                startCamera();
            } else {
                finish();
            }
        }
    }
}