package com.sd.facultyfacialrecognition;

import android.content.ComponentName;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.IBinder;
import android.util.Log;
import android.content.Intent;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.OptIn;
import androidx.appcompat.app.AppCompatActivity;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.ExperimentalGetImage;
import androidx.camera.core.ImageAnalysis;
import androidx.camera.core.Preview;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.camera.view.PreviewView;
import androidx.core.content.ContextCompat;

import com.google.common.util.concurrent.ListenableFuture;
import com.google.firebase.firestore.FirebaseFirestore;
import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.face.Face;
import com.google.mlkit.vision.face.FaceDetection;
import com.google.mlkit.vision.face.FaceDetector;
import com.google.mlkit.vision.face.FaceDetectorOptions;
import com.google.mlkit.vision.face.FaceLandmark;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;

import java.text.SimpleDateFormat;
import java.util.Date;

import com.google.firebase.database.DatabaseReference;
import com.google.firebase.database.FirebaseDatabase;



public class MainActivity extends AppCompatActivity {
    private static final String TAG = "MainActivity";
    private static final int REQUEST_CAMERA_PERMISSION = 1001;
    private PreviewView previewView;
    private FaceOverlayView overlayView;
    private TextView statusTextView;
    private TextView countdownTextView;
    private Button confirmYesButton;
    private Button confirmNoButton;

    private FaceNet faceNet;
    private ImageAligner imageAligner;
    private ExecutorService cameraExecutor;

    private final Map<String, float[]> KNOWN_FACE_EMBEDDINGS = new HashMap<>();
    private final Map<String, List<float[]>> facultyEmbeddings = new HashMap<>();

    private static final int STABILITY_FRAMES_NEEDED = 20;
    private static final long UNLOCK_COOLDOWN_MILLIS = 10000;

    private static final long CONFIRMATION_TIMEOUT_MILLIS = 10000;
    private static final int VISUAL_COUNTDOWN_SECONDS = 5;

    private String stableMatchName = "Scanning...";
    private String currentBestMatch = "Scanning...";
    private int stableMatchCount = 0;

    private boolean isDoorLocked = true;
    private boolean isAwaitingLockConfirmation = false;
    private boolean isAwaitingUnlockConfirmation = false;
    private boolean isAwaitingLockerRecognition = false;

    private String authorizedLocker = null;
    private String authorizedUnlocker = null;
    private long lastLockTimestamp = 0;

    private Handler confirmationHandler;
    private Runnable confirmationRunnable;
    private Handler countdownDisplayHandler;
    private Runnable countdownDisplayRunnable;
    private int confirmationTimeRemaining = VISUAL_COUNTDOWN_SECONDS;
    private FirebaseFirestore db;

    private final String currentLab = "CpeLab"; //CpeLab or CompLab3

    private BluetoothService mBluetoothService;
    private boolean mIsBound = false;

    private final ServiceConnection mConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName className, IBinder service) {
            BluetoothService.LocalBinder binder = (BluetoothService.LocalBinder) service;
            mBluetoothService = binder.getService();
            mIsBound = true;
            // Automatically connect to the device when the service is connected
            if (!mBluetoothService.connectToDevice()) {
                Log.e(TAG, "Failed to connect to ESP32");
            }
        }

        @Override
        public void onServiceDisconnected(ComponentName arg0) {
            mIsBound = false;
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        previewView = findViewById(R.id.previewView);
        overlayView = findViewById(R.id.faceOverlayView);
        statusTextView = findViewById(R.id.text_status_label);
        countdownTextView = findViewById(R.id.text_countdown_status);

        confirmYesButton = findViewById(R.id.confirm_yes_button);
        confirmNoButton = findViewById(R.id.confirm_no_button);
        Button btnBreakDone = findViewById(R.id.btn_break_done);

        confirmYesButton.setVisibility(View.GONE);
        confirmNoButton.setVisibility(View.GONE);
        btnBreakDone.setVisibility(View.GONE);

        confirmationHandler = new Handler();
        countdownDisplayHandler = new Handler();
        cameraExecutor = Executors.newSingleThreadExecutor();
        imageAligner = new ImageAligner();


        initializeSystem();
        startCamera();
        testLoadEmbeddings();

        db = FirebaseFirestore.getInstance();

        Intent intent = new Intent(this, BluetoothService.class);
        bindService(intent, mConnection, BIND_AUTO_CREATE);

    }

    private void initializeSystem() {
        try {
            faceNet = new FaceNet(this, "facenet.tflite");

            // Try loading from storage first
            boolean embeddingsLoaded = loadEmbeddingsFromStorage();
            if (!embeddingsLoaded) {
                // Fallback to assets
                embeddingsLoaded = loadEmbeddingsFromAssets();
            }

            Log.d(TAG, "FaceNet model and embeddings loaded successfully. Embeddings loaded: " + embeddingsLoaded);
        } catch (Exception e) {
            Log.e(TAG, "Error initializing FaceNet or embeddings", e);
        }

        startCamera();
    }

    private void startConfirmationTimer(boolean isLock) {
        stopConfirmationTimer();

        confirmationRunnable = () -> {
            if (isLock) {
                onConfirmNoClicked(null);
                updateUiOnThread("Lock Timed Out", "Lock request cancelled due to inactivity.");
            } else {
                onConfirmNoClicked(null);
                updateUiOnThread("Unlock Timed Out", "Unlock request cancelled due to inactivity.");
            }
        };

        confirmationHandler.postDelayed(confirmationRunnable, CONFIRMATION_TIMEOUT_MILLIS);
    }

    private void stopConfirmationTimer() {
        if (confirmationRunnable != null) {
            confirmationHandler.removeCallbacks(confirmationRunnable);
            confirmationRunnable = null;
        }
    }

    private void startVisualCountdown() {
        stopVisualCountdown();

        confirmationTimeRemaining = VISUAL_COUNTDOWN_SECONDS;

        countdownDisplayRunnable = new Runnable() {
            @Override
            public void run() {
                String currentAction = isAwaitingLockConfirmation ? "Lock" : "Unlock";

                if (confirmationTimeRemaining > 0) {
                    String name = isAwaitingLockConfirmation ? authorizedLocker : stableMatchName;

                    updateUiOnThread("Confirm " + currentAction + " Identity",
                            "Is this you: " + name + "?\nAction auto-cancels in " + (CONFIRMATION_TIMEOUT_MILLIS / 1000) + "s (Visual countdown: " + confirmationTimeRemaining + "s).");

                    confirmationTimeRemaining--;
                    countdownDisplayHandler.postDelayed(this, 1000);
                } else {
                    String name = isAwaitingLockConfirmation ? authorizedLocker : stableMatchName;
                    String finalStatus = isAwaitingLockConfirmation ? "Confirm Lock Identity" : "Confirm Unlock Identity";
                    String finalCountdown = "Is this you: " + name + "? (Awaiting confirmation)";
                    updateUiOnThread(finalStatus, finalCountdown);
                    stopVisualCountdown();
                }
            }
        };

        countdownDisplayHandler.post(countdownDisplayRunnable);
    }

    private void stopVisualCountdown() {
        if (countdownDisplayRunnable != null) {
            countdownDisplayHandler.removeCallbacks(countdownDisplayRunnable);
            countdownDisplayRunnable = null;
        }
    }

    public void onConfirmYesClicked(View view) {
        stopConfirmationTimer();
        stopVisualCountdown();

        if (isAwaitingLockConfirmation) {
            handleLockConfirmation();

        } else if (isAwaitingUnlockConfirmation) {
            handleUnlockConfirmation();
        }
    }

    private String getCurrentTimestamp() {
        SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd | EEEE | HH:mm:ss", Locale.getDefault());
        return sdf.format(new Date());
    }

    private void updateRealtimeStatus(String facultyStatus, String doorStatus) {
        if (authorizedUnlocker == null ||
                authorizedUnlocker.equals("Scanning...") ||
                authorizedUnlocker.equals("Unknown")) {
            Log.w("DoorDebug", "Skipping Realtime DB update: unauthorized or unknown faculty.");
            return;
        }

        String timestamp = new SimpleDateFormat("yyyy-MM-dd | EEEE | HH:mm:ss", Locale.getDefault())
                .format(new Date());

        Map<String, Object> data = new HashMap<>();
        data.put("facultyStatus", facultyStatus);
        data.put("facultyName", authorizedUnlocker);
        data.put("doorStatus", doorStatus);
        data.put("timestamp", timestamp);

        try {
            FirebaseDatabase database = FirebaseDatabase.getInstance(
                    "https://facultyfacialrecognition-default-rtdb.asia-southeast1.firebasedatabase.app/"
            );

            DatabaseReference dbRef = database
                    .getReference(currentLab)
                    .child("Latest");

            dbRef.setValue(data)
                    .addOnSuccessListener(aVoid -> Log.d("DoorDebug", "Realtime DB successfully updated"))
                    .addOnFailureListener(e -> Log.e("DoorDebug", "Realtime DB update FAILED", e));


        } catch (Exception e) {
            Log.e("DoorDebug", "Database initialization error", e);
        }
    }


    private void logDoorEvent(String facultyName, String facultyStatus, String doorStatus) {
        if (facultyName == null || facultyName.equals("Scanning...") || facultyName.equals("Unknown")) {
            Log.w("DoorLockDebug", "Skipping logging: invalid faculty name");
            return;
        }

        String timestamp = getCurrentTimestamp();

        Map<String, Object> logEntry = new HashMap<>();
        logEntry.put("facultyName", facultyName);
        logEntry.put("facultyStatus", facultyStatus);
        logEntry.put("doorStatus", doorStatus);
        logEntry.put("timestamp", timestamp);
        logEntry.put("lab", currentLab);

        db.collection("DoorLogs")
                .add(logEntry)
                .addOnSuccessListener(docRef -> Log.d("DoorLockDebug",
                        "Door event logged: " + facultyName + " | " + facultyStatus + " | " + doorStatus + " | " + timestamp))
                .addOnFailureListener(e -> Log.e("DoorLockDebug", "Error logging door event", e));

        updateLabStatus(facultyName, facultyStatus, doorStatus, timestamp);
    }

    private void updateLabStatus(String facultyName, String facultyStatus, String doorStatus, String timestamp) {
        if (facultyName == null || facultyName.equals("Scanning...") || facultyName.equals("Unknown")) return;

        Map<String, Object> data = new HashMap<>();
        data.put("facultyName", facultyName);   // store the name
        data.put("facultyStatus", facultyStatus);
        data.put("doorStatus", doorStatus);
        data.put("timestamp", timestamp);

        db.collection(currentLab)
                .document("Latest")   // Always overwrite the same document
                .set(data)
                .addOnSuccessListener(aVoid -> Log.d("DoorLockDebug",
                        "Updated " + currentLab + " Latest: " + facultyName + " | " + facultyStatus + " | " + doorStatus + " | " + timestamp))
                .addOnFailureListener(e -> Log.e("DoorLockDebug",
                        "Error updating " + currentLab + " Latest", e));
    }


    private void handleLockConfirmation() {
        isDoorLocked = true;
        isAwaitingLockConfirmation = false;
        isAwaitingLockerRecognition = false;
        lastLockTimestamp = System.currentTimeMillis();

        final String facultyNameFinal = authorizedLocker; // make final for lambda

        Log.d("DoorLockDebug", "Handling LOCK confirmation for faculty: " + facultyNameFinal);

        // Log door event
        logDoorEvent(authorizedLocker, "End Class", "LOCKED");

        // Update Firestore with debug
        updateFacultyStatusWithDebug(facultyNameFinal, "LOCKED");

        sendLockCommand();

        resetStateAfterAction();
        updateUiOnThread("System Locked", "Door secured. Cooldown active.");
    }

    private void handleUnlockConfirmation() {
        isDoorLocked = false;
        isAwaitingUnlockConfirmation = false;
        final String facultyNameFinal = stableMatchName;
        authorizedUnlocker = facultyNameFinal;

        String facultyStatus = "In Class";
        String doorStatus = "UNLOCKED";
        String timestamp = new SimpleDateFormat("yyyy-MM-dd | EEEE | HH:mm:ss", Locale.getDefault()).format(new Date());

        Log.d("DoorLockDebug", "Handling UNLOCK confirmation for faculty: " + facultyNameFinal);

        // Firestore logs
        logDoorEvent(facultyNameFinal, facultyStatus, doorStatus);

        // Firestore lab status update
        updateLabStatus(facultyNameFinal, facultyStatus, doorStatus, timestamp);

        // Realtime Database update
        updateRealtimeStatus(facultyStatus, doorStatus);

        sendUnlockCommand();

        if (cameraExecutor != null) {
            cameraExecutor.shutdown();
        }

        boolean isRescanMode = getIntent().hasExtra("mode") &&
                "rescan".equals(getIntent().getStringExtra("mode"));
        boolean isFromBreak = getIntent().getBooleanExtra("from_break", false);

        if (isRescanMode) {
            runOnUiThread(() -> {
                if (isFromBreak) {
                    findViewById(R.id.btn_break_done).setVisibility(View.VISIBLE);
                } else {
                    findViewById(R.id.btn_take_break).setVisibility(View.VISIBLE);
                    findViewById(R.id.btn_end_class).setVisibility(View.VISIBLE);
                }
                findViewById(R.id.confirm_yes_button).setVisibility(View.GONE);
                findViewById(R.id.confirm_no_button).setVisibility(View.GONE);

                updateUiOnThread("What would you like to do?", "Select an option below.");
            });

            resetStateAfterAction();
            return;
        }

        Intent intent = new Intent(MainActivity.this, DashboardActivity.class);
        intent.putExtra("profName", facultyNameFinal);
        startActivity(intent);

        resetStateAfterAction();
        updateUiOnThread("Access Granted:\n" + facultyNameFinal,
                "Door UNLOCKED. Choose options below.");
    }


    // New method with detailed debug logging
    private void updateFacultyStatusWithDebug(String facultyName, String status) {
        if (facultyName == null || facultyName.equals("Scanning...") || facultyName.equals("Unknown")) {
            Log.e("DoorLockDebug", "Skipping Firestore update: invalid faculty name '" + facultyName + "'");
            return;
        }

        final String facultyNameFinal = facultyName;

        Log.d("DoorLockDebug", "Updating Firestore for faculty: " + facultyNameFinal + " with status: " + status);

        Map<String, Object> data = new HashMap<>();
        data.put("status", status); // will be "LOCKED", "UNLOCKED", or "BREAK"
        data.put("timestamp", System.currentTimeMillis());

        db.collection(currentLab)
                .document(facultyNameFinal)
                .set(data)
                .addOnSuccessListener(aVoid -> Log.d("DoorLockDebug", "Successfully updated faculty status for " + facultyNameFinal))
                .addOnFailureListener(e -> Log.e("DoorLockDebug", "Error updating faculty status for " + facultyNameFinal, e));
    }

    public void onTakeBreakClicked(View view) {
        if (authorizedUnlocker == null) return;

        String facultyNameFinal = authorizedUnlocker;
        String facultyStatus = "Break";
        String doorStatus = "UNLOCKED";
        String timestamp = new SimpleDateFormat("yyyy-MM-dd | EEEE | HH:mm:ss", Locale.getDefault()).format(new Date());

        Log.d("DoorLockDebug", "Professor taking break: " + facultyNameFinal);

        logDoorEvent(facultyNameFinal, facultyStatus, doorStatus);
        updateLabStatus(facultyNameFinal, facultyStatus, doorStatus, timestamp);
        updateRealtimeStatus(facultyStatus, doorStatus);

        // Navigate to dashboard
        Intent intent = new Intent(MainActivity.this, DashboardActivity.class);
        intent.putExtra("profName", facultyNameFinal);
        intent.putExtra("status", "Professor is on break. Please scan to resume class.");
        startActivity(intent);
        finish();
    }

    public void onEndClassClicked(View view) {
        if (authorizedUnlocker == null) return;

        String facultyNameFinal = authorizedUnlocker;
        String facultyStatus = "End Class";
        String doorStatus = "LOCKED";
        String timestamp = new SimpleDateFormat("yyyy-MM-dd | EEEE | HH:mm:ss", Locale.getDefault()).format(new Date());

        Log.d("DoorLockDebug", "Class ended by: " + facultyNameFinal);

        // Firestore logging
        logDoorEvent(facultyNameFinal, facultyStatus, doorStatus);
        updateLabStatus(facultyNameFinal, facultyStatus, doorStatus, timestamp);

        // Realtime Database update
        updateRealtimeStatus(facultyStatus, doorStatus);

        isDoorLocked = true;
        sendLockCommand();

        Intent intent = new Intent(MainActivity.this, ThankYouActivity.class);
        intent.putExtra("message", "Class ended and door is locked, thank you!");
        startActivity(intent);
        finish();
    }

    public void onBreakDoneClicked(View view) {
        if (authorizedUnlocker == null) return;

        String facultyStatus = "In Class";
        String doorStatus = "UNLOCKED";

        // Realtime Database update
        updateRealtimeStatus(facultyStatus, doorStatus);

        sendUnlockCommand();

        Intent intent = new Intent(MainActivity.this, DashboardActivity.class);
        intent.putExtra("profName", authorizedUnlocker);
        startActivity(intent);
        finish();
    }

    private void sendLockCommand() {
        if (mIsBound) {
            mBluetoothService.sendData("lock");
        }
    }

    private void sendUnlockCommand() {
        if (mIsBound) {
            mBluetoothService.sendData("unlock");
        }
    }


    public void onConfirmNoClicked(View view) {
        stopConfirmationTimer();
        stopVisualCountdown();

        if (isAwaitingLockConfirmation) {
            isAwaitingLockConfirmation = false;
            isAwaitingLockerRecognition = false;
            authorizedLocker = null;

            if (view != null) {
                updateUiOnThread("Access Granted: " + authorizedUnlocker, "Lock cancelled by user. Door is UNLOCKED.");
            }

        } else if (isAwaitingUnlockConfirmation) {
            isAwaitingUnlockConfirmation = false;

            if (view != null) {
                updateUiOnThread("Access Denied", "Unlock cancelled by user. Awaiting recognition.");
            }
        }

        stableMatchCount = 0;
        stableMatchName = "Scanning...";
        currentBestMatch = "Scanning...";
    }

    private void resetStateAfterAction() {
        stableMatchCount = 0;
        authorizedLocker = null;
        stableMatchName = "Scanning...";
        currentBestMatch = "Scanning...";
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

    @OptIn(markerClass = ExperimentalGetImage.class)
    private void bindPreviewAndAnalyzer(ProcessCameraProvider cameraProvider) {
        cameraProvider.unbindAll();

        Preview preview = new Preview.Builder().build();
        CameraSelector selector = CameraSelector.DEFAULT_FRONT_CAMERA;
        preview.setSurfaceProvider(previewView.getSurfaceProvider());

        ImageAnalysis imageAnalysis = new ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build();

        FaceDetectorOptions options = new FaceDetectorOptions.Builder()
                .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_ACCURATE)
                .setLandmarkMode(FaceDetectorOptions.LANDMARK_MODE_ALL)
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

        String currentBestFrameMatch = "Scanning...";
        float bestDist = Float.MAX_VALUE;
        String finalMessage = "";
        String countdownMessage = "";

        if (faces.size() != 1) {
            // No single face detected, don't calculate accuracy or log anything
            currentBestFrameMatch = "Scanning...";
        } else {
            // Single face detected — proceed with embedding, ranking, and logging
            Face face = faces.get(0);
            android.graphics.PointF leftEye = face.getLandmark(FaceLandmark.LEFT_EYE) != null ? face.getLandmark(FaceLandmark.LEFT_EYE).getPosition() : null;
            android.graphics.PointF rightEye = face.getLandmark(FaceLandmark.RIGHT_EYE) != null ? face.getLandmark(FaceLandmark.RIGHT_EYE).getPosition() : null;

            Bitmap faceBmp = imageAligner.alignAndCropFace(fullBmp, face.getBoundingBox(), leftEye, rightEye);

            if (faceBmp != null) {
                float[] emb = faceNet.getEmbedding(faceBmp);
                if (emb != null) {
                    normalizeEmbedding(emb);

                    float maxDistance = 10f;
                    int rank = 1;
                    Map<String, Float> rankedMatches = new HashMap<>();

                    for (Map.Entry<String, float[]> entry : KNOWN_FACE_EMBEDDINGS.entrySet()) {
                        float distance = FaceNet.distance(emb, entry.getValue());
                        float accuracy = 1f - (distance / maxDistance);
                        rankedMatches.put(entry.getKey(), accuracy);
                    }

                    // Sort matches by descending accuracy
                    List<Map.Entry<String, Float>> sortedList = new ArrayList<>(rankedMatches.entrySet());
                    sortedList.sort((a, b) -> Float.compare(b.getValue(), a.getValue()));

                    // Log only if there’s a valid match
                    Log.d("FaceRecognitionRanking", "===== Ranking of Matches =====");
                    for (Map.Entry<String, Float> match : sortedList) {
                        Log.d("FaceRecognitionRanking", String.format(Locale.US, "%d. %s : %.4f",
                                rank++, match.getKey(), match.getValue()));
                    }

                    Map.Entry<String, Float> bestMatch = sortedList.get(0);
                    Log.d("FaceRecognition", String.format(Locale.US, "Best match this frame: %s | Accuracy = %.4f",
                            bestMatch.getKey(), bestMatch.getValue()));

                    currentBestFrameMatch = bestMatch.getKey();

                    // --- BLOCK ACCESS IF UNKNOWN DETECTED ---
                    if ("Unknown".equals(currentBestFrameMatch)) {
                        finalMessage = "Access Denied";
                        countdownMessage = "Unknown face detected. Recognition failed.";
                        stableMatchCount = 0;
                        stableMatchName = "Scanning...";
                        updateUiOnThread(finalMessage, countdownMessage);
                        runOnUiThread(() -> overlayView.setFaces(graphics));
                        return;
                    }


                }

            }

            graphics.add(new FaceOverlayView.FaceGraphic(face.getBoundingBox(), "", bestDist));
        }

        this.currentBestMatch = currentBestFrameMatch;

        if (isAwaitingLockConfirmation || isAwaitingUnlockConfirmation) {

            String authorizedName = isAwaitingLockConfirmation ? authorizedLocker : stableMatchName;

            if (countdownDisplayRunnable == null) {
                finalMessage = isAwaitingLockConfirmation ? "Confirm Lock Identity" : "Confirm Unlock Identity";
                countdownMessage = "Is this you: " + authorizedName + "? (Awaiting confirmation)";
            } else {
                runOnUiThread(() -> overlayView.setFaces(graphics));
                return;
            }

        } else if (isAwaitingLockerRecognition) {

            updateStabilityState(currentBestFrameMatch);

            if (stableMatchCount >= STABILITY_FRAMES_NEEDED) {

                boolean isLockerIdentityConfirmed = !stableMatchName.equals("Unknown") &&
                        !stableMatchName.equals("Scanning...") &&
                        stableMatchName.equals(currentBestMatch);

                if (isLockerIdentityConfirmed) {
                    isAwaitingLockerRecognition = false;
                    isAwaitingLockConfirmation = true;
                    authorizedLocker = stableMatchName;
                    stableMatchCount = 0;

                    startConfirmationTimer(true);
                    startVisualCountdown();
                    finalMessage = "";
                    countdownMessage = "";

                } else {
                    isAwaitingLockerRecognition = false;
                    finalMessage = "Recognition Failed";
                    countdownMessage = "Lock initiation failed. Please try again.";
                }
            } else if (stableMatchCount > 0 && !currentBestFrameMatch.equals("Scanning...")) {
                int remainingFrames = STABILITY_FRAMES_NEEDED - stableMatchCount;
                finalMessage = "Recognizing: " + currentBestMatch;
                countdownMessage = String.format(Locale.US, "Hold Steady to LOCK! (%d frames remaining)", remainingFrames);
            } else {
                finalMessage = "Awaiting Locker Recognition";
                countdownMessage = "Please hold a faculty face steady for 5 seconds to initiate lock.";
            }

        } else if (isDoorLocked) {

            long timeSinceLock = System.currentTimeMillis() - lastLockTimestamp;
            if (timeSinceLock < UNLOCK_COOLDOWN_MILLIS) {
                long remainingSeconds = (UNLOCK_COOLDOWN_MILLIS - timeSinceLock) / 1000 + 1;
                finalMessage = "System Locked";
                countdownMessage = String.format(Locale.US, "Unlock Cooldown Active: %d seconds remaining.", remainingSeconds);

                updateUiOnThread(finalMessage, countdownMessage);
                runOnUiThread(() -> overlayView.setFaces(graphics));
                return;
            }

            updateStabilityState(currentBestFrameMatch);

            if (stableMatchCount >= STABILITY_FRAMES_NEEDED) {

                boolean isUnlockIdentityConfirmed = !stableMatchName.equals("Unknown") &&
                        !stableMatchName.equals("Scanning...") &&
                        stableMatchName.equals(currentBestMatch);

                if (isUnlockIdentityConfirmed) {
                    isAwaitingUnlockConfirmation = true;
                    stableMatchCount = 0;

                    startConfirmationTimer(false);
                    startVisualCountdown();
                    finalMessage = "";
                    countdownMessage = "";

                } else {
                    finalMessage = "Access Denied";
                    countdownMessage = "Recognition Failed. Please try again.";
                    stableMatchCount = 0;
                }
            } else if (stableMatchCount > 0 && !currentBestFrameMatch.equals("Scanning...")) {
                int remainingFrames = STABILITY_FRAMES_NEEDED - stableMatchCount;
                finalMessage = "Recognizing: " + currentBestMatch;
                countdownMessage = String.format(Locale.US, "Hold Steady for unlock! (%d frames remaining)", remainingFrames);
            } else {
                finalMessage = "Awaiting Recognition";
                countdownMessage = "Scanning for faculty...";
            }
        } else {
            finalMessage = "Access Granted: " + authorizedUnlocker;
            countdownMessage = "Door UNLOCKED. Choose options below.";
        }

        updateUiOnThread(finalMessage, countdownMessage);

        overlayView.setImageSourceInfo(inputImage.getWidth(), inputImage.getHeight(), true);
        runOnUiThread(() -> overlayView.setFaces(graphics));
    }

    private synchronized void updateStabilityState(String newMatch) {
        if (newMatch.equals(currentBestMatch)) {
            stableMatchCount++;
        } else {
            currentBestMatch = newMatch;
            stableMatchCount = 1;
        }

        if (stableMatchCount >= STABILITY_FRAMES_NEEDED) {
            stableMatchName = currentBestMatch;
        } else if (stableMatchCount == 0 || newMatch.equals("Scanning...")) {
            stableMatchName = "Scanning...";
        }
    }

    private void updateUiOnThread(final String status, final String countdown) {
        runOnUiThread(() -> {
            statusTextView.setText(status);
            countdownTextView.setText(countdown);

            if (isAwaitingLockConfirmation || isAwaitingUnlockConfirmation) {
                confirmYesButton.setVisibility(View.VISIBLE);
                confirmNoButton.setVisibility(View.VISIBLE);
            } else {
                confirmYesButton.setVisibility(View.GONE);
                confirmNoButton.setVisibility(View.GONE);
            }
        });
    }

    private void normalizeEmbedding(float[] emb) {
        float norm = 0;
        for (float v : emb) norm += v * v;
        norm = (float) Math.sqrt(norm);
        if (norm > 0) {
            for (int i = 0; i < emb.length; i++) emb[i] /= norm;
        }
    }

    private boolean loadEmbeddingsFromAssets() {
        try {
            InputStream is = getAssets().open("embeddings.json");
            String json = readStreamToString(is);
            is.close();

            // Parse JSON as Map<String, List<List<Double>>> first
            Map<String, List<List<Double>>> temp = new Gson().fromJson(
                    json,
                    new TypeToken<Map<String, List<List<Double>>>>() {}.getType()
            );

            KNOWN_FACE_EMBEDDINGS.clear();

            for (Map.Entry<String, List<List<Double>>> entry : temp.entrySet()) {
                String name = entry.getKey();
                List<List<Double>> embeddingsList = entry.getValue();

                List<Double> firstEmb = embeddingsList.get(0);
                float[] embArr = new float[firstEmb.size()];
                for (int j = 0; j < firstEmb.size(); j++) {
                    embArr[j] = firstEmb.get(j).floatValue();
                }
                KNOWN_FACE_EMBEDDINGS.put(name, embArr);
            }

            Log.i(TAG, "✅ Loaded embeddings from assets: " + KNOWN_FACE_EMBEDDINGS.size());
            return true;

        } catch (Exception e) {
            Log.e(TAG, "Error loading embeddings from assets", e);
            return false;
        }
    }


    private boolean loadEmbeddingsFromStorage() {
        try {
            File embeddingsFile = new File(getExternalFilesDir("Pictures/FacultyPhotos"), "embeddings.json");
            if (!embeddingsFile.exists()) {
                Log.d(TAG, "Embeddings file does not exist");
                return false;
            }

            String jsonStr = new String(java.nio.file.Files.readAllBytes(embeddingsFile.toPath()), StandardCharsets.UTF_8);
            JSONObject jsonObj = new JSONObject(jsonStr);

            facultyEmbeddings.clear();
            KNOWN_FACE_EMBEDDINGS.clear();

            Iterator<String> keys = jsonObj.keys();
            while (keys.hasNext()) {
                String facultyName = keys.next();
                JSONArray embeddingsArray = jsonObj.getJSONArray(facultyName);

                if (embeddingsArray.length() == 0) continue;

                // Store all embeddings in facultyEmbeddings
                List<float[]> allEmbeddings = new ArrayList<>();
                for (int i = 0; i < embeddingsArray.length(); i++) {
                    JSONArray arr = embeddingsArray.getJSONArray(i);
                    float[] emb = new float[arr.length()];
                    for (int j = 0; j < arr.length(); j++) {
                        emb[j] = (float) arr.getDouble(j);
                    }
                    allEmbeddings.add(emb);
                }
                facultyEmbeddings.put(facultyName, allEmbeddings);

                // Compute the average embedding for KNOWN_FACE_EMBEDDINGS
                int embSize = allEmbeddings.get(0).length;
                float[] avgEmb = new float[embSize];
                for (float[] emb : allEmbeddings) {
                    for (int j = 0; j < embSize; j++) {
                        avgEmb[j] += emb[j];
                    }
                }
                for (int j = 0; j < embSize; j++) {
                    avgEmb[j] /= allEmbeddings.size();
                }

                // Put only one key per person
                KNOWN_FACE_EMBEDDINGS.put(facultyName, avgEmb);
            }

            Log.d(TAG, "✅ Embeddings loaded successfully from storage.");
            Log.d(TAG, "Faculties loaded: " + facultyEmbeddings.size());
            Log.d(TAG, "KNOWN_FACE_EMBEDDINGS loaded: " + KNOWN_FACE_EMBEDDINGS.size());

            return true;

        } catch (Exception e) {
            Log.e(TAG, "Failed to load embeddings from storage", e);
            return false;
        }
    }

    private String readStreamToString(InputStream is) throws Exception {
        StringBuilder sb = new StringBuilder();
        byte[] buffer = new byte[1024];
        int length;
        while ((length = is.read(buffer)) != -1) {
            sb.append(new String(buffer, 0, length, StandardCharsets.UTF_8));
        }
        return sb.toString();
    }

    private void testLoadEmbeddings() {
        try {
            File embeddingsFile = new File(
                    getExternalFilesDir(Environment.DIRECTORY_PICTURES),
                    "FacultyPhotos/embeddings.json"
            );

            Log.d(TAG, "Embeddings file exists: " + embeddingsFile.exists());
            Log.d(TAG, "Embeddings file path: " + embeddingsFile.getAbsolutePath());

            if (!embeddingsFile.exists()) return;

            FileInputStream fis = new FileInputStream(embeddingsFile);
            String json = readStreamToString(fis);
            fis.close();

            Log.d(TAG, "JSON content snippet: " + json.substring(0, Math.min(json.length(), 200)) + "...");

            // Try parsing as Map<String, List<List<Double>>>
            Map<String, List<List<Double>>> temp = new Gson().fromJson(
                    json,
                    new TypeToken<Map<String, List<List<Double>>>>(){}.getType()
            );

            if (temp == null || temp.isEmpty()) {
                Log.e(TAG, "Parsed JSON is null or empty!");
                return;
            }

            for (Map.Entry<String, List<List<Double>>> entry : temp.entrySet()) {
                String name = entry.getKey();
                List<List<Double>> embeddingsList = entry.getValue();
                Log.d(TAG, "Person: " + name + " | # of embeddings: " + embeddingsList.size());

                if (!embeddingsList.isEmpty()) {
                    List<Double> firstEmb = embeddingsList.get(0);
                    Log.d(TAG, "First embedding sample: " + firstEmb.subList(0, Math.min(firstEmb.size(), 10)));
                }
            }

        } catch (Exception e) {
            Log.e(TAG, "Error testing embeddings load", e);
        }
    }


    @Override
    protected void onDestroy() {
        super.onDestroy();
        stopConfirmationTimer();
        stopVisualCountdown();
        if (cameraExecutor != null) cameraExecutor.shutdown();
        if (faceNet != null) faceNet.close();
        if (mIsBound) {
            unbindService(mConnection);
            mIsBound = false;
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions,
                                           @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);

        if (requestCode == REQUEST_CAMERA_PERMISSION) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                initializeSystem();
            } else {
                finish();
            }
        }
    }

}