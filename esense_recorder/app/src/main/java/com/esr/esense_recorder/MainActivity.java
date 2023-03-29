package com.esr.esense_recorder;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.preference.PreferenceManager;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import java.text.DecimalFormat;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.Locale;

import static android.Manifest.permission.RECORD_AUDIO;
import static android.Manifest.permission.ACCESS_FINE_LOCATION;
import static android.Manifest.permission.WRITE_EXTERNAL_STORAGE;

import io.esense.esenselib.ESenseConfig;
import io.esense.esenselib.ESenseEvent;
import io.esense.esenselib.ESenseManager;

public class MainActivity extends BluetoothCheckActivity implements BluetoothCheckCallback,
        ESenseListener {
    // Debug
    @SuppressWarnings("unused")
    private static final String DEBUG_TAG = "eSenseRecorder-Debug";
    @SuppressWarnings("unused")
    private static final boolean DEBUG = true;

    // UI components
    private TextView connectionStateLabel;

    private Button connectButton;
    private Button disconnectButton;
    private Button startRecordButton;
    private Button stopRecordButton;
    private TextView recordStateLabel;

    // eSense controller
    ESenseController eSenseController = new ESenseController();
    Intent audioRecordServiceIntent;

    // Logger (null is not logging)
    private SimpleLogger logger;
    private long startLogNanoTime;

    // Log parameters
    private @NonNull String logSeparator = "\t";
    private @NonNull String logTerminator = "\n";

    // Handler for regular updates of sensor fields
    Handler uiUpdateHandler = new Handler();
    public static final long UI_UPDATE_DELAY_MILLIS = 500;

    // Decimal formats
    private DecimalFormat convAccFormat = new DecimalFormat("00.00");
    private DecimalFormat convGyroFormat = new DecimalFormat("00.00");

    // Flag for pending log (after config read and start sensor)
    private boolean pendingStartLog = false;

    private String LAST_SAMPLING_RATE_KEY = "LAST_SAMPLING_RATE_KEY";
    private int lastSamplingRate = 50;

    // app permissions
    private static final int PERMISSION_REQUEST_CODE = 200;
    // debug logging
    private static final String TAG = "MainActivity";
    // date format
    SimpleDateFormat sdf = new SimpleDateFormat("yyyyMMdd_HH:mm:ss:SSS");

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        // References to UI components
        connectionStateLabel = findViewById(R.id.activity_main_connection_state_label);
        recordStateLabel = findViewById(R.id.activity_main_record_state_label);

        // Retrieve log parameters
        logSeparator = getString(R.string.log_field_separator);
        logTerminator = getString(R.string.log_line_terminator);

        // Init. formats
        convAccFormat = new DecimalFormat(getString(R.string.conv_acc_data_decimal_format));
        convGyroFormat = new DecimalFormat(getString(R.string.conv_gyro_data_decimal_format));

        // Retrieve defaults
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(this);
        lastSamplingRate = prefs.getInt(LAST_SAMPLING_RATE_KEY, lastSamplingRate);

        audioRecordServiceIntent = new Intent(this, AudioRecordService.class);

        if (!checkPermission()) {
            requestPermission();
        } else {
            Log.d(TAG, "Permission already granted..");
        }

        // *** UI event handlers ***

        // Connect button
        connectButton = findViewById(R.id.activity_main_connect_button);
        if (connectButton != null) {
            connectButton.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    if (eSenseController.getState() == ESenseConnectionState.DISCONNECTED) {
                        // First check Bluetooth
                        activateBluetooth(MainActivity.this);
                    }
                }
            });
        }

        // Disconnect button
        disconnectButton = findViewById(R.id.activity_main_disconnect_button);
        if (disconnectButton != null) {
            disconnectButton.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    if (eSenseController.getState() != ESenseConnectionState.DISCONNECTED) {
                        if (logger != null && logger.isLogging()) {
                            AlertDialog.Builder builder = new AlertDialog.Builder(
                                    MainActivity.this);
                            builder.setMessage(R.string.dialog_cancel_log_message);
                            builder.setPositiveButton(
                                    R.string.dialog_cancel_log_yes_button,
                                    new DialogInterface.OnClickListener() {
                                        @Override
                                        public void onClick(DialogInterface dialog, int which) {
                                            eSenseController.disconnect();
                                        }
                                    });
                            builder.setNegativeButton(
                                    R.string.dialog_cancel_log_no_button,
                                    new DialogInterface.OnClickListener() {
                                        @Override
                                        public void onClick(DialogInterface dialog, int which) {
                                            // Cancel
                                        }
                                    });
                            builder.create().show();
                        } else {
                            eSenseController.disconnect();
                        }
                    }
                }
            });
        }

        // Start record button
        startRecordButton = findViewById(R.id.activity_main_start_record_button);
        //startRecordButton.setEnabled(false);
        if (startRecordButton != null) {
            startRecordButton.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    if (logger != null && logger.isLogging()) {
                        // Ignore when already logging
                        return;
                    }
                    if (eSenseController.getState() == ESenseConnectionState.CONNECTED ){
                        // Check config
                        if (eSenseController.getESenseConfig() == null) {
                            pendingStartLog = true;
                            eSenseController.readESenseConfig(); // also done in IMUconfig button
                            Log.d(TAG, "read config from start record button");
                        }
                        if (!eSenseController.areSensorNotificationsActive()) { // this statement is executed first time startRecordButton is pressed
                            pendingStartLog = true;
                            Log.d(TAG, "start sensors from start record button");
                            startSensors(); // also done in IMUmonitor button
                        }

                        Log.d(TAG, "start log from start record button");
                        startLog();

                    } else {
                        showToast(getString(R.string.toast_message_no_device_connected));
                    }
                }
            });
        }

        // Stop record button
        stopRecordButton = findViewById(R.id.activity_main_stop_record_button);
        if (stopRecordButton != null) {
            stopRecordButton.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    long elapsedMillis = (System.nanoTime()-startLogNanoTime)/1000000;
                    logger.log(MainActivity.this, logSeparator, logTerminator,
                            String.format(
                                    Locale.getDefault(), "%d", elapsedMillis),
                            getString(R.string.log_stop_message));
                    // TODO: add timestamp of sensor stop and other log messages
                    logger.closeLog(MainActivity.this);
                    stopService(audioRecordServiceIntent);
                    logger = null;
                    updateLoggerPanel();
                }
            });
        }
    }

    /**
     * Starts the log of sensor events.
     */
    private void startLog() {
        pendingStartLog = false;
        if (logger != null && logger.isLogging()) {
            logger.closeLog(this);
        }
        // Create logger
        String folderName = getString(R.string.log_folder);
        SimpleDateFormat logFileFormat = new SimpleDateFormat(
                getString(R.string.log_file_date_pattern), Locale.getDefault());
        // TODO: create meta data logger
        // TODO: initialize headers in data logger
        logger = new SimpleLogger(folderName, logFileFormat.format(new Date()));
        // First log
        startLogNanoTime = System.nanoTime();
        SimpleDateFormat dateFormat = new SimpleDateFormat(
                getString(R.string.log_start_date_pattern), Locale.getDefault());
        String date = dateFormat.format(new Date());

        // start audio recording
        startService(audioRecordServiceIntent);

        // configuration details
        ESenseConfig config = eSenseController.getESenseConfig();
        if (config != null) {
            logger.log(this, logSeparator, logTerminator,
                    "0",
                    "sampling rate",
                    lastSamplingRate
            );
            logger.log(this, logSeparator, logTerminator,
                    "0",
                    "acc. range",
                    config.getAccRange().toString()
                    );
            logger.log(this, logSeparator, logTerminator,
                    "0",
                    "gyro. range",
                    config.getGyroRange().toString()
            );
            logger.log(this, logSeparator, logTerminator,
                    "0",
                    "acc. LPF",
                    config.getAccLPF().toString()
            );
            logger.log(this, logSeparator, logTerminator,
                    "0",
                    "gyro. LPF",
                    config.getGyroLPF().toString()
            );
        }
        if (!logger.log(this, logSeparator, logTerminator,
                "0",
                getString(R.string.log_start_message),
                date)) {
            // Log failed
            logger.closeLog(this);
            logger = null;
            showToast(getString(R.string.toast_log_failed));
        }
        updateLoggerPanel();
    }

    /**
     * Asks for the sampling rate and start the sensors (asynchronously)
     */
    private void startSensors() {
        if (eSenseController.areSensorNotificationsActive()) {
            if (pendingStartLog) {
                startLog();
            }
            return;
        }

        // Dialog for sampling rate

        AlertDialog.Builder builder = new AlertDialog.Builder(MainActivity.this);
        builder.setTitle(R.string.dialog_sampling_rate_title);
        final EditText edittext = new EditText(MainActivity.this);
        edittext.setText(String.format(Locale.getDefault(), "%d", lastSamplingRate));
        edittext.setInputType(2); // Number keyboard
        builder.setView(edittext);
        builder.setPositiveButton(R.string.dialog_sampling_rate_ok_button,
                new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        // Retrieve sampling rate
                        String rateString = edittext.getText().toString().trim();
                        try {
                            int rate = Integer.parseInt(rateString);
                            if (rate < 1 || rate > 100) {
                                showToast(getString(R.string.toast_sampling_rate_out_of_bounds));
                                pendingStartLog = false;
                            } else {
                                // Save value
                                lastSamplingRate = rate;
                                SharedPreferences prefs = PreferenceManager
                                        .getDefaultSharedPreferences(MainActivity.this);
                                prefs.edit().putInt(LAST_SAMPLING_RATE_KEY, lastSamplingRate)
                                        .apply();
                                // Start sensors
                                if (!eSenseController.startSensorNotifications(
                                        lastSamplingRate)) {
                                    Log.d("sampling rate: ", Integer.toString(lastSamplingRate));
                                    showToast(
                                            getString(R.string.toast_message_start_sensor_failed));
                                }
                            }
                        } catch (Exception e) {
                            showToast(getString(R.string.toast_sampling_rate_illegal));
                            pendingStartLog = false;
                        }
                    }
                });
        builder.setNegativeButton(
                R.string.dialog_sampling_rate_cancel_button,
                new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        // Cancel
                        pendingStartLog = false;
                    }
                });
        builder.create().show();

    }

    @Override
    protected void onResume() {
        super.onResume();
        eSenseController.addListener(this);
        updateUI();
        // Start handler for regular sensor fields updates
        uiUpdateHandler.postDelayed(new Runnable() {
            @Override
            public void run() {
                try {
                    if (eSenseController != null &&
                            eSenseController.areSensorNotificationsActive()) {
                        //updateSensorDataPanel();
                    }
                } catch (Exception e) {
                    Log.e(DEBUG_TAG, "Failed to update UI.", e);
                } finally {
                    uiUpdateHandler.postDelayed(this, UI_UPDATE_DELAY_MILLIS);
                }
            }
        }, UI_UPDATE_DELAY_MILLIS);
    }

    // TODO: does this cause the app to stop recording IMU when phone screen is off?
    @Override
    protected void onPause() {
        super.onPause();
        eSenseController.removeListener(this);
        // Stop UI update handler
        uiUpdateHandler.removeCallbacksAndMessages(null);
        // Close connection and logger on finishing
        if (isFinishing()) {
            // Stop log
            if (logger != null && logger.isLogging()) {
                long elapsedMillis = (System.nanoTime()-startLogNanoTime)/1000000;
                logger.log(MainActivity.this, logSeparator, logTerminator,
                        String.format(
                                Locale.getDefault(), "%d", elapsedMillis),
                        getString(R.string.log_stop_message));
                logger.closeLog(this);
                logger = null;
            }
            // Disconnect
            eSenseController.disconnect();
        }
    }

    /**
     * Shows a toast.
     *
     * @param message The message to display in the toast.
     */
    private void showToast(@NonNull final String message) {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                Toast.makeText(MainActivity.this, message, Toast.LENGTH_LONG).show();
            }
        });
    }

    @Override
    public void onBluetoothReady() {
        showToast(getString(R.string.toast_message_bt_ready));
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                // Retrieve the list of Paired devices
                ArrayList<BluetoothDevice> devices = new ArrayList<>(
                        BluetoothAdapter.getDefaultAdapter().getBondedDevices());
                if (devices.size() == 0) {
                    showToast(getString(R.string.toast_message_no_paired_device));
                    return;
                }
                // Get name
                final String[] deviceNames = new String[devices.size()];
                for (int i=0; i<devices.size(); i++) {
                    deviceNames[i] = devices.get(i).getName();
                }
                AlertDialog.Builder builder = new AlertDialog.Builder(MainActivity.this);
                builder.setTitle(R.string.dialog_device_selection_title);
                builder.setItems(deviceNames, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        // Connect when a name is selected
                        try {
                            MainActivity.this.eSenseController.connect(
                                    deviceNames[which], MainActivity.this);
                        } catch (Exception e) {
                            showToast(getString(R.string.toast_message_device_name_error));
                        }
                    }
                });
                AlertDialog dialog = builder.create();
                dialog.show();
            }
        });
    }

    @Override
    public void onBluetoothActivationRejected() {
        showToast(getString(R.string.toast_message_bt_activation_rejected));
    }

    @Override
    public void onBluetoothActivationFailed() {
        showToast(getString(R.string.toast_message_bt_activation_failed));
    }

    /**
     * Updates all UI components according to the eSense state.
     */
    private void updateUI() {
        updateConnectionPanel();
        updateLoggerPanel();
    }

    /**
     * Updates the connection panel UI.
     */
    private void updateConnectionPanel() {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                if (connectionStateLabel != null) {
                    connectionStateLabel.setText(eSenseController.getState().toString());
                }
                if (connectButton != null && disconnectButton != null) {
                    if (eSenseController.getState() == ESenseConnectionState.DISCONNECTED) {
                        connectButton.setEnabled(true);
                        disconnectButton.setEnabled(false);
                    } else {
                        connectButton.setEnabled(false);
                        disconnectButton.setEnabled(true);
                    }
                }
            }
        });
    }

    /**
     * Updates the logger panel.
     */
    private void updateLoggerPanel() {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                if (eSenseController != null &&
                        startRecordButton != null && stopRecordButton != null) {
                    if (eSenseController.getState() != ESenseConnectionState.CONNECTED) {
                        startRecordButton.setEnabled(false);
                        stopRecordButton.setEnabled(false);
                    } else if (logger == null || !logger.isLogging()) {
                        startRecordButton.setEnabled(true);
                        stopRecordButton.setEnabled(false);
                    } else {
                        startRecordButton.setEnabled(false);
                        stopRecordButton.setEnabled(true);
                    }
                }
                if (recordStateLabel != null) {
                    if (logger == null || !logger.isLogging()) {
                        recordStateLabel.setText(R.string.activity_main_not_recording_text);
                        recordStateLabel.setTextColor(getColor(R.color.colorLabelDisabled));
                    } else {
                        recordStateLabel.setText(R.string.activity_main_recording_text);
                        recordStateLabel.setTextColor(getColor(R.color.colorAccent));
                    }
                }
            }
        });
    }

    @Override
    public void onDeviceFound(ESenseManager manager) {
        showToast(getString(R.string.toast_message_device_found));
        updateUI();
    }

    @Override
    public void onDeviceNotFound(ESenseManager manager) {
        showToast(getString(R.string.toast_message_device_not_found));
        updateUI();
    }

    @Override
    public void onConnected(ESenseManager manager) {
        // Read IMU config
        /*if (!eSenseController.readESenseConfig()) { // TODO: why does this return false? readCharacteristic() in ESenseManager.java always returns false
            showToast(getString(R.string.toast_message_read_config_failed));
        }*/
        // Show toast and update UI
        showToast(getString(R.string.toast_message_device_connected));
        updateUI();
    }

    @Override
    public void onDisconnected(ESenseManager manager) {
        // Stop log
        if (logger != null && logger.isLogging()) {
            long elapsedMillis = (System.nanoTime()-startLogNanoTime)/1000000;
            logger.log(this, logSeparator, logTerminator,
                    String.format(
                            Locale.getDefault(), "%d", elapsedMillis),
                    getString(R.string.log_disconnection_message));
            // TODO: add timestamp to log
            logger.closeLog(this);
            logger = null;
        }
        // Toast and UI update
        showToast(getString(R.string.toast_message_device_disconnected));
        updateUI();
    }

    @Override
    public void onBatteryRead(double voltage) {
        // No monitoring of the battery voltage
    }

    @Override
    public void onButtonEventChanged(boolean pressed) {
        // Log event
        if (logger != null && logger.isLogging()) {
            long elapsedMillis = (System.nanoTime()-startLogNanoTime)/1000000;
            String elapsed = String.format(Locale.getDefault(), "%d", elapsedMillis);
            if (!logger.log(this, logSeparator, logTerminator,
                    elapsed, // TODO: log timestamp
                    getString(R.string.log_button_event_message))) {
                // Log failed
                logger.closeLog(this);
                logger = null;
                showToast(getString(R.string.toast_log_failed));
            }
        }
        showToast(getString(R.string.toast_message_esense_button_pressed));
    }

    @Override
    public void onAdvertisementAndConnectionIntervalRead(int minAdvertisementInterval,
                                                         int maxAdvertisementInterval,
                                                         int minConnectionInterval,
                                                         int maxConnectionInterval) {
        // Nothing to do
    }

    @Override
    public void onDeviceNameRead(String deviceName) {
        // Nothing to do
    }

    @Override
    public void onSensorConfigRead(ESenseConfig config) {
        //updateIMUConfigurationPanel();
        if (pendingStartLog && config != null) {
            Log.d(TAG, "start sensors from onSensorConfigRead()");
            startSensors();
        }
    }

    @Override
    public void onSensorConfigChanged(ESenseConfig config) {
        //updateIMUConfigurationPanel();
        // TODO: find appropriate thing to do here
    }

    @Override
    public void onAccelerometerOffsetRead(int offsetX, int offsetY, int offsetZ) {
        // Nothing to do
    }

    @Override
    public void onSensorChanged(ESenseEvent evt) {
        // Log sensor data
        if (logger != null) {
            long elapsedMillis = (System.nanoTime()-startLogNanoTime)/1000000;
            String elapsed = String.format(Locale.getDefault(), "%d", elapsedMillis);
            //Log.d("elapsed time: ", elapsed);
            ESenseConfig config = eSenseController.getESenseConfig();
            double[] convAcc = null;
            double[] convGyro = null;
            if (config != null) {
                convAcc = evt.convertAccToG(config);
                convGyro = evt.convertGyroToDegPerSecond(config);
            }
            // TODO: why logging is <100 samples/second though sampling rate=100Hz?
            Date date = new Date();
            Log.d("time: ", sdf.format(date));
            if (!logger.log(this, logSeparator, logTerminator,
                    elapsed,
                    sdf.format(date),
                    getString(R.string.log_sensor_event_message),
                    evt.getAccel()[0], evt.getAccel()[1], evt.getAccel()[2],
                    evt.getGyro()[0], evt.getGyro()[1], evt.getGyro()[2],
                    (convAcc==null)?("-"):(convAcc[0]),
                    (convAcc==null)?("-"):(convAcc[1]),
                    (convAcc==null)?("-"):(convAcc[2]),
                    (convGyro==null)?("-"):(convGyro[0]),
                    (convGyro==null)?("-"):(convGyro[1]),
                    (convGyro==null)?("-"):(convGyro[2])
                    )) {
                // Log failed
                logger.closeLog(this);
                logger = null;
                showToast(getString(R.string.toast_log_failed));
                updateLoggerPanel();
            }
        }
        // No UI update. This is made in separate handler.
    }

    @Override
    public void onConnecting() {
        updateUI();
    }

    @Override
    public void onSensorNotificationsStarted(int samplingRate) {
        if(pendingStartLog){
            startLog();
        }
        //updateSensorDataPanel();
    }

    @Override
    public void onSensorNotificationsStopped() {
        // Stop log
        if (logger != null && logger.isLogging()) {
            long elapsedMillis = (System.nanoTime()-startLogNanoTime)/1000000;
            logger.log(this, logSeparator, logTerminator,
                    String.format(
                            Locale.getDefault(), "%d", elapsedMillis), // TODO: log timestamp
                    getString(R.string.log_sensors_stopped_message));
            logger.closeLog(this);
            logger = null;
        }
        updateLoggerPanel();
        //updateSensorDataPanel();
    }

    private boolean checkPermission() {
        int recordResult = ContextCompat.checkSelfPermission(getApplicationContext(), RECORD_AUDIO);
        int locationResult = ContextCompat.checkSelfPermission(getApplicationContext(), ACCESS_FINE_LOCATION);
        int writeResult = ContextCompat.checkSelfPermission(getApplicationContext(), WRITE_EXTERNAL_STORAGE);

        return locationResult == PackageManager.PERMISSION_GRANTED &&
                writeResult == PackageManager.PERMISSION_GRANTED && recordResult == PackageManager.PERMISSION_GRANTED;
    }

    private void requestPermission() {
        ActivityCompat.requestPermissions(this, new String[]{ACCESS_FINE_LOCATION,
                WRITE_EXTERNAL_STORAGE, RECORD_AUDIO}, PERMISSION_REQUEST_CODE);
    }
    @Override
    public void onRequestPermissionsResult(int requestCode, String permissions[], int[] grantResults) {
        switch (requestCode) {
            case PERMISSION_REQUEST_CODE:
                if (grantResults.length > 0) {

                    boolean locationAccepted = grantResults[0] == PackageManager.PERMISSION_GRANTED;
                    boolean storageAccepted = grantResults[1] == PackageManager.PERMISSION_GRANTED;
                    boolean recordAccepted = grantResults[2] == PackageManager.PERMISSION_GRANTED;

                    if (locationAccepted && storageAccepted && recordAccepted){
                        Log.d(TAG, "Permission granted");
                    } else {
                        Log.d(TAG, "Permission denied");

                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                            if (shouldShowRequestPermissionRationale(ACCESS_FINE_LOCATION)) {
                                showMessageOKCancel("You need to allow access to all permissions",
                                        new DialogInterface.OnClickListener() {
                                            @Override
                                            public void onClick(DialogInterface dialog, int which) {
                                                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                                                    requestPermissions(new String[]{ACCESS_FINE_LOCATION,
                                                            WRITE_EXTERNAL_STORAGE, RECORD_AUDIO}, PERMISSION_REQUEST_CODE);
                                                }
                                            }
                                        });
                                return;
                            }
                        }
                    }
                }
                break;
        }
    }

    private void showMessageOKCancel(String message, DialogInterface.OnClickListener okListener) {
        new AlertDialog.Builder(MainActivity.this)
                .setMessage(message)
                .setPositiveButton("OK", okListener)
                .setNegativeButton("Cancel", null)
                .create()
                .show();
    }
}

