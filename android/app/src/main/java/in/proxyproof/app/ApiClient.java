package in.proxyproof.app;

import org.json.JSONObject;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.UUID;

final class ApiClient {
    String base, token="";
    private final DeviceKey key;
    ApiClient(String base, DeviceKey key) { this.base=base; this.key=key; }
    JSONObject call(String method,String path,JSONObject body) throws Exception { return new JSONObject(request(method,path,body)); }
    String request(String method,String path,JSONObject body) throws Exception {
        byte[] bytes=body==null ? new byte[0] : body.toString().getBytes(StandardCharsets.UTF_8);
        HttpURLConnection connection=(HttpURLConnection)new URL(base+path).openConnection();
        connection.setInstanceFollowRedirects(false); connection.setConnectTimeout(10000); connection.setReadTimeout(10000);
        connection.setRequestMethod(method); connection.setRequestProperty("Accept","application/json");
        if (!token.isEmpty()) {
            String stamp=Long.toString(System.currentTimeMillis()/1000), nonce=UUID.randomUUID().toString();
            String hash=DeviceKey.hex(MessageDigest.getInstance("SHA-256").digest(bytes));
            connection.setRequestProperty("Authorization","Bearer "+token);
            connection.setRequestProperty("X-Time",stamp); connection.setRequestProperty("X-Nonce",nonce);
            connection.setRequestProperty("X-Signature",key.sign(method+"\n"+path+"\n"+stamp+"\n"+nonce+"\n"+hash));
        }
        try {
            if (body!=null) { connection.setDoOutput(true); connection.setRequestProperty("Content-Type","application/json"); try(OutputStream os=connection.getOutputStream()){ os.write(bytes); } }
            int code=connection.getResponseCode();
            InputStream input=code>=400 ? connection.getErrorStream() : connection.getInputStream();
            ByteArrayOutputStream output=new ByteArrayOutputStream();
            if(input!=null) try(InputStream stream=input){ byte[] buffer=new byte[4096]; int n; while((n=stream.read(buffer))!=-1)output.write(buffer,0,n); }
            String result=output.toString("UTF-8");
            if(code<200 || code>=300) { String error; try { error=new JSONObject(result).optString("error","HTTP "+code); } catch(Exception e){ error="HTTP "+code; } throw new IOException(error); }
            return result;
        } finally { connection.disconnect(); }
    }
}
