package com.example.airemote

import android.content.Context
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Android 10+ AES-GCM wrapping for externally provisioned X25519/Ed25519 secrets.
 * Hardware-backed key availability is device-dependent and MUST be checked
 * before production enrollment. No private material is committed to this repo.
 * Not connected to any live sync or provisioning flow in Stage 3. */
class T043AndroidKeyVault(private val context: Context) {
    private val alias = "t043.hpke.wrap.v1"
    private val folder=File(context.noBackupFilesDir,"t043-keyvault/v1").also {
        require(it.isDirectory || it.mkdirs())
    }
    private fun keystore(): KeyStore =
        KeyStore.getInstance("AndroidKeyStore").apply { load(null) }

    private fun wrapKey(): SecretKey {
        require(Build.VERSION.SDK_INT>=29)
        val old=keystore().getKey(alias,null)
        if(old is SecretKey)return old
        val spec=KeyGenParameterSpec.Builder(
            alias,KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
        ).setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setRandomizedEncryptionRequired(true)
            .setKeySize(256).build()
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES,"AndroidKeyStore")
            .apply { init(spec) }.generateKey()
    }

    /** Key identity is never interpreted as a path. */
    private fun path(id:String): AtomicFile {
        require(Regex("[A-Za-z0-9_-]{1,128}").matches(id))
        return AtomicFile(File(folder,id+".sealed"))
    }

    fun isHardwareBacked(): Boolean {
        val key=wrapKey()
        return try {
            val factory=javax.crypto.SecretKeyFactory.getInstance(
                key.algorithm,"AndroidKeyStore")
            val info=factory.getKeySpec(key,
                android.security.keystore.KeyInfo::class.java)
                as android.security.keystore.KeyInfo
            info.isInsideSecureHardware
        } catch (_: Exception) { false }
    }

    /** Explicit, trusted provisioning only. Never accept secrets from FTP. */
    @Synchronized fun storeProvisionedSecret(keyId:String,secret:ByteArray) {
        require(secret.size==32)
        val f=path(keyId)
        require(!f.baseFile.exists()) { "Cannot silently rotate key" }
        val cipher=Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE,wrapKey())
        cipher.updateAAD(keyId.toByteArray(Charsets.UTF_8))
        val temporary=secret.copyOf()
        val ciphertext=try { cipher.doFinal(temporary) } finally { temporary.fill(0) }
        val data=byteArrayOf(1,cipher.iv.size.toByte())+cipher.iv+ciphertext
        val stream=f.startWrite()
        try {
            stream.write(data)
            f.finishWrite(stream)
        } catch (e: Exception) {
            f.failWrite(stream)
            throw e
        }
    }

    @Synchronized fun <T> withSecret(keyId:String,action:(ByteArray)->T):T {
        val file=path(keyId)
        val bytes=file.openRead().use { it.readBytes() }
        require(bytes.size in 46..256 && bytes[0].toInt()==1)
        val len=bytes[1].toInt() and 255
        require(len==12)
        val cipher=Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE,wrapKey(),
            GCMParameterSpec(128,bytes.copyOfRange(2,2+len)))
        cipher.updateAAD(keyId.toByteArray(Charsets.UTF_8))
        val clear=cipher.doFinal(bytes.copyOfRange(2+len,bytes.size))
        require(clear.size==32)
        try { return action(clear) } finally { clear.fill(0) }
    }

    /** Vault adaptors keep private key use scoped to a single operation. */
    fun recipientKey(keyId:String):T043HpkeSecret = object:T043HpkeSecret {
        override fun <T> withPrivateX25519(block:(ByteArray)->T):T =
            withSecret(keyId,block)
    }

    fun signingKey(keyId:String):T043SigningKey = object:T043SigningKey {
        override fun sign(input:ByteArray):ByteArray =
            withSecret(keyId) { seed -> T043SyncCrypto.signEd25519(seed,input) }
    }
}
