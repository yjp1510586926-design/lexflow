package com.lexflow.service.ai;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** AES-256-GCM 凭据封装；租户与用途作为认证数据，密文不能跨租户或跨模型用途复用。 */
@Component
public final class AiCredentialCipher {

    private final SecretKeySpec key;
    private final SecureRandom random = new SecureRandom();

    public AiCredentialCipher(@Value("${LEXFLOW_AI_MASTER_KEY:}") String encodedKey) {
        if (encodedKey.isBlank()) {
            key = null;
            return;
        }
        try {
            byte[] bytes = Base64.getDecoder().decode(encodedKey);
            if (bytes.length != 32) {
                throw new IllegalArgumentException();
            }
            key = new SecretKeySpec(bytes, "AES");
        } catch (IllegalArgumentException ex) {
            throw new IllegalArgumentException("AI 主密钥必须为 Base64 编码的 32 字节密钥");
        }
    }

    public boolean configured() {
        return key != null;
    }

    public String encrypt(long tenantId, String purpose, String secret) {
        requireConfigured();
        byte[] aad = context(tenantId, purpose);
        if (secret == null || secret.isBlank() || secret.length() > 8192) {
            throw new IllegalArgumentException("API Key 为空或过长");
        }
        try {
            // 每次加密使用随机 96 位 nonce；v1 格式依次保存 nonce 与含认证标签的密文。
            byte[] nonce = new byte[12];
            random.nextBytes(nonce);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(128, nonce));
            cipher.updateAAD(aad);
            byte[] encrypted = cipher.doFinal(secret.getBytes(StandardCharsets.UTF_8));
            return (
                "v1:" +
                Base64.getEncoder().encodeToString(
                    ByteBuffer.allocate(nonce.length + encrypted.length)
                        .put(nonce)
                        .put(encrypted)
                        .array()
                )
            );
        } catch (Exception ex) {
            throw new IllegalStateException("AI 凭据加密失败");
        }
    }

    public String decrypt(long tenantId, String purpose, String encoded) {
        requireConfigured();
        byte[] aad = context(tenantId, purpose);
        try {
            if (encoded == null || !encoded.startsWith("v1:") || encoded.length() > 50000) {
                throw new IllegalArgumentException();
            }
            byte[] bytes = Base64.getDecoder().decode(encoded.substring(3));
            if (bytes.length < 29) {
                throw new IllegalArgumentException();
            }
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(128, bytes, 0, 12));
            cipher.updateAAD(aad);
            return new String(cipher.doFinal(bytes, 12, bytes.length - 12), StandardCharsets.UTF_8);
        } catch (Exception ex) {
            // 不把底层异常或凭据内容写入日志/接口。
            throw new IllegalStateException("AI 凭据不可解密，请检查主密钥或重新配置");
        }
    }

    private void requireConfigured() {
        if (key == null) {
            throw new IllegalStateException("部署环境尚未配置 AI 凭据加密主密钥");
        }
    }

    // 解密必须重建加密时的同一上下文；租户或用途变化会导致 GCM 认证失败。
    private byte[] context(long tenantId, String purpose) {
        if (tenantId <= 0 || purpose == null || !purpose.matches("LLM|EMBEDDING|RERANK")) {
            throw new IllegalArgumentException("无效的 AI 凭据上下文");
        }
        return ("lexflow:ai:v1:" + tenantId + ":" + purpose).getBytes(StandardCharsets.UTF_8);
    }
}
