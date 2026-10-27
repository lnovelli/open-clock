package com.openclock;

import android.app.*;
import android.content.*;
import android.os.*;
import android.view.*;
import android.widget.*;
import org.json.JSONObject;

public final class AlarmActivity extends Activity {
    private long id; private String token;
    private final Handler handler=new Handler(Looper.getMainLooper());
    private final Runnable check=new Runnable() { public void run() {
        if(AlarmStore.active(AlarmActivity.this,id,token)==null) finish(); else handler.postDelayed(this,500);
    }};
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        if(Build.VERSION.SDK_INT>=27) { setShowWhenLocked(true); setTurnScreenOn(true); }
        else getWindow().addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED|WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON); show(getIntent());
    }
    @Override public void onNewIntent(Intent intent) { super.onNewIntent(intent); setIntent(intent); show(intent); }
    private void show(Intent intent) {
        handler.removeCallbacks(check); id=intent.getLongExtra("id",-1); token=intent.getStringExtra("token");
        if(token==null) { finish(); return; }
        JSONObject a=AlarmStore.active(this,id,token); if(a==null) {finish();return;}
        LinearLayout root=new LinearLayout(this); root.setOrientation(LinearLayout.VERTICAL); root.setGravity(Gravity.CENTER);
        int pad=(int)(24*getResources().getDisplayMetrics().density); root.setPadding(pad,pad,pad,pad);
        TextView time=new TextView(this); time.setText(String.format(java.util.Locale.getDefault(),"%02d:%02d",a.optInt("hour"),a.optInt("minute"))); time.setTextSize(64); root.addView(time);
        TextView label=new TextView(this); label.setText(a.optString("label").isEmpty()?"Alarm":a.optString("label")); label.setTextSize(26); root.addView(label);
        if(AlarmStore.canSnooze(a)) { Button snooze=new Button(this); snooze.setText("Snooze "+a.optInt("snoozeMinutes")+" minutes"); snooze.setOnClickListener(v->complete(true)); root.addView(snooze); }
        Button dismiss=new Button(this); dismiss.setText("Dismiss"); dismiss.setOnClickListener(v->complete(false)); root.addView(dismiss);
        setContentView(root); handler.post(check);
        try { startForegroundService(new Intent(this,RingingService.class)); } catch(RuntimeException e) { AlarmNotifications.warning(this,"Continuous ringing could not start"); }
    }
    private void complete(boolean snooze) {
        try {
            AlarmStore.finish(this,id,token,snooze);
            getSystemService(NotificationManager.class).cancel("alarm:"+id,11);
            AlarmStore.refreshService(this); finish();
        } catch(Exception e) { new AlertDialog.Builder(this).setTitle("Alarm unchanged").setMessage(AlarmStore.message(e)).setPositiveButton("OK",null).show(); }
    }
    @Override public void onResume() { super.onResume(); handler.removeCallbacks(check); if(token!=null) handler.post(check); }
    @Override public void onPause() { handler.removeCallbacks(check); super.onPause(); }
    @Override public void onDestroy() { handler.removeCallbacksAndMessages(null); super.onDestroy(); }
}
