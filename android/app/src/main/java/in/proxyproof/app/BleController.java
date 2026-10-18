package in.proxyproof.app;

import android.bluetooth.*;
import android.bluetooth.le.*;
import android.content.Context;
import android.os.Handler;
import java.nio.ByteBuffer;
import java.util.*;

/** Foreground-only BLE. Demo manufacturer ID 0xFFFF, version byte + session + token. */
@android.annotation.SuppressLint("MissingPermission")
final class BleController {
    interface Listener { void status(String text); void progress(int count); void beacon(int sid,String token,List<Integer> samples); }
    private static final int COMPANY=0xFFFF;
    private final BluetoothAdapter adapter;
    private final Listener listener;
    private final Handler main=new Handler(android.os.Looper.getMainLooper());
    private BluetoothLeScanner scanner;
    private BluetoothLeAdvertiser advertiser;
    private ScanCallback scanCallback;
    private AdvertiseCallback advertiseCallback;
    private final Map<String,ArrayList<Integer>> samples=new HashMap<>();
    private final Map<String,Long> last=new HashMap<>();
    private String broadcasting="";
    BleController(Context context,Listener listener) {
        BluetoothManager manager=(BluetoothManager)context.getSystemService(Context.BLUETOOTH_SERVICE);
        adapter=manager==null?null:manager.getAdapter(); this.listener=listener;
    }
    boolean ready() { return adapter!=null && adapter.isEnabled(); }
    void scan() {
        if(scanCallback!=null) return;
        if(!ready()){listener.status("Turn on Bluetooth, then restart the scan.");return;}
        scanner=adapter.getBluetoothLeScanner();
        if(scanner==null){listener.status("BLE scanner is unavailable.");return;}
        listener.progress(0);
        scanCallback=new ScanCallback(){
            @Override public void onScanResult(int type,ScanResult result){
                if(result.getScanRecord()==null)return;
                byte[] bytes=result.getScanRecord().getManufacturerSpecificData(COMPANY);
                if(bytes==null || bytes.length!=13 || bytes[0]!=1)return;
                ByteBuffer buffer=ByteBuffer.wrap(bytes); buffer.get(); int sid=buffer.getInt(); byte[] token=new byte[8];buffer.get(token);
                String hex=DeviceKey.hex(token), id=sid+":"+hex;
                long now=System.currentTimeMillis();
                if(last.size()>50){last.clear();samples.clear();}
                if(now-last.getOrDefault(id,0L)>12000)samples.remove(id);
                ArrayList<Integer> readings=samples.computeIfAbsent(id,k->new ArrayList<>());
                if(now-last.getOrDefault(id,0L)<180)return;
                last.put(id,now); readings.add(result.getRssi());
                listener.progress(Math.min(5,readings.size()));
                if(readings.size()>=5){listener.beacon(sid,hex,new ArrayList<>(readings));readings.clear();}
            }
            @Override public void onScanFailed(int code){ scanCallback=null;listener.progress(0);listener.status("BLE scan failed ("+code+"). Wait a few seconds and retry."); }
        };
        ScanFilter filter=new ScanFilter.Builder().setManufacturerData(COMPANY,new byte[]{1},new byte[]{(byte)255}).build();
        scanner.startScan(Collections.singletonList(filter),new ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build(),scanCallback);
        listener.status("Listening for nearby sessions… Attendance is automatic.");
    }
    void advertise(int sid,String token) {
        if(token.equals(broadcasting))return;
        stopAdvertising();
        if(!ready()){listener.status("Bluetooth is off. Turn it on to broadcast.");return;}
        if(!adapter.isMultipleAdvertisementSupported()){listener.status("This phone cannot advertise BLE. Use a different organiser phone.");return;}
        advertiser=adapter.getBluetoothLeAdvertiser();
        if(advertiser==null){listener.status("BLE advertiser unavailable.");return;}
        ByteBuffer payload=ByteBuffer.allocate(13);payload.put((byte)1).putInt(sid);
        for(int i=0;i<16;i+=2)payload.put((byte)Integer.parseInt(token.substring(i,i+2),16));
        advertiseCallback=new AdvertiseCallback(){
            @Override public void onStartSuccess(AdvertiseSettings settings){ listener.status("Live • Bluetooth beacon is broadcasting"); }
            @Override public void onStartFailure(int error){ broadcasting="";listener.status("Broadcast failed ("+error+"). Check Bluetooth or use another phone."); }
        };
        broadcasting=token;
        advertiser.startAdvertising(new AdvertiseSettings.Builder().setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_MEDIUM).setConnectable(false).build(),
            new AdvertiseData.Builder().addManufacturerData(COMPANY,payload.array()).build(),advertiseCallback);
    }
    void stopAdvertising(){ if(advertiser!=null && advertiseCallback!=null)try{advertiser.stopAdvertising(advertiseCallback);}catch(SecurityException ignored){} advertiseCallback=null;broadcasting=""; }
    void stop(){ if(scanner!=null && scanCallback!=null)try{scanner.stopScan(scanCallback);}catch(SecurityException ignored){} scanCallback=null;stopAdvertising();samples.clear();last.clear();listener.progress(0); }
}
