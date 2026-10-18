package in.proxyproof.app;

import android.Manifest;
import android.app.*;
import android.content.*;
import android.content.pm.PackageManager;
import android.graphics.*;
import android.graphics.drawable.GradientDrawable;
import android.os.*;
import android.text.InputType;
import android.view.*;
import android.widget.*;
import org.json.*;
import java.util.*;
import java.util.concurrent.*;

public class MainActivity extends Activity implements BleController.Listener {
    private final int BG=Color.rgb(6,18,29), CARD=Color.rgb(13,34,49), CARD2=Color.rgb(16,40,56), MINT=Color.rgb(67,240,179), CYAN=Color.rgb(51,201,255), WHITE=Color.rgb(243,249,252), MUTED=Color.rgb(154,176,194), BORDER=Color.rgb(28,64,82), DANGER=Color.rgb(255,107,122);
    private final Handler handler=new Handler(Looper.getMainLooper());
    private final ExecutorService worker=Executors.newSingleThreadExecutor();
    private LinearLayout root,content,nav;
    private TextView statusView,sampleText;
    private SignalDotsView signalDots;
    private DeviceKey key; private ApiClient api; private BleController ble;
    private String role="",name="",ownId="",screen="home",csv="";
    private boolean foreground=false,busy=false,loading=false,mismatch=false;
    private int currentSession=0,generation=0,lastMarkedSession=0,sampleCount=0;
    private long ends=0,lastMarkedAt=0;
    private final Set<Integer> marked=new HashSet<>();
    private final Map<Integer,Long> tried=new HashMap<>();
    private final Runnable tick=new Runnable(){public void run(){ if(!foreground || role.isEmpty())return; refresh(); handler.postDelayed(this,4000); }};
    interface Work { Object run() throws Exception; }
    interface Done { void run(Object value) throws Exception; }

    @Override public void onCreate(Bundle state){
        super.onCreate(state);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        getWindow().setStatusBarColor(BG);getWindow().setNavigationBarColor(BG);
        try{ key=new DeviceKey();api=new ApiClient(getPreferences(0).getString("server","http://10.0.2.2:8000"),key);ble=new BleController(this,this);login(); }
        catch(Exception e){new AlertDialog.Builder(this).setTitle("Device security unavailable").setMessage(e.getMessage()).setPositiveButton("Close",(d,w)->finish()).show();}
    }
    @Override protected void onResume(){super.onResume();foreground=true;handler.removeCallbacks(tick); if(!role.isEmpty()){startBle();handler.post(tick);}}
    @Override protected void onPause(){super.onPause();foreground=false;handler.removeCallbacks(tick);if(ble!=null)ble.stop();}
    @Override protected void onDestroy(){super.onDestroy();worker.shutdownNow();}

    private int dp(int n){return Math.round(n*getResources().getDisplayMetrics().density);}
    private GradientDrawable shape(int color,int radius){GradientDrawable d=new GradientDrawable();d.setColor(color);d.setCornerRadius(dp(radius));return d;}
    private GradientDrawable gradient(int radius,int... colors){GradientDrawable d=new GradientDrawable(GradientDrawable.Orientation.TL_BR,colors);d.setCornerRadius(dp(radius));return d;}
    private LinearLayout column(){LinearLayout l=new LinearLayout(this);l.setOrientation(LinearLayout.VERTICAL);return l;}
    private TextView text(String s,int size,int color){TextView v=new TextView(this);v.setText(s);v.setTextColor(color);v.setTextSize(size);v.setPadding(0,dp(4),0,dp(4));v.setLineSpacing(0,1.08f);return v;}
    private void label(LinearLayout parent,String s,int size,int color){parent.addView(text(s,size,color));}
    private void title(LinearLayout parent,String s){TextView t=text(s,28,WHITE);t.setTypeface(null,Typeface.BOLD);t.setLetterSpacing(-.02f);parent.addView(t);}
    private void headline(LinearLayout parent,String top,String accent){TextView a=text(top,30,WHITE);a.setTypeface(null,Typeface.BOLD);a.setLetterSpacing(-.025f);parent.addView(a);TextView b=text(accent,30,MINT);b.setTypeface(null,Typeface.BOLD);b.setLetterSpacing(-.025f);parent.addView(b);}
    private void eyebrow(LinearLayout parent,String s){TextView t=text(s.toUpperCase(Locale.ROOT),11,MINT);t.setTypeface(null,Typeface.BOLD);t.setLetterSpacing(.11f);parent.addView(t);}
    private void gap(LinearLayout p,int n){Space v=new Space(this);p.addView(v,new LinearLayout.LayoutParams(1,dp(n)));}
    private LinearLayout card(LinearLayout parent){LinearLayout c=column();c.setPadding(dp(18),dp(16),dp(18),dp(16));GradientDrawable bg=gradient(20,CARD,CARD2);bg.setStroke(dp(1),BORDER);c.setBackground(bg);c.setElevation(dp(4));LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-1,-2);lp.setMargins(0,dp(10),0,dp(6));parent.addView(c,lp);return c;}
    private View divider(){View v=new View(this);v.setBackgroundColor(BORDER);v.setAlpha(.65f);v.setLayoutParams(new LinearLayout.LayoutParams(-1,dp(1)));return v;}
    private Button button(LinearLayout parent,String s,Runnable action){Button b=new Button(this);b.setText(s);b.setAllCaps(false);b.setTextColor(BG);b.setTextSize(15);b.setTypeface(null,Typeface.BOLD);b.setBackground(gradient(14,MINT,Color.rgb(56,226,205)));b.setElevation(dp(3));LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-1,dp(54));lp.setMargins(0,dp(11),0,dp(2));parent.addView(b,lp);b.setOnClickListener(v->action.run());return b;}
    private Button secondaryButton(LinearLayout parent,String s,Runnable action){Button b=new Button(this);b.setText(s);b.setAllCaps(false);b.setTextColor(WHITE);b.setTextSize(14);b.setTypeface(null,Typeface.BOLD);GradientDrawable bg=shape(Color.TRANSPARENT,14);bg.setStroke(dp(1),Color.rgb(69,115,137));b.setBackground(bg);LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-1,dp(50));lp.setMargins(0,dp(9),0,dp(1));parent.addView(b,lp);b.setOnClickListener(v->action.run());return b;}
    private EditText field(LinearLayout parent,String hint,String value,int type){EditText e=new EditText(this);e.setTextColor(WHITE);e.setHintTextColor(MUTED);e.setTextSize(15);e.setSingleLine(true);e.setHint(hint);e.setInputType(type);e.setText(value);e.setPadding(dp(15),0,dp(15),0);GradientDrawable bg=shape(Color.rgb(11,30,44),13);bg.setStroke(dp(1),Color.rgb(61,94,115));e.setBackground(bg);LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-1,dp(56));lp.setMargins(0,dp(8),0,0);parent.addView(e,lp);return e;}
    private TextView chip(String s,int color){TextView t=text(s,11,color);t.setTypeface(null,Typeface.BOLD);t.setGravity(Gravity.CENTER);GradientDrawable bg=shape(Color.argb(36,Color.red(color),Color.green(color),Color.blue(color)),99);bg.setStroke(dp(1),Color.argb(120,Color.red(color),Color.green(color),Color.blue(color)));t.setBackground(bg);t.setPadding(dp(12),dp(5),dp(12),dp(5));return t;}
    private void scaffold(String subtitle){
        root=column();root.setBackgroundColor(BG);root.setPadding(dp(20),dp(12),dp(20),dp(10));
        root.setOnApplyWindowInsetsListener((v,insets)->{v.setPadding(dp(20),insets.getSystemWindowInsetTop()+dp(8),dp(20),insets.getSystemWindowInsetBottom()+dp(8));return insets;});
        setContentView(root);root.requestApplyInsets();
        LinearLayout header=new LinearLayout(this);header.setGravity(Gravity.CENTER_VERTICAL);root.addView(header,new LinearLayout.LayoutParams(-1,-2));
        TextView brand=text("◉  PROXYPROOF",15,MINT);brand.setTypeface(null,Typeface.BOLD);brand.setLetterSpacing(.03f);header.addView(brand,new LinearLayout.LayoutParams(0,-2,1));
        TextView mode=chip(role.isEmpty()?"SECURE":role.toUpperCase(Locale.ROOT),MINT);header.addView(mode);
        label(root,subtitle,12,MUTED);
        ScrollView scroll=new ScrollView(this);scroll.setFillViewport(true);scroll.setClipToPadding(false);content=column();content.setPadding(0,dp(4),0,dp(4));scroll.addView(content);root.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));
        statusView=text("",12,MINT);statusView.setPadding(0,dp(7),0,dp(5));root.addView(statusView);
    }

    private void login(){
        role="";scaffold("Secure attendance • real presence");gap(content,18);
        headline(content,"Be present.","Be counted.");
        label(content,"Automatic, secure and verifiable attendance for a better campus experience.",15,MUTED);
        HeroOrbitView hero=new HeroOrbitView(this);content.addView(hero,new LinearLayout.LayoutParams(-1,dp(190)));
        LinearLayout box=card(content);eyebrow(box,"Sign in to your campus");label(box,"Connect to the server used by your organiser.",13,MUTED);
        EditText url=field(box,"Campus server URL",api.base,InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_VARIATION_URI);
        EditText id=field(box,"Student / organiser ID","",InputType.TYPE_CLASS_TEXT);
        EditText password=field(box,"Password","",InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_VARIATION_PASSWORD);
        button(box,"Sign in securely  →",()->{
            String base=url.getText().toString().trim().replaceAll("/+$","");
            if(!(base.startsWith("https://")||base.startsWith("http://"))){status("Enter a full http:// or https:// server URL.");return;}
            api.base=base;getPreferences(0).edit().putString("server",base).apply();
            task(()->api.call("POST","/login",json("id",id.getText().toString().trim(),"password",password.getText().toString(),"pub",key.publicKey())),value->{
                JSONObject j=(JSONObject)value;api.token=j.getString("token");role=j.getString("role");name=j.getString("name");ownId=j.getString("id");mismatch=j.getBoolean("device_mismatch");generation++;
                home();startBle();handler.removeCallbacks(tick);handler.post(tick);
            });
        });
        LinearLayout trust=card(content);eyebrow(trust,"Secure & reliable");label(trust,"✓ Server-verified attendance",14,WHITE);label(trust,"✓ Device-bound student identity",14,WHITE);label(trust,"✓ Rotating Bluetooth session beacon",14,WHITE);label(trust,"No roll calls. No QR codes. No shared attendance PIN.",12,MUTED);
    }

    private void shell(String page){
        scaffold(name+" • "+role);screen=page;nav=new LinearLayout(this);nav.setGravity(Gravity.CENTER);nav.setPadding(0,dp(3),0,0);GradientDrawable navBg=shape(Color.rgb(8,25,37),18);navBg.setStroke(dp(1),BORDER);nav.setBackground(navBg);root.addView(nav,new LinearLayout.LayoutParams(-1,dp(56)));
        String[] items={"⌂\nHome","◷\nHistory","○\nAccount"};String[] keys={"Home","History","Account"};
        for(int i=0;i<items.length;i++){String item=keys[i];Button b=new Button(this);b.setText(items[i]);b.setAllCaps(false);b.setTextSize(11);b.setTypeface(null,Typeface.BOLD);boolean active=(item.equals("Home")&&(page.equals("home")||page.equals("session")))||item.equalsIgnoreCase(page);b.setTextColor(active?MINT:MUTED);b.setBackgroundColor(Color.TRANSPARENT);nav.addView(b,new LinearLayout.LayoutParams(0,-1,1));b.setOnClickListener(v->{if(item.equals("Home"))home();else if(item.equals("History"))history();else account();});}
    }

    private void home(){
        shell("home");sampleText=null;signalDots=null;gap(content,12);
        if(mismatch){headline(content,"Device approval","needed.");LinearLayout c=card(content);eyebrow(c,"Action required");label(c,"This account is linked to another app installation. Request a change, then ask the admin to verify your identity.",14,MUTED);button(c,"Request device change",()->task(()->api.call("POST","/device/request",json("pub",key.publicKey())),r->status("Request sent. Sign in again after approval.")));return;}
        if(role.equals("student")){
            headline(content,"Your presence","matters.");label(content,"Keep this screen open near your organiser. Attendance is automatic and secure.",14,MUTED);
            if(lastMarkedSession>0){LinearLayout ok=card(content);eyebrow(ok,"Attendance confirmed");label(ok,"✓  You're marked present",21,MINT);label(ok,"Session #"+lastMarkedSession+" • verified by the ProxyProof server",13,WHITE);label(ok,new java.text.SimpleDateFormat("h:mm a",Locale.getDefault()).format(new Date(lastMarkedAt))+" • device-signed check-in",12,MUTED);}
            RadarView radar=new RadarView(this);content.addView(radar,new LinearLayout.LayoutParams(-1,dp(250)));
            LinearLayout listen=card(content);LinearLayout top=new LinearLayout(this);top.setGravity(Gravity.CENTER_VERTICAL);listen.addView(top);TextView wave=text("▥  Listening nearby",20,WHITE);wave.setTypeface(null,Typeface.BOLD);top.addView(wave,new LinearLayout.LayoutParams(0,-2,1));top.addView(chip("LISTENING",MINT));label(listen,"Detecting organiser beacons and collecting recent signal samples.",13,MUTED);
            signalDots=new SignalDotsView(this);listen.addView(signalDots,new LinearLayout.LayoutParams(-1,dp(34)));sampleText=text(sampleCount+" / 5 signal samples",13,WHITE);sampleText.setGravity(Gravity.CENTER);listen.addView(sampleText);
            LinearLayout tiles=new LinearLayout(this);tiles.setPadding(0,dp(10),0,0);listen.addView(tiles,new LinearLayout.LayoutParams(-1,-2));statusTile(tiles,"ᛒ","Bluetooth","On");statusTile(tiles,"●","Location","On");statusTile(tiles,"⌁","Network","On");
            secondaryButton(listen,"Restart Bluetooth scan",this::startBle);
            LinearLayout info=card(content);eyebrow(info,"No pairing. No attendance code.");label(info,"ProxyProof checks a rotating BLE beacon, five recent RSSI samples and your device signature before the server records attendance.",13,MUTED);
        }else{
            headline(content,"Ready for","roll call?");label(content,"Start a timed proximity session and watch students appear live.",14,MUTED);
            if(currentSession>0){showSession();return;}
            LinearLayout c=card(content);eyebrow(c,"New attendance session");label(c,"Create a live Bluetooth check-in",19,WHITE);
            EditText sessionTitle=field(c,"Session title","App Development • Round 1",InputType.TYPE_CLASS_TEXT);
            EditText duration=field(c,"Duration in minutes","5",InputType.TYPE_CLASS_NUMBER);
            EditText threshold=field(c,"RSSI threshold (dBm)","-70",InputType.TYPE_CLASS_NUMBER|InputType.TYPE_NUMBER_FLAG_SIGNED);
            button(c,"Start session & broadcast  →",()->{try{int seconds=Integer.parseInt(duration.getText().toString())*60,dbm=Integer.parseInt(threshold.getText().toString());task(()->api.call("POST","/sessions",json("title",sessionTitle.getText().toString(),"seconds",seconds,"threshold",dbm)),v->{JSONObject j=(JSONObject)v;currentSession=j.getInt("id");ends=j.getLong("ends")*1000;showSession();startBle();});}catch(Exception e){status("Enter a duration and negative RSSI threshold.");}});
            LinearLayout note=card(content);eyebrow(note,"Before you start");label(note,"The class roster is snapshotted when the session begins. Create new student accounts before starting.",13,MUTED);
            if(role.equals("admin"))secondaryButton(content,"Review device change requests",this::deviceRequests);
        }
    }

    private void statusTile(LinearLayout parent,String icon,String label,String state){LinearLayout tile=column();tile.setGravity(Gravity.CENTER);tile.setPadding(dp(6),dp(10),dp(6),dp(10));GradientDrawable bg=shape(Color.rgb(10,29,43),15);bg.setStroke(dp(1),BORDER);tile.setBackground(bg);TextView i=text(icon,24,MINT);i.setGravity(Gravity.CENTER);tile.addView(i);TextView l=text(label,11,WHITE);l.setGravity(Gravity.CENTER);tile.addView(l);TextView st=text(state+" ✓",11,MINT);st.setTypeface(null,Typeface.BOLD);st.setGravity(Gravity.CENTER);tile.addView(st);LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(0,-2,1);lp.setMargins(dp(3),0,dp(3),0);parent.addView(tile,lp);}

    private void showSession(){shell("session");gap(content,8);title(content,"Live attendance");label(content,"Loading session #"+currentSession+"…",13,MUTED);refresh();}

    private void renderSession(JSONObject result) throws Exception {
        content.removeAllViews();JSONObject session=result.getJSONObject("session");boolean active=result.getBoolean("active");String sessionTitle=session.getString("title");title(content,sessionTitle);long secondsLeft=Math.max(0,(session.getLong("ends")*1000-System.currentTimeMillis())/1000);
        LinearLayout liveRow=new LinearLayout(this);liveRow.setGravity(Gravity.CENTER_VERTICAL);content.addView(liveRow);liveRow.addView(chip(active?"● LIVE":"✓ COMPLETE",active?MINT:MUTED));TextView time=text(active?secondsLeft+" seconds left":"Session closed",12,active?MINT:MUTED);time.setPadding(dp(10),0,0,0);liveRow.addView(time);
        JSONArray roster=result.getJSONArray("roster");int present=result.getInt("present"),total=roster.length(),absent=Math.max(0,total-present);int rate=total==0?0:(int)Math.round(100.0*present/total);
        LinearLayout overview=card(content);eyebrow(overview,active?"Live insights":"Session summary");LinearLayout statRow=new LinearLayout(this);statRow.setGravity(Gravity.CENTER_VERTICAL);overview.addView(statRow);AttendanceRingView ring=new AttendanceRingView(this,present,total);statRow.addView(ring,new LinearLayout.LayoutParams(dp(150),dp(150)));LinearLayout side=column();side.setPadding(dp(12),0,0,0);statRow.addView(side,new LinearLayout.LayoutParams(0,-2,1));statLine(side,"●",present,"Present",MINT);statLine(side,"●",absent,"Absent",MUTED);statLine(side,"◉",total,"Expected",CYAN);label(overview,active?"Dashboard refreshes every 4 seconds • Bluetooth beacon rotates automatically":present+" present • "+absent+" absent • "+rate+"% attendance",12,MUTED);
        if(active)button(overview,"■  Close attendance session",()->new AlertDialog.Builder(this).setTitle("Close this session?").setMessage("Automatic check-ins will stop and the final summary will be shown.").setNegativeButton("Cancel",null).setPositiveButton("Close",(d,w)->task(()->api.call("POST","/sessions/"+currentSession+"/close",json()),r->{ble.stopAdvertising();refresh();})).show());
        secondaryButton(overview,"⇩  Export attendance CSV",this::exportCsv);if(!active)button(overview,"Create another session",()->{currentSession=0;home();});

        LinearLayout attendees=card(content);LinearLayout attHead=new LinearLayout(this);attHead.setGravity(Gravity.CENTER_VERTICAL);attendees.addView(attHead);TextView ah=text("Live attendees",18,WHITE);ah.setTypeface(null,Typeface.BOLD);attHead.addView(ah,new LinearLayout.LayoutParams(0,-2,1));attHead.addView(chip(String.valueOf(present),MINT));label(attendees,active?"Updates every 4 seconds":"Final verified roster",12,MUTED);
        for(int i=0;i<roster.length();i++){
            if(i>0){gap(attendees,5);attendees.addView(divider());gap(attendees,5);}JSONObject person=roster.getJSONObject(i);boolean isPresent=person.getInt("present")==1;String source=person.isNull("source")?"":person.optString("source");String sourceText=source.equals("ble")?"BLE":source.equals("manual")?"Manual":"—";
            LinearLayout row=new LinearLayout(this);row.setGravity(Gravity.CENTER_VERTICAL);row.setPadding(0,dp(7),0,dp(4));attendees.addView(row);TextView avatar=text(initials(person.getString("name")),13,BG);avatar.setTypeface(null,Typeface.BOLD);avatar.setGravity(Gravity.CENTER);avatar.setBackground(shape(isPresent?MINT:Color.rgb(85,105,120),99));row.addView(avatar,new LinearLayout.LayoutParams(dp(42),dp(42)));LinearLayout personBox=column();personBox.setPadding(dp(12),0,0,0);row.addView(personBox,new LinearLayout.LayoutParams(0,-2,1));TextView pn=text(person.getString("name"),15,WHITE);pn.setTypeface(null,Typeface.BOLD);personBox.addView(pn);label(personBox,person.getString("id")+" • "+(isPresent?"Present":"Absent")+" • "+sourceText,12,isPresent?MINT:MUTED);TextView edit=text("⋮",23,MUTED);edit.setGravity(Gravity.CENTER);edit.setPadding(dp(12),0,dp(8),0);row.addView(edit,new LinearLayout.LayoutParams(dp(44),dp(44)));edit.setOnClickListener(v->manual(person));
        }
        if(roster.length()==0)label(attendees,"No students are in this session roster.",14,MUTED);
        if(!active)ble.stopAdvertising();
    }

    private void statLine(LinearLayout parent,String dot,int value,String name,int color){LinearLayout row=new LinearLayout(this);row.setGravity(Gravity.CENTER_VERTICAL);TextView d=text(dot,13,color);row.addView(d);TextView v=text(String.valueOf(value),20,WHITE);v.setTypeface(null,Typeface.BOLD);v.setPadding(dp(8),0,dp(8),0);row.addView(v);TextView n=text(name,12,MUTED);row.addView(n);parent.addView(row);}
    private String initials(String full){String[] p=full.trim().split("\\s+");if(p.length==0||p[0].isEmpty())return "?";return (p[0].substring(0,1)+(p.length>1?p[p.length-1].substring(0,1):"")).toUpperCase(Locale.ROOT);}

    private void manual(JSONObject person){EditText reason=new EditText(this);reason.setHint("Reason for correction");new AlertDialog.Builder(this).setTitle(person.optString("name")).setView(reason).setNegativeButton("Cancel",null).setPositiveButton("Save correction",(dialog,which)->{int sid=currentSession;task(()->api.call("POST","/sessions/"+sid+"/manual",json("id",person.optString("id"),"present",person.optInt("present")==0,"reason",reason.getText().toString())),r->refresh());}).show();}

    private void history(){shell("history");gap(content,10);title(content,"Attendance history");loadHistory();}
    private void loadHistory(){
        if(mismatch){status("Request device approval first.");return;}
        task(()->api.call("GET","/sessions",null),value->{if(!screen.equals("history"))return;JSONObject j=(JSONObject)value;content.removeAllViews();title(content,"Attendance history");if(role.equals("student")){LinearLayout stats=card(content);eyebrow(stats,"Overall attendance");label(stats,j.isNull("percentage")?"—":j.getDouble("percentage")+"%",42,MINT);label(stats,"Across "+j.getInt("completed")+" completed sessions",14,MUTED);}JSONArray items=j.getJSONArray("sessions");if(items.length()==0)label(content,"No sessions yet. Your first session will appear here.",16,MUTED);for(int i=0;i<items.length();i++){JSONObject s=items.getJSONObject(i);LinearLayout c=card(content);label(c,s.getString("title"),18,WHITE);label(c,new java.text.SimpleDateFormat("dd MMM, h:mm a",Locale.getDefault()).format(new Date(s.getLong("started")*1000)),12,MUTED);String state=s.getBoolean("active")?"● Live now":role.equals("student")?(s.optInt("present")==1?"✓ Present":"○ Absent"):"✓ Completed";label(c,state,14,MINT);if(!role.equals("student"))secondaryButton(c,"Open dashboard",()->{ble.stopAdvertising();currentSession=s.optInt("id");ends=s.optLong("ends")*1000;showSession();});}});
    }

    private void account(){
        shell("account");gap(content,10);title(content,"Your account");LinearLayout c=card(content);eyebrow(c,"Signed in");label(c,name,23,WHITE);label(c,"Role: "+role,15,MUTED);label(c,"Server: "+api.base,13,MUTED);label(c,"Device key • Android Keystore P-256",13,MINT);
        LinearLayout security=card(content);eyebrow(security,"Security model");label(security,"Student requests are signed by this app installation. BLE proximity is estimated using recent RSSI samples and a rotating server-issued beacon.",13,MUTED);label(security,"This demo is not a room-boundary guarantee and does not claim relay-proof physical identity.",12,MUTED);
        if(role.equals("admin"))secondaryButton(content,"Review device changes",this::deviceRequests);secondaryButton(content,"Sign out",()->task(()->api.call("POST","/logout",json()),r->{generation++;ble.stop();handler.removeCallbacks(tick);role="";api.token="";currentSession=0;lastMarkedSession=0;sampleCount=0;marked.clear();tried.clear();login();}));
    }

    private void deviceRequests(){shell("devices");title(content,"Device approvals");task(()->api.call("GET","/device/requests",null),value->{if(!screen.equals("devices"))return;JSONArray requests=((JSONObject)value).getJSONArray("requests");if(requests.length()==0)label(content,"No pending device changes.",16,MUTED);for(int i=0;i<requests.length();i++){JSONObject r=requests.getJSONObject(i);LinearLayout c=card(content);label(c,r.getString("name")+" • "+r.getString("uid"),18,WHITE);label(c,"Verify this student's identity in person before approval.",13,MUTED);for(boolean approve:new boolean[]{true,false})secondaryButton(c,approve?"Approve new device":"Reject request",()->new AlertDialog.Builder(this).setTitle(approve?"Approve this device?":"Reject this request?").setMessage(r.optString("uid")).setNegativeButton("Cancel",null).setPositiveButton("Confirm",(d,w)->task(()->api.call("POST","/device/approve",json("id",r.optString("uid"),"approve",approve)),v->deviceRequests())).show());}});}
    private void exportCsv(){int sid=currentSession;task(()->api.request("GET","/sessions/"+sid+"/csv",null),value->{csv=(String)value;Intent intent=new Intent(Intent.ACTION_CREATE_DOCUMENT);intent.addCategory(Intent.CATEGORY_OPENABLE);intent.setType("text/csv");intent.putExtra(Intent.EXTRA_TITLE,"proxyproof-session-"+sid+".csv");startActivityForResult(intent,50);});}
    @Override protected void onActivityResult(int request,int result,Intent data){super.onActivityResult(request,result,data);if(request==50 && result==RESULT_OK && data!=null && data.getData()!=null){try(java.io.OutputStream out=getContentResolver().openOutputStream(data.getData())){out.write(csv.getBytes(java.nio.charset.StandardCharsets.UTF_8));status("CSV saved.");}catch(Exception e){status("Could not save CSV: "+e.getMessage());}}}

    private void startBle(){if(ble==null || mismatch || role.isEmpty() || !foreground)return;String[] permissions=Build.VERSION.SDK_INT>=31?new String[]{Manifest.permission.BLUETOOTH_SCAN,Manifest.permission.BLUETOOTH_CONNECT,Manifest.permission.BLUETOOTH_ADVERTISE,Manifest.permission.ACCESS_FINE_LOCATION,Manifest.permission.ACCESS_COARSE_LOCATION}:new String[]{Manifest.permission.ACCESS_FINE_LOCATION,Manifest.permission.ACCESS_COARSE_LOCATION};for(String p:permissions)if(checkSelfPermission(p)!=PackageManager.PERMISSION_GRANTED){requestPermissions(permissions,7);return;}try{if(role.equals("student")){sampleCount=0;updateSampleUi();ble.scan();}else refresh();}catch(Exception e){status("Bluetooth: "+e.getMessage());}}
    @Override public void onRequestPermissionsResult(int request,String[] permissions,int[] results){super.onRequestPermissionsResult(request,permissions,results);if(request==7){boolean all=results.length>0;for(int value:results)all&=value==PackageManager.PERMISSION_GRANTED;if(all)startBle();else status("Permissions denied. Enable Nearby devices and Location in app settings, then retry.");}}
    private boolean canBluetooth(){return Build.VERSION.SDK_INT<31 || (checkSelfPermission(Manifest.permission.BLUETOOTH_ADVERTISE)==PackageManager.PERMISSION_GRANTED && checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT)==PackageManager.PERMISSION_GRANTED);}
    private void refresh(){
        if(!foreground||role.isEmpty()||mismatch||loading)return;if(role.equals("student"))return;if(currentSession<=0)return;loading=true;int sid=currentSession,g=generation;ApiClient client=api;
        worker.execute(()->{JSONObject dashboard=null,beacon=null;String error=null;try{dashboard=client.call("GET","/sessions/"+sid,null);if(dashboard.getBoolean("active") && dashboard.getJSONObject("session").getString("owner").equals(ownId) && canBluetooth())beacon=client.call("POST","/sessions/"+sid+"/beacon",json());}catch(Exception e){error=e.getMessage();}JSONObject finalDashboard=dashboard,finalBeacon=beacon;String finalError=error;handler.post(()->{loading=false;if(g!=generation || sid!=currentSession || !foreground)return;try{if(finalDashboard!=null && screen.equals("session"))renderSession(finalDashboard);if(finalBeacon!=null){ble.advertise(sid,finalBeacon.getString("beacon"));long delay=Math.max(0,finalBeacon.getLong("ends")*1000-System.currentTimeMillis());handler.postDelayed(()->{if(currentSession==sid)ble.stopAdvertising();},Math.min(delay,3600000));}if(finalError!=null){ble.stopAdvertising();status(finalError);}}catch(Exception e){status(e.getMessage());}});});
    }
    @Override public void status(String message){handler.post(()->{if(statusView!=null)statusView.setText(message);});}
    @Override public void progress(int count){handler.post(()->{sampleCount=Math.max(0,Math.min(5,count));updateSampleUi();});}
    private void updateSampleUi(){if(sampleText!=null)sampleText.setText(sampleCount+" / 5 signal samples");if(signalDots!=null)signalDots.setCount(sampleCount);}
    @Override public void beacon(int sid,String token,List<Integer> samples){
        if(busy || mismatch || !foreground || marked.contains(sid) || System.currentTimeMillis()-tried.getOrDefault(sid,0L)<4000)return;busy=true;tried.put(sid,System.currentTimeMillis());int g=generation;ApiClient client=api;
        worker.execute(()->{String message;boolean success=false;try{JSONObject r=client.call("POST","/attendance",json("sid",sid,"beacon",token,"samples",new JSONArray(samples)));message=r.getString("message")+" • session #"+sid;success=true;}catch(Exception e){message=e.getMessage();}String result=message;boolean ok=success;handler.post(()->{busy=false;if(g!=generation)return;if(ok){marked.add(sid);lastMarkedSession=sid;lastMarkedAt=System.currentTimeMillis();sampleCount=5;Toast.makeText(this,"Attendance confirmed ✓",Toast.LENGTH_LONG).show();if(screen.equals("home"))home();}status(result);});});
    }
    private void task(Work work,Done done){if(busy){status("Please wait for the current request…");return;}busy=true;status("Connecting…");int g=generation;worker.execute(()->{try{Object result=work.run();handler.post(()->{busy=false;if(g!=generation)return;try{status("");done.run(result);}catch(Exception e){status(e.getMessage());}});}catch(Exception e){handler.post(()->{busy=false;if(g==generation)status(e.getMessage()==null?"Connection failed. Check server URL and network.":e.getMessage());});}});}
    private static JSONObject json(Object... pairs){JSONObject j=new JSONObject();try{for(int i=0;i<pairs.length;i+=2)j.put((String)pairs[i],pairs[i+1]);}catch(JSONException e){throw new IllegalArgumentException(e);}return j;}

    private class HeroOrbitView extends View {
        private final Paint p=new Paint(Paint.ANTI_ALIAS_FLAG);HeroOrbitView(Context c){super(c);}
        @Override protected void onDraw(Canvas c){super.onDraw(c);float w=getWidth(),h=getHeight(),cx=w/2f,cy=h*.58f;p.setStyle(Paint.Style.STROKE);for(int i=0;i<3;i++){p.setStrokeWidth(dp(1));p.setColor(Color.argb(70-i*15,67,240,179));c.drawCircle(cx,cy,dp(42+i*24),p);}p.setStyle(Paint.Style.FILL);drawOrb(c,cx-dp(84),cy-dp(24),dp(29),CYAN,"ᛒ");drawOrb(c,cx+dp(82),cy-dp(32),dp(27),MINT,"✓");drawOrb(c,cx,cy+dp(6),dp(34),MINT,"●");p.setColor(Color.argb(25,67,240,179));c.drawCircle(cx,cy,dp(70),p);}
        private void drawOrb(Canvas c,float x,float y,float r,int color,String s){p.setStyle(Paint.Style.FILL);p.setColor(Color.argb(45,Color.red(color),Color.green(color),Color.blue(color)));c.drawCircle(x,y,r+dp(6),p);p.setColor(color);c.drawCircle(x,y,r,p);p.setColor(BG);p.setTextAlign(Paint.Align.CENTER);p.setTypeface(Typeface.DEFAULT_BOLD);p.setTextSize(r*.9f);Paint.FontMetrics fm=p.getFontMetrics();c.drawText(s,x,y-(fm.ascent+fm.descent)/2,p);}
    }

    private class RadarView extends View {
        private final Paint p=new Paint(Paint.ANTI_ALIAS_FLAG);RadarView(Context c){super(c);}
        @Override protected void onDraw(Canvas c){super.onDraw(c);float cx=getWidth()/2f,cy=getHeight()/2f;float max=Math.min(getWidth(),getHeight())*.40f;p.setStyle(Paint.Style.FILL);p.setColor(Color.argb(24,51,201,255));c.drawCircle(cx,cy,max+dp(10),p);p.setStyle(Paint.Style.STROKE);for(int i=1;i<=4;i++){p.setStrokeWidth(dp(i==4?2:1));p.setColor(Color.argb(70+i*16,67,240,179));c.drawCircle(cx,cy,max*i/4f,p);}RectF arc=new RectF(cx-max,cy-max,cx+max,cy+max);p.setStrokeWidth(dp(7));p.setStrokeCap(Paint.Cap.ROUND);p.setColor(MINT);c.drawArc(arc,-75,48,false,p);p.setStrokeCap(Paint.Cap.BUTT);p.setStyle(Paint.Style.FILL);p.setColor(Color.argb(55,67,240,179));c.drawCircle(cx,cy,dp(49),p);p.setColor(MINT);c.drawCircle(cx,cy,dp(34),p);p.setColor(BG);p.setTextAlign(Paint.Align.CENTER);p.setTextSize(dp(31));p.setTypeface(Typeface.DEFAULT_BOLD);Paint.FontMetrics fm=p.getFontMetrics();c.drawText("ᛒ",cx,cy-(fm.ascent+fm.descent)/2,p);p.setColor(MINT);c.drawCircle(cx-max*.55f,cy-max*.35f,dp(5),p);c.drawCircle(cx+max*.65f,cy+max*.08f,dp(5),p);c.drawCircle(cx-max*.22f,cy+max*.72f,dp(4),p);}
    }

    private class SignalDotsView extends View {
        private final Paint p=new Paint(Paint.ANTI_ALIAS_FLAG);private int count=0;SignalDotsView(Context c){super(c);}void setCount(int n){count=n;invalidate();}
        @Override protected void onDraw(Canvas c){super.onDraw(c);float gap=getWidth()/6f,cy=getHeight()/2f;for(int i=0;i<5;i++){float x=gap*(i+1);p.setStyle(Paint.Style.FILL);p.setColor(i<count?MINT:Color.rgb(41,66,82));c.drawCircle(x,cy,dp(8),p);if(i<count){p.setColor(BG);p.setStrokeWidth(dp(2));p.setStyle(Paint.Style.STROKE);c.drawLine(x-dp(4),cy,x-dp(1),cy+dp(3),p);c.drawLine(x-dp(1),cy+dp(3),x+dp(5),cy-dp(4),p);}}}
    }

    private class AttendanceRingView extends View {
        private final Paint p=new Paint(Paint.ANTI_ALIAS_FLAG);private final int present,total;AttendanceRingView(Context c,int pr,int to){super(c);present=pr;total=to;}
        @Override protected void onDraw(Canvas c){super.onDraw(c);float cx=getWidth()/2f,cy=getHeight()/2f,r=Math.min(getWidth(),getHeight())*.36f;p.setStyle(Paint.Style.STROKE);p.setStrokeWidth(dp(15));p.setStrokeCap(Paint.Cap.ROUND);p.setColor(Color.rgb(31,60,75));c.drawCircle(cx,cy,r,p);float fraction=total==0?0:(float)present/total;p.setColor(MINT);RectF rect=new RectF(cx-r,cy-r,cx+r,cy+r);c.drawArc(rect,-90,360*fraction,false,p);p.setStrokeCap(Paint.Cap.BUTT);p.setStyle(Paint.Style.FILL);p.setTextAlign(Paint.Align.CENTER);p.setColor(WHITE);p.setTypeface(Typeface.DEFAULT_BOLD);p.setTextSize(dp(27));Paint.FontMetrics fm=p.getFontMetrics();c.drawText(present+" / "+total,cx,cy-(fm.ascent+fm.descent)/2-dp(5),p);p.setTypeface(Typeface.DEFAULT);p.setTextSize(dp(12));p.setColor(MUTED);c.drawText("Present",cx,cy+dp(27),p);}
    }
}
