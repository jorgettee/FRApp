package com.sd.facultyfacialrecognition;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.util.Log;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

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

import com.google.android.gms.auth.api.signin.*;
import com.google.android.gms.common.api.ApiException;
import com.google.android.gms.common.api.Scope;
import com.google.android.gms.tasks.Task;
import com.google.api.client.googleapis.extensions.android.gms.auth.GoogleAccountCredential;
import com.google.api.services.drive.DriveScopes;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class AdminActivity extends AppCompatActivity {

    private static final int REQUEST_CODE_SIGN_IN = 2001;
    private static final int REQUEST_CODE_PICK_IMAGES = 3001;


    private Button buttonAddFaculty, buttonDeleteFaculty, buttonImportDrive, buttonGenerateEmbeddings;
    private TextView textStatus;
    private PreviewView previewView;

    private int photoCount = 0;
    private String currentFacultyName;
    private File currentFacultyDir;
    private static final int NUM_PHOTOS_TO_CAPTURE = 50;
    private static final long CAPTURE_INTERVAL_MS = 1;

    private ImageCapture imageCapture;
    private ExecutorService cameraExecutor;

    private FaceAligner faceAligner;
    private FaceNet faceNet;

    // Google Drive
    private GoogleSignInClient googleSignInClient;
    private com.google.api.services.drive.Drive googleDriveService;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_admin);

        buttonAddFaculty = findViewById(R.id.buttonAddFaculty);
        buttonDeleteFaculty = findViewById(R.id.buttonDeleteFaculty);
        buttonImportDrive = findViewById(R.id.buttonImportDrive);
        buttonGenerateEmbeddings = findViewById(R.id.buttonGenerateEmbeddings);
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
        buttonImportDrive.setOnClickListener(v -> promptFacultyNameForDriveImport());
        buttonGenerateEmbeddings.setOnClickListener(v -> generateEmbeddings());
    }

    // -------------------- Storage Permissions --------------------
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

    // -------------------- Add Faculty --------------------
    private void showAddFacultyDialog() {
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle("Enter Faculty Name");

        final EditText input = new EditText(this);
        input.setInputType(android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_FLAG_CAP_WORDS);
        builder.setView(input);

        builder.setPositiveButton("Next", (dialog, which) -> {
            String facultyName = input.getText().toString().trim();
            if (facultyName.isEmpty()) {
                textStatus.setText("Faculty name cannot be empty.");
                return;
            }
            startNewFacultyRegistration(facultyName);
        });

        builder.setNegativeButton("Cancel", (dialog, which) -> dialog.cancel());
        builder.show();
    }

    private void startNewFacultyRegistration(String facultyName) {
        currentFacultyName = facultyName;

        File picturesDir = getExternalFilesDir(Environment.DIRECTORY_PICTURES);
        currentFacultyDir = new File(picturesDir, "FacultyPhotos/" + facultyName);
        if (!currentFacultyDir.exists()) currentFacultyDir.mkdirs();

        photoCount = 0;
        textStatus.setText("Ready to capture photos for: " + facultyName);
        startCameraForFaculty();
    }

    private void showDeleteFacultyListDialog() {
        File facultyRoot = new File(getExternalFilesDir(Environment.DIRECTORY_PICTURES), "FacultyPhotos");
        File[] facultyDirs = facultyRoot.listFiles(File::isDirectory);

        if (facultyDirs != null && facultyDirs.length > 0) {
            String[] facultyNames = Arrays.stream(facultyDirs).map(File::getName).toArray(String[]::new);
            new AlertDialog.Builder(this)
                    .setTitle("Select Faculty to Delete")
                    .setItems(facultyNames, (dialog, which) -> {
                        deleteRecursive(new File(facultyRoot, facultyNames[which]));
                        textStatus.setText("Deleted photos for: " + facultyNames[which]);
                        Toast.makeText(this, "Regenerating embeddings...", Toast.LENGTH_SHORT).show();
                    })
                    .setNegativeButton("Cancel", (dialog, which) -> dialog.dismiss())
                    .show();
        } else {
            textStatus.setText("No faculty found to delete.");
        }
    }

    private void deleteRecursive(File fileOrDirectory) {
        if (fileOrDirectory.isDirectory())
            for (File child : Objects.requireNonNull(fileOrDirectory.listFiles()))
                deleteRecursive(child);
        fileOrDirectory.delete();
    }

    // -------------------- CameraX --------------------
    private void startCameraForFaculty() {
        com.google.common.util.concurrent.ListenableFuture<ProcessCameraProvider> cameraProviderFuture =
                ProcessCameraProvider.getInstance(this);
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
                textStatus.setText("Camera init failed: " + e.getMessage());
            }
        }, ContextCompat.getMainExecutor(this));
    }

    private void captureNextPhoto() {
        if (photoCount >= NUM_PHOTOS_TO_CAPTURE) {
            textStatus.setText("All photos captured for: " + currentFacultyName);
            Toast.makeText(this, "Photos ready. Press 'Generate Embeddings' to continue.", Toast.LENGTH_SHORT).show();
            return;
        }

        File photoFile = new File(currentFacultyDir, "photo_" + (photoCount + 1) + ".jpg");
        ImageCapture.OutputFileOptions outputOptions =
                new ImageCapture.OutputFileOptions.Builder(photoFile).build();

        imageCapture.takePicture(outputOptions, cameraExecutor, new ImageCapture.OnImageSavedCallback() {
            @Override
            public void onImageSaved(ImageCapture.OutputFileResults results) {
                photoCount++;
                runOnUiThread(() -> textStatus.setText("Captured photo " + photoCount + "/" + NUM_PHOTOS_TO_CAPTURE));
                new android.os.Handler(getMainLooper()).postDelayed(AdminActivity.this::captureNextPhoto, CAPTURE_INTERVAL_MS);
            }

            @Override
            public void onError(ImageCaptureException exception) {
                runOnUiThread(() -> textStatus.setText("Capture failed: " + exception.getMessage()));
            }
        });
    }

    // -------------------- Google Drive --------------------
    private void promptFacultyNameForDriveImport() {
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle("Import Faculty Photos from Drive");

        final EditText input = new EditText(this);
        input.setHint("Enter Faculty Name");
        builder.setView(input);

        builder.setPositiveButton("Next", (dialog, which) -> {
            currentFacultyName = input.getText().toString().trim();
            if (currentFacultyName.isEmpty()) {
                Toast.makeText(this, "Faculty name cannot be empty.", Toast.LENGTH_SHORT).show();
                return;
            }

            requestDriveSignIn(); // sign-in before picking
        });

        builder.setNegativeButton("Cancel", (dialog, which) -> dialog.dismiss());
        builder.show();
    }


    private void openDrivePicker() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("image/*");
        intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true); // allow multiple selection
        startActivityForResult(intent, REQUEST_CODE_PICK_IMAGES);
    }

    private void requestDriveSignIn() {
        GoogleSignInOptions signInOptions = new GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
                .requestEmail()
                .requestScopes(new Scope(DriveScopes.DRIVE_READONLY))
                .build();

        googleSignInClient = GoogleSignIn.getClient(this, signInOptions);

        // Sign out first to allow account selection each time
        googleSignInClient.signOut().addOnCompleteListener(task -> {
            startActivityForResult(googleSignInClient.getSignInIntent(), REQUEST_CODE_SIGN_IN);
        });
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);

        if (requestCode == REQUEST_CODE_SIGN_IN && resultCode == RESULT_OK) {
            Task<GoogleSignInAccount> task = GoogleSignIn.getSignedInAccountFromIntent(data);
            try {
                GoogleSignInAccount account = task.getResult(ApiException.class);
                initializeDriveService(account); // will open picker automatically
            } catch (ApiException e) {
                e.printStackTrace();
                textStatus.setText("Drive sign-in failed: " + e.getMessage());
            }
        }
        else if (requestCode == REQUEST_CODE_PICK_IMAGES && resultCode == RESULT_OK) {
            if (data == null) return;

            File facultyDir = new File(getExternalFilesDir(Environment.DIRECTORY_PICTURES),
                    "FacultyPhotos/" + currentFacultyName);
            if (!facultyDir.exists()) facultyDir.mkdirs();

            try {
                int imported = 0;

                // Multiple images
                if (data.getClipData() != null) {
                    for (int i = 0; i < data.getClipData().getItemCount(); i++) {
                        Uri uri = data.getClipData().getItemAt(i).getUri();
                        copyUriToFile(uri, facultyDir, ++imported);
                    }
                }
                // Single image
                else if (data.getData() != null) {
                    Uri uri = data.getData();
                    copyUriToFile(uri, facultyDir, 1);
                    imported = 1;
                }

                final int finalImported = imported;
                runOnUiThread(() -> {
                    textStatus.setText("Imported " + finalImported + " photo(s)! Press 'Generate Embeddings' to continue.");
                    Toast.makeText(this, "Photos ready.", Toast.LENGTH_SHORT).show();
                });

                // Auto sign-out after importing
                if (googleSignInClient != null) {
                    googleSignInClient.signOut();
                }

            } catch (Exception e) {
                e.printStackTrace();
                textStatus.setText("Import failed: " + e.getMessage());
            }
        }
    }


    private void copyUriToFile(Uri uri, File facultyDir, int count) throws Exception {
        InputStream inputStream = getContentResolver().openInputStream(uri);
        File outFile = new File(facultyDir, "photo_" + count + ".jpg");
        OutputStream outputStream = new FileOutputStream(outFile);

        byte[] buffer = new byte[4096];
        int bytesRead;
        while ((bytesRead = inputStream.read(buffer)) != -1) {
            outputStream.write(buffer, 0, bytesRead);
        }

        inputStream.close();
        outputStream.close();
    }

    private void initializeDriveService(GoogleSignInAccount account) {
        // No need to pre-fetch files. Just open picker
        openDrivePicker();
    }

    private void importImagesFromDrive() {
        new Thread(() -> {
            try {
                com.google.api.services.drive.model.FileList result = googleDriveService.files().list()
                        .setQ("mimeType contains 'image/' and trashed=false")
                        .setFields("files(id, name)")
                        .execute();

                List<com.google.api.services.drive.model.File> files = result.getFiles();
                if (files == null || files.isEmpty()) {
                    runOnUiThread(() -> textStatus.setText("No images found in Drive."));
                    return;
                }

                File facultyDir = new File(getExternalFilesDir(Environment.DIRECTORY_PICTURES),
                        "FacultyPhotos/" + currentFacultyName);
                if (!facultyDir.exists()) facultyDir.mkdirs();

                int downloaded = 0;
                for (com.google.api.services.drive.model.File driveFile : files) {
                    if (downloaded >= 5) break; // optional limit
                    try (OutputStream outputStream = new FileOutputStream(new File(facultyDir, driveFile.getName()))) {
                        googleDriveService.files().get(driveFile.getId()).executeMediaAndDownloadTo(outputStream);
                    }
                    downloaded++;
                }

                int finalDownloaded = downloaded;
                runOnUiThread(() -> {
                    textStatus.setText("Imported " + finalDownloaded + " photo(s) from Drive! Press 'Generate Embeddings' to continue.");
                    Toast.makeText(AdminActivity.this, "Photos ready.", Toast.LENGTH_SHORT).show();
                });

                googleSignInClient.signOut();

            } catch (Exception e) {
                e.printStackTrace();
                runOnUiThread(() -> textStatus.setText("Drive import failed: " + e.getMessage()));
            }
        }).start();
    }

    // -------------------- Embeddings --------------------
    private void generateEmbeddings() {
        textStatus.setText("Generating embeddings...");
        Toast.makeText(this, "Embeddings would be generated here (stub)", Toast.LENGTH_SHORT).show();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (cameraExecutor != null) cameraExecutor.shutdown();
        if (faceNet != null) faceNet.close();
    }

    // -------------------- FaceNet/FaceAligner Stubs --------------------
    private static class FaceNet {
        public FaceNet(AdminActivity context, String modelFile) {}
        public float[] getEmbedding(Bitmap bitmap) { return new float[128]; }
        public void close() {}
    }

    private static class FaceAligner {
        public Bitmap alignFace(Bitmap bitmap) { return bitmap; }
    }
}
