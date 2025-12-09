package com.sd.facultyfacialrecognition;

import android.content.Intent;
import android.os.Bundle;
import android.widget.Button;
import android.widget.EditText;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.google.firebase.Timestamp;
import com.google.firebase.firestore.FirebaseFirestore;

import java.util.HashMap;
import java.util.Map;

public class PinLockActivity extends AppCompatActivity {

    private EditText editTextPin;
    private Button buttonSubmit;

    private static final String FIXED_PIN = "1234";
    private FirebaseFirestore firestore;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_pin_lock);

        editTextPin = findViewById(R.id.editTextPin);
        buttonSubmit = findViewById(R.id.buttonSubmit);

        firestore = FirebaseFirestore.getInstance();

        buttonSubmit.setBackgroundColor(getResources().getColor(android.R.color.holo_blue_dark));

        buttonSubmit.setOnClickListener(v -> handlePinSubmit());
    }

    private void handlePinSubmit() {
        String enteredPin = editTextPin.getText().toString().trim();

        if (enteredPin.isEmpty()) {
            Toast.makeText(this, "Please enter a PIN", Toast.LENGTH_SHORT).show();
            return;
        }

        if (enteredPin.equals(FIXED_PIN)) {
            Toast.makeText(this, "Access granted!", Toast.LENGTH_SHORT).show();

            logAccessToFirestore();

            Intent intent = new Intent(PinLockActivity.this, AdminActivity.class);
            startActivity(intent);
            finish();
        } else {
            Toast.makeText(this, "Incorrect PIN. Try again.", Toast.LENGTH_SHORT).show();
        }
    }

    private void logAccessToFirestore() {
        Map<String, Object> logEntry = new HashMap<>();
        logEntry.put("pin", FIXED_PIN);
        logEntry.put("timestamp", Timestamp.now());

        firestore.collection("access_to_database_logs")
                .add(logEntry)
                .addOnSuccessListener(documentReference ->
                        Toast.makeText(this, "Access logged to Firestore.", Toast.LENGTH_SHORT).show())
                .addOnFailureListener(e ->
                        Toast.makeText(this, "Failed to log access: " + e.getMessage(), Toast.LENGTH_LONG).show());
    }
}
