package com.example.our_thesis_arm;

import android.content.Intent;
import android.graphics.Color;
import android.os.Bundle;
import android.widget.Button;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

public class CameraActivity extends AppCompatActivity {

    private Button btnManual,btnSwitchCamera,btnStartCamera;
    private TextView tvConnectionStatus;

    private BluetoothManager bt = BluetoothManager.getInstance();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_camera);

        btnManual = findViewById(R.id.btnManual);
        tvConnectionStatus = findViewById(R.id.tvConnectionStatus);

        btnManual.setOnClickListener(v -> {
            Intent intent = new Intent(CameraActivity.this, MainActivity.class);
            startActivity(intent);
        });
    }

    @Override
    protected void onResume() {
        super.onResume();

        // SAME CODE AS MAIN ACTIVITY
        if (bt.isConnected()) {
            tvConnectionStatus.setText("Connected to");
            tvConnectionStatus.setTextColor(Color.GREEN);
        } else {
            tvConnectionStatus.setText("Disconnected");
            tvConnectionStatus.setTextColor(Color.RED);
        }
    }
}