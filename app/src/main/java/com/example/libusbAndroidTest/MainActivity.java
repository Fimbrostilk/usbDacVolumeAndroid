package com.example.libusbAndroidTest;

import androidx.appcompat.app.AppCompatActivity;

import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbEndpoint;
import android.hardware.usb.UsbInterface;
import android.hardware.usb.UsbManager;
import android.hardware.usb.UsbConstants;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.TextView;

import com.example.libusbAndroidTest.databinding.ActivityMainBinding;

import java.util.HashMap;

public class MainActivity extends AppCompatActivity {

    // Used to load the 'lib' library on application startup.
    static {
        System.loadLibrary("libusbAndroidTest");
    }

    private ActivityMainBinding binding;
    private UsbManager usbManager;

    private TextView tv;
    private EditText volInput;

    private CheckBox autoApply;

    private int deviceDescriptor = -1;

    private static String deviceName;
    private static final String TAG = "USB DAC Volume Adjustment" ;
    private static final String ACTION_USB_PERMISSION =
            "com.android.example.USB_PERMISSION";

    private final BroadcastReceiver usbReceiver = new BroadcastReceiver() {

        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if (ACTION_USB_PERMISSION.equals(action)) {
                synchronized (this) {
                    UsbDevice device = (UsbDevice)intent.getParcelableExtra(UsbManager.EXTRA_DEVICE);

                    if (intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)) {
                        if(device != null){
                            connectDevice(device);
                        }
                    }
                    else {
                        Log.d(TAG, "permission denied for device " + device);
                    }
                }
            }
        }
    };

    protected void connectDevice(UsbDevice device) {
        UsbInterface controlInterface = null;

        // Buscamos la interfaz específica de CONTROL DE AUDIO (Clase 1, Subclase 1)
        for (int i = 0; i < device.getInterfaceCount(); i++) {
            UsbInterface iface = device.getInterface(i);

            // USB_CLASS_AUDIO = 1, Subclase 1 = Audio Control
            // Si la encontramos, esa es la prioritaria
            if (iface.getInterfaceClass() == UsbConstants.USB_CLASS_AUDIO &&
                    iface.getInterfaceSubclass() == 1) {
                controlInterface = iface;
                break; // Ya la tenemos, dejamos de buscar
            }
        }

        // Si después de todo no tenemos interfaz
        if (controlInterface == null) return;


        // Abrimos la conexión sobre esa interfaz
        UsbDeviceConnection connection = usbManager.openDevice(device);
        if (connection != null) {
            // Reclamamos la interfaz (true = forzar desconexión del kernel si es necesario)
            connection.claimInterface(controlInterface, true);

            int fileDescriptor = connection.getFileDescriptor();

            // Pasamos el descriptor al código nativo (C++)
            deviceName = initializeNativeDevice(fileDescriptor);
            deviceDescriptor = fileDescriptor;

            if (autoApply.isChecked()) {
                setDeviceVolume(fileDescriptor);
            }

            tv.setText(deviceName);
            tv.setBackgroundColor(Color.TRANSPARENT);
        }
    }

    protected void checkUsbDevices()
    {
        PendingIntent permissionIntent = PendingIntent.getBroadcast(this, 0, new Intent(ACTION_USB_PERMISSION), 0);

        HashMap<String, UsbDevice> deviceList = usbManager.getDeviceList();
        for (UsbDevice device : deviceList.values()) {
            if(usbManager.hasPermission(device))
            {
                connectDevice(device);
            }
            else {
                usbManager.requestPermission(device, permissionIntent);
            }
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        binding = ActivityMainBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());
        tv = binding.sampleText;
        volInput = binding.volume;
        autoApply = binding.autoApply;

        SharedPreferences settings = getApplicationContext().getSharedPreferences("myPrefs", 0);
        volInput.setText(settings.getString("volume", "0000"));
        autoApply.setChecked(settings.getBoolean("autoApply", false));

        // Initialize UsbManager
        usbManager = (UsbManager) getSystemService(Context.USB_SERVICE);

        // Initialize the receiver for getting the device permission
        IntentFilter filter = new IntentFilter(ACTION_USB_PERMISSION);
        registerReceiver(usbReceiver, filter);

        checkUsbDevices();
    }

    public void applyButtonPressed(View view){
        String volume = volInput.getText().toString();

        if(deviceDescriptor < 0){
            tv.setBackgroundColor(Color.RED);
            return;
        }

        try {
            setDeviceVolume(deviceDescriptor);
            volInput.setBackgroundColor(Color.TRANSPARENT);
        } catch (IllegalArgumentException e){
            volInput.setText("00EC");
            volInput.setBackgroundColor(Color.RED);
            return;
        }

        SharedPreferences settings = getApplicationContext().getSharedPreferences("myPrefs", 0);
        if(!settings.getString("volume", "").equals(volume)) {
            SharedPreferences.Editor editor = settings.edit();
            editor.putString("volume", volume);
            editor.apply();
        }
    }
    public void checkboxPressed(View view){
        SharedPreferences settings = getApplicationContext().getSharedPreferences("myPrefs", 0);
        SharedPreferences.Editor editor = settings.edit();
        editor.putBoolean("autoApply", autoApply.isChecked());
        editor.apply();
    }


    public static byte[] hexStringToByteArray(String s) {
        int len = s.length();
        byte[] data = new byte[len / 2];
        for (int i = 0; i < len; i += 2) {
            data[i / 2] = (byte) ((Character.digit(s.charAt(i), 16) << 4)
                    + Character.digit(s.charAt(i+1), 16));
        }
        return data;
    }

    /**
     * A native method that is implemented by the 'lib' native library,
     * which is packaged with this application.
     */
    public native String initializeNativeDevice(int fileDescriptor);
    public native void setDeviceVolume(int fileDescriptor, byte[] volume);

    public void setDeviceVolume(int fileDescriptor){
        String volume = volInput.getText().toString();

        if(!volume.matches("[0-9A-Fa-f]{4}")){
            throw new IllegalArgumentException();
        }

        setDeviceVolume(fileDescriptor, hexStringToByteArray(volume));
    }
}
