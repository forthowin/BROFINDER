package com.example.allen.brofinder.activity;

import android.content.Context;
import android.content.Intent;
import android.content.IntentSender.SendIntentException;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.os.AsyncTask;
import android.support.v7.app.ActionBarActivity;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.view.View.OnClickListener;
import android.widget.ViewSwitcher;

import com.example.allen.brofinder.R;
import com.google.android.gms.common.ConnectionResult;
import com.google.android.gms.common.GooglePlayServicesUtil;
import com.google.android.gms.common.api.GoogleApiClient;
import com.google.android.gms.common.api.GoogleApiClient.OnConnectionFailedListener;
import com.google.android.gms.common.api.GoogleApiClient.ConnectionCallbacks;
import com.google.android.gms.gcm.GoogleCloudMessaging;
import com.google.android.gms.plus.Plus;
import com.google.gson.GsonBuilder;

import org.apache.http.client.methods.HttpPost;
import org.apache.http.entity.StringEntity;
import org.apache.http.impl.client.DefaultHttpClient;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

public class LoginActivity extends ActionBarActivity implements ConnectionCallbacks, OnConnectionFailedListener, OnClickListener {
    private static final String PROPERTY_REG_ID = "registration_id";
    private static final String PROPERTY_APP_VERSION = "appVersion";
    private final static int PLAY_SERVICES_RESOLUTION_REQUEST = 9000;
    private static final String SENDER_ID = "1026788982313";
    private static final String TAG = "LoginActivity";
    private GoogleCloudMessaging gcm;
    private String registrationId;

    private static final int RC_SIGN_IN = 0;
    private GoogleApiClient googleApiClient;
    private boolean signInClicked;
    private ConnectionResult connectionResult;
    private boolean intentInProgress;
    private ViewSwitcher viewSwitcher;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_login);
        findViewById(R.id.sign_in_button).setOnClickListener(this);
        viewSwitcher = (ViewSwitcher) findViewById(R.id.viewSwitcher);

        googleApiClient = new GoogleApiClient.Builder(this)
                .addConnectionCallbacks(this)
                .addOnConnectionFailedListener(this)
                .addApi(Plus.API)
                .addScope(Plus.SCOPE_PLUS_LOGIN)
                .build();
    }

    @Override
    protected void onStart() {
        super.onStart();
        googleApiClient.connect();
    }

    @Override
    protected void onStop() {
        super.onStop();
//        TODO: Remove google account for testing
//        Plus.AccountApi.clearDefaultAccount(googleApiClient);
//        Plus.AccountApi.revokeAccessAndDisconnect(googleApiClient);

        if(googleApiClient.isConnected())
            googleApiClient.disconnect();
    }

    protected void onActivityResult(int requestCode, int responseCode, Intent intent) {
        if (requestCode == RC_SIGN_IN) {
            if (responseCode != RESULT_OK) {
                signInClicked = false;
                viewSwitcher.showNext();
            }

            intentInProgress = false;

            if (!googleApiClient.isConnecting()) {
                googleApiClient.connect();
            }
        }
    }

    @Override
    public void onConnected(Bundle bundle) {
        Log.i("derp", "SIGNED IN YEAHHHHHHH");
        if (checkPlayServices()) {
            gcm = GoogleCloudMessaging.getInstance(this);
            registrationId = getRegistrationId(this);

            if (registrationId.isEmpty()) {
                registerInBackground();
            }
        } else {
            Log.i(TAG, "No valid Google Play Services APK found.");
        }

        Intent mainActivityIntent = new Intent(this, MainActivity.class);
        startActivity(mainActivityIntent);
        finish();
    }

    @Override
    public void onConnectionSuspended(int i) {
        googleApiClient.connect();
    }

    @Override
    public void onConnectionFailed(ConnectionResult result) {
        if (!intentInProgress) {
            viewSwitcher.showNext();
            connectionResult = result;
            if (signInClicked) {
                resolveSignInError();
            }
        }
    }

    public void onClick(View view) {
        if (view.getId() == R.id.sign_in_button && !googleApiClient.isConnecting()) {
            signInClicked = true;
            resolveSignInError();
        }
    }

    private void resolveSignInError() {
        if (connectionResult.hasResolution()) {
            try {
                intentInProgress = true;
                startIntentSenderForResult(connectionResult.getResolution().getIntentSender(), RC_SIGN_IN, null, 0, 0, 0);
            } catch (SendIntentException e) {
                intentInProgress = false;
                googleApiClient.connect();
            }
        }
    }

    private boolean checkPlayServices() {
        int resultCode = GooglePlayServicesUtil.isGooglePlayServicesAvailable(this);
        if (resultCode != ConnectionResult.SUCCESS) {
            if (GooglePlayServicesUtil.isUserRecoverableError(resultCode)) {
                GooglePlayServicesUtil.getErrorDialog(resultCode, this, PLAY_SERVICES_RESOLUTION_REQUEST).show();
            } else {
                Log.i(TAG, "This device is not supported.");
                finish();
            }
            return false;
        }
        return true;
    }

    private String getRegistrationId(Context context) {
        final SharedPreferences prefs = getGCMPreferences();
        String registrationId = prefs.getString(PROPERTY_REG_ID, "");
        if (registrationId.isEmpty()) {
            Log.i(TAG, "Registration not found.");
            return "";
        }

        int registeredVersion = prefs.getInt(PROPERTY_APP_VERSION, Integer.MIN_VALUE);
        int currentVersion = getAppVersion(context);
        if (registeredVersion != currentVersion) {
            Log.i(TAG, "App version changed.");
            return "";
        }
        return registrationId;
    }

    private SharedPreferences getGCMPreferences() {
        return getSharedPreferences(LoginActivity.class.getSimpleName(), Context.MODE_PRIVATE);
    }

    private static int getAppVersion(Context context) {
        try {
            PackageInfo packageInfo = context.getPackageManager().getPackageInfo(context.getPackageName(), 0);
            return packageInfo.versionCode;
        } catch (PackageManager.NameNotFoundException e) {
            throw new RuntimeException("Could not get package name: " + e);
        }
    }

    private void registerInBackground() {
        new AsyncTask<Void, Void, String>() {
            @Override
            protected String doInBackground(Void... params) {
                String msg = "";
                try {
                    if (gcm == null) {
                        gcm = GoogleCloudMessaging.getInstance(getApplicationContext());
                    }
                    registrationId = gcm.register(SENDER_ID);
                    msg = "Device registered, registration ID=" + registrationId;

                    String accountName = Plus.AccountApi.getAccountName(googleApiClient);
                    sendRegistrationIdToBackend(accountName, registrationId);
                    storeRegistrationId(getApplicationContext(), registrationId);
                } catch (IOException ex) {
                    msg = "Error :" + ex.getMessage();
                }
                return msg;
            }

            @Override
            protected void onPostExecute(String msg) {
                Log.i(TAG, "HERP DERP");
            }
        }.execute(null, null, null);
    }

    private void storeRegistrationId(Context context, String regId) {
        final SharedPreferences prefs = getGCMPreferences();
        int appVersion = getAppVersion(context);
        Log.i(TAG, "Saving regId on app version " + appVersion);
        SharedPreferences.Editor editor = prefs.edit();
        editor.putString(PROPERTY_REG_ID, regId);
        editor.putInt(PROPERTY_APP_VERSION, appVersion);
        editor.commit();
    }

    private void sendRegistrationIdToBackend(String email, String registrationId) {
        Map<String, String> userMap = new HashMap<>();
        userMap.put("email", email);
        userMap.put("registrationId", registrationId);
        String userMapJson= new GsonBuilder().create().toJson(userMap);

        // TODO: Uri for localhost from genymotion
        String uri = "http://10.0.3.2:8080/register";

        HttpPost request = new HttpPost(uri);
        try {
            request.setEntity(new StringEntity(userMapJson));
            request.setHeader("Content-Type", "application/json");
            new DefaultHttpClient().execute(request);
        } catch (Exception e) {
            Log.i(TAG, e.toString());
        }
    }
}
