package com.sd.facultyfacialrecognition;

import android.content.Intent;
import android.os.Bundle;
import android.widget.Button;
import android.widget.TextView;
import androidx.appcompat.app.AppCompatActivity;

public class DashboardActivity extends AppCompatActivity {

    private TextView welcomeText;
    private TextView statusText;
    private Button scanAgainButton;
    private String profName;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_dashboard);

        profName = getIntent().getStringExtra("profName");

        welcomeText = findViewById(R.id.text_welcome);
        statusText = findViewById(R.id.text_status);
        scanAgainButton = findViewById(R.id.btn_scan_again);

        welcomeText.setText("Prof. " + profName);

        String status = getIntent().getStringExtra("status");
        if (status == null) status = "Status: In Class";
        statusText.setText(status);

        scanAgainButton.setOnClickListener(v -> {
            Intent intent = new Intent(DashboardActivity.this, MainActivity.class);
            intent.putExtra("mode", "rescan");
            intent.putExtra("profName", profName);
            if (statusText.getText().toString().contains("on break")) {
                intent.putExtra("from_break", true);
            }
            startActivity(intent);
            finish();
        });


    }
}
