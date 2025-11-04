package com.sd.facultyfacialrecognition;

import android.Manifest;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.util.Log;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;
import android.media.MediaScannerConnection;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.ImageCapture;
import androidx.camera.core.ImageCaptureException;
import androidx.camera.core.Preview;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.camera.view.PreviewView;
import androidx.core.content.ContextCompat;

import com.google.common.util.concurrent.ListenableFuture;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class AdminActivity extends AppCompatActivity {

    private static final String TAG = "AdminActivity";

    private Button buttonAddFaculty, buttonDeleteFaculty;
    private TextView textStatus;
    private PreviewView previewView;

    private int photoCount = 0;
    private String currentFacultyName;
    private File currentFacultyDir;
    private static final int NUM_PHOTOS_TO_CAPTURE = 100;

    private ImageCapture imageCapture;
    private ExecutorService cameraExecutor;

    private FaceAligner faceAligner;
    private FaceNet faceNet;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_admin);

        buttonAddFaculty = findViewById(R.id.buttonAddFaculty);
        buttonDeleteFaculty = findViewById(R.id.buttonDeleteFaculty);
        textStatus = findViewById(R.id.textStatus);
        previewView = findViewById(R.id.previewView);

        cameraExecutor = Executors.newSingleThreadExecutor();
        faceAligner = new FaceAligner();

        try {
            faceNet = new FaceNet(this, "facenet.tflite");
        } catch (Exception e) {
            e.printStackTrace();
            textStatus.setText("FaceNet model load failed!");
        }

        requestStoragePermissions();

        buttonAddFaculty.setOnClickListener(v -> showAddFacultyDialog());
        buttonDeleteFaculty.setOnClickListener(v -> showDeleteFacultyListDialog());
    }

    private void requestStoragePermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED
                    || checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(new String[]{
                        Manifest.permission.READ_EXTERNAL_STORAGE,
                        Manifest.permission.WRITE_EXTERNAL_STORAGE
                }, 1001);
            }
        }
    }

    private void showAddFacultyDialog() {
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle("Enter Faculty Name");

        final EditText input = new EditText(this);
        input.setInputType(android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_FLAG_CAP_WORDS);
        builder.setView(input);

        builder.setPositiveButton("Next", (dialog, which) -> {
            String facultyName = input.getText().toString().trim();
            if (!facultyName.isEmpty()) {
                currentFacultyName = facultyName;

                File picturesDir = getExternalFilesDir(Environment.DIRECTORY_PICTURES);
                currentFacultyDir = new File(picturesDir, "FacultyPhotos/" + facultyName);
                if (!currentFacultyDir.exists()) currentFacultyDir.mkdirs();

                photoCount = 0;
                textStatus.setText("Ready to capture photos for: " + facultyName);

                startCameraForFaculty();

            } else {
                textStatus.setText("Faculty name cannot be empty.");
            }
        });

        builder.setNegativeButton("Cancel", (dialog, which) -> dialog.cancel());
        builder.show();
    }

    private void showDeleteFacultyListDialog() {
        File facultyRoot = new File(getExternalFilesDir(Environment.DIRECTORY_PICTURES), "FacultyPhotos");
        File[] facultyDirs = facultyRoot.listFiles(File::isDirectory);

        if (facultyDirs != null && facultyDirs.length > 0) {
            String[] facultyNames = new String[facultyDirs.length];
            for (int i = 0; i < facultyDirs.length; i++) facultyNames[i] = facultyDirs[i].getName();

            AlertDialog.Builder builder = new AlertDialog.Builder(this);
            builder.setTitle("Select Faculty to Delete");
            builder.setItems(facultyNames, (dialog, which) -> {
                deleteRecursive(new File(facultyRoot, facultyNames[which]));
                textStatus.setText("Deleted photos for: " + facultyNames[which]);
            });
            builder.setNegativeButton("Cancel", (dialog, which) -> dialog.dismiss());
            builder.show();
        } else {
            textStatus.setText("No faculty found to delete.");
        }
    }

    private void deleteRecursive(File fileOrDirectory) {
        if (fileOrDirectory.isDirectory()) {
            for (File child : fileOrDirectory.listFiles()) deleteRecursive(child);
        }
        fileOrDirectory.delete();
    }

    private void startCameraForFaculty() {
        ListenableFuture<ProcessCameraProvider> cameraProviderFuture = ProcessCameraProvider.getInstance(this);
        cameraProviderFuture.addListener(() -> {
            try {
                ProcessCameraProvider cameraProvider = cameraProviderFuture.get();

                Preview preview = new Preview.Builder().build();
                preview.setSurfaceProvider(previewView.getSurfaceProvider());

                imageCapture = new ImageCapture.Builder().build();
                CameraSelector cameraSelector = CameraSelector.DEFAULT_FRONT_CAMERA;

                cameraProvider.unbindAll();
                cameraProvider.bindToLifecycle(this, cameraSelector, preview, imageCapture);

                textStatus.setText("Camera ready. Capturing photos automatically...");
                captureNextPhoto();

            } catch (Exception e) {
                e.printStackTrace();
                textStatus.setText("Camera init failed: " + e.getMessage());
            }
        }, ContextCompat.getMainExecutor(this));
    }

    private void captureNextPhoto() {
        if (photoCount >= NUM_PHOTOS_TO_CAPTURE) {
            textStatus.setText("All photos captured for: " + currentFacultyName);
            generateEmbeddings();
            return;
        }

        File photoFile = new File(currentFacultyDir, "photo_" + (photoCount + 1) + ".jpg");
        ImageCapture.OutputFileOptions outputOptions =
                new ImageCapture.OutputFileOptions.Builder(photoFile).build();

        imageCapture.takePicture(outputOptions, cameraExecutor, new ImageCapture.OnImageSavedCallback() {
            @Override
            public void onImageSaved(ImageCapture.OutputFileResults outputFileResults) {
                photoCount++;

                MediaScannerConnection.scanFile(
                        AdminActivity.this,
                        new String[]{photoFile.getAbsolutePath()},
                        null,
                        (path, uri) -> Log.d("MediaScanner", "Scanned " + path))
                ;

                runOnUiThread(() -> textStatus.setText("Captured photo " + photoCount + "/" + NUM_PHOTOS_TO_CAPTURE));
                captureNextPhoto();
            }

            @Override
            public void onError(ImageCaptureException exception) {
                runOnUiThread(() -> textStatus.setText("Photo capture failed: " + exception.getMessage()));
            }
        });
    }

    private void generateEmbeddings() {
        textStatus.setText("Generating embeddings...");
        new Thread(() -> {
            try {
                File facultyRoot = new File(getExternalFilesDir(Environment.DIRECTORY_PICTURES), "FacultyPhotos");
                if (!facultyRoot.exists() || !facultyRoot.isDirectory()) {
                    runOnUiThread(() -> textStatus.setText("No faculty photos found."));
                    return;
                }

                Map<String, float[]> embeddingsMap = new HashMap<>();
                File[] facultyDirs = facultyRoot.listFiles(File::isDirectory);
                if (facultyDirs != null) {
                    for (File facultyDir : facultyDirs) {
                        String facultyName = facultyDir.getName();
                        File[] photos = facultyDir.listFiles((dir, name) -> name.toLowerCase().endsWith(".jpg") || name.toLowerCase().endsWith(".png"));
                        if (photos == null || photos.length == 0) continue;

                        List<float[]> photoEmbeddings = new ArrayList<>();
                        for (File photoFile : photos) {
                            Bitmap bitmap = BitmapUtils.loadBitmap(photoFile.getAbsolutePath());
                            if (bitmap == null) continue;

                            Bitmap alignedFace = faceAligner.alignFace(bitmap);
                            if (alignedFace == null) continue;

                            float[] emb = faceNet.getEmbedding(alignedFace);
                            if (emb == null) continue;

                            normalizeEmbedding(emb);
                            photoEmbeddings.add(emb);
                        }

                        if (!photoEmbeddings.isEmpty()) {
                            float[] avg = averageEmbeddings(photoEmbeddings);
                            embeddingsMap.put(facultyName, avg);
                        }
                    }
                }

                File embeddingsDir = new File(getExternalFilesDir(Environment.DIRECTORY_PICTURES), "FacultyRecognition");
                if (!embeddingsDir.exists()) embeddingsDir.mkdirs();

                File embeddingsFile = new File(embeddingsDir, "embeddings.json");

                JSONObject obj = new JSONObject();
                for (Map.Entry<String, float[]> entry : embeddingsMap.entrySet()) {
                    JSONArray arr = new JSONArray();
                    for (float v : entry.getValue()) arr.put(v);
                    obj.put(entry.getKey(), arr);
                }

                try (FileOutputStream fos = new FileOutputStream(embeddingsFile)) {
                    fos.write(obj.toString(4).getBytes());
                }

                Log.d("AdminActivity", "Embeddings saved at: " + embeddingsFile.getAbsolutePath());
                Log.d("AdminActivity", "Embeddings JSON:\n" + obj.toString(4));

                MediaScannerConnection.scanFile(
                        AdminActivity.this,
                        new String[]{embeddingsFile.getAbsolutePath()},
                        null,
                        (path, uri) -> Log.d("MediaScanner", "Scanned " + path))
                ;

                runOnUiThread(() -> textStatus.setText("Embeddings saved: " + embeddingsFile.getAbsolutePath()));

            } catch (Exception e) {
                e.printStackTrace();
                runOnUiThread(() -> textStatus.setText("Error generating embeddings: " + e.getMessage()));
            }
        }).start();
    }


    private float[] averageEmbeddings(List<float[]> embeddingsList) {
        int length = embeddingsList.get(0).length;
        float[] avg = new float[length];
        for (float[] emb : embeddingsList) for (int i = 0; i < length; i++) avg[i] += emb[i];
        for (int i = 0; i < length; i++) avg[i] /= embeddingsList.size();
        return avg;
    }

    private void normalizeEmbedding(float[] emb) {
        float norm = 0;
        for (float v : emb) norm += v * v;
        norm = (float) Math.sqrt(norm);
        if (norm > 0) {
            for (int i = 0; i < emb.length; i++) emb[i] /= norm;
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (cameraExecutor != null) cameraExecutor.shutdown();
        if (faceNet != null) faceNet.close();
    }
}