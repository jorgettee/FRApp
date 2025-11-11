package com.sd.facultyfacialrecognition;

import android.content.Intent;
import android.os.Bundle;
import android.util.Log;
import android.widget.Button;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;

import com.google.android.gms.auth.api.signin.GoogleSignIn;
import com.google.android.gms.auth.api.signin.GoogleSignInAccount;
import com.google.android.gms.auth.api.signin.GoogleSignInClient;
import com.google.android.gms.auth.api.signin.GoogleSignInOptions;
import com.google.android.gms.common.api.ApiException;
import com.google.firebase.auth.AuthCredential;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.GoogleAuthProvider;

public class LoginActivity extends AppCompatActivity {

    private static final int RC_SIGN_IN = 100;
    private static final String TAG = "LoginActivity";

    private FirebaseAuth mAuth;
    private GoogleSignInClient mGoogleSignInClient;
    private Button buttonGoogleSignIn;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_login);

        buttonGoogleSignIn = findViewById(R.id.buttonGoogleSignIn);
        mAuth = FirebaseAuth.getInstance();

        // Configure Google Sign-In
        GoogleSignInOptions gso = new GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
                .requestIdToken(getString(R.string.default_web_client_id))
                .requestEmail()
                .build();
        mGoogleSignInClient = GoogleSignIn.getClient(this, gso);

        buttonGoogleSignIn.setOnClickListener(v -> signIn());
    }

    private void signIn() {
        Intent signInIntent = mGoogleSignInClient.getSignInIntent();
        startActivityForResult(signInIntent, RC_SIGN_IN);
    }

    @Override
    public void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);

        if (requestCode == RC_SIGN_IN) {
            try {
                GoogleSignInAccount account = GoogleSignIn.getSignedInAccountFromIntent(data)
                        .getResult(ApiException.class);

                if (account != null) {
                    String email = account.getEmail();
                    if (email != null && email.endsWith("@g.batstate-u.edu.ph")) {
                        firebaseAuthWithGoogle(account);
                    } else {
                        // Invalid email — force logout
                        signOutImmediately();
                        Toast.makeText(this, "Access denied. Use a @g.batstate-u.edu.ph email.", Toast.LENGTH_LONG).show();
                    }
                }

            } catch (ApiException e) {
                Log.e(TAG, "Google sign-in failed", e);
                Toast.makeText(this, "Google sign-in failed: " + e.getMessage(), Toast.LENGTH_LONG).show();
            }
        }
    }

    private void firebaseAuthWithGoogle(GoogleSignInAccount acct) {
        if (acct == null) {
            Log.e(TAG, "GoogleSignInAccount is null");
            return;
        }

        AuthCredential credential = GoogleAuthProvider.getCredential(acct.getIdToken(), null);
        mAuth.signInWithCredential(credential).addOnCompleteListener(this, task -> {
            if (task.isSuccessful()) {
                Toast.makeText(this, "Signed in as " + acct.getEmail(), Toast.LENGTH_SHORT).show();
                Intent intent = new Intent(LoginActivity.this, AdminActivity.class);
                startActivity(intent);
                finish(); // Prevent going back
            } else {
                Log.e(TAG, "Firebase authentication failed", task.getException());
                Toast.makeText(this, "Authentication failed.", Toast.LENGTH_LONG).show();
            }
        });
    }

    // 🔒 Always logout when leaving this screen
    @Override
    protected void onDestroy() {
        super.onDestroy();
        signOutImmediately();
    }

    private void signOutImmediately() {
        mAuth.signOut();
        mGoogleSignInClient.signOut();
    }

}
