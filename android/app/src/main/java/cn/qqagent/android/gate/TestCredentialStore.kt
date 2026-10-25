package cn.qqagent.android.gate

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import org.json.JSONObject
import java.io.File
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.spec.MGF1ParameterSpec
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.OAEPParameterSpec
import javax.crypto.spec.PSource

/** Test credentials are transported as RSA ciphertext and retained with Android Keystore AES-GCM. */
class TestCredentialStore(private val context: Context) {
    private val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
    private val transportAlias = "qqagent-test-transport-v1"
    private val storageAlias = "qqagent-test-storage-v1"
    private val encryptedFile = File(context.filesDir, "credentials/model-test.enc")

    @Synchronized fun exportPublicKey() {
        if (!store.containsAlias(transportAlias)) {
            KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_RSA, "AndroidKeyStore").apply {
                initialize(KeyGenParameterSpec.Builder(transportAlias, KeyProperties.PURPOSE_DECRYPT)
                    .setKeySize(3072).setDigests(KeyProperties.DIGEST_SHA256, KeyProperties.DIGEST_SHA1)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_RSA_OAEP).build())
            }.generateKeyPair()
        }
        File(context.filesDir, "model-provision-public.der").writeBytes(store.getCertificate(transportAlias).publicKey.encoded)
    }

    fun read(): JSONObject {
        val envelope = File(context.filesDir, "model-provision.bin")
        if (envelope.isFile) {
            require(envelope.length() == 384L) { "Invalid credential envelope" }
            val cipher = Cipher.getInstance("RSA/ECB/OAEPWithSHA-256AndMGF1Padding")
            cipher.init(Cipher.DECRYPT_MODE, store.getKey(transportAlias, null),
                OAEPParameterSpec("SHA-256", "MGF1", MGF1ParameterSpec.SHA1, PSource.PSpecified.DEFAULT))
            val plain = cipher.doFinal(envelope.readBytes())
            try {
                validate(JSONObject(plain.toString(Charsets.UTF_8)))
                val encryption = Cipher.getInstance("AES/GCM/NoPadding")
                encryption.init(Cipher.ENCRYPT_MODE, aesKey())
                val bytes = byteArrayOf(1) + encryption.iv + encryption.doFinal(plain)
                check(encryptedFile.parentFile!!.mkdirs() || encryptedFile.parentFile!!.isDirectory)
                val staging = File(encryptedFile.parentFile, "model-test.enc.partial")
                staging.writeBytes(bytes)
                check(staging.renameTo(encryptedFile))
                check(envelope.delete())
            } finally { plain.fill(0) }
        }
        require(encryptedFile.isFile) { "Import test API credentials first" }
        val bytes = encryptedFile.readBytes()
        require(bytes.size > 29 && bytes[0] == 1.toByte())
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, aesKey(), GCMParameterSpec(128, bytes.copyOfRange(1, 13)))
        val plain = cipher.doFinal(bytes.copyOfRange(13, bytes.size))
        return try { JSONObject(plain.toString(Charsets.UTF_8)).also { validate(it) } } finally { plain.fill(0) }
    }

    private fun validate(value: JSONObject) {
        require(value.getString("baseUrl") == "https://discovery-api.intern-ai.org.cn/v1") { "Unexpected test endpoint" }
        require(value.getString("apiKey").length in 16..256) { "Invalid API credential" }
    }

    private fun aesKey(): SecretKey {
        if (!store.containsAlias(storageAlias)) {
            KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
                init(KeyGenParameterSpec.Builder(storageAlias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                    .setKeySize(256).setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
            }.generateKey()
        }
        return store.getKey(storageAlias, null) as SecretKey
    }
}
