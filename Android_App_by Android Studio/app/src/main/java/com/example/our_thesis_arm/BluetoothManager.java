package com.example.our_thesis_arm;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothSocket;
import android.util.Log;

import java.io.IOException;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Set;
import java.util.UUID;

public class BluetoothManager {

    private static BluetoothManager instance;

    private BluetoothAdapter bluetoothAdapter;
    private BluetoothDevice connectedDevice;
    private BluetoothSocket socket;
    private OutputStream outputStream;

    private final Object writeLock = new Object();

    // Unique UUID for HC-05 Serial Communication
    private final UUID SERIAL_UUID =
            UUID.fromString("00001101-0000-1000-8000-00805F9B34FB");

    private BluetoothManager() {
        bluetoothAdapter = BluetoothAdapter.getDefaultAdapter();
    }

    public static BluetoothManager getInstance() {
        if (instance == null) instance = new BluetoothManager();
        return instance;
    }

    /** Bluetooth enabled check */
    public boolean isBluetoothEnabled() {
        return bluetoothAdapter != null && bluetoothAdapter.isEnabled();
    }

    /** Get list of paired devices */
    public ArrayList<String> getPairedDeviceNames() {
        ArrayList<String> list = new ArrayList<>();
        if (!isBluetoothEnabled()) return list;

        Set<BluetoothDevice> devices = bluetoothAdapter.getBondedDevices();
        for (BluetoothDevice device : devices) {
            list.add(device.getName());
        }
        return list;
    }

    /** Connect to device by name */
    public void connectToDevice(String name) {
        if (!isBluetoothEnabled()) return;

        for (BluetoothDevice device : bluetoothAdapter.getBondedDevices()) {
            if (device.getName().equals(name)) {
                connectedDevice = device;
                new Thread(this::connect).start();
                break;
            }
        }
    }

    /** Background connection thread */
    private void connect() {
        try {
            socket = connectedDevice.createRfcommSocketToServiceRecord(SERIAL_UUID);
            bluetoothAdapter.cancelDiscovery();
            socket.connect();

            outputStream = socket.getOutputStream();

            Log.d("BluetoothManager", "Connected to " + connectedDevice.getName());
        } catch (IOException e) {
            Log.e("BluetoothManager", "Connection failed: " + e.getMessage());
            closeSocket();
        }
    }

    /** Safe write function */
    public void sendData(String data) {
        if (!isConnected()) return;

        synchronized (writeLock) {
            try {
                outputStream.write((data + "\n").getBytes());
                outputStream.flush();
            } catch (IOException e) {
                Log.e("BluetoothManager", "Write failed: " + e.getMessage());
                closeSocket();
            }
        }
    }

    /** Correct connection check */
    public boolean isConnected() {
        return socket != null && socket.isConnected();
    }

    /** Disconnect safely */
    public void disconnect() {
        closeSocket();
    }

    /** Close socket safely */
    private void closeSocket() {
        try {
            if (outputStream != null) outputStream.close();
            if (socket != null) socket.close();
        } catch (IOException ignored) {}
        outputStream = null;
        socket = null;
    }
}
