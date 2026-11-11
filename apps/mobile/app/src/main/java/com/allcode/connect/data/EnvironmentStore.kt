package com.allcode.connect.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import com.allcode.connect.core.WireJson
import kotlinx.serialization.builtins.ListSerializer
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Paired machines, sealed with an Android Keystore key so bearer tokens never sit in plain text. */
class EnvironmentStore(context: Context) {
    private val file = File(context.noBackupFilesDir, "environments.sealed")

    @Synchronized
    fun load(): List<SavedEnvironment> {
        if (!file.exists()) return emptyList()
        return runCatching {
            val bytes = file.readBytes()
            val iv = bytes.copyOfRange(0, IV_BYTES)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, iv))
            val plain = cipher.doFinal(bytes, IV_BYTES, bytes.size - IV_BYTES).decodeToString()
            WireJson.decodeFromString(ListSerializer(SavedEnvironment.serializer()), plain)
        }.getOrElse { emptyList() }
    }

    @Synchronized
    fun save(environments: List<SavedEnvironment>) {
        val plain = WireJson.encodeToString(ListSerializer(SavedEnvironment.serializer()), environments)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val sealed = cipher.iv + cipher.doFinal(plain.encodeToByteArray())
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeBytes(sealed)
        tmp.renameTo(file)
    }

    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getEntry(ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return generator.generateKey()
    }

    private companion object {
        const val ALIAS = "allcode.environments"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val IV_BYTES = 12
    }
}
