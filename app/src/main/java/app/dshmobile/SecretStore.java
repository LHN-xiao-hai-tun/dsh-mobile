package app.dshmobile;

import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;

import java.nio.charset.StandardCharsets;
import java.security.KeyStore;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/**
 * 敏感偏好项的**本机加密**（AES-256-GCM · 密钥在 Android Keystore 里，永不出设备）。
 *
 * ── 为什么不用 androidx.security:security-crypto（EncryptedSharedPreferences）
 *    ① 它**已被 Google 标记废弃**，最新版长期停在 alpha；
 *    ② 它拖 **Tink** 进来 —— 本 App 全部价值就是「约 3 MB 的极简壳」，
 *       为一个十几个字段的偏好加密把包体推大几百 KB，与本项目定位冲突（见 CONTRIBUTING 的隐私与体积红线）。
 *    ③ 这里要做的事很小：AES/GCM + Keystore 取密钥，标准 API 就够，几十行可控。
 *
 * ── 设计要点
 *    · 落盘格式：`enc1:` + Base64(IV(12B) || 密文||GCM Tag)。前缀即版本号，将来换算法可平滑再迁。
 *    · **零成本迁移**：{@link #decrypt} 遇到**没有前缀**的值（= 旧版明文）**原样返回**，
 *      所以老用户升级后地址不会丢；下一次写入时自然变成密文（惰性迁移，不需要迁移脚本/迁移页）。
 *    · **永不上抛**：加解密失败一律返回 null，由调用方当「空值」处理 —— 加密坏了也不该让 App 崩。
 *      （失败的真实场景：设备数据被云备份还原到另一台机器，而 Keystore 密钥不跟着走。）
 *
 * ⚠️ 与清单里的 android:allowBackup="false" 配套：不备份就没有"还原后解不开"的问题。
 */
final class SecretStore {

    private static final String KEYSTORE = "AndroidKeyStore";
    private static final String ALIAS = "dsh_prefs_aes_v1";
    private static final String PREFIX = "enc1:";
    private static final String TRANSFORM = "AES/GCM/NoPadding";
    private static final int IV_LEN = 12;      // GCM 推荐 12 字节 IV
    private static final int TAG_BITS = 128;
    private static final int KEY_BITS = 256;

    private SecretStore() {
    }

    static boolean isEncrypted(String v) {
        return v != null && v.startsWith(PREFIX);
    }

    /** @return 形如 `enc1:xxxx`；失败返回 null（调用方自行决定降级） */
    static String encrypt(String plain) {
        if (plain == null) {
            return null;
        }
        try {
            Cipher c = Cipher.getInstance(TRANSFORM);
            // 每次写入都由 Keystore 生成新 IV（清单里开了 randomizedEncryptionRequired）
            c.init(Cipher.ENCRYPT_MODE, key());
            byte[] iv = c.getIV();
            byte[] ct = c.doFinal(plain.getBytes(StandardCharsets.UTF_8));
            byte[] joined = new byte[iv.length + ct.length];
            System.arraycopy(iv, 0, joined, 0, iv.length);
            System.arraycopy(ct, 0, joined, iv.length, ct.length);
            return PREFIX + Base64.encodeToString(joined, Base64.NO_WRAP);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 解密。**非加密值（旧版明文）原样返回** —— 这就是惰性迁移的支点。
     *
     * @return 明文；密文损坏 / 密钥丢失时返回 null（**不要**把 null 当空串写回去，那会覆盖掉数据）
     */
    static String decrypt(String stored) {
        if (stored == null) {
            return "";
        }
        if (!isEncrypted(stored)) {
            return stored;   // 旧版明文 → 直接可用
        }
        try {
            byte[] all = Base64.decode(stored.substring(PREFIX.length()), Base64.NO_WRAP);
            if (all.length <= IV_LEN) {
                return null;
            }
            Cipher c = Cipher.getInstance(TRANSFORM);
            c.init(Cipher.DECRYPT_MODE, key(), new GCMParameterSpec(TAG_BITS, all, 0, IV_LEN));
            byte[] pt = c.doFinal(all, IV_LEN, all.length - IV_LEN);
            return new String(pt, StandardCharsets.UTF_8);
        } catch (Exception e) {
            return null;
        }
    }

    /** 取（或首次生成）本 App 的 AES 密钥；密钥存于 Android Keystore，应用卸载即销毁 */
    private static SecretKey key() throws Exception {
        KeyStore ks = KeyStore.getInstance(KEYSTORE);
        ks.load(null);
        KeyStore.Entry entry = ks.getEntry(ALIAS, null);
        if (entry instanceof KeyStore.SecretKeyEntry) {
            return ((KeyStore.SecretKeyEntry) entry).getSecretKey();
        }
        KeyGenerator kg = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE);
        kg.init(new KeyGenParameterSpec.Builder(ALIAS,
                KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(KEY_BITS)
                // 不要求用户认证：本 App 要在息屏/后台重连时也能读地址，加锁会把它挡死
                .setUserAuthenticationRequired(false)
                .setRandomizedEncryptionRequired(true)
                .build());
        return kg.generateKey();
    }
}
