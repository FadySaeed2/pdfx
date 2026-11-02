package com.fadysaeed.pdfx.data

import android.content.Context
import android.net.Uri
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import com.fadysaeed.pdfx.engine.Crypto
import com.fadysaeed.pdfx.engine.isSelfSigned
import com.fadysaeed.pdfx.engine.personOf
import org.bouncycastle.asn1.x500.X500NameBuilder
import org.bouncycastle.asn1.x500.style.BCStyle
import org.bouncycastle.asn1.x509.BasicConstraints
import org.bouncycastle.asn1.x509.Extension
import org.bouncycastle.asn1.x509.KeyUsage
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.math.BigInteger
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.Date
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** A certificate kept for digital signing: who it names, who issued it, until when. */
data class SavedCert(
    val id: String,
    val name: String,
    val email: String,
    val org: String,
    val issuer: String,
    val validTo: Long,
    val selfSigned: Boolean,
    /** Its password is remembered on this phone, locked by the phone's own key store. */
    val remembered: Boolean,
)

/**
 * Signing certificates (.p12 / .pfx), kept in the app's private files. A password is asked for
 * each signing unless it is remembered; a remembered one is encrypted with a key that never
 * leaves the phone's secure hardware (Android Keystore) and is useless anywhere else.
 */
class Certificates(private val dir: File) {
    private val folder = File(dir, "certs").apply { mkdirs() }
    private val index = File(folder, "certs.json")

    @Synchronized
    fun list(): List<SavedCert> = runCatching {
        if (!index.exists()) return emptyList()
        val arr = JSONArray(index.readText())
        (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            SavedCert(o.getString("id"), o.optString("name"), o.optString("email"), o.optString("org"), o.optString("issuer"),
                o.optLong("validTo"), o.optBoolean("selfSigned"), o.has("secret"))
        }
    }.getOrDefault(emptyList())

    @Synchronized
    private fun raw(): JSONArray = runCatching { JSONArray(index.readText()) }.getOrDefault(JSONArray())

    @Synchronized
    private fun write(arr: JSONArray) {
        val tmp = File(folder, "certs.json.tmp")
        tmp.writeText(arr.toString())
        tmp.renameTo(index)
    }

    private fun describe(id: String, bytes: ByteArray, password: CharArray): JSONObject {
        // Throws on a wrong password, or when there is no key to sign with.
        val ks = KeyStore.getInstance("PKCS12", Crypto).apply { load(bytes.inputStream(), password) }
        val alias = ks.aliases().toList().firstOrNull { ks.isKeyEntry(it) } ?: error("This file has no private key to sign with.")
        ks.getKey(alias, password) ?: error("This file has no private key to sign with.")
        val cert = ks.getCertificate(alias) as X509Certificate
        val who = personOf(cert)
        val issuer = runCatching { personOf(org.bouncycastle.cert.X509CertificateHolder(cert.encoded).issuer).let { it.name.ifBlank { it.org } } }.getOrDefault("")
        return JSONObject().put("id", id).put("name", who.name).put("email", who.email).put("org", who.org)
            .put("issuer", issuer).put("validTo", cert.notAfter.time).put("selfSigned", isSelfSigned(cert))
    }

    /** Adds a .p12 / .pfx file. Throws when the password does not open it. */
    fun import(context: Context, uri: Uri, password: CharArray, remember: Boolean): SavedCert {
        val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: error("The file could not be read.")
        return add(bytes, password, remember)
    }

    fun add(bytes: ByteArray, password: CharArray, remember: Boolean): SavedCert {
        val id = UUID.randomUUID().toString()
        val o = describe(id, bytes, password)
        File(folder, "$id.p12").writeBytes(bytes)
        if (remember) o.put("secret", Secret.seal(String(password)))
        write(raw().put(o))
        return list().first { it.id == id }
    }

    /**
     * Makes a certificate of your own (self-signed): RSA 2048, SHA-256, valid for [years]. PDF readers
     * show who signed and that the file is unchanged, but mark it as not vouched for by an authority.
     */
    fun create(name: String, email: String, org: String, password: CharArray, remember: Boolean, years: Int = 5): SavedCert {
        val kp = KeyPairGenerator.getInstance("RSA").apply { initialize(2048, SecureRandom()) }.generateKeyPair()
        val subject = X500NameBuilder(BCStyle.INSTANCE).apply {
            addRDN(BCStyle.CN, name)
            if (email.isNotBlank()) addRDN(BCStyle.EmailAddress, email)
            if (org.isNotBlank()) addRDN(BCStyle.O, org)
        }.build()
        val now = System.currentTimeMillis()
        val builder = JcaX509v3CertificateBuilder(subject, BigInteger(64, SecureRandom()), Date(now - 60_000), Date(now + years * 365L * 86_400_000L), subject, kp.public)
            .addExtension(Extension.basicConstraints, true, BasicConstraints(false))
            .addExtension(Extension.keyUsage, true, KeyUsage(KeyUsage.digitalSignature or KeyUsage.nonRepudiation))
        val cert = JcaX509CertificateConverter().setProvider(Crypto).getCertificate(builder.build(JcaContentSignerBuilder("SHA256withRSA").setProvider(Crypto).build(kp.private)))
        val ks = KeyStore.getInstance("PKCS12", Crypto).apply { load(null, null) }
        ks.setKeyEntry("signing", kp.private, password, arrayOf(cert))
        val out = ByteArrayOutputStream()
        ks.store(out, password)
        return add(out.toByteArray(), password, remember)
    }

    /** The certificate's file bytes, and its remembered password if there is one. */
    fun open(id: String): Pair<ByteArray, CharArray?> {
        val bytes = File(folder, "$id.p12").readBytes()
        val arr = raw()
        val secret = (0 until arr.length()).map { arr.getJSONObject(it) }.firstOrNull { it.getString("id") == id }?.optString("secret")?.ifEmpty { null }
        return bytes to secret?.let { runCatching { Secret.open(it).toCharArray() }.getOrNull() }
    }

    fun remember(id: String, password: CharArray) {
        val arr = raw()
        for (i in 0 until arr.length()) { val o = arr.getJSONObject(i); if (o.getString("id") == id) o.put("secret", Secret.seal(String(password))) }
        write(arr)
    }

    fun forget(id: String) {
        val arr = raw()
        for (i in 0 until arr.length()) { val o = arr.getJSONObject(i); if (o.getString("id") == id) o.remove("secret") }
        write(arr)
    }

    fun delete(id: String) {
        File(folder, "$id.p12").delete()
        val arr = raw()
        val keep = JSONArray()
        for (i in 0 until arr.length()) { val o = arr.getJSONObject(i); if (o.getString("id") != id) keep.put(o) }
        write(keep)
    }
}

/** Seals short secrets with an AES key held in the Android Keystore (it cannot be copied off the phone). */
object Secret {
    private const val ALIAS = "pdfx-cert-passwords"

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        gen.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build(),
        )
        return gen.generateKey()
    }

    fun seal(text: String): String {
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, key())
        val body = c.doFinal(text.toByteArray())
        return Base64.encodeToString(c.iv + body, Base64.NO_WRAP)
    }

    fun open(sealed: String): String {
        val all = Base64.decode(sealed, Base64.NO_WRAP)
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, all, 0, 12))
        return String(c.doFinal(all, 12, all.size - 12))
    }
}
