package com.sd.facultyfacialrecognition;

import android.Manifest;
import android.app.Service;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothSocket;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Binder;
import android.os.IBinder;
import android.util.Log;
import androidx.core.app.ActivityCompat;
import java.io.IOException;
import java.io.OutputStream;
import java.util.UUID;

public class BluetoothService extends Service {

    private static final String TAG = "BluetoothService";
    private BluetoothAdapter bluetoothAdapter;
    private BluetoothSocket bluetoothSocket;
    private OutputStream outputStream;
    private final IBinder binder = new LocalBinder();
    // Standard SerialPortService ID
    private static final UUID MY_UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB");
    private static final String ESP32_MAC_ADDRESS = "20:e7:c8:b4:3c:ea"; // MAC address ESP32

    public class LocalBinder extends Binder {
        BluetoothService getService() {
            return BluetoothService.this;
        }
    }

    @Override
    public IBinder onBind(Intent intent) {
        return binder;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        bluetoothAdapter = BluetoothAdapter.getDefaultAdapter();
        if (bluetoothAdapter == null) {
            Log.e(TAG, "Bluetooth not supported on this device");
            stopSelf();
        }
    }

    public boolean connectToDevice() {
        if (bluetoothAdapter == null || !bluetoothAdapter.isEnabled()) {
            Log.e(TAG, "Bluetooth is not enabled");
            return false;
        }

        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN) != PackageManager.PERMISSION_GRANTED) {
            Log.e(TAG, "Bluetooth scan permission not granted");
            return false;
        }

        try {
            BluetoothDevice device = bluetoothAdapter.getRemoteDevice(ESP32_MAC_ADDRESS);
            if (ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
                Log.e(TAG, "Bluetooth connect permission not granted");
                return false;
            }
            bluetoothSocket = device.createRfcommSocketToServiceRecord(MY_UUID);
            bluetoothSocket.connect();
            outputStream = bluetoothSocket.getOutputStream();
            return true;
        } catch (IllegalArgumentException e) {
            Log.e(TAG, "Invalid Bluetooth MAC address: " + ESP32_MAC_ADDRESS, e);
            return false;
        } catch (IOException e) {
            Log.e(TAG, "Error connecting to ESP32", e);
            try {
                if (bluetoothSocket != null) {
                    bluetoothSocket.close();
                }
            } catch (IOException ex) {
                Log.e(TAG, "Error closing socket", ex);
            }
            return false;
        }
    }

    public void sendData(String message) {
        String dataToSend = "";
        if ("lock".equals(message)) {
            dataToSend = "1";
        } else if ("unlock".equals(message)) {
            dataToSend = "0";
        }

        if (outputStream != null && !dataToSend.isEmpty()) {
            try {
                outputStream.write(dataToSend.getBytes());
                Log.d(TAG, "Sent data: " + dataToSend);
            } catch (IOException e) {
                Log.e(TAG, "Error sending data", e);
            }
        } else {
            Log.w(TAG, "Could not send data: outputStream is null or message is not valid.");
        }
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        try {
            if (outputStream != null) {
                outputStream.close();
            }
            if (bluetoothSocket != null) {
                bluetoothSocket.close();
            }
        } catch (IOException e) {
            Log.e(TAG, "Error closing resources", e);
        }
    }
}
