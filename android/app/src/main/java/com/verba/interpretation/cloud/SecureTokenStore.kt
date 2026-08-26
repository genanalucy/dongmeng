package com.verba.interpretation.cloud

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.nio.charset.StandardCharsets
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Tokens are encrypted before persistence; callers must never log their values. */
data class AuthTokens(val accessToken: String, val refreshToken: String)

interface TokenStore {
    fun read(): AuthTokens?
    fun write(tokens: AuthTokens)
    fun clear()
}

class KeystoreTokenStore(context: Context) : TokenStore {
    private val preferences = context.applicationContext.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)

    override fun read(): AuthTokens? = runCatching {
        val encoded = preferences.getString(TOKENS, null) ?: return null
        val plain = decrypt(encoded)
        val separator = plain.indexOf('\n')
        if (separator <= 0 || separator == plain.lastIndex) null
        else AuthTokens(plain.substring(0, separator), plain.substring(separator + 1))
    }.getOrNull()

    override fun write(tokens: AuthTokens) {
        require(tokens.accessToken.isNotBlank() && tokens.refreshToken.isNotBlank())
        preferences.edit().putString(TOKENS, encrypt("${tokens.accessToken}\n${tokens.refreshToken}")).commit()
    }

    override fun clear() {
        preferences.edit().remove(TOKENS).commit()
    }

    private fun key(): SecretKey {
        val store = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        (store.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE).apply {
            init(
                KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .build(),
            )
        }.generateKey()
    }

    private fun encrypt(value: String): String {
        val cipher = Cipher.getInstance(TRANSFORMATION).apply { init(Cipher.ENCRYPT_MODE, key()) }
        val encrypted = cipher.doFinal(value.toByteArray(StandardCharsets.UTF_8))
        return "${Base64.encodeToString(cipher.iv, Base64.NO_WRAP)}:${Base64.encodeToString(encrypted, Base64.NO_WRAP)}"
    }

    private fun decrypt(value: String): String {
        val parts = value.split(':', limit = 2)
        require(parts.size == 2)
        val iv = Base64.decode(parts[0], Base64.NO_WRAP)
        val encrypted = Base64.decode(parts[1], Base64.NO_WRAP)
        val cipher = Cipher.getInstance(TRANSFORMATION).apply {
            init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, iv))
        }
        return String(cipher.doFinal(encrypted), StandardCharsets.UTF_8)
    }

    private companion object {
        const val PREFERENCES = "cloud_credentials"
        const val TOKENS = "encrypted_tokens"
        const val KEYSTORE = "AndroidKeyStore"
        const val KEY_ALIAS = "verba_cloud_tokens_v1"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
    }
}
