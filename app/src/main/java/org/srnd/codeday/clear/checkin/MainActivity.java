package org.srnd.codeday.clear.checkin;

import android.Manifest;
import android.content.pm.PackageManager;
import android.media.AudioManager;
import android.media.ToneGenerator;
import android.os.StrictMode;
import android.support.v4.app.ActivityCompat;
import android.support.v4.content.ContextCompat;
import android.support.v7.app.AppCompatActivity;
import android.os.Bundle;
import com.google.zxing.Result;
import me.dm7.barcodescanner.zxing.ZXingScannerView;
import okhttp3.FormBody;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

import android.view.View;
import android.content.SharedPreferences;
import android.widget.Toast;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;


public class MainActivity extends AppCompatActivity implements ZXingScannerView.ResultHandler {
    private ZXingScannerView scanner;
    private OkHttpClient http = new OkHttpClient();
    private ToneGenerator tone = new ToneGenerator(AudioManager.STREAM_NOTIFICATION, 100);
    private String eventName;
    private String eventId;
    private String token;
    private boolean allowMissing;
    private SharedPreferences preferences;
    private int PERMISSION_REQUEST_CAMERA = 10; // 10 seems like a cool number

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // Network
        StrictMode.ThreadPolicy policy = new StrictMode.ThreadPolicy.Builder().permitAll().build();
        StrictMode.setThreadPolicy(policy);

        // Settings
        preferences = getSharedPreferences("org.srnd.codeday.clear.checkin", MODE_PRIVATE);
        if (!preferences.getString("configuration", "").equals("")) {
            configure(preferences.getString("configuration", ""));
        } else {
            toast("Please scan configuration barcode from checkin page.");
        }

        // Start the app
        setContentView(R.layout.activity_main);

        if (ContextCompat.checkSelfPermission(this,
                Manifest.permission.CAMERA)
                != PackageManager.PERMISSION_GRANTED) {

            if (ActivityCompat.shouldShowRequestPermissionRationale(this,
                    Manifest.permission.CAMERA)) {
                toast("The camera permission is needed to scan barcodes.");
            } else {
                ActivityCompat.requestPermissions(this,
                        new String[]{Manifest.permission.CAMERA},
                        PERMISSION_REQUEST_CAMERA);
            }
        }

        // Find the scanner view
        scanner = (ZXingScannerView) findViewById(R.id.scanner);
    }

    public void configure(String json) {
        try {
            // Update local properties
            JSONObject data = new JSONObject(json);
            eventName = data.getString("eventName");
            eventId = data.getString("eventId");
            token = data.getString("token");

            // Update the stored configuration data
            SharedPreferences.Editor edit = preferences.edit();
            edit.putString("configuration", json);
            edit.commit();

            toast("Checking in for "+eventName);
        } catch (Exception ex) {
            toast("Invalid configuration JSON");
        }
    }

    private void checkin(String id) {
        if (token == null || eventId == null) {
            error("App not configured! Scan barcode on checkin page.");
            return;
        }

        // Build the request
        FormBody.Builder builder = new FormBody.Builder()
                .add("token", token)
                .add("r", id)
                .add("check", "in")
                .add("event", eventId);

        if (allowMissing) {
            builder.add("allow_missing", "true");
        }

        Request request = new Request.Builder()
                .url("https://clear.codeday.org/api/checkin")
                .post(builder.build())
                .build();


        // Do the request
        Response responseObj;
        String responseStr;
        try {
            responseObj = http.newCall(request).execute();
            responseStr = responseObj.body().string();
        } catch (IOException ex) {
            error("Error connecting to Clear.");
            return;
        }

        // Check for HTTP errors
        if (!responseObj.isSuccessful()) {
            error("Error: Clear returned "+responseObj.code());
            return;
        }

        // Decode and process the request
        JSONObject response;
        try {
            response = new JSONObject(responseStr);

            if (!response.getBoolean("success")) {
                error("Error: "+response.getString("error"));
                return;
            }


            JSONObject user = response.getJSONObject("registration");
            success(user.getString("first_name")+" "+user.getString("last_name")+" was checked in.");


        } catch (JSONException ex) {
            error("Error: Clear produced an invalid response.");
            return;
        }
    }

    private void toast(String message) {
        Toast.makeText(this, message,Toast.LENGTH_LONG).show();
    }

    private void success(String message) {
        tone.startTone(ToneGenerator.TONE_CDMA_ALERT_CALL_GUARD);
        toast(message);
    }

    private void error(String message) {
        tone.startTone(ToneGenerator.TONE_PROP_NACK);
        toast(message);
    }

    @Override
    public void onResume() {
        super.onResume();
        scanner.setResultHandler(this); // Register ourselves as a handler for scan results.
        scanner.startCamera();          // Start camera on resume
    }

    @Override
    public void onPause() {
        super.onPause();
        scanner.stopCamera();           // Stop camera on pause
    }

    public void toggleFlashlight(View v) {
        scanner.toggleFlash();
    }


    String lastResult = "";
    int repeatScans = 0;
    @Override
    public void handleResult(Result rawResult) {
        String thisResult = rawResult.getText();

        // Debounce
        if (thisResult.equals(lastResult)) {
            scanner.resumeCameraPreview(this);
            if (repeatScans++ > 3) {
                lastResult = "";
            }
            return;
        }
        lastResult = thisResult;
        repeatScans = 0;

        // Check if this is a configuration barcode
        if (thisResult.charAt(0) == '{') {
            configure(thisResult);
        } else {
            checkin(thisResult);
        }

        scanner.resumeCameraPreview(this);
    }

}
