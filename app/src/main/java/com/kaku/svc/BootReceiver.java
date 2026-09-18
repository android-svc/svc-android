package com.kaku.svc;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;

public class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context ctx, Intent i) {
        Intent svc = new Intent(ctx, BeaconService.class);
        if (Build.VERSION.SDK_INT >= 26) ctx.startForegroundService(svc);
        else ctx.startService(svc);
    }
}
