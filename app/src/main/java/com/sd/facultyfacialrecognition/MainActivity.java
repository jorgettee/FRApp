package com.sd.facultyfacialrecognition;

import android.Manifest;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.os.Bundle;
import android.os.Handler;
import android.util.Log;
import android.util.Size;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.ImageAnalysis;
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
import com.google.mlkit.vision.face.FaceLandmark;

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

    // --- UI Components ---
    private PreviewView previewView;
    private FaceOverlayView overlayView;
    private TextView statusTextView;
    private TextView countdownTextView;
    private Button lockButton;
    private Button confirmYesButton;
    private Button confirmNoButton;

    // --- Core Model/Utility ---
    // NOTE: FaceNet, ImageAligner, InputImageUtils, FaceOverlayView are presumed available helper classes
    private FaceNet faceNet;
    private ImageAligner imageAligner;
    private ExecutorService cameraExecutor;

    private final Map<String, float[]> KNOWN_FACE_EMBEDDINGS = new HashMap<>();
    private static final float THRESHOLD = 1.3f;

    // --- STATE VARIABLES ---
    private static final int STABILITY_FRAMES_NEEDED = 60; // Approx 5 seconds
    private static final long UNLOCK_COOLDOWN_MILLIS = 10000;

    // Timer Constants
    private static final long CONFIRMATION_TIMEOUT_MILLIS = 10000; // 10 seconds total hold
    private static final int VISUAL_COUNTDOWN_SECONDS = 5; // Visual countdown runs for 5 seconds

    private String stableMatchName = "Scanning...";
    private String currentBestMatch = "Scanning...";
    private int stableMatchCount = 0;

    private boolean isDoorLocked = true;
    private boolean isAwaitingLockConfirmation = false;
    private boolean isAwaitingUnlockConfirmation = false;
    private boolean isAwaitingLockerRecognition = false; // <-- NEW STATE FOR 5s LOCK SCAN
    private String authorizedLocker = null;
    private String authorizedUnlocker = null;
    private long lastLockTimestamp = 0;

    // --- TIMER COMPONENTS ---
    private Handler confirmationHandler;
    private Runnable confirmationRunnable;
    private Handler countdownDisplayHandler;
    private Runnable countdownDisplayRunnable;
    private int confirmationTimeRemaining = VISUAL_COUNTDOWN_SECONDS;
    // --- END STATE VARIABLES ---

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        // 1. Initialize UI components
        previewView = findViewById(R.id.previewView);
        overlayView = findViewById(R.id.faceOverlayView);
        statusTextView = findViewById(R.id.text_status_label);
        countdownTextView = findViewById(R.id.text_countdown_status);

        lockButton = findViewById(R.id.lock_door_button);
        confirmYesButton = findViewById(R.id.confirm_yes_button);
        confirmNoButton = findViewById(R.id.confirm_no_button);

        lockButton.setVisibility(View.GONE);
        confirmYesButton.setVisibility(View.GONE);
        confirmNoButton.setVisibility(View.GONE);

        // Initialize Handlers
        confirmationHandler = new Handler();
        countdownDisplayHandler = new Handler();

        cameraExecutor = Executors.newSingleThreadExecutor();
        imageAligner = new ImageAligner();

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

    /**
     * Starts the 10-second non-visual timer for confirmation timeout.
     */
    private void startConfirmationTimer(boolean isLock) {
        stopConfirmationTimer();

        confirmationRunnable = () -> {
            // Time out acts as a "No" press for Lock or Unlock
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

    /**
     * Stops the 10-second non-visual timer.
     */
    private void stopConfirmationTimer() {
        if (confirmationRunnable != null) {
            confirmationHandler.removeCallbacks(confirmationRunnable);
            confirmationRunnable = null;
        }
    }

    /**
     * Starts the 5-second visual timer for confirmation display.
     */
    private void startVisualCountdown(String action, String matchName) {
        stopVisualCountdown();

        confirmationTimeRemaining = VISUAL_COUNTDOWN_SECONDS;

        countdownDisplayRunnable = new Runnable() {
            @Override
            public void run() {
                String currentAction = isAwaitingLockConfirmation ? "Lock" : "Unlock";

                if (confirmationTimeRemaining > 0) {
                    // Display countdown
                    // Ensure the authorizedName is used for display consistently
                    String name = isAwaitingLockConfirmation ? authorizedLocker : stableMatchName;

                    updateUiOnThread("Confirm " + currentAction + " Identity",
                            "Is this you: " + name + "?\nAction auto-cancels in " + (CONFIRMATION_TIMEOUT_MILLIS / 1000) + "s (Visual countdown: " + confirmationTimeRemaining + "s).");

                    confirmationTimeRemaining--;
                    countdownDisplayHandler.postDelayed(this, 1000);
                } else {
                    // Countdown finished, switch to static message while 10s timer runs out.
                    String name = isAwaitingLockConfirmation ? authorizedLocker : stableMatchName;
                    String finalStatus = isAwaitingLockConfirmation ? "Confirm Lock Identity" : "Confirm Unlock Identity";
                    String finalCountdown = "Is this you: " + name + "? (Awaiting confirmation)";
                    updateUiOnThread(finalStatus, finalCountdown);
                    stopVisualCountdown(); // Stop the visual timer now that it's complete
                }
            }
        };

        countdownDisplayHandler.post(countdownDisplayRunnable);
    }

    /**
     * Stops the 5-second visual timer.
     */
    private void stopVisualCountdown() {
        if (countdownDisplayRunnable != null) {
            countdownDisplayHandler.removeCallbacks(countdownDisplayRunnable);
            countdownDisplayRunnable = null;
        }
    }

    /**
     * Handles the manual "Lock Door" button click.
     * This now initiates the 5-second recognition period.
     */
    public void onLockDoorButtonClicked(View view) {

        if (!isDoorLocked && !isAwaitingLockConfirmation && !isAwaitingLockerRecognition) {

            // When Lock Door is pressed, start the 5-second scan sequence
            isAwaitingLockerRecognition = true;

            // Reset stability count to 0 to start the 5s hold check immediately
            stableMatchCount = 0;
            stableMatchName = "Scanning...";

            updateUiOnThread("Awaiting Locker Recognition", "Please hold a faculty face steady for 5 seconds to initiate lock.");

        } else {
            Log.w(TAG, "Lock button pressed in incorrect state.");
        }
    }

    /**
     * Handles the 'Yes' confirmation button for EITHER Lock or Unlock.
     */
    public void onConfirmYesClicked(View view) {
        stopConfirmationTimer(); // Stop the 10-second timer
        stopVisualCountdown(); // Stop the visual countdown

        if (isAwaitingLockConfirmation) {
            // LOCK CONFIRMATION
            isDoorLocked = true;
            isAwaitingLockConfirmation = false;
            // isAwaitingLockerRecognition should already be false, but confirm
            isAwaitingLockerRecognition = false;
            lastLockTimestamp = System.currentTimeMillis();

            // **TODO: Implement your physical door LOCK API call here!**

            resetStateAfterAction();
            updateUiOnThread("System Locked", "Door secured. Cooldown active.");

        } else if (isAwaitingUnlockConfirmation) {
            // UNLOCK CONFIRMATION
            isDoorLocked = false;
            isAwaitingUnlockConfirmation = false;
            authorizedUnlocker = stableMatchName; // Final confirmation of who unlocked it

            // **TODO: Implement your physical door UNLOCK API call here!**

            resetStateAfterAction();
            updateUiOnThread("Access Granted:\n" + authorizedUnlocker, "Door UNLOCKED. Press 'Lock Door' to secure.");
        }
    }

    /**
     * Handles the 'No' cancellation button for EITHER Lock or Unlock (Also used by timeout).
     */
    public void onConfirmNoClicked(View view) {
        stopConfirmationTimer(); // Stop the 10-second timer
        stopVisualCountdown(); // Stop the visual countdown

        if (isAwaitingLockConfirmation) {
            // Lock CANCELED: Returns to UNLOCKED state
            isAwaitingLockConfirmation = false;
            isAwaitingLockerRecognition = false; // NEW: Crucial to return to button state
            authorizedLocker = null;

            // Only update message if it was a user button click. Timeout handler updates its own message.
            if (view != null) {
                updateUiOnThread("Access Granted: " + authorizedUnlocker, "Lock cancelled by user. Door is UNLOCKED.");
            }

        } else if (isAwaitingUnlockConfirmation) {
            // Unlock CANCELED (Stay Locked)
            isAwaitingUnlockConfirmation = false;
            // The system naturally returns to STATE A: DOOR IS LOCKED

            // Only update message if it was a user button click.
            if (view != null) {
                updateUiOnThread("Access Denied", "Unlock cancelled by user. Awaiting recognition.");
            }
        }

        // Reset stability state to ensure a fresh scan is required next time
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
        // NOTE: InputImageUtils, FaceOverlayView, FaceNet, ImageAligner are MISSING helper classes
        Bitmap fullBmp = InputImageUtils.getBitmapFromInputImage(this, inputImage);
        if (fullBmp == null) return;

        String currentBestFrameMatch = "Scanning...";
        float bestDist = Float.MAX_VALUE;

        // --- 1. Face Detection and Recognition ---
        if (faces.isEmpty() || faces.size() > 1) {
            currentBestFrameMatch = "Scanning...";
        } else {
            Face face = faces.get(0);
            android.graphics.PointF leftEye = face.getLandmark(FaceLandmark.LEFT_EYE) != null ? face.getLandmark(FaceLandmark.LEFT_EYE).getPosition() : null;
            android.graphics.PointF rightEye = face.getLandmark(FaceLandmark.RIGHT_EYE) != null ? face.getLandmark(FaceLandmark.RIGHT_EYE).getPosition() : null;

            Bitmap faceBmp = imageAligner.alignAndCropFace(fullBmp, face.getBoundingBox(), leftEye, rightEye);

            if (faceBmp != null) {
                float[] emb = faceNet.getEmbedding(faceBmp);
                if (emb != null) {
                    normalizeEmbedding(emb);

                    for (Map.Entry<String, float[]> entry : KNOWN_FACE_EMBEDDINGS.entrySet()) {
                        float d = FaceNet.distance(emb, entry.getValue());
                        if (d < bestDist) {
                            bestDist = d;
                            currentBestFrameMatch = entry.getKey();
                        }
                    }
                    if (bestDist > THRESHOLD) {
                        currentBestFrameMatch = "Unknown";
                    }
                }
            }
            graphics.add(new FaceOverlayView.FaceGraphic(face.getBoundingBox(), "", bestDist));
        }

        this.currentBestMatch = currentBestFrameMatch;

        // --- 2. Lock/Unlock State Management ---

        String finalMessage = "";
        String countdownMessage = "";

        if (isAwaitingLockConfirmation || isAwaitingUnlockConfirmation) {
            // STATE: CONFIRMATION PENDING (Locked after 5s hold)

            // ... (Confirmation logic remains the same) ...

            String authorizedName = isAwaitingLockConfirmation ? authorizedLocker : stableMatchName;

            // If the visual timer has run out, display a static message. Otherwise, the timer's runnable manages the display.
            if (countdownDisplayRunnable == null) {
                finalMessage = isAwaitingLockConfirmation ? "Confirm Lock Identity" : "Confirm Unlock Identity";
                countdownMessage = "Is this you: " + authorizedName + "? (Awaiting confirmation)";
            } else {
                // If the visual timer is running, let the runnable handle the UI updates.
                runOnUiThread(() -> overlayView.setFaces(graphics));
                return;
            }

        } else if (isAwaitingLockerRecognition) { // <-- NEW STATE: SCANNING AFTER LOCK BUTTON PRESS

            updateStabilityState(currentBestFrameMatch);

            if (stableMatchCount >= STABILITY_FRAMES_NEEDED) {

                // Final Identity Check (Passed 5s hold)
                boolean isLockerIdentityConfirmed = !stableMatchName.equals("Unknown") &&
                        !stableMatchName.equals("Scanning...") &&
                        stableMatchName.equals(currentBestMatch);

                if (isLockerIdentityConfirmed) {
                    // Passed stability! Transition to the confirmation step.
                    isAwaitingLockerRecognition = false; // Exit scanning state
                    isAwaitingLockConfirmation = true; // Enter confirmation state
                    authorizedLocker = stableMatchName; // Record who is locking
                    stableMatchCount = 0; // Reset count

                    // Start Timers
                    startConfirmationTimer(true);
                    startVisualCountdown("Lock", authorizedLocker);

                } else {
                    // Stability achieved, but match is Unknown/Scanning. Fail gracefully.
                    isAwaitingLockerRecognition = false; // Return to button press state
                    finalMessage = "Recognition Failed";
                    countdownMessage = "Lock initiation failed. Please try again.";
                }
            } else if (stableMatchCount > 0 && !currentBestFrameMatch.equals("Unknown") && !currentBestFrameMatch.equals("Scanning...")) {
                // COUNTING DOWN for Lock
                int remainingFrames = STABILITY_FRAMES_NEEDED - stableMatchCount;
                finalMessage = "Recognizing: " + currentBestMatch;
                countdownMessage = String.format("Hold Steady to LOCK! (%d frames remaining)", remainingFrames);
            } else {
                // Default Locker Scan Status (While awaiting stability)
                finalMessage = "Awaiting Locker Recognition";
                countdownMessage = "Please hold a faculty face steady for 5 seconds to initiate lock.";
            }

        } else if (isDoorLocked) {
            // STATE A: DOOR IS LOCKED (Awaiting Unlock Scan)

            // Check Cooldown Period
            long timeSinceLock = System.currentTimeMillis() - lastLockTimestamp;
            if (timeSinceLock < UNLOCK_COOLDOWN_MILLIS) {
                long remainingSeconds = (UNLOCK_COOLDOWN_MILLIS - timeSinceLock) / 1000 + 1;
                finalMessage = "System Locked";
                countdownMessage = String.format("Unlock Cooldown Active: %d seconds remaining.", remainingSeconds);

                updateUiOnThread(finalMessage, countdownMessage);
                runOnUiThread(() -> overlayView.setFaces(graphics));
                return;
            }

            // Proceed with normal unlock recognition (5s stability required)
            updateStabilityState(currentBestFrameMatch);

            if (stableMatchCount >= STABILITY_FRAMES_NEEDED) {

                // Final Identity Confirmation Check (Passed 5s hold)
                boolean isUnlockIdentityConfirmed = !stableMatchName.equals("Unknown") &&
                        !stableMatchName.equals("Scanning...") &&
                        stableMatchName.equals(currentBestMatch);

                if (isUnlockIdentityConfirmed) {
                    // Passed stability! Now transition to the confirmation step.
                    isAwaitingUnlockConfirmation = true;
                    stableMatchCount = 0;

                    // Start Timers
                    startConfirmationTimer(false);
                    startVisualCountdown("Unlock", stableMatchName);

                } else {
                    // ACCESS DENIED
                    finalMessage = "Access Denied";
                    countdownMessage = "Recognition Failed. Please try again.";
                    stableMatchCount = 0;
                }
            } else if (stableMatchCount > 0 && !currentBestFrameMatch.equals("Unknown") && !currentBestFrameMatch.equals("Scanning...")) {
                // COUNTING DOWN for Unlock
                int remainingFrames = STABILITY_FRAMES_NEEDED - stableMatchCount;
                finalMessage = "Recognizing: " + currentBestMatch;
                countdownMessage = String.format("Hold Steady for unlock! (%d frames remaining)", remainingFrames);
            } else {
                // Default Locked Status
                finalMessage = "Awaiting Recognition";
                countdownMessage = "Scanning for faculty...";
            }
        } else {
            // STATE C: DOOR IS UNLOCKED (Awaiting Lock Button Press)
            finalMessage = "Access Granted: " + authorizedUnlocker;
            countdownMessage = "Door is UNLOCKED. Press 'Lock Door' to secure.";
        }

        // --- 3. UI Update ---
        updateUiOnThread(finalMessage, countdownMessage);

        overlayView.setImageSourceInfo(inputImage.getWidth(), inputImage.getHeight(), true);
        runOnUiThread(() -> overlayView.setFaces(graphics));
    }

    /**
     * Updates the state machine for stable recognition over time.
     */
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

    /**
     * Helper to update the TextViews and Button safely from the analyzer thread.
     */
    private void updateUiOnThread(final String status, final String countdown) {
        runOnUiThread(() -> {
            statusTextView.setText(status);
            countdownTextView.setText(countdown);

            // Control button visibility:
            if (isAwaitingLockConfirmation || isAwaitingUnlockConfirmation) {
                // Show YES/NO buttons for EITHER Lock or Unlock confirmation
                lockButton.setVisibility(View.GONE);
                confirmYesButton.setVisibility(View.VISIBLE);
                confirmNoButton.setVisibility(View.VISIBLE);
            } else if (!isDoorLocked && !isAwaitingLockerRecognition) {
                // Show LOCK button (Door is Open, no scan pending)
                lockButton.setVisibility(View.VISIBLE);
                confirmYesButton.setVisibility(View.GONE);
                confirmNoButton.setVisibility(View.GONE);
            } else {
                // Show NO buttons (Locked, Cooldown, Scanning, or Awaiting Locker Recognition)
                lockButton.setVisibility(View.GONE);
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
        stopConfirmationTimer(); // Stop the 10-second timer
        stopVisualCountdown(); // Stop the visual timer
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