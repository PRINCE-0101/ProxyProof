package in.proxyproof.app;

import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.security.spec.ECGenParameterSpec;

/** The private key stays inside Android Keystore, never in preferences or the API. */
final class DeviceKey {
    private static final String ALIAS = "proxyproof.device.v1";
    private final KeyStore store;
    DeviceKey() throws Exception {
        store = KeyStore.getInstance("AndroidKeyStore"); store.load(null);
        if (!store.containsAlias(ALIAS)) {
            KeyPairGenerator generator = KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC,"AndroidKeyStore");
            generator.initialize(new KeyGenParameterSpec.Builder(ALIAS,KeyProperties.PURPOSE_SIGN | KeyProperties.PURPOSE_VERIFY)
                .setAlgorithmParameterSpec(new ECGenParameterSpec("secp256r1"))
                .setDigests(KeyProperties.DIGEST_SHA256).build());
            generator.generateKeyPair();
        }
    }
    String publicKey() throws Exception { return Base64.encodeToString(store.getCertificate(ALIAS).getPublicKey().getEncoded(),Base64.NO_WRAP); }
    String sign(String text) throws Exception {
        Signature signature = Signature.getInstance("SHA256withECDSA");
        signature.initSign((PrivateKey)store.getKey(ALIAS,null));
        signature.update(text.getBytes(StandardCharsets.UTF_8));
        return Base64.encodeToString(signature.sign(),Base64.NO_WRAP);
    }
    static String hex(byte[] bytes) { StringBuilder out=new StringBuilder(); for(byte b:bytes) out.append(String.format("%02x",b & 255)); return out.toString(); }
}
