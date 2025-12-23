package com.example.our_thesis_arm;

import android.content.Intent;
import android.graphics.Color;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import java.util.ArrayList;

public class MainActivity extends AppCompatActivity {

    // UI
    private SeekBar seekGrip, seekWrist, seekWristRotation, seekElbow, seekShoulder, seekBase;
    private TextView tvGripperAngle, tvWristAngle, tvWristRotationAngle, tvElbowAngle, tvShoulderAngle, tvBaseAngle, tvPoseCount, tvConnectionStatus;
    private Button btnSavePose, btnPlayPose, btnStop, btnReset, btnConnect, btnCamera,btnDisconnect;
    private CheckBox checkLoop;

    private BluetoothManager bt = BluetoothManager.getInstance();
    private final int MAX_POSES = 10;
    private int saveCount = 0;

    // DEBOUNCE HANDLERS

    private final int SEEK_DEBOUNCE_MS = 120;
    private long lastClickTime = 0;
    private final int CLICK_DEBOUNCE_MS = 300;

    private long lastGripTime = 0;
    private long lastWristTime = 0;
    private long lastWristRotationTime = 0;
    private long lastElbowTime = 0;
    private long lastShoulderTime = 0;
    private long lastBaseTime = 0;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        bindViews();
        setupSeekBarListeners();
        setupButtonListeners();
    }

    private void bindViews() {
        seekGrip = findViewById(R.id.seekGrip);
        seekWrist = findViewById(R.id.seekWrist);
        seekWristRotation = findViewById(R.id.seekWristRotation);
        seekElbow = findViewById(R.id.seekElbow);
        seekShoulder = findViewById(R.id.seekShoulder);
        seekBase = findViewById(R.id.seekBase);

        tvGripperAngle = findViewById(R.id.tvGripperAngle);
        tvWristAngle = findViewById(R.id.tvWristAngle);
        tvWristRotationAngle = findViewById(R.id.tvWristRotationAngle);
        tvElbowAngle = findViewById(R.id.tvElbowAngle);
        tvShoulderAngle = findViewById(R.id.tvShoulderAngle);
        tvBaseAngle = findViewById(R.id.tvBaseAngle);

        tvPoseCount = findViewById(R.id.tvPoseCount);
        tvConnectionStatus = findViewById(R.id.tvConnectionStatus);

        btnSavePose = findViewById(R.id.btnSavePose);
        btnPlayPose = findViewById(R.id.btnPlayPose);
        btnStop = findViewById(R.id.btnStop);
        btnReset = findViewById(R.id.btnReset);
        btnConnect = findViewById(R.id.btnConnect);
        btnCamera = findViewById(R.id.btnCamera);
        btnDisconnect = findViewById(R.id.btnDisconnect);


        checkLoop = findViewById(R.id.checkLoop);
    }

    /** SEND COMMAND: ID,value */
    private void sendJointData(int id, int value) {
        if (bt.isConnected()) {
            String data = id + "," + value;
            bt.sendData(data);
        }
    }

    private void setupSeekBarListeners() {
        SeekBar.OnSeekBarChangeListener listener = new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                if (!fromUser) return;

                long now = System.currentTimeMillis();

                if (seekBar.getId() == R.id.seekGrip) {
                    tvGripperAngle.setText(progress + "°");
                    if (now - lastGripTime < SEEK_DEBOUNCE_MS) return;
                    lastGripTime = now;
                    sendJointData(1, progress);
                } else if (seekBar.getId() == R.id.seekWrist) {
                    tvWristAngle.setText(progress + "°");
                    if (now - lastWristTime < SEEK_DEBOUNCE_MS) return;
                    lastWristTime = now;
                    sendJointData(2, progress);
                } else if (seekBar.getId() == R.id.seekWristRotation) {
                    tvWristRotationAngle.setText(progress + "°");
                    if (now - lastWristRotationTime < SEEK_DEBOUNCE_MS) return;
                    lastWristRotationTime = now;
                    sendJointData(3, progress);
                } else if (seekBar.getId() == R.id.seekElbow) {
                    tvElbowAngle.setText(progress + "°");
                    if (now - lastElbowTime < SEEK_DEBOUNCE_MS) return;
                    lastElbowTime = now;
                    sendJointData(4, progress);
                } else if (seekBar.getId() == R.id.seekShoulder) {
                    tvShoulderAngle.setText(progress + "°");
                    if (now - lastShoulderTime < SEEK_DEBOUNCE_MS) return;
                    lastShoulderTime = now;
                    sendJointData(5, progress);
                } else if (seekBar.getId() == R.id.seekBase) {
                    tvBaseAngle.setText(progress + "°");
                    if (now - lastBaseTime < SEEK_DEBOUNCE_MS) return;
                    lastBaseTime = now;
                    sendJointData(6, progress);
                }
            }

            @Override public void onStartTrackingTouch(SeekBar seekBar) {}
            @Override public void onStopTrackingTouch(SeekBar seekBar) {}
        };

        // Attach the same listener to all SeekBars
        seekGrip.setOnSeekBarChangeListener(listener);
        seekWrist.setOnSeekBarChangeListener(listener);
        seekWristRotation.setOnSeekBarChangeListener(listener);
        seekElbow.setOnSeekBarChangeListener(listener);
        seekShoulder.setOnSeekBarChangeListener(listener);
        seekBase.setOnSeekBarChangeListener(listener);
    }


    private boolean allowClick() {
        long now = System.currentTimeMillis();
        if (now - lastClickTime < CLICK_DEBOUNCE_MS) return false;
        lastClickTime = now;
        return true;
    }

    private void setupButtonListeners() {

        btnConnect.setOnClickListener(v -> {
            if (allowClick()) scanDevices();
        });

        btnSavePose.setOnClickListener(v -> {
            if (!allowClick()) return;
            if (!bt.isConnected()) {
                Toast.makeText(this, "Bluetooth not connected!", Toast.LENGTH_SHORT).show();
                return;
            }
            if (saveCount >= MAX_POSES) {
                Toast.makeText(this, "Maximum 10 saves reached", Toast.LENGTH_SHORT).show();
                return;
            }
            bt.sendData("S");
            saveCount++;
            tvPoseCount.setText("Saved Positions: " + saveCount);
            Toast.makeText(this, "Saved", Toast.LENGTH_SHORT).show();
        });

        btnPlayPose.setOnClickListener(v -> {
            if (!allowClick()) return;
            if (!bt.isConnected()) {
                Toast.makeText(this, "Bluetooth not connected!", Toast.LENGTH_SHORT).show();
                return;
            }
            bt.sendData("P");
            Toast.makeText(this, "Playing", Toast.LENGTH_SHORT).show();
        });

        btnStop.setOnClickListener(v -> {
            if (!allowClick()) return;
            if (!bt.isConnected()) {
                Toast.makeText(this, "Bluetooth not connected!", Toast.LENGTH_SHORT).show();
                return;
            }
            bt.sendData("St");
            Toast.makeText(this, "Stopped", Toast.LENGTH_SHORT).show();
        });

        btnReset.setOnClickListener(v -> {
            if (!allowClick()) return;
            if (!bt.isConnected()) {
                Toast.makeText(this, "Bluetooth not connected!", Toast.LENGTH_SHORT).show();
                return;
            }
            bt.sendData("R");
            saveCount = 0;
            tvPoseCount.setText("Saved Positions: " + saveCount);
            Toast.makeText(this, "Reset complete", Toast.LENGTH_SHORT).show();
        });

        btnCamera.setOnClickListener(v -> {
            if (allowClick()) {
                Intent intent = new Intent(MainActivity.this, CameraActivity.class);
                startActivity(intent);
            }
        });

        checkLoop.setOnCheckedChangeListener((b, checked) -> {
            if (!bt.isConnected()) return;
            if (checked) bt.sendData("LoopON");
            else bt.sendData("LoopOFF");
        });

        btnDisconnect.setOnClickListener(b -> {
            if (!allowClick()) return;
            if(bt.isConnected()) {
                bt.disconnect();
                tvConnectionStatus.setText("Disconnected");
                tvConnectionStatus.setTextColor(Color.RED);
                Toast.makeText(this, "Bluetooth disconnected", Toast.LENGTH_SHORT).show();
            }
            else {
                Toast.makeText(this, "Bluetooth already disconnected", Toast.LENGTH_SHORT).show();
            }
        });
    }

    /** Bluetooth Scan & Connect */
    private void scanDevices() {
        if (!bt.isBluetoothEnabled()) {
            Toast.makeText(this, "Please enable Bluetooth first", Toast.LENGTH_SHORT).show();
            return;
        }

        ArrayList<String> deviceNames = bt.getPairedDeviceNames();
        if (deviceNames.isEmpty()) {
            Toast.makeText(this, "No paired devices found", Toast.LENGTH_SHORT).show();
            return;
        }

        String[] devices = deviceNames.toArray(new String[0]);
        new android.app.AlertDialog.Builder(this)
                .setTitle("Select Bluetooth Device")
                .setItems(devices, (dialog, which) -> {
                    String selectedDevice = devices[which];
                    bt.connectToDevice(selectedDevice);
                    tvConnectionStatus.setText("Connected to: " + selectedDevice);
                    tvConnectionStatus.setTextColor(Color.BLUE);
                })
                .show();
    }
    
    @Override
    protected void onResume() {
        super.onResume();

        if (bt.isConnected()) {
            tvConnectionStatus.setText("Connected");
            tvConnectionStatus.setTextColor(Color.GREEN);
        } else {
            tvConnectionStatus.setText("Disconnected");
            tvConnectionStatus.setTextColor(Color.RED);
        }
    }
}
