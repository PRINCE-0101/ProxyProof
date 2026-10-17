package in.proxyproof.app;

import android.Manifest;
import android.app.*;
import android.content.*;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.*;
import android.provider.Settings;
import android.text.InputType;
import android.view.*;
import android.widget.*;
import org.json.*;
import java.util.*;
import java.util.concurrent.*;

public class MainActivity extends Activity implements BleController.Listener {
    private final int BG=Color.rgb(16,25,35), CARD=Color.rgb(27,40,53), GREEN=Color.rgb(94,230,176), WHITE=Color.rgb(237,244,250), MUTED=Color.rgb(163,182,198);
    private final Handler handler=new Handler(Looper.getMainLooper());
    private final ExecutorService worker=Executors.newSingleThreadExecutor();
    private LinearLayout root,content,nav;
    private TextView statusView;
    private DeviceKey key; private ApiClient api; private BleController ble;
    private String role="",name="",ownId="",screen="home",csv="";
    private boolean foreground=false,busy=false,loading=false,mismatch=false;
    private int currentSession=0,generation=0;
    private long ends=0;
    private final Set<Integer> marked=new HashSet<>();
    private final Map<Integer,Long> tried=new HashMap<>();
    private final Runnable tick=new Runnable(){public void run(){ if(!foreground || role.isEmpty())return; refresh(); handler.postDelayed(this,4000); }};
    interface Work { Object run() throws Exception; }
    interface Done { void run(Object value) throws Exception; }
    @Override public void onCreate(Bundle state){
        super.onCreate(state);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        try{ key=new DeviceKey();api=new ApiClient(getPreferences(0).getString("server","http://10.0.2.2:8000"),key);ble=new BleController(this,this);login(); }
        catch(Exception e){new AlertDialog.Builder(this).setTitle("Device security unavailable").setMessage(e.getMessage()).setPositiveButton("Close",(d,w)->finish()).show();}
    }
    @Override protected void onResume(){super.onResume();foreground=true;handler.removeCallbacks(tick); if(!role.isEmpty()){startBle();handler.post(tick);}}
    @Override protected void onPause(){super.onPause();foreground=false;handler.removeCallbacks(tick);if(ble!=null)ble.stop();}
    @Override protected void onDestroy(){super.onDestroy();worker.shutdownNow();}
    private int dp(int n){return Math.round(n*getResources().getDisplayMetrics().density);}
    private GradientDrawable shape(int color,int radius){GradientDrawable d=new GradientDrawable();d.setColor(color);d.setCornerRadius(dp(radius));return d;}
    private LinearLayout column(){LinearLayout l=new LinearLayout(this);l.setOrientation(LinearLayout.VERTICAL);return l;}
    private TextView text(String s,int size,int color){TextView v=new TextView(this);v.setText(s);v.setTextColor(color);v.setTextSize(size);v.setPadding(0,dp(5),0,dp(5));return v;}
    private void label(LinearLayout parent,String s,int size,int color){parent.addView(text(s,size,color));}
    private void title(LinearLayout parent,String s){TextView t=text(s,25,WHITE);t.setTypeface(null,Typeface.BOLD);parent.addView(t);}
    private void gap(LinearLayout p,int n){Space v=new Space(this);p.addView(v,new LinearLayout.LayoutParams(1,dp(n)));}
    private LinearLayout card(LinearLayout parent){LinearLayout c=column();c.setPadding(dp(18),dp(15),dp(18),dp(15));c.setBackground(shape(CARD,18));LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-1,-2);lp.setMargins(0,dp(10),0,dp(6));parent.addView(c,lp);return c;}
    private Button button(LinearLayout parent,String s,Runnable action){Button b=new Button(this);b.setText(s);b.setAllCaps(false);b.setTextColor(BG);b.setTextSize(15);b.setBackgroundTintList(android.content.res.ColorStateList.valueOf(GREEN));LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-1,dp(52));lp.setMargins(0,dp(10),0,dp(2));parent.addView(b,lp);b.setOnClickListener(v->action.run());return b;}
    private EditText field(LinearLayout parent,String hint,String value,int type){EditText e=new EditText(this);e.setTextColor(WHITE);e.setHintTextColor(MUTED);e.setTextSize(16);e.setSingleLine(true);e.setHint(hint);e.setInputType(type);e.setText(value);parent.addView(e,new LinearLayout.LayoutParams(-1,dp(56)));return e;}
    private void scaffold(String subtitle){
        root=column();root.setBackgroundColor(BG);root.setPadding(dp(22),dp(18),dp(22),dp(12));
        root.setOnApplyWindowInsetsListener((v,insets)->{v.setPadding(dp(22),insets.getSystemWindowInsetTop()+dp(12),dp(22),insets.getSystemWindowInsetBottom()+dp(10));return insets;});
        setContentView(root);root.requestApplyInsets();
        label(root,"◉  PROXYPROOF",14,GREEN);label(root,subtitle,13,MUTED);
        ScrollView scroll=new ScrollView(this);scroll.setFillViewport(true);content=column();scroll.addView(content);root.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));
        statusView=text("",13,GREEN);root.addView(statusView);
    }
    private void login(){
        scaffold("Bluetooth attendance • Android");gap(content,32);title(content,"Be present.\nBe counted.");
        label(content,"Secure check-in, without roll calls or shared codes.",16,MUTED);gap(content,14);
        LinearLayout box=card(content);label(box,"CONNECT TO YOUR CAMPUS SERVER",11,GREEN);
        EditText url=field(box,"Server URL",api.base,InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_VARIATION_URI);
        EditText id=field(box,"Student / organiser ID","",InputType.TYPE_CLASS_TEXT);
        EditText password=field(box,"Password","",InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_VARIATION_PASSWORD);
        button(box,"Sign in",()->{
            String base=url.getText().toString().trim().replaceAll("/+$","");
            if(!(base.startsWith("https://")||base.startsWith("http://"))){status("Enter a full http:// or https:// server URL.");return;}
            api.base=base;getPreferences(0).edit().putString("server",base).apply();
            task(()->api.call("POST","/login",json("id",id.getText().toString().trim(),"password",password.getText().toString(),"pub",key.publicKey())),value->{
                JSONObject j=(JSONObject)value;api.token=j.getString("token");role=j.getString("role");name=j.getString("name");ownId=j.getString("id");mismatch=j.getBoolean("device_mismatch");generation++;
                home();startBle();handler.removeCallbacks(tick);handler.post(tick);
            });
        });
        label(content,"Accounts are created by your organiser. Your first student sign-in binds this app installation to your account.",13,MUTED);
        label(content,"Demo APK: use a trusted local network. For a hosted backend, use HTTPS.",12,MUTED);
    }
    private void shell(String page){scaffold(name+" • "+role);screen=page;nav=new LinearLayout(this);root.addView(nav);
        for(String item:new String[]{"Home","History","Account"}){Button b=new Button(this);b.setText(item);b.setAllCaps(false);b.setTextSize(12);nav.addView(b,new LinearLayout.LayoutParams(0,dp(48),1));b.setOnClickListener(v->{if(item.equals("Home"))home();else if(item.equals("History"))history();else account();});}}
    private void home(){
        shell("home");gap(content,14);title(content,role.equals("student")?"Your presence matters.":"Ready for roll call?");
        if(mismatch){LinearLayout c=card(content);label(c,"Device approval needed",20,WHITE);label(c,"This account is linked to another app installation. Request a change, then ask the admin to verify your identity.",14,MUTED);button(c,"Request device change",()->task(()->api.call("POST","/device/request",json("pub",key.publicKey())),r->status("Request sent. Sign in again after approval.")));return;}
        if(role.equals("student")){
            LinearLayout c=card(content);label(c,"AUTOMATIC CHECK-IN",12,GREEN);label(c,"◉",54,GREEN);label(c,"Listening nearby",23,WHITE);label(c,"Keep this screen open near your organiser. Once five signal samples pass the checks, you are marked automatically.",15,MUTED);button(c,"Retry Bluetooth",this::startBle);
            LinearLayout info=card(content);label(info,"No pairing. No attendance code.",17,WHITE);label(info,"Bluetooth, location permission, and network access are needed. Scanning pauses when you leave the app.",14,MUTED);
        }else{
            if(currentSession>0){showSession();return;}
            LinearLayout c=card(content);label(c,"NEW ATTENDANCE SESSION",12,GREEN);
            EditText title=field(c,"Session title","App Development • Round 1",InputType.TYPE_CLASS_TEXT);
            EditText duration=field(c,"Duration in minutes","5",InputType.TYPE_CLASS_NUMBER);
            EditText threshold=field(c,"RSSI threshold (dBm)","-70",InputType.TYPE_CLASS_NUMBER|InputType.TYPE_NUMBER_FLAG_SIGNED);
            button(c,"Start session & broadcast",()->{
                try{int seconds=Integer.parseInt(duration.getText().toString())*60,dbm=Integer.parseInt(threshold.getText().toString());
                    task(()->api.call("POST","/sessions",json("title",title.getText().toString(),"seconds",seconds,"threshold",dbm)),v->{JSONObject j=(JSONObject)v;currentSession=j.getInt("id");ends=j.getLong("ends")*1000;showSession();startBle();});
                }catch(Exception e){status("Enter a duration and negative RSSI threshold.");}
            });
            label(content,"All provisioned students form this demo's class roster. Use History to reopen an active session after signing in again.",13,MUTED);
            if(role.equals("admin"))button(content,"Review device change requests",this::deviceRequests);
        }
    }
    private void showSession(){shell("session");title(content,"Live attendance");label(content,"Loading session #"+currentSession+"…",15,MUTED);refresh();}
    private void renderSession(JSONObject result) throws Exception {
        content.removeAllViews();JSONObject session=result.getJSONObject("session");boolean active=result.getBoolean("active");
        title(content,session.getString("title"));label(content,active?"LIVE • "+Math.max(0,(session.getLong("ends")*1000-System.currentTimeMillis())/1000)+" seconds left":"SESSION COMPLETE",12,GREEN);
        JSONArray roster=result.getJSONArray("roster");LinearLayout count=card(content);label(count,result.getInt("present")+" / "+roster.length(),40,GREEN);label(count,"students present • refreshes every 4 seconds",13,MUTED);
        if(active)button(count,"Close attendance session",()->new AlertDialog.Builder(this).setTitle("Close this session?").setMessage("Automatic check-ins will stop.").setNegativeButton("Cancel",null).setPositiveButton("Close",(d,w)->task(()->api.call("POST","/sessions/"+currentSession+"/close",json()),r->{ble.stopAdvertising();refresh();})).show());
        button(count,"Export CSV",this::exportCsv);
        if(!active)button(count,"Create another session",()->{currentSession=0;home();});
        for(int i=0;i<roster.length();i++){
            JSONObject person=roster.getJSONObject(i);LinearLayout row=card(content);boolean present=person.getInt("present")==1;
            label(row,(present?"✓  ":"○  ")+person.getString("name"),17,present?GREEN:WHITE);
            label(row,person.getString("id")+" • "+(present?"Present":"Absent")+(person.isNull("source")?"":" • "+person.getString("source")),12,MUTED);
            button(row,present?"Remove attendance":"Mark present",()->manual(person));
        }
        if(!active)ble.stopAdvertising();
    }
    private void manual(JSONObject person){
        EditText reason=new EditText(this);reason.setHint("Reason for correction");
        new AlertDialog.Builder(this).setTitle(person.optString("name")).setView(reason).setNegativeButton("Cancel",null).setPositiveButton("Save correction",(dialog,which)->{
            int sid=currentSession;task(()->api.call("POST","/sessions/"+sid+"/manual",json("id",person.optString("id"),"present",person.optInt("present")==0,"reason",reason.getText().toString())),r->refresh());
        }).show();
    }
    private void history(){shell("history");title(content,"Attendance history");loadHistory();}
    private void loadHistory(){
        if(mismatch){status("Request device approval first.");return;}
        task(()->api.call("GET","/sessions",null),value->{if(!screen.equals("history"))return;JSONObject j=(JSONObject)value;content.removeAllViews();title(content,"Attendance history");
            if(role.equals("student")){LinearLayout stats=card(content);label(stats,j.isNull("percentage")?"—":j.getDouble("percentage")+"%",42,GREEN);label(stats,"Attendance across "+j.getInt("completed")+" completed sessions",14,MUTED);}
            JSONArray items=j.getJSONArray("sessions");if(items.length()==0)label(content,"No sessions yet. Your first session will appear here.",16,MUTED);
            for(int i=0;i<items.length();i++){JSONObject s=items.getJSONObject(i);LinearLayout c=card(content);label(c,s.getString("title"),18,WHITE);
                label(c,new java.text.SimpleDateFormat("dd MMM, h:mm a",Locale.getDefault()).format(new Date(s.getLong("started")*1000)),12,MUTED);
                label(c,s.getBoolean("active")?"Live now":role.equals("student")?(s.optInt("present")==1?"Present":"Absent"):"Completed",14,GREEN);
                if(!role.equals("student"))button(c,"Open dashboard",()->{ble.stopAdvertising();currentSession=s.optInt("id");ends=s.optLong("ends")*1000;showSession();});
            }
        });
    }
    private void account(){shell("account");title(content,"Your account");LinearLayout c=card(content);label(c,name,23,WHITE);label(c,"Role: "+role,15,MUTED);label(c,"Server: "+api.base,13,MUTED);
        label(c,"Device key: Android Keystore P-256",14,GREEN);
        label(content,"Physical presence is estimated using BLE signal strength. It is not a room boundary or relay-proof identity check.",14,MUTED);
        if(role.equals("admin"))button(content,"Review device changes",this::deviceRequests);
        button(content,"Sign out",()->task(()->api.call("POST","/logout",json()),r->{generation++;ble.stop();handler.removeCallbacks(tick);role="";api.token="";currentSession=0;marked.clear();tried.clear();login();}));
    }
    private void deviceRequests(){shell("devices");title(content,"Device approvals");task(()->api.call("GET","/device/requests",null),value->{
        if(!screen.equals("devices"))return;
        JSONArray requests=((JSONObject)value).getJSONArray("requests");if(requests.length()==0)label(content,"No pending device changes.",16,MUTED);
        for(int i=0;i<requests.length();i++){JSONObject r=requests.getJSONObject(i);LinearLayout c=card(content);label(c,r.getString("name")+" • "+r.getString("uid"),18,WHITE);
            label(c,"Verify this student's identity in person before approval.",13,MUTED);
            for(boolean approve:new boolean[]{true,false})button(c,approve?"Approve new device":"Reject request",()->new AlertDialog.Builder(this).setTitle(approve?"Approve this device?":"Reject this request?").setMessage(r.optString("uid")).setNegativeButton("Cancel",null).setPositiveButton("Confirm",(d,w)->task(()->api.call("POST","/device/approve",json("id",r.optString("uid"),"approve",approve)),v->deviceRequests())).show());
        }
    });}
    private void exportCsv(){int sid=currentSession;task(()->api.request("GET","/sessions/"+sid+"/csv",null),value->{csv=(String)value;Intent intent=new Intent(Intent.ACTION_CREATE_DOCUMENT);intent.addCategory(Intent.CATEGORY_OPENABLE);intent.setType("text/csv");intent.putExtra(Intent.EXTRA_TITLE,"proxyproof-session-"+sid+".csv");startActivityForResult(intent,50);});}
    @Override protected void onActivityResult(int request,int result,Intent data){super.onActivityResult(request,result,data);if(request==50 && result==RESULT_OK && data!=null && data.getData()!=null){try(java.io.OutputStream out=getContentResolver().openOutputStream(data.getData())){out.write(csv.getBytes(java.nio.charset.StandardCharsets.UTF_8));status("CSV saved.");}catch(Exception e){status("Could not save CSV: "+e.getMessage());}}}
    private void startBle(){
        if(ble==null || mismatch || role.isEmpty() || !foreground)return;
        String[] permissions=Build.VERSION.SDK_INT>=31?new String[]{Manifest.permission.BLUETOOTH_SCAN,Manifest.permission.BLUETOOTH_CONNECT,Manifest.permission.BLUETOOTH_ADVERTISE,Manifest.permission.ACCESS_FINE_LOCATION,Manifest.permission.ACCESS_COARSE_LOCATION}:new String[]{Manifest.permission.ACCESS_FINE_LOCATION,Manifest.permission.ACCESS_COARSE_LOCATION};
        for(String p:permissions)if(checkSelfPermission(p)!=PackageManager.PERMISSION_GRANTED){requestPermissions(permissions,7);return;}
        try{if(role.equals("student"))ble.scan();else refresh();}catch(Exception e){status("Bluetooth: "+e.getMessage());}
    }
    @Override public void onRequestPermissionsResult(int request,String[] permissions,int[] results){super.onRequestPermissionsResult(request,permissions,results);if(request==7){boolean all=results.length>0;for(int value:results)all&=value==PackageManager.PERMISSION_GRANTED;if(all)startBle();else status("Permissions denied. Enable Nearby devices and Location in app settings, then retry.");}}
    private boolean canBluetooth(){return Build.VERSION.SDK_INT<31 || (checkSelfPermission(Manifest.permission.BLUETOOTH_ADVERTISE)==PackageManager.PERMISSION_GRANTED && checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT)==PackageManager.PERMISSION_GRANTED);}
    private void refresh(){
        if(!foreground||role.isEmpty()||mismatch||loading)return;
        if(role.equals("student"))return;
        if(currentSession<=0)return;
        loading=true;int sid=currentSession,g= generation;ApiClient client=api;
        worker.execute(()->{
            JSONObject dashboard=null,beacon=null;String error=null;
            try{dashboard=client.call("GET","/sessions/"+sid,null);
                if(dashboard.getBoolean("active") && dashboard.getJSONObject("session").getString("owner").equals(ownId) && canBluetooth())beacon=client.call("POST","/sessions/"+sid+"/beacon",json());
            }catch(Exception e){error=e.getMessage();}
            JSONObject finalDashboard=dashboard,finalBeacon=beacon;String finalError=error;
            handler.post(()->{loading=false;if(g!=generation || sid!=currentSession || !foreground)return;
                try{if(finalDashboard!=null && screen.equals("session"))renderSession(finalDashboard);
                    if(finalBeacon!=null){ble.advertise(sid,finalBeacon.getString("beacon"));long delay=Math.max(0,finalBeacon.getLong("ends")*1000-System.currentTimeMillis());handler.postDelayed(()->{if(currentSession==sid)ble.stopAdvertising();},Math.min(delay,3600000));}
                    if(finalError!=null){ble.stopAdvertising();status(finalError);}
                }catch(Exception e){status(e.getMessage());}
            });
        });
    }
    @Override public void status(String message){handler.post(()->{if(statusView!=null)statusView.setText(message);});}
    @Override public void beacon(int sid,String token,List<Integer> samples){
        if(busy || mismatch || !foreground || marked.contains(sid) || System.currentTimeMillis()-tried.getOrDefault(sid,0L)<4000)return;
        busy=true;tried.put(sid,System.currentTimeMillis());int g=generation;ApiClient client=api;
        worker.execute(()->{String message;boolean success=false;try{JSONObject r=client.call("POST","/attendance",json("sid",sid,"beacon",token,"samples",new JSONArray(samples)));message=r.getString("message")+" • session #"+sid;success=true;}catch(Exception e){message=e.getMessage();}
            String result=message;boolean ok=success;handler.post(()->{busy=false;if(g!=generation)return;if(ok){marked.add(sid);if(screen.equals("home")){LinearLayout c=card(content);label(c,"✓  You’re marked present",21,GREEN);label(c,"Session #"+sid+" • Confirmed by server",14,MUTED);} }status(result);});});
    }
    private void task(Work work,Done done){
        if(busy){status("Please wait for the current request…");return;}busy=true;status("Connecting…");int g=generation;
        worker.execute(()->{try{Object result=work.run();handler.post(()->{busy=false;if(g!=generation)return;try{status("");done.run(result);}catch(Exception e){status(e.getMessage());}});}catch(Exception e){handler.post(()->{busy=false;if(g==generation)status(e.getMessage()==null?"Connection failed. Check server URL and network.":e.getMessage());});}});
    }
    private static JSONObject json(Object... pairs){JSONObject j=new JSONObject();try{for(int i=0;i<pairs.length;i+=2)j.put((String)pairs[i],pairs[i+1]);}catch(JSONException e){throw new IllegalArgumentException(e);}return j;}
}
