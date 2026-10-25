package cn.wordflow.app;

import android.content.*;
import android.security.keystore.*;
import android.util.Base64;
import java.security.KeyStore;
import javax.crypto.*;
import javax.crypto.spec.GCMParameterSpec;
import java.nio.charset.StandardCharsets;

final class AiSettings {
    private static final String ALIAS = "WordFlow.AiKeys.v1";
    private final SharedPreferences prefs;
    AiSettings(Context context) { prefs = context.getSharedPreferences("ai-settings", Context.MODE_PRIVATE); }
    String provider() { return prefs.getString("provider", "OpenRouter"); }
    String model() { return prefs.getString("model", "openrouter/free"); }
    boolean hasKey(String provider) { return prefs.contains(provider + ".key"); }
    private javax.crypto.SecretKey secret() throws Exception {
        KeyStore store = KeyStore.getInstance("AndroidKeyStore"); store.load(null);
        if (!store.containsAlias(ALIAS)) {
            KeyGenerator generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
            generator.init(new KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build());
            generator.generateKey();
        }
        return (javax.crypto.SecretKey)store.getKey(ALIAS, null);
    }
    private String encrypt(String value) throws Exception {
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding"); cipher.init(Cipher.ENCRYPT_MODE, secret());
        return Base64.encodeToString(cipher.getIV(), Base64.NO_WRAP) + ":" + Base64.encodeToString(cipher.doFinal(value.getBytes(StandardCharsets.UTF_8)), Base64.NO_WRAP);
    }
    String key(String provider) throws Exception {
        String value = prefs.getString(provider + ".key", ""); if (value.isEmpty()) return "";
        String[] parts = value.split(":", 2);
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding"); cipher.init(Cipher.DECRYPT_MODE, secret(), new GCMParameterSpec(128, Base64.decode(parts[0], Base64.NO_WRAP)));
        return new String(cipher.doFinal(Base64.decode(parts[1], Base64.NO_WRAP)), StandardCharsets.UTF_8);
    }
    void save(String provider, String model, String deepseek, String openrouter) throws Exception {
        if (!provider.equals("DeepSeek") && !provider.equals("OpenRouter")) throw new IllegalArgumentException("请选择服务商");
        if (model.trim().isEmpty() || model.length() > 200) throw new IllegalArgumentException("请填写有效的 OpenRouter 模型 ID");
        SharedPreferences.Editor edit = prefs.edit().putString("provider", provider).putString("model", model.trim());
        if (!deepseek.trim().isEmpty()) edit.putString("DeepSeek.key", encrypt(deepseek.trim()));
        if (!openrouter.trim().isEmpty()) edit.putString("OpenRouter.key", encrypt(openrouter.trim()));
        if (!edit.commit()) throw new java.io.IOException("设置保存失败");
    }
    void clear(String provider) { prefs.edit().remove(provider + ".key").apply(); }
}
