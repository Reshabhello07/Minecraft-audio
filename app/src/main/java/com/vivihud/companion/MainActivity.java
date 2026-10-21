package com.vivihud.companion;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.provider.Settings;
import android.view.Gravity;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

public class MainActivity extends Activity {

    private TextView status;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        int pad = (int) (24 * getResources().getDisplayMetrics().density);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(pad, pad * 2, pad, pad);
        root.setGravity(Gravity.TOP);

        TextView title = new TextView(this);
        title.setText("Vivi HUD Companion");
        title.setTextSize(24);
        root.addView(title);

        status = new TextView(this);
        status.setTextSize(16);
        status.setPadding(0, pad / 2, 0, pad / 2);
        root.addView(status);

        Button access = new Button(this);
        access.setText("1. Grant notification access");
        access.setOnClickListener(v ->
                startActivity(new Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)));
        root.addView(access);

        Button battery = new Button(this);
        battery.setText("2. Battery: set to Unrestricted");
        battery.setOnClickListener(v ->
                startActivity(new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)));
        root.addView(battery);

        TextView hint = new TextView(this);
        hint.setText("Then play a song in Vivi Music and launch Minecraft. "
                + "Test: open http://127.0.0.1:" + MusicListenerService.PORT + "/now in your browser.");
        hint.setPadding(0, pad, 0, 0);
        root.addView(hint);

        setContentView(root);
    }

    @Override
    protected void onResume() {
        super.onResume();
        String enabled = Settings.Secure.getString(getContentResolver(), "enabled_notification_listeners");
        boolean ok = enabled != null && enabled.contains(getPackageName());
        status.setText(ok ? "Status: notification access granted" : "Status: notification access NOT granted");
    }
}
