package com.sd.facultyfacialrecognition;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
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
import androidx.core.content.res.ResourcesCompat;

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

import android.app.ProgressDialog;
import android.graphics.drawable.BitmapDrawable;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.ImageView;
import android.widget.ProgressBar;
import android.widget.LinearLayout;
import android.os.Handler;
import android.os.Looper;

import java.util.concurrent.atomic.AtomicInteger;

public class AdminActivity extends AppCompatActivity {

    private ProgressDialog progressDialog;
    private Handler mainHandler = new Handler(Looper.getMainLooper());
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

        LayoutInflater inflater = getLayoutInflater();
        View dialogView = inflater.inflate(R.layout.popup_input_window, null);

        TextView titleView = dialogView.findViewById(R.id.dialogTitle);
        EditText input = dialogView.findViewById(R.id.inputFacultyName);
        TextView errorText = dialogView.findViewById(R.id.errorText);

        titleView.setText("Add Faculty");
        input.setHint("Enter faculty name...");
        input.setHintTextColor(ContextCompat.getColor(this, R.color.light_gray));
        input.setInputType(android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_FLAG_CAP_WORDS);

        builder.setView(dialogView);

        builder.setPositiveButton("Add", null);
        builder.setNegativeButton("Cancel", (dialog, which) -> dialog.cancel());

        AlertDialog dialog = builder.create();
        dialog.show();
        dialog.getButton(AlertDialog.BUTTON_POSITIVE)
                .setTextColor(ContextCompat.getColor(this, R.color.dark_blue));
        dialog.getButton(AlertDialog.BUTTON_NEGATIVE)
                .setTextColor(ContextCompat.getColor(this, R.color.dark_red));

        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            String facultyName = input.getText().toString().trim();

            if (facultyName.length() < 7) {
                errorText.setText("Characters must have more than 7 letters");
                errorText.setVisibility(View.VISIBLE);
                input.setText("");
            } else if (!facultyName.matches("[a-zA-Z. ]+")) {
                errorText.setText("Only letters are allowed");
                errorText.setVisibility(View.VISIBLE);
                input.setText("");
            } else {
                errorText.setVisibility(View.GONE);
                startNewFacultyRegistration(facultyName);
                dialog.dismiss();
            }
        });
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

            AlertDialog.Builder builder = new AlertDialog.Builder(this)
                    .setTitle("Select Faculty to Delete")
                    .setItems(facultyNames, (d, which) -> {
                        String nameToDelete = facultyNames[which];
                        deleteRecursive(new File(facultyRoot, nameToDelete));
                        removeFacultyFromEmbeddings(nameToDelete);
                        textStatus.setText("Deleted faculty: " + nameToDelete);
                        Toast.makeText(this, "Faculty removed and embeddings updated!", Toast.LENGTH_SHORT).show();
                    })
                    .setNegativeButton("Cancel", (d, which) -> d.dismiss());

            AlertDialog dialog = builder.create();
            dialog.show();
            dialog.getButton(AlertDialog.BUTTON_NEGATIVE)
                    .setTextColor(ContextCompat.getColor(this, R.color.dark_red));
            TextView title = dialog.findViewById(android.R.id.title);
            if (title != null) {
                title.setTypeface(ResourcesCompat.getFont(this, R.font.roundelay_extrabold));
            }

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

        LayoutInflater inflater = getLayoutInflater();
        View dialogView = inflater.inflate(R.layout.popup_input_window, null);

        TextView titleView = dialogView.findViewById(R.id.dialogTitle);
        EditText input = dialogView.findViewById(R.id.inputFacultyName);
        TextView errorText = dialogView.findViewById(R.id.errorText);

        titleView.setText("Import Faculty Photos from Drive");
        input.setHint("Enter faculty name...");
        input.setHintTextColor(ContextCompat.getColor(this, R.color.light_gray));
        input.setInputType(android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_FLAG_CAP_WORDS);

        builder.setView(dialogView);

        builder.setPositiveButton("Next", null); // No default listener
        builder.setNegativeButton("Cancel", (dialog, which) -> dialog.dismiss());

        AlertDialog dialog = builder.create();
        dialog.show();

        dialog.getButton(AlertDialog.BUTTON_POSITIVE)
                .setTextColor(ContextCompat.getColor(this, R.color.dark_blue));
        dialog.getButton(AlertDialog.BUTTON_NEGATIVE)
                .setTextColor(ContextCompat.getColor(this, R.color.dark_red));

        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            String facultyName = input.getText().toString().trim();

            if (facultyName.length() < 7) {
                errorText.setText("Characters must have more than 7 letters");
                errorText.setVisibility(View.VISIBLE);
                input.setText("");
            } else if (!facultyName.matches("[a-zA-Z. ]+")) {
                errorText.setText("Only letters are allowed");
                errorText.setVisibility(View.VISIBLE);
                input.setText("");
            } else {
                errorText.setVisibility(View.GONE);
                currentFacultyName = facultyName;
                requestDriveSignIn(); // Only runs if validation passes
                dialog.dismiss();
            }
        });
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

        // Collect URIs
        final List<Uri> uris = new ArrayList<>();
        if (data.getClipData() != null) {
            for (int i = 0; i < data.getClipData().getItemCount(); i++)
                uris.add(data.getClipData().getItemAt(i).getUri());
        } else if (data.getData() != null) {
            uris.add(data.getData());
        }

        if (uris.isEmpty()) {
            textStatus.setText("No images selected.");
            return;
        }

        // Show indeterminate progress initially, then update
        progressDialog = new ProgressDialog(this);
        progressDialog.setTitle("Importing photos");
        progressDialog.setMessage("Preparing...");
        progressDialog.setProgressStyle(ProgressDialog.STYLE_HORIZONTAL);
        progressDialog.setMax(uris.size());
        progressDialog.setCancelable(false);
        progressDialog.show();

        final AtomicInteger importedCount = new AtomicInteger(0);
        final AtomicInteger skippedCount = new AtomicInteger(0);

        ExecutorService exec = Executors.newSingleThreadExecutor();

        exec.execute(() -> {
            int counter = 0;
            for (Uri uri : uris) {
                final int indexForName = ++counter;

                // load -> correct rotation -> detect faces (possibly multiple) -> preview -> save
                Bitmap original = ImageProcessing.loadBitmapFromUri(this, uri);
                if (original == null) {
                    skippedCount.incrementAndGet();
                    updateProgressOnUI(progressDialog, counter, importedCount.get(), skippedCount.get());
                    continue;
                }

                original = ImageProcessing.rotateBitmapIfRequired(this, uri, original);

                // detect faces:
                List<Bitmap> faces = detectFacesFallback(original);

                if (faces == null || faces.size() == 0) {
                    // fallback: try single-face alignFace
                    Bitmap single = faceAligner.alignFace(original);
                    if (single != null) faces = new ArrayList<>();
                    if (single != null) faces.add(single);
                }

                if (faces == null || faces.size() == 0) {
                    skippedCount.incrementAndGet();
                    updateProgressOnUI(progressDialog, counter, importedCount.get(), skippedCount.get());
                    continue;
                }

                // If multiple faces -> ask user which to save (on UI thread)
                Bitmap chosenFace = null;
                if (faces.size() == 1) {
                    chosenFace = faces.get(0);
                } else {
                    // ask user to pick one (blocking until selection)
                    chosenFace = promptUserToSelectFace(faces);
                    if (chosenFace == null) {
                        skippedCount.incrementAndGet();
                        updateProgressOnUI(progressDialog, counter, importedCount.get(), skippedCount.get());
                        continue;
                    }
                }

                // blur detection
                if (ImageProcessing.isBlurry(chosenFace)) {
                    // skip blurry
                    skippedCount.incrementAndGet();
                    updateProgressOnUI(progressDialog, counter, importedCount.get(), skippedCount.get());
                    continue;
                }

                // resize to FaceNet expected size
                Bitmap resized = ImageProcessing.resize(chosenFace, 160, 160);

                // Save file
                try {
                    File outFile = new File(facultyDir, "photo_" + (importedCount.get() + 1) + ".jpg");
                    FileOutputStream fos = new FileOutputStream(outFile);
                    resized.compress(Bitmap.CompressFormat.JPEG, 100, fos);
                    fos.close();
                    importedCount.incrementAndGet();
                } catch (Exception e) {
                    e.printStackTrace();
                    skippedCount.incrementAndGet();
                }

                updateProgressOnUI(progressDialog, counter, importedCount.get(), skippedCount.get());
            }

            // finished
            mainHandler.post(() -> {
                progressDialog.dismiss();
                textStatus.setText("Imported: " + importedCount.get() + " | Skipped: " + skippedCount.get() + ". Press 'Update Dataset'.");
                Toast.makeText(this, "Imported " + importedCount.get() + ". Skipped " + skippedCount.get(), Toast.LENGTH_LONG).show();
                if (googleSignInClient != null) googleSignInClient.signOut();
            });

            exec.shutdown();
        });
    }

    // Shows a dialog with images and returns the chosen Bitmap (or null if user cancels).
    private Bitmap promptUserToSelectFace(List<Bitmap> faces) {
        final Object lock = new Object();
        final Bitmap[] result = new Bitmap[1];

        mainHandler.post(() -> {
            AlertDialog.Builder b = new AlertDialog.Builder(AdminActivity.this);
            b.setTitle("Select the correct face");

            // create horizontal scroll of images
            LinearLayout layout = new LinearLayout(AdminActivity.this);
            layout.setOrientation(LinearLayout.HORIZONTAL);
            layout.setPadding(16, 16, 16, 16);
            for (int i = 0; i < faces.size(); i++) {
                final int idx = i;
                ImageView iv = new ImageView(AdminActivity.this);
                iv.setImageDrawable(new BitmapDrawable(getResources(), faces.get(i)));
                LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(300, 300);
                p.setMargins(12, 0, 12, 0);
                iv.setLayoutParams(p);
                iv.setScaleType(ImageView.ScaleType.CENTER_CROP);
                iv.setOnClickListener(v -> {
                    synchronized (lock) {
                        result[0] = faces.get(idx);
                        lock.notify();
                    }
                });
                layout.addView(iv);
            }

            b.setView(layout);
            b.setNegativeButton("Skip", (d, w) -> {
                synchronized (lock) {
                    result[0] = null;
                    lock.notify();
                }
            });

            AlertDialog dialog = b.create();
            dialog.setCancelable(false);
            dialog.show();
        });

        // wait for user selection
        try {
            synchronized (lock) {
                lock.wait(); // will be notified when user picks or skips
            }
        } catch (InterruptedException e) {
            return null;
        }

        return result[0];
    }

    private List<Bitmap> detectFacesFallback(Bitmap original) {
        try {
            // Try to call detectFaces if FaceAligner supports it
            List<Bitmap> faces = faceAligner.detectFaces(original); // OPTIONAL method in your FaceAligner
            if (faces != null && faces.size() > 0) return faces;
        } catch (Exception ignored) { }

        // fallback: try to use android.media.FaceDetector (very simple; prefers RGB_565)
        try {
            Bitmap rgb = original.copy(Bitmap.Config.RGB_565, true);
            int maxFaces = 5;
            android.media.FaceDetector.Face[] fArray = new android.media.FaceDetector.Face[maxFaces];
            android.media.FaceDetector fd = new android.media.FaceDetector(rgb.getWidth(), rgb.getHeight(), maxFaces);
            int found = fd.findFaces(rgb, fArray);
            List<Bitmap> out = new ArrayList<>();
            for (int i = 0; i < found; i++) {
                android.media.FaceDetector.Face f = fArray[i];
                if (f == null) continue;
                android.graphics.PointF mid = new android.graphics.PointF();
                f.getMidPoint(mid);
                float eyesDist = f.eyesDistance();
                int left = (int) Math.max(0, mid.x - eyesDist * 2);
                int top = (int) Math.max(0, mid.y - eyesDist * 2);
                int right = (int) Math.min(rgb.getWidth(), mid.x + eyesDist * 2);
                int bottom = (int) Math.min(rgb.getHeight(), mid.y + eyesDist * 2);
                if (right - left <= 20 || bottom - top <= 20) continue;
                Bitmap faceCrop = Bitmap.createBitmap(original, left, top, right - left, bottom - top);
                out.add(faceCrop);
            }
            return out;
        } catch (Exception e) {
            return null;
        }
    }


    private void updateProgressOnUI(ProgressDialog pd, int processed, int imported, int skipped) {
        mainHandler.post(() -> {
            if (pd != null && pd.isShowing()) {
                pd.setProgress(processed);
                pd.setMessage("Processed " + processed + " | Imported: " + imported + " | Skipped: " + skipped);
            }
        });
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
                File facultyRoot = new File(
                        getExternalFilesDir(Environment.DIRECTORY_PICTURES),
                        "FacultyPhotos"
                );

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
                        Bitmap original = BitmapFactory.decodeFile(photo.getAbsolutePath());
                        if (original == null) continue;

                        // --- 1. CROP IMAGE FIRST ---
                        Bitmap croppedFace = faceAligner.alignFace(original);

                        if (croppedFace == null) {
                            Log.w("Embedding", "No face found in: " + photo.getName());
                            continue;
                        }

                        // --- 2. SAVE CROPPED FACE BACK TO SAME FILE ---
                        try (FileOutputStream out = new FileOutputStream(photo)) {
                            croppedFace.compress(Bitmap.CompressFormat.JPEG, 100, out);
                        }

                        // --- 3. EMBEDDING FROM CROPPED FACE ---
                        float[] emb = faceNet.getEmbedding(croppedFace);
                        if (emb != null) {
                            embeddingsList.add(emb);
                        }
                    }

                    allEmbeddings.put(facultyName, embeddingsList);
                }

                // --- ADD MULTIPLE UNKNOWN EMBEDDINGS ---
                int embeddingSize = 128; // same as FaceNet output size
                int unknownCount = 10;    // number of unknown embeddings
                Random random = new Random(42); // fixed seed for reproducibility
                List<float[]> unknownEmbeddings = new ArrayList<>();

                for (int j = 0; j < unknownCount; j++) {
                    float[] unknownEmbedding = new float[embeddingSize];
                    for (int i = 0; i < embeddingSize; i++) {
                        unknownEmbedding[i] = (random.nextFloat() - 0.5f) * 0.05f; // range roughly -0.025 to +0.025
                    }

                    // Normalize embedding to unit vector like FaceNet outputs
                    float norm = 0f;
                    for (float v : unknownEmbedding) norm += v * v;
                    norm = (float) Math.sqrt(norm);
                    for (int i = 0; i < embeddingSize; i++) unknownEmbedding[i] /= norm;

                    unknownEmbeddings.add(unknownEmbedding);
                }

                allEmbeddings.put("Unknown", unknownEmbeddings);



                // Save embeddings.json
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
                runOnUiThread(() ->
                        textStatus.setText("Error generating embeddings: " + e.getMessage())
                );
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
