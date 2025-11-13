package com.sd.facultyfacialrecognition;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.util.Log;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.ImageCapture;
import androidx.camera.core.ImageCaptureException;
import androidx.camera.core.ImageProxy;
import androidx.camera.core.Preview;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.camera.view.PreviewView;
import androidx.core.content.ContextCompat;

import com.google.android.gms.auth.api.signin.*;
import com.google.android.gms.common.api.ApiException;
import com.google.android.gms.common.api.Scope;
import com.google.android.gms.tasks.Task;
import com.google.api.services.drive.DriveScopes;
import com.google.gson.Gson;
import com.sd.facultyfacialrecognition.FaceAligner;
import com.sd.facultyfacialrecognition.FaceNet;

import java.io.File;
import java.io.FileOutputStream;
import java.io.FileWriter;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class AdminActivity extends AppCompatActivity {

    private static final int REQUEST_CODE_SIGN_IN = 2001;
    private static final int REQUEST_CODE_PICK_IMAGES = 3001;
    private static final int NUM_PHOTOS_TO_CAPTURE = 10;
    private static final long CAPTURE_INTERVAL_MS = 1;

    private Button buttonAddFaculty, buttonDeleteFaculty, buttonImportDrive, buttonGenerateEmbeddings;
    private TextView textStatus;
    private PreviewView previewView;

    private String currentFacultyName;
    private File currentFacultyDir;
    private int photoCount = 0;

    private ImageCapture imageCapture;
    private ExecutorService cameraExecutor;

    private FaceAligner faceAligner;
    private FaceNet faceNet;

    private GoogleSignInClient googleSignInClient;

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
        faceAligner = new FaceAligner(this);

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

        if (currentFacultyDir.exists()) {
            // Faculty already exists — ask user whether to overwrite
            new AlertDialog.Builder(this)
                    .setTitle("Faculty Exists")
                    .setMessage("A faculty named \"" + facultyName + "\" already exists. Do you want to overwrite their existing photos?")
                    .setPositiveButton("Overwrite", (dialog, which) -> {
                        // Delete existing folder and recreate
                        deleteRecursive(currentFacultyDir);
                        currentFacultyDir.mkdirs();

                        photoCount = 0;
                        textStatus.setText("Overwriting data for: " + facultyName);
                        startCameraForFaculty();
                    })
                    .setNegativeButton("Cancel", (dialog, which) -> {
                        textStatus.setText("Cancelled adding " + facultyName);
                        dialog.dismiss();
                    })
                    .show();
        } else {
            // Faculty doesn’t exist — proceed normally
            currentFacultyDir.mkdirs();
            photoCount = 0;
            textStatus.setText("Ready to capture photos for: " + facultyName);
            startCameraForFaculty();
        }
    }


    private void showDeleteFacultyListDialog() {
        File facultyRoot = new File(getExternalFilesDir(Environment.DIRECTORY_PICTURES), "FacultyPhotos");
        File[] facultyDirs = facultyRoot.listFiles(File::isDirectory);

        if (facultyDirs != null && facultyDirs.length > 0) {
            String[] facultyNames = Arrays.stream(facultyDirs).map(File::getName).toArray(String[]::new);
            new AlertDialog.Builder(this)
                    .setTitle("Select Faculty to Delete")
                    .setItems(facultyNames, (dialog, which) -> {
                        String nameToDelete = facultyNames[which];
                        deleteRecursive(new File(facultyRoot, nameToDelete));
                        removeFacultyFromEmbeddings(nameToDelete);
                        textStatus.setText("Deleted faculty: " + nameToDelete);
                        Toast.makeText(this, "Faculty removed and embeddings updated!", Toast.LENGTH_SHORT).show();
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

    // -------------------- Remove Faculty from Embeddings --------------------
    private void removeFacultyFromEmbeddings(String facultyName) {
        try {
            File embeddingsFile = new File(
                    getExternalFilesDir(Environment.DIRECTORY_PICTURES),
                    "FacultyPhotos/embeddings.json"
            );

            if (!embeddingsFile.exists()) {
                Log.e("Embeddings", "Embeddings file not found!");
                return;
            }

            // Read JSON file
            StringBuilder jsonBuilder = new StringBuilder();
            Scanner scanner = new Scanner(embeddingsFile);
            while (scanner.hasNextLine()) {
                jsonBuilder.append(scanner.nextLine());
            }
            scanner.close();

            String jsonString = jsonBuilder.toString();
            if (jsonString.isEmpty()) {
                Log.e("Embeddings", "Embeddings file empty!");
                return;
            }

            // Parse and remove faculty entry
            Gson gson = new Gson();
            Map<String, List<float[]>> allEmbeddings = gson.fromJson(
                    jsonString,
                    new com.google.gson.reflect.TypeToken<Map<String, List<float[]>>>(){}.getType()
            );

            if (allEmbeddings != null && allEmbeddings.containsKey(facultyName)) {
                allEmbeddings.remove(facultyName);
                Log.d("Embeddings", "Removed faculty from embeddings: " + facultyName);

                // Write updated JSON back
                try (FileWriter writer = new FileWriter(embeddingsFile)) {
                    gson.toJson(allEmbeddings, writer);
                }
            } else {
                Log.d("Embeddings", "Faculty not found in embeddings: " + facultyName);
            }

        } catch (Exception e) {
            Log.e("Embeddings", "Error removing faculty: " + e.getMessage());
            e.printStackTrace();
        }
    }

    // -------------------- CameraX --------------------
    private void startCameraForFaculty() {
        ProcessCameraProvider.getInstance(this).addListener(() -> {
            try {
                ProcessCameraProvider cameraProvider = ProcessCameraProvider.getInstance(this).get();

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
            Toast.makeText(this, "Photos ready. Press 'Update Dataset' to continue.", Toast.LENGTH_SHORT).show();
            return;
        }

        imageCapture.takePicture(cameraExecutor, new ImageCapture.OnImageCapturedCallback() {
            @Override
            public void onCaptureSuccess(@NonNull ImageProxy imageProxy) {
                Bitmap bitmap = imageProxyToBitmap(imageProxy);
                imageProxy.close();

                if (bitmap == null) {
                    runOnUiThread(() -> textStatus.setText("Capture failed: empty image."));
                    return;
                }

                Bitmap faceBitmap = faceAligner.alignFace(bitmap);
                if (faceBitmap == null) {
                    runOnUiThread(() -> textStatus.setText("No face detected, retrying..."));
                    new android.os.Handler(getMainLooper()).postDelayed(AdminActivity.this::captureNextPhoto, CAPTURE_INTERVAL_MS);
                    return;
                }

                savePhoto(faceBitmap);

                photoCount++;
                runOnUiThread(() -> textStatus.setText("Captured photo " + photoCount + "/" + NUM_PHOTOS_TO_CAPTURE));
                new android.os.Handler(getMainLooper()).postDelayed(AdminActivity.this::captureNextPhoto, CAPTURE_INTERVAL_MS);
            }

            @Override
            public void onError(@NonNull ImageCaptureException exception) {
                runOnUiThread(() -> textStatus.setText("Capture failed: " + exception.getMessage()));
            }
        });
    }

    private void savePhoto(Bitmap bitmap) {
        try {
            File photoFile = new File(currentFacultyDir, "photo_" + (photoCount + 1) + ".jpg");
            try (FileOutputStream out = new FileOutputStream(photoFile)) {
                bitmap.compress(Bitmap.CompressFormat.JPEG, 100, out);
            }
        } catch (Exception e) {
            e.printStackTrace();
            runOnUiThread(() -> textStatus.setText("Error saving photo: " + e.getMessage()));
        }
    }

    private Bitmap imageProxyToBitmap(ImageProxy image) {
        try {
            ImageProxy.PlaneProxy plane = image.getPlanes()[0];
            ByteBuffer buffer = plane.getBuffer();
            byte[] bytes = new byte[buffer.remaining()];
            buffer.get(bytes);
            return BitmapFactory.decodeByteArray(bytes, 0, bytes.length);
        } catch (Exception e) {
            e.printStackTrace();
            return null;
        }
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
            requestDriveSignIn();
        });

        builder.setNegativeButton("Cancel", (dialog, which) -> dialog.dismiss());
        builder.show();
    }

    private void openDrivePicker() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("image/*");
        intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
        startActivityForResult(intent, REQUEST_CODE_PICK_IMAGES);
    }

    private void requestDriveSignIn() {
        GoogleSignInOptions signInOptions = new GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
                .requestEmail()
                .requestScopes(new Scope(DriveScopes.DRIVE_READONLY))
                .build();

        googleSignInClient = GoogleSignIn.getClient(this, signInOptions);
        googleSignInClient.signOut().addOnCompleteListener(task -> startActivityForResult(googleSignInClient.getSignInIntent(), REQUEST_CODE_SIGN_IN));
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, @Nullable Intent data) {
        super.onActivityResult(requestCode, resultCode, data);

        if (requestCode == REQUEST_CODE_SIGN_IN && resultCode == RESULT_OK) {
            Task<GoogleSignInAccount> task = GoogleSignIn.getSignedInAccountFromIntent(data);
            try {
                GoogleSignInAccount account = task.getResult(ApiException.class);
                openDrivePicker();
            } catch (ApiException e) {
                e.printStackTrace();
                textStatus.setText("Drive sign-in failed: " + e.getMessage());
            }
        } else if (requestCode == REQUEST_CODE_PICK_IMAGES && resultCode == RESULT_OK) {
            handleDriveImages(data);
        }
    }

    private void handleDriveImages(Intent data) {
        if (data == null) return;

        File facultyDir = new File(getExternalFilesDir(Environment.DIRECTORY_PICTURES), "FacultyPhotos/" + currentFacultyName);
        if (!facultyDir.exists()) facultyDir.mkdirs();

        try {
            int imported = 0;

            if (data.getClipData() != null) {
                for (int i = 0; i < data.getClipData().getItemCount(); i++) {
                    Uri uri = data.getClipData().getItemAt(i).getUri();
                    copyUriToFile(uri, facultyDir, ++imported);
                }
            } else if (data.getData() != null) {
                copyUriToFile(data.getData(), facultyDir, 1);
                imported = 1;
            }

            int finalImported = imported;
            runOnUiThread(() -> {
                textStatus.setText("Imported " + finalImported + " photo(s)! Press 'Update Dataset' to continue.");
                Toast.makeText(this, "Photos ready.", Toast.LENGTH_SHORT).show();
            });

            if (googleSignInClient != null) googleSignInClient.signOut();

        } catch (Exception e) {
            e.printStackTrace();
            textStatus.setText("Import failed: " + e.getMessage());
        }
    }

    private void copyUriToFile(Uri uri, File facultyDir, int count) throws Exception {
        InputStream inputStream = getContentResolver().openInputStream(uri);
        File outFile = new File(facultyDir, "photo_" + count + ".jpg");
        OutputStream outputStream = new FileOutputStream(outFile);

        byte[] buffer = new byte[4096];
        int bytesRead;
        while ((bytesRead = inputStream.read(buffer)) != -1) outputStream.write(buffer, 0, bytesRead);

        inputStream.close();
        outputStream.close();
    }

    // -------------------- Embeddings --------------------
    private void generateEmbeddings() {
        textStatus.setText("Generating embeddings...");
        new Thread(() -> {
            try {
                File facultyRoot = new File(getExternalFilesDir(Environment.DIRECTORY_PICTURES), "FacultyPhotos");
                File[] facultyDirs = facultyRoot.listFiles(File::isDirectory);
                if (facultyDirs == null || facultyDirs.length == 0) {
                    runOnUiThread(() -> textStatus.setText("No faculty found to generate embeddings."));
                    return;
                }

                Map<String, List<float[]>> allEmbeddings = new HashMap<>();

                for (File facultyDir : facultyDirs) {
                    String facultyName = facultyDir.getName();
                    File[] photos = facultyDir.listFiles((dir, name) -> name.endsWith(".jpg"));
                    if (photos == null || photos.length == 0) continue;

                    List<float[]> embeddingsList = new ArrayList<>();
                    for (File photo : photos) {
                        Bitmap bitmap = BitmapFactory.decodeFile(photo.getAbsolutePath());
                        if (bitmap == null) continue;

                        Bitmap faceBitmap = faceAligner.alignFace(bitmap);
                        if (faceBitmap == null) continue;

                        float[] emb = faceNet.getEmbedding(faceBitmap);
                        if (emb != null) embeddingsList.add(emb);
                    }
                    allEmbeddings.put(facultyName, embeddingsList);
                }

                File embeddingsFile = new File(facultyRoot, "embeddings.json");
                Gson gson = new Gson();
                try (FileWriter writer = new FileWriter(embeddingsFile)) {
                    gson.toJson(allEmbeddings, writer);
                }

                runOnUiThread(() -> {
                    textStatus.setText("Embeddings generated for all faculty!");
                    Toast.makeText(AdminActivity.this, "Embeddings generation complete!", Toast.LENGTH_SHORT).show();
                });

            } catch (Exception e) {
                e.printStackTrace();
                runOnUiThread(() -> textStatus.setText("Error generating embeddings: " + e.getMessage()));
            }
        }).start();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (cameraExecutor != null) cameraExecutor.shutdown();
        if (faceNet != null) faceNet.close();
    }
}
