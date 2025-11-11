package com.sd.facultyfacialrecognition;

import android.app.AlertDialog;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.widget.Button;
import android.widget.EditText;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

public class PinLockActivity extends AppCompatActivity {

    private EditText editTextPin;
    private Button buttonSubmit, buttonReset;
    private SharedPreferences sharedPreferences;

    private static final String PREFS_NAME = "PinPrefs";
    private static final String KEY_PIN = "user_pin";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_pin_lock);

        editTextPin = findViewById(R.id.editTextPin);
        buttonSubmit = findViewById(R.id.buttonSubmit);
        buttonReset = findViewById(R.id.buttonReset);

        sharedPreferences = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);

        buttonSubmit.setOnClickListener(v -> handlePinSubmit());
        buttonReset.setOnClickListener(v -> attemptResetPin());
    }

    private void handlePinSubmit() {
        String enteredPin = editTextPin.getText().toString().trim();

        if (enteredPin.isEmpty()) {
            Toast.makeText(this, "Please enter a PIN", Toast.LENGTH_SHORT).show();
            return;
        }

        String savedPin = sharedPreferences.getString(KEY_PIN, null);

        if (savedPin == null) {
            // No PIN yet → set new one
            sharedPreferences.edit().putString(KEY_PIN, enteredPin).apply();
            Toast.makeText(this, "New PIN created successfully!", Toast.LENGTH_SHORT).show();
            editTextPin.setText("");
        } else if (enteredPin.equals(savedPin)) {
            // Correct PIN → go to LoginActivity
            Toast.makeText(this, "Access granted!", Toast.LENGTH_SHORT).show();
            Intent intent = new Intent(PinLockActivity.this, LoginActivity.class);
            startActivity(intent);
            finish();
        } else {
            Toast.makeText(this, "Incorrect PIN. Try again.", Toast.LENGTH_SHORT).show();
        }
    }

    private void attemptResetPin() {
        String savedPin = sharedPreferences.getString(KEY_PIN, null);

        if (savedPin == null) {
            Toast.makeText(this, "No PIN set yet. Please create one first.", Toast.LENGTH_LONG).show();
            return;
        }

        // Ask user to enter the old PIN first
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle("Reset PIN");

        final EditText input = new EditText(this);
        input.setHint("Enter current PIN");
        input.setInputType(android.text.InputType.TYPE_CLASS_NUMBER | android.text.InputType.TYPE_NUMBER_VARIATION_PASSWORD);
        builder.setView(input);

        builder.setPositiveButton("Confirm", (dialog, which) -> {
            String enteredOldPin = input.getText().toString().trim();

            if (enteredOldPin.equals(savedPin)) {
                showNewPinDialog(); // proceed to create new PIN
            } else {
                Toast.makeText(this, "Incorrect old PIN. Cannot reset.", Toast.LENGTH_SHORT).show();
            }
        });

        builder.setNegativeButton("Cancel", (dialog, which) -> dialog.cancel());
        builder.show();
    }

    private void showNewPinDialog() {
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle("Create New PIN");

        final EditText newPinInput = new EditText(this);
        newPinInput.setHint("Enter new PIN");
        newPinInput.setInputType(android.text.InputType.TYPE_CLASS_NUMBER | android.text.InputType.TYPE_NUMBER_VARIATION_PASSWORD);
        builder.setView(newPinInput);

        builder.setPositiveButton("Save", (dialog, which) -> {
            String newPin = newPinInput.getText().toString().trim();

            if (newPin.isEmpty()) {
                Toast.makeText(this, "PIN cannot be empty.", Toast.LENGTH_SHORT).show();
            } else {
                sharedPreferences.edit().putString(KEY_PIN, newPin).apply();
                Toast.makeText(this, "PIN reset successfully!", Toast.LENGTH_SHORT).show();
                editTextPin.setText("");
            }
        });

        builder.setNegativeButton("Cancel", (dialog, which) -> dialog.cancel());
        builder.show();
    }

}
