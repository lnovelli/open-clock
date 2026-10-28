package com.openclock;

import android.app.Activity;
import android.app.KeyguardManager;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.content.res.Configuration;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/** Private review screen: notification PendingIntents only open it, never apply a decision. */
public final class AccessActivity extends Activity {
    // Per-activity palette: Android recreates this screen when system night mode changes.
    private int BG, SURFACE, INK, MUTED, BLUE, SOFT, PALE, RED, RED_SOFT, GREEN, GREEN_SOFT;
    private int PRIMARY, ON_PRIMARY, RIPPLE;
    // Shared and bounded across recreations; no storage or policy-lock work on the UI thread.
    private static final ThreadPoolExecutor WORKER=new ThreadPoolExecutor(1,1,0,TimeUnit.SECONDS,new ArrayBlockingQueue<>(16));
    private final AtomicInteger generation=new AtomicInteger();
    private volatile boolean resumed;
    private LinearLayout body;
    private String targetToken, selectedChoice;
    private boolean historyExpanded;

    private static final class Entry {
        final String pkg,token,status,label;
        final Drawable icon;
        Entry(String p,String t,String s,String l,Drawable i) { pkg=p; token=t; status=s; label=l; icon=i; }
    }
    private static final class Event {
        final String pkg,operation,outcome,time;
        Event(String p,String o,String result,String t) { pkg=p; operation=o; outcome=result; time=t; }
    }
    @Override public void onCreate(Bundle b) {
        super.onCreate(b);
        boolean night=(getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK)
                == Configuration.UI_MODE_NIGHT_YES;
        if(night) {
            BG=0xff10141d; SURFACE=0xff1c2331; INK=0xffedf0f7; MUTED=0xffafb9ca;
            BLUE=0xffafc1ff; SOFT=0xff29365b; PALE=0xff303949;
            RED=0xffffb4be; RED_SOFT=0xff512b36; GREEN=0xff8bdaba; GREEN_SOFT=0xff204638;
            PRIMARY=0xff7895ff; ON_PRIMARY=0xff0e1a3e; RIPPLE=0x40afc1ff;
        } else {
            BG=0xfff0f3f9; SURFACE=0xffffffff; INK=0xff202635; MUTED=0xff737d90;
            BLUE=0xff365ee9; SOFT=0xffe6ecff; PALE=0xfff1f3f8;
            RED=0xffa53d50; RED_SOFT=0xffffeef0; GREEN=0xff24735a; GREEN_SOFT=0xffe2f5eb;
            PRIMARY=BLUE; ON_PRIMARY=0xffffffff; RIPPLE=0x26365ee9;
        }
        if(Build.VERSION.SDK_INT>=31) getWindow().setHideOverlayWindows(true);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
        getWindow().setStatusBarColor(BG);
        getWindow().setNavigationBarColor(BG);
        View decor=getWindow().getDecorView();
        int lightBars=View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
        int visibility=decor.getSystemUiVisibility() & ~lightBars;
        decor.setSystemUiVisibility(night?visibility:visibility | lightBars);
        if(b!=null) historyExpanded=b.getBoolean("historyExpanded",false);
        readTarget(getIntent());
        // Retain navigation away from a notification's targeted review across recreation.
        if(b!=null && b.containsKey("reviewTarget")) {
            targetToken=b.getString("reviewTarget");
            selectedChoice=b.getString("reviewChoice");
        }
    }
    @Override protected void onSaveInstanceState(Bundle b) {
        b.putBoolean("historyExpanded",historyExpanded);
        b.putString("reviewTarget",targetToken);
        b.putString("reviewChoice",selectedChoice);
        super.onSaveInstanceState(b);
    }
    private void readTarget(Intent intent) {
        targetToken=null; selectedChoice=null;
        if(intent==null || !"com.openclock.REVIEW_ACCESS".equals(intent.getAction())) return;
        Uri uri=intent.getData();
        if(uri==null || !"openclock".equals(uri.getScheme()) || !"access".equals(uri.getHost())) { targetToken="invalid"; return; }
        List<String> parts=uri.getPathSegments();
        if(parts.size()<1 || parts.size()>2 || !parts.get(0).matches("[0-9a-f-]{36}")) { targetToken="invalid"; return; }
        targetToken=parts.get(0);
        if(parts.size()==2) {
            String choice=parts.get(1);
            if(!choice.equals("allow") && !choice.equals("declined") && !choice.equals("block")) targetToken="invalid";
            else selectedChoice=choice;
        }
    }
    @Override protected void onResume() { super.onResume(); resumed=true; showList(); }
    @Override protected void onPause() { resumed=false; generation.incrementAndGet(); super.onPause(); }
    private boolean current(int g) { return resumed && generation.get()==g; }
    private boolean unlocked() {
        KeyguardManager k=getSystemService(KeyguardManager.class);
        return k!=null && !k.isDeviceLocked() && !k.isKeyguardLocked();
    }
    private int dp(int n) { return Math.round(n*getResources().getDisplayMetrics().density); }
    private GradientDrawable shape(int color,int radius) {
        GradientDrawable d=new GradientDrawable(); d.setColor(color); d.setCornerRadius(dp(radius)); return d;
    }
    private Drawable ripple(int color,int radius) {
        return new RippleDrawable(ColorStateList.valueOf(RIPPLE),shape(color,radius),null);
    }
    private LinearLayout column() { LinearLayout l=new LinearLayout(this); l.setOrientation(LinearLayout.VERTICAL); return l; }
    private LinearLayout row() { LinearLayout l=new LinearLayout(this); l.setGravity(Gravity.CENTER_VERTICAL); return l; }
    private TextView text(String value,int size,int color,boolean bold) {
        TextView t=new TextView(this); t.setText(value); t.setTextSize(size); t.setTextColor(color);
        if(bold) t.setTypeface(Typeface.create("sans-serif",Typeface.BOLD));
        return t;
    }
    private LinearLayout card(int color) {
        LinearLayout c=column(); c.setBackground(shape(color,24)); c.setPadding(dp(18),dp(18),dp(18),dp(18)); return c;
    }
    private void addBlock(LinearLayout parent,View child,int top) {
        LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-1,-2); lp.topMargin=dp(top); parent.addView(child,lp);
    }
    private void setup(String heading,String detail) {
        LinearLayout root=column(); root.setBackgroundColor(BG);
        root.setOnApplyWindowInsetsListener((v,insets) -> {
            int top,bottom,left,right;
            if(Build.VERSION.SDK_INT>=30) {
                android.graphics.Insets bars=insets.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
                top=bars.top; bottom=bars.bottom; left=bars.left; right=bars.right;
            } else {
                top=insets.getSystemWindowInsetTop(); bottom=insets.getSystemWindowInsetBottom();
                left=insets.getSystemWindowInsetLeft(); right=insets.getSystemWindowInsetRight();
            }
            v.setPadding(left,top,right,bottom); return insets;
        });
        setContentView(root); root.requestApplyInsets();
        LinearLayout header=row(); header.setPadding(dp(8),dp(12),dp(20),dp(8));
        TextView back=text("‹",32,INK,false); back.setGravity(Gravity.CENTER); back.setContentDescription("Back");
        back.setBackground(ripple(BG,24)); back.setOnClickListener(v -> finish());
        header.addView(back,new LinearLayout.LayoutParams(dp(48),dp(48)));
        TextView title=text(heading,25,INK,true); header.addView(title,new LinearLayout.LayoutParams(0,-2,1));
        root.addView(header);
        ScrollView scroll=new ScrollView(this); scroll.setFillViewport(true); scroll.setClipToPadding(false); scroll.setVerticalScrollBarEnabled(false);
        body=column(); body.setPadding(dp(20),dp(8),dp(20),dp(28));
        scroll.addView(body,new ScrollView.LayoutParams(-1,-2)); root.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));
        if(detail!=null && !detail.isEmpty()) {
            TextView subtitle=text(detail,14,MUTED,false); subtitle.setLineSpacing(dp(2),1f); addBlock(body,subtitle,0);
        }
    }
    private void enqueue(Runnable task) {
        try { WORKER.execute(task); }
        catch(java.util.concurrent.RejectedExecutionException e) {
            setup("Apps & access","Access review is busy. Please try again.");
            Button retry=plainButton("Retry",BLUE,SOFT); retry.setOnClickListener(v -> showList()); addBlock(body,retry,16);
        }
    }
    private void showList() {
        final int g=generation.incrementAndGet();
        final String target=targetToken, selected=selectedChoice;
        setup(target==null?"Apps & access":"Review app access","Loading access requests…");
        enqueue(() -> {
            if(!current(g)) return;
            ArrayList<Entry> entries=new ArrayList<>(); boolean error=false;
            ArrayList<Event> events=new ArrayList<>(); boolean logError=false;
            try {
                JSONObject state; synchronized(AccessPolicy.LOCK) { if(!current(g)) return; state=AccessPolicy.load(getApplicationContext()); }
                JSONArray records=state.getJSONArray("records");
                for(int i=0;i<records.length();i++) {
                    JSONObject r=records.getJSONObject(i); String pkg=r.getString("pkg"); Drawable icon=null;
                    try { icon=getPackageManager().getApplicationIcon(pkg); } catch(Exception ignored) { }
                    entries.add(new Entry(pkg,r.getString("token"),r.getString("state"),AccessPolicy.label(getApplicationContext(),pkg),icon));
                }
            } catch(Exception e) { error=true; }
            if(!current(g)) return;
            if(target==null) try {
                JSONArray history=AccessLog.read(getApplicationContext());
                java.text.DateFormat date=android.text.format.DateFormat.getDateFormat(getApplicationContext());
                java.text.DateFormat time=android.text.format.DateFormat.getTimeFormat(getApplicationContext());
                for(int i=history.length()-1;i>=0;i--) {
                    JSONObject event=history.getJSONObject(i); java.util.Date at=new java.util.Date(event.getLong("time"));
                    events.add(new Event(event.getString("package"),event.getString("operation"),event.getString("outcome"),date.format(at)+" · "+time.format(at)));
                }
            } catch(Exception e) { logError=true; }
            final boolean failed=error, logFailed=logError;
            runOnUiThread(() -> { if(current(g)) render(entries,events,target,selected,failed,logFailed); });
        });
    }
    private void render(List<Entry> entries,List<Event> events,String target,String selected,boolean error,boolean logError) {
        setup(target==null?"Apps & access":"Review app access",target==null?
                "Choose which apps can manage your alarms and groups.":"Review the app and choose whether it can access your alarms.");
        LinearLayout note=card(SOFT);
        note.addView(text("Full access to your alarms",16,BLUE,true));
        TextView explanation=text("Allowed apps can read, create, change and delete all alarms and groups. Only allow apps you trust.",13,INK,false);
        explanation.setLineSpacing(dp(2),1f); addBlock(note,explanation,6); addBlock(body,note,16);
        if(error) {
            addMessage("Access list unavailable","Unlock the device and try again.");
        } else if(target!=null) {
            Entry found=null; for(Entry entry:entries) if(target.equals(entry.token) && entry.status.equals("pending")) { found=entry; break; }
            if(found==null) addMessage("Request no longer available","This notification is stale. Review the current access list instead.");
            else {
                addSection("REQUESTING APP",null);
                addEntry(found,selected);
                TextView retry=text("The original request was not run. The app must retry after you allow it.",13,MUTED,false);
                retry.setLineSpacing(dp(2),1f); addBlock(body,retry,14);
            }
            Button all=plainButton("View all apps",BLUE,SURFACE);
            all.setOnClickListener(v -> { targetToken=null; selectedChoice=null; showList(); }); addBlock(body,all,20);
        } else {
            addSection("APPS",entries.isEmpty()?null:String.valueOf(entries.size()));
            if(entries.isEmpty()) addMessage("No app requests yet","When another app asks for access, it will appear here.");
            else for(Entry entry:entries) addEntry(entry,null);
            addHistory(events,logError);
        }
    }
    private void addSection(String title,String trailing) {
        LinearLayout heading=row(); heading.setPadding(dp(4),0,dp(4),0);
        TextView label=text(title,12,MUTED,true); label.setLetterSpacing(0.06f);
        heading.addView(label,new LinearLayout.LayoutParams(0,-2,1));
        if(trailing!=null) heading.addView(text(trailing,12,MUTED,false));
        addBlock(body,heading,24);
    }
    private void addMessage(String title,String detail) {
        LinearLayout box=card(SURFACE); box.addView(text(title,16,INK,true));
        TextView message=text(detail,13,MUTED,false); message.setLineSpacing(dp(2),1f); addBlock(box,message,6); addBlock(body,box,12);
    }
    private void addEntry(Entry entry,String selected) {
        LinearLayout box=card(SURFACE); LinearLayout identity=row();
        if(entry.icon!=null) {
            ImageView image=new ImageView(this); image.setImageDrawable(entry.icon); image.setScaleType(ImageView.ScaleType.FIT_CENTER);
            identity.addView(image,new LinearLayout.LayoutParams(dp(44),dp(44)));
        } else {
            TextView badge=text(entry.label.isEmpty()?"?":entry.label.substring(0,1).toUpperCase(java.util.Locale.ROOT),20,BLUE,true);
            badge.setGravity(Gravity.CENTER); badge.setBackground(shape(SOFT,16)); identity.addView(badge,new LinearLayout.LayoutParams(dp(44),dp(44)));
        }
        LinearLayout name=column(); LinearLayout.LayoutParams nameParams=new LinearLayout.LayoutParams(0,-2,1); nameParams.leftMargin=dp(12);
        name.addView(text(entry.label,17,INK,true));
        TextView packageName=text(entry.pkg,12,MUTED,false); packageName.setTextIsSelectable(false); addBlock(name,packageName,3);
        identity.addView(name,nameParams); box.addView(identity);
        String status=entry.status.equals("allow")?"Allowed":entry.status.equals("block")?"Blocked · don't ask again":entry.status.equals("declined")?"Not allowed":"Awaiting decision";
        int badgeInk=entry.status.equals("allow")?GREEN:entry.status.equals("block")?RED:BLUE;
        int badgeBg=entry.status.equals("allow")?GREEN_SOFT:entry.status.equals("block")?RED_SOFT:SOFT;
        TextView state=text(status,12,badgeInk,true); state.setBackground(shape(badgeBg,20)); state.setPadding(dp(11),dp(6),dp(11),dp(6));
        LinearLayout.LayoutParams statusParams=new LinearLayout.LayoutParams(-2,-2); statusParams.topMargin=dp(14); box.addView(state,statusParams);
        if(selected!=null) {
            TextView selectedNote=text("Selected from notification: "+choiceTitle(selected)+". Tap an action below to decide.",13,MUTED,false);
            selectedNote.setLineSpacing(dp(2),1f); addBlock(box,selectedNote,12);
        }
        LinearLayout first=row(); first.setGravity(Gravity.TOP);
        if(!entry.status.equals("allow")) addAction(first,"Allow",entry,"allow",true);
        if(!entry.status.equals("declined")) addAction(first,"Not allow",entry,"declined",false);
        if(first.getChildCount()>0) addBlock(box,first,14);
        LinearLayout second=row(); second.setGravity(Gravity.TOP);
        if(!entry.status.equals("block")) addAction(second,"Don't ask again",entry,"block",false);
        addAction(second,"Remove / reset",entry,"remove",false);
        addBlock(box,second,8); addBlock(body,box,12);
    }
    private String choiceTitle(String choice) {
        return choice.equals("allow")?"Allow":choice.equals("declined")?"Not allow":choice.equals("block")?"Don't ask again":"Remove / reset";
    }
    private Button plainButton(String label,int color,int background) {
        Button b=new Button(this); styleButton(b,label,color,background); return b;
    }
    private void styleButton(Button b,String label,int color,int background) {
        b.setText(label); b.setAllCaps(false); b.setTextColor(color); b.setTextSize(13); b.setTypeface(Typeface.create("sans-serif-medium",Typeface.NORMAL));
        b.setGravity(Gravity.CENTER); b.setMinWidth(0); b.setMinimumWidth(0); b.setMinHeight(dp(48)); b.setMinimumHeight(dp(48));
        b.setPadding(dp(10),dp(8),dp(10),dp(8)); b.setBackground(ripple(background,24));
    }
    private void addAction(LinearLayout container,String title,Entry entry,String choice,boolean primary) {
        Button button=new Button(this) {
            @Override public boolean onFilterTouchEventForSecurity(MotionEvent e) {
                return (e.getFlags() & (MotionEvent.FLAG_WINDOW_IS_OBSCURED | MotionEvent.FLAG_WINDOW_IS_PARTIALLY_OBSCURED))==0 && super.onFilterTouchEventForSecurity(e);
            }
        };
        int color=primary?ON_PRIMARY:choice.equals("block")?RED:INK;
        int bg=primary?PRIMARY:choice.equals("block")?RED_SOFT:PALE;
        styleButton(button,title,color,bg); button.setFilterTouchesWhenObscured(true);
        button.setOnClickListener(v -> {
            // Notification actions can be invoked by notification listeners. Only this
            // explicit, unobscured in-app tap may apply a decision; never auto-apply an intent.
            if(!unlocked() || !hasWindowFocus() || !resumed) { message("Unlock the device and try again."); return; }
            apply(entry,choice);
        });
        LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(0,-2,1);
        if(container.getChildCount()>0) lp.leftMargin=dp(8);
        container.addView(button,lp);
    }
    private void addHistory(List<Event> events,boolean error) {
        addSection("RECENT ACTIVITY",null);
        if(error) { addMessage("Activity unavailable","Some activity may not have been recorded."); return; }
        if(events.isEmpty()) { addMessage("No recent activity","External app requests will appear here."); return; }
        LinearLayout history=card(SURFACE); int count=historyExpanded?events.size():Math.min(5,events.size());
        for(int i=0;i<count;i++) {
            Event e=events.get(i);
            LinearLayout item=column();
            TextView title=text(e.operation+" · "+e.outcome,14,INK,true); item.addView(title);
            TextView detail=text((e.pkg.isEmpty()?"Unverified app":e.pkg)+"\n"+e.time,12,MUTED,false); addBlock(item,detail,4);
            if(i>0) { View line=new View(this); line.setBackgroundColor(PALE); LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-1,dp(1)); lp.topMargin=dp(12); history.addView(line,lp); }
            addBlock(history,item,i==0?0:12);
        }
        if(events.size()>5) {
            Button more=plainButton(historyExpanded?"Show less":"Show all activity",BLUE,SOFT);
            more.setOnClickListener(v -> { historyExpanded=!historyExpanded; showList(); }); addBlock(history,more,14);
        }
        addBlock(body,history,12);
        TextView caption=text("Up to 100 recent events · stored only on this device",12,MUTED,false); addBlock(body,caption,10);
    }
    private void apply(Entry entry,String choice) {
        final int g=generation.incrementAndGet();
        setup("Apps & access","Saving your choice…");
        enqueue(() -> {
            if(!current(g)) return;
            boolean success=true;
            try { AccessPolicy.decide(getApplicationContext(),entry.pkg,entry.token,choice,() -> current(g)); }
            catch(Exception e) { success=false; }
            final boolean ok=success;
            runOnUiThread(() -> {
                if(!current(g)) return;
                if(!ok) message("Could not change access. The app identity or choice may have changed; review again.");
                else message(choice.equals("allow")?"Access allowed":choice.equals("declined")?"Access not allowed":choice.equals("block")?"App blocked":"Access reset");
                targetToken=null; selectedChoice=null; showList();
            });
        });
    }
    private void message(String value) { android.widget.Toast.makeText(this,value,android.widget.Toast.LENGTH_LONG).show(); }
}