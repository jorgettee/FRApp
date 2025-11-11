package com.sd.facultyfacialrecognition;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.widget.Button;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

public class HomeActivity extends AppCompatActivity {

    private static final int REQUEST_CAMERA_PERMISSION = 1001;
    private Button buttonFacialRecognition, buttonAdminPanel;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_home);

        buttonFacialRecognition = findViewById(R.id.buttonFacialRecognition);
        buttonAdminPanel = findViewById(R.id.buttonAdminPanel);

        // Always set button click listeners here
        setupButtons();

        // Then handle permission
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
                != PackageManager.PERMISSION_GRANTED) {

            ActivityCompat.requestPermissions(
                    this,
                    new String[]{Manifest.permission.CAMERA},
                    REQUEST_CAMERA_PERMISSION
            );
        }
    }

    private void setupButtons() {
        buttonFacialRecognition.setOnClickListener(v ->
                startActivity(new Intent(HomeActivity.this, MainActivity.class)));

        buttonAdminPanel.setOnClickListener(v ->
                startActivity(new Intent(HomeActivity.this, PinLockActivity.class)));
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions,
                                           @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);

        if (requestCode == REQUEST_CAMERA_PERMISSION) {
            if (!(grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED)) {
                Toast.makeText(this, "Camera permission is required to continue.", Toast.LENGTH_LONG).show();
                finish();
            }
            // ✅ NO NEED to reset buttons here — already set above
        }
    }
}

