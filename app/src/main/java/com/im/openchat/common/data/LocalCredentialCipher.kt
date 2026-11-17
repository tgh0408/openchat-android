package com.im.openchat.common.data

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.GeneralSecurityException
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** 使用不可导出的安装级 Keystore 密钥保护认证信息；调用方在 IO 线程执行。 */
internal object LocalCredentialCipher {
    private const val ALIAS = "openchat.local.credentials.v1"
    private const val PREFIX = "gcm1:"
    private const val IV_SIZE = 12
    private const val TAG_BITS = 128
    private const val MAX_ENCODED_SIZE = 131072

    /** 加密 UTF-8 内容，随机生成 IV，并将用途和服务器作为认证附加数据。 */
    fun encrypt(value: String, purpose: String, server: String): String {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key(create = true))
        cipher.updateAAD(associatedData(purpose, server))
        val plaintext = value.toByteArray(Charsets.UTF_8)
        try {
            check(cipher.iv.size == IV_SIZE) { "认证加密初始化失败" }
            val result = PREFIX + Base64.encodeToString(cipher.iv + cipher.doFinal(plaintext), Base64.NO_WRAP)
            require(result.length <= MAX_ENCODED_SIZE) { "认证信息过长" }
            return result
        } finally {
            plaintext.fill(0)
        }
    }

    /** 仅解密当前密文格式，拒绝篡改、跨服务器复制及明文，不创建替代解密密钥。 */
    fun decrypt(value: String, purpose: String, server: String): String {
        require(value.startsWith(PREFIX) && value.length <= MAX_ENCODED_SIZE) { "认证密文无效" }
        val payload = Base64.decode(value.substring(PREFIX.length), Base64.NO_WRAP)
        require(payload.size >= IV_SIZE + TAG_BITS / 8) { "认证密文无效" }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(create = false),
            GCMParameterSpec(TAG_BITS, payload.copyOfRange(0, IV_SIZE)))
        cipher.updateAAD(associatedData(purpose, server))
        val plaintext = cipher.doFinal(payload, IV_SIZE, payload.size - IV_SIZE)
        try {
            return String(plaintext, Charsets.UTF_8)
        } finally {
            plaintext.fill(0)
        }
    }

    /** 分隔用途与规范化服务器，阻止账号提示密文被当作认证会话使用。 */
    private fun associatedData(purpose: String, server: String): ByteArray =
        "openchat.credentials.v1\n$purpose\n${storageServerIdentity(server)}".toByteArray(Charsets.UTF_8)

    /** 串行取得或首次创建 AES-256 密钥；不导出密钥，不在读取失败时重置已有条目。 */
    @Synchronized
    private fun key(create: Boolean): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        val existing = store.getKey(ALIAS, null)
        if (existing is SecretKey) return existing
        if (existing != null || !create) throw GeneralSecurityException("本地认证密钥不可用")
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setKeySize(256)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true)
                .build())
        }.generateKey()
    }
}
