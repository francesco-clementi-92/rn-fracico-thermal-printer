package com.pinmi.react.printer.adapter;

import static com.pinmi.react.printer.adapter.UtilsImage.getPixelsSlow;
import static com.pinmi.react.printer.adapter.UtilsImage.recollectSlice;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothProfile;
import android.bluetooth.BluetoothSocket;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanResult;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.Base64;
import android.util.Log;
import android.widget.Toast;

import com.facebook.react.bridge.Callback;
import com.facebook.react.bridge.ReactApplicationContext;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Created by xiesubin on 2017/9/21.
 */

public class BLEPrinterAdapter implements PrinterAdapter {


    private static BLEPrinterAdapter mInstance;


    private final String LOG_TAG = "RNBLEPrinter";

    private BluetoothDevice mBluetoothDevice;
    private BluetoothSocket mBluetoothSocket;

    private BluetoothGatt mBluetoothGatt;
    private BluetoothGattCharacteristic mWriteCharacteristic;
    private volatile boolean mGattConnected = false;
    private CountDownLatch mGattConnectLatch;
    private int mNegotiatedMtu = 23;
    private CountDownLatch mWriteLatch;

    private ReactApplicationContext mContext;

    private final static char ESC_CHAR = 0x1B;
    private static final byte[] SELECT_BIT_IMAGE_MODE = {0x1B, 0x2A, 33};
    private final static byte[] SET_LINE_SPACE_24 = new byte[]{ESC_CHAR, 0x33, 24};
    private final static byte[] SET_LINE_SPACE_32 = new byte[]{ESC_CHAR, 0x33, 32};
    private final static byte[] LINE_FEED = new byte[]{0x0A};
    private static final byte[] CENTER_ALIGN = {0x1B, 0X61, 0X31};


    private BLEPrinterAdapter() {
    }

    public static synchronized BLEPrinterAdapter getInstance() {
        if (mInstance == null) {
            mInstance = new BLEPrinterAdapter();
        }
        return mInstance;
    }

    @Override
    public void init(ReactApplicationContext reactContext, Callback successCallback, Callback errorCallback) {
        this.mContext = reactContext;
        BluetoothAdapter bluetoothAdapter = getBTAdapter();
        if (bluetoothAdapter == null) {
            errorCallback.invoke("No bluetooth adapter available");
            return;
        }
        if (!bluetoothAdapter.isEnabled()) {
            errorCallback.invoke("bluetooth adapter is not enabled");
            return;
        } else {
            successCallback.invoke();
        }

    }

    @SuppressWarnings("deprecation")
    private BluetoothAdapter getBTAdapter() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR2 && mContext != null) {
            BluetoothManager manager = (BluetoothManager) mContext.getSystemService(Context.BLUETOOTH_SERVICE);
            return manager != null ? manager.getAdapter() : null;
        }
        return BluetoothAdapter.getDefaultAdapter();
    }

    @Override
    public List<PrinterDevice> getDeviceList(Callback errorCallback) {
        BluetoothAdapter bluetoothAdapter = getBTAdapter();
        List<PrinterDevice> printerDevices = new ArrayList<>();
        if (bluetoothAdapter == null) {
            errorCallback.invoke("No bluetooth adapter available");
            return printerDevices;
        }
        if (!bluetoothAdapter.isEnabled()) {
            errorCallback.invoke("bluetooth is not enabled");
            return printerDevices;
        }

        Set<BluetoothDevice> pairedDevices = getBTAdapter().getBondedDevices();
        for (BluetoothDevice device : pairedDevices) {
            printerDevices.add(new BLEPrinterDevice(device));
        }
        return printerDevices;
    }

    @Override
    public void selectDevice(PrinterDeviceId printerDeviceId, Callback successCallback, Callback errorCallback) {
        BluetoothAdapter bluetoothAdapter = getBTAdapter();
        if (bluetoothAdapter == null) {
            errorCallback.invoke("No bluetooth adapter available");
            return;
        }
        if (!bluetoothAdapter.isEnabled()) {
            errorCallback.invoke("bluetooth is not enabled");
            return;
        }
        BLEPrinterDeviceId blePrinterDeviceId = (BLEPrinterDeviceId) printerDeviceId;
        if (this.mBluetoothDevice != null) {
            if (this.mBluetoothDevice.getAddress().equals(blePrinterDeviceId.getInnerMacAddress())
                    && (this.mBluetoothSocket != null || this.mGattConnected)) {
                Log.v(LOG_TAG, "do not need to reconnect");
                successCallback.invoke(new BLEPrinterDevice(this.mBluetoothDevice).toRNWritableMap());
                return;
            } else {
                closeConnectionIfExists();
            }
        }
        Set<BluetoothDevice> pairedDevices = getBTAdapter().getBondedDevices();

        for (BluetoothDevice device : pairedDevices) {
            if (device.getAddress().equals(blePrinterDeviceId.getInnerMacAddress())) {

                // Try BLE GATT first (fast for BLE printers)
                try {
                    connectBleGatt(device);
                    successCallback.invoke(new BLEPrinterDevice(this.mBluetoothDevice).toRNWritableMap());
                    return;
                } catch (IOException bleEx) {
                    Log.w(LOG_TAG, "BLE GATT failed, trying RFCOMM...", bleEx);
                }

                // Fall back to Classic Bluetooth (RFCOMM)
                try {
                    connectBluetoothDevice(device, false);
                    successCallback.invoke(new BLEPrinterDevice(this.mBluetoothDevice).toRNWritableMap());
                    return;
                } catch (IOException e) {
                    Log.w(LOG_TAG, "RFCOMM insecure failed, trying reflection...", e);
                    try {
                        connectBluetoothDevice(device, true);
                        successCallback.invoke(new BLEPrinterDevice(this.mBluetoothDevice).toRNWritableMap());
                        return;
                    } catch (IOException er) {
                        Log.e(LOG_TAG, "All connection methods failed", er);
                        errorCallback.invoke("Failed to connect via BLE GATT and Classic BT: " + er.getMessage());
                        return;
                    }
                }
            }
        }
        String errorText = "Can not find the specified printing device, please perform Bluetooth pairing in the system settings first.";
        Toast.makeText(this.mContext, errorText, Toast.LENGTH_LONG).show();
        errorCallback.invoke(errorText);
        return;
    }

    private void connectBluetoothDevice(BluetoothDevice device, Boolean retry) throws IOException {
        UUID uuid = UUID.fromString("00001101-0000-1000-8000-00805f9b34fb");

        BluetoothAdapter adapter = getBTAdapter();
        if (adapter != null) {
            adapter.cancelDiscovery();
        }

        if (retry) {
            try {
                this.mBluetoothSocket = (BluetoothSocket) device.getClass()
                        .getMethod("createRfcommSocket", new Class[]{int.class}).invoke(device, 1);
            } catch (Exception e) {
                e.printStackTrace();
                throw new IOException("Failed to create RFComm socket using reflection", e);
            }
        } else {
            this.mBluetoothSocket = device.createInsecureRfcommSocketToServiceRecord(uuid);
        }

        try {
            this.mBluetoothSocket.connect();
        } catch (IOException e) {
            try {
                this.mBluetoothSocket.close();
            } catch (IOException closeException) {
                closeException.printStackTrace();
            }
            this.mBluetoothSocket = null;
            throw new IOException("Failed to connect to Bluetooth device", e);
        }

        this.mBluetoothDevice = device;
    }

    private final BluetoothGattCallback mGattCallback = new BluetoothGattCallback() {
        @Override
        public void onConnectionStateChange(BluetoothGatt gatt, int status, int newState) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                Log.v(LOG_TAG, "BLE GATT connected, requesting MTU...");
                gatt.requestMtu(512);
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                Log.w(LOG_TAG, "BLE GATT disconnected, status: " + status);
                mGattConnected = false;
                if (mGattConnectLatch != null) mGattConnectLatch.countDown();
            }
        }

        @Override
        public void onMtuChanged(BluetoothGatt gatt, int mtu, int status) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                mNegotiatedMtu = mtu;
                Log.v(LOG_TAG, "BLE MTU negotiated: " + mtu);
            } else {
                Log.w(LOG_TAG, "MTU negotiation failed, using default");
            }
            gatt.discoverServices();
        }

        @Override
        public void onCharacteristicWrite(BluetoothGatt gatt, BluetoothGattCharacteristic characteristic, int status) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                Log.w(LOG_TAG, "Characteristic write failed, status: " + status);
            }
            if (mWriteLatch != null) mWriteLatch.countDown();
        }

        @Override
        public void onServicesDiscovered(BluetoothGatt gatt, int status) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                BluetoothGattCharacteristic fallback = null;

                for (BluetoothGattService service : gatt.getServices()) {
                    UUID serviceUuid = service.getUuid();
                    boolean isStandardService = (serviceUuid.getMostSignificantBits() & 0xFFFF00000000L)
                            == 0x180000000000L
                            && serviceUuid.getLeastSignificantBits() == 0x800000805f9b34fbL;

                    for (BluetoothGattCharacteristic c : service.getCharacteristics()) {
                        int props = c.getProperties();
                        boolean writable = (props & BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE) != 0
                                || (props & BluetoothGattCharacteristic.PROPERTY_WRITE) != 0;
                        if (!writable) continue;

                        Log.v(LOG_TAG, "Writable characteristic: " + c.getUuid()
                                + " in service: " + serviceUuid
                                + (isStandardService ? " (standard)" : " (vendor)"));

                        if (!isStandardService) {
                            mWriteCharacteristic = c;
                            mGattConnected = true;
                            break;
                        } else if (fallback == null) {
                            fallback = c;
                        }
                    }
                    if (mWriteCharacteristic != null) break;
                }

                if (mWriteCharacteristic == null && fallback != null) {
                    Log.w(LOG_TAG, "No vendor characteristic found, using standard fallback: " + fallback.getUuid());
                    mWriteCharacteristic = fallback;
                    mGattConnected = true;
                }

                if (mWriteCharacteristic != null) {
                    Log.v(LOG_TAG, "Selected write characteristic: " + mWriteCharacteristic.getUuid());
                }
            } else {
                Log.e(LOG_TAG, "Service discovery failed, status: " + status);
            }
            if (mGattConnectLatch != null) mGattConnectLatch.countDown();
        }
    };

    private void connectBleGatt(BluetoothDevice bondedDevice) throws IOException {
        BluetoothAdapter adapter = getBTAdapter();
        if (adapter == null) {
            throw new IOException("No bluetooth adapter");
        }
        adapter.cancelDiscovery();

        final String targetAddress = bondedDevice.getAddress();

        // Step 1: Scan for the device via BLE to get a proper LE-aware BluetoothDevice
        BluetoothDevice bleDevice = scanForBleDevice(adapter, targetAddress);

        // Step 2: Connect via GATT on the main thread (required by some Samsung devices)
        mGattConnectLatch = new CountDownLatch(1);
        mGattConnected = false;
        mWriteCharacteristic = null;
        mNegotiatedMtu = 23;

        final BluetoothDevice deviceToConnect = bleDevice != null ? bleDevice : bondedDevice;
        Log.v(LOG_TAG, "Connecting GATT to " + deviceToConnect.getAddress()
                + (bleDevice != null ? " (discovered via BLE scan)" : " (from bonded list)"));

        new Handler(Looper.getMainLooper()).post(new Runnable() {
            @Override
            public void run() {
                mBluetoothGatt = deviceToConnect.connectGatt(
                        mContext, false, mGattCallback, BluetoothDevice.TRANSPORT_LE);
                if (mBluetoothGatt == null) {
                    Log.e(LOG_TAG, "connectGatt returned null");
                    mGattConnectLatch.countDown();
                }
            }
        });

        try {
            if (!mGattConnectLatch.await(5, TimeUnit.SECONDS)) {
                closeGattIfExists();
                throw new IOException("BLE GATT connection timed out");
            }
        } catch (InterruptedException e) {
            closeGattIfExists();
            throw new IOException("BLE GATT connection interrupted", e);
        }

        if (!mGattConnected || mWriteCharacteristic == null) {
            closeGattIfExists();
            throw new IOException("Failed to establish BLE GATT connection or no writable characteristic found");
        }

        this.mBluetoothDevice = deviceToConnect;
        Log.v(LOG_TAG, "BLE GATT connected successfully, MTU: " + mNegotiatedMtu);
    }

    private BluetoothDevice scanForBleDevice(BluetoothAdapter adapter, String targetAddress) {
        BluetoothLeScanner scanner = adapter.getBluetoothLeScanner();
        if (scanner == null) {
            Log.w(LOG_TAG, "BLE scanner not available");
            return null;
        }

        final CountDownLatch scanLatch = new CountDownLatch(1);
        final BluetoothDevice[] exactMatch = new BluetoothDevice[1];

        ScanCallback scanCallback = new ScanCallback() {
            @Override
            public void onScanResult(int callbackType, ScanResult result) {
                BluetoothDevice device = result.getDevice();
                if (device.getAddress().equals(targetAddress)) {
                    Log.v(LOG_TAG, "BLE scan: exact MAC match: " + device.getAddress());
                    exactMatch[0] = device;
                    scanLatch.countDown();
                }
            }

            @Override
            public void onScanFailed(int errorCode) {
                Log.e(LOG_TAG, "BLE scan failed with error: " + errorCode);
                scanLatch.countDown();
            }
        };

        Log.v(LOG_TAG, "Starting BLE scan for device: " + targetAddress);
        scanner.startScan(scanCallback);

        try {
            scanLatch.await(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Log.w(LOG_TAG, "BLE scan interrupted");
        }

        try {
            scanner.stopScan(scanCallback);
        } catch (Exception e) {
            Log.w(LOG_TAG, "Error stopping BLE scan", e);
        }

        if (exactMatch[0] != null) {
            Log.v(LOG_TAG, "Target device found via exact MAC match");
            return exactMatch[0];
        }

        Log.w(LOG_TAG, "Target device NOT found via BLE scan");
        return null;
    }

    private void closeGattIfExists() {
        if (mBluetoothGatt != null) {
            mBluetoothGatt.close();
            mBluetoothGatt = null;
        }
        mWriteCharacteristic = null;
        mGattConnected = false;
    }

    @Override
    public void closeConnectionIfExists() {
        try{
            if(this.mBluetoothSocket != null){
                this.mBluetoothSocket.close();
                this.mBluetoothSocket = null;
            }
        }catch(IOException e){
            e.printStackTrace();
        }

        closeGattIfExists();

        if(this.mBluetoothDevice != null) {
            this.mBluetoothDevice = null;
        }
    }

    @SuppressWarnings("deprecation")
    private void writeChunkedBleGatt(byte[] bytes, Callback successCallback, Callback errorCallback) {
        final BluetoothGatt gatt = this.mBluetoothGatt;
        final BluetoothGattCharacteristic characteristic = this.mWriteCharacteristic;
        final int chunkSize = Math.max(mNegotiatedMtu - 3, 20);

        Log.v(LOG_TAG, "Printing via BLE GATT, chunk size: " + chunkSize + ", total bytes: " + bytes.length);

        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    int writeType = ((characteristic.getProperties()
                            & BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE) != 0)
                            ? BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
                            : BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT;
                    characteristic.setWriteType(writeType);
                    boolean waitForCallback = (writeType == BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT);

                    for (int offset = 0; offset < bytes.length; offset += chunkSize) {
                        int end = Math.min(offset + chunkSize, bytes.length);
                        byte[] chunk = Arrays.copyOfRange(bytes, offset, end);

                        if (waitForCallback) {
                            mWriteLatch = new CountDownLatch(1);
                        }

                        characteristic.setValue(chunk);
                        boolean ok = gatt.writeCharacteristic(characteristic);
                        if (!ok) {
                            Log.w(LOG_TAG, "writeCharacteristic returned false at offset " + offset);
                        }

                        if (waitForCallback) {
                            if (!mWriteLatch.await(5, TimeUnit.SECONDS)) {
                                Log.w(LOG_TAG, "Write callback timed out at offset " + offset);
                            }
                        } else {
                            Thread.sleep(50);
                        }
                    }
                    if (successCallback != null) {
                        successCallback.invoke("Done");
                    }
                } catch (Exception e) {
                    Log.e(LOG_TAG, "Failed to print data via BLE GATT");
                    e.printStackTrace();
                    if (errorCallback != null) {
                        errorCallback.invoke("Error printing via BLE: " + e.getMessage());
                    }
                }
            }
        }).start();
    }

    @Override
    public void printRawData(String rawBase64Data, Callback successCallback, Callback errorCallback) {
        if (this.mBluetoothSocket != null) {
            final String rawData = rawBase64Data;
            final BluetoothSocket socket = this.mBluetoothSocket;
            Log.v(LOG_TAG, "Printing via RFCOMM");
            new Thread(new Runnable() {
                @Override
                public void run() {
                    byte[] bytes = Base64.decode(rawData, Base64.DEFAULT);
                    try {
                        OutputStream printerOutputStream = socket.getOutputStream();
                        printerOutputStream.write(bytes, 0, bytes.length);
                        printerOutputStream.flush();
                        if (successCallback != null) {
                            successCallback.invoke("Done");
                        }
                    } catch (IOException e) {
                        Log.e(LOG_TAG, "Failed to print data via RFCOMM");
                        e.printStackTrace();
                        if (errorCallback != null) {
                            errorCallback.invoke("Error printing: " + e.getMessage());
                        }
                    }
                }
            }).start();
            return;
        }

        if (this.mBluetoothGatt != null && this.mWriteCharacteristic != null) {
            byte[] bytes = Base64.decode(rawBase64Data, Base64.DEFAULT);
            writeChunkedBleGatt(bytes, successCallback, errorCallback);
            return;
        }

        errorCallback.invoke("bluetooth connection is not built, may be you forgot to connectPrinter");
    }

    public static Bitmap getBitmapFromURL(String src) {
        HttpURLConnection connection = null;
        InputStream input = null;
        try {
            URL url = new URL(src);
            connection = (HttpURLConnection) url.openConnection();
            connection.setDoInput(true);
            connection.connect();
            input = connection.getInputStream();
            return BitmapFactory.decodeStream(input);
        } catch (IOException e) {
            Log.e("RNBLEPrinter", "Failed to load image from URL: " + src, e);
            return null;
        } finally {
            if (input != null) {
                try { input.close(); } catch (IOException ignored) {}
            }
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    private byte[] renderImageToBytes(Bitmap bitmapImage, int imageWidth, int imageHeight) throws IOException {
        int[][] pixels = getPixelsSlow(bitmapImage, imageWidth, imageHeight);
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();

        buffer.write(SET_LINE_SPACE_24);
        buffer.write(CENTER_ALIGN);

        for (int y = 0; y < pixels.length; y += 24) {
            buffer.write(SELECT_BIT_IMAGE_MODE);
            buffer.write(new byte[]{(byte)(0x00ff & pixels[y].length),
                    (byte)((0xff00 & pixels[y].length) >> 8)});
            for (int x = 0; x < pixels[y].length; x++) {
                buffer.write(recollectSlice(y, x, pixels));
            }
            buffer.write(LINE_FEED);
        }
        buffer.write(SET_LINE_SPACE_32);
        buffer.write(LINE_FEED);

        return buffer.toByteArray();
    }

    @Override
    public void printImageData(final String imageUrl, final int imageWidth, final int imageHeight, final Callback errorCallback) {
        final BluetoothSocket socket = this.mBluetoothSocket;
        final BluetoothGatt gatt = this.mBluetoothGatt;
        final BluetoothGattCharacteristic characteristic = this.mWriteCharacteristic;

        if (socket == null && (gatt == null || characteristic == null)) {
            errorCallback.invoke("bluetooth connection is not built, may be you forgot to connectPrinter");
            return;
        }

        new Thread(new Runnable() {
            @Override
            public void run() {
                Bitmap bitmapImage = getBitmapFromURL(imageUrl);
                if (bitmapImage == null) {
                    errorCallback.invoke("image not found");
                    return;
                }

                try {
                    byte[] imageBytes = renderImageToBytes(bitmapImage, imageWidth, imageHeight);
                    if (socket != null) {
                        OutputStream printerOutputStream = socket.getOutputStream();
                        printerOutputStream.write(imageBytes);
                        printerOutputStream.flush();
                    } else {
                        writeChunkedBleGatt(imageBytes, null, errorCallback);
                    }
                } catch (IOException e) {
                    Log.e(LOG_TAG, "failed to print image data");
                    e.printStackTrace();
                    errorCallback.invoke("Error printing image: " + e.getMessage());
                }
            }
        }).start();
    }

    @Override
    public void printImageBase64(final Bitmap bitmapImage, final int imageWidth, final int imageHeight, final Callback errorCallback) {
        if (bitmapImage == null) {
            errorCallback.invoke("image not found");
            return;
        }

        if (this.mBluetoothSocket != null) {
            final BluetoothSocket socket = this.mBluetoothSocket;
            new Thread(new Runnable() {
                @Override
                public void run() {
                    try {
                        byte[] imageBytes = renderImageToBytes(bitmapImage, imageWidth, imageHeight);
                        OutputStream printerOutputStream = socket.getOutputStream();
                        printerOutputStream.write(imageBytes);
                        printerOutputStream.flush();
                    } catch (IOException e) {
                        Log.e(LOG_TAG, "failed to print image base64");
                        e.printStackTrace();
                        errorCallback.invoke("Error printing image: " + e.getMessage());
                    }
                }
            }).start();
            return;
        }

        if (this.mBluetoothGatt != null && this.mWriteCharacteristic != null) {
            try {
                byte[] imageBytes = renderImageToBytes(bitmapImage, imageWidth, imageHeight);
                writeChunkedBleGatt(imageBytes, null, errorCallback);
            } catch (IOException e) {
                Log.e(LOG_TAG, "failed to render image base64 for BLE");
                e.printStackTrace();
                errorCallback.invoke("Error rendering image: " + e.getMessage());
            }
            return;
        }

        errorCallback.invoke("bluetooth connection is not built, may be you forgot to connectPrinter");
    }
}
