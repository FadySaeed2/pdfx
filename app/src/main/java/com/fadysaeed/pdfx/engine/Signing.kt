package com.fadysaeed.pdfx.engine

import com.artifex.mupdf.fitz.FitzInputStream
import com.artifex.mupdf.fitz.PKCS7DistinguishedName
import com.artifex.mupdf.fitz.PKCS7Signer
import com.artifex.mupdf.fitz.PKCS7Verifier
import org.bouncycastle.asn1.ASN1UTCTime
import org.bouncycastle.asn1.ASN1GeneralizedTime
import org.bouncycastle.asn1.cms.CMSAttributes
import org.bouncycastle.asn1.x500.RDN
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.asn1.x500.style.BCStyle
import org.bouncycastle.asn1.x500.style.IETFUtils
import org.bouncycastle.cert.X509CertificateHolder
import org.bouncycastle.cert.jcajce.JcaCertStore
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cms.CMSProcessableByteArray
import org.bouncycastle.cms.CMSSignedData
import org.bouncycastle.cms.CMSSignedDataGenerator
import org.bouncycastle.cms.jcajce.JcaSignerInfoGeneratorBuilder
import org.bouncycastle.cms.jcajce.JcaSimpleSignerInfoVerifierBuilder
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import org.bouncycastle.operator.jcajce.JcaDigestCalculatorProviderBuilder
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.security.KeyStore
import java.security.PrivateKey
import java.security.cert.CertPathValidator
import java.security.cert.CertificateFactory
import java.security.cert.PKIXParameters
import java.security.cert.TrustAnchor
import java.security.cert.X509Certificate

/**
 * BouncyCastle's own provider, used directly rather than installed: Android ships a cut-down one
 * under the same name, which cannot read every .p12 file (newer ones use AES).
 */
val Crypto: java.security.Provider by lazy { org.bouncycastle.jce.provider.BouncyCastleProvider() }

/** Who a certificate names. */
data class Person(val name: String, val email: String = "", val org: String = "", val country: String = "")

fun personOf(x: X500Name): Person {
    fun get(o: org.bouncycastle.asn1.ASN1ObjectIdentifier): String =
        x.getRDNs(o).firstOrNull()?.let { rdn: RDN -> IETFUtils.valueToString(rdn.first.value) } ?: ""
    return Person(get(BCStyle.CN), get(BCStyle.EmailAddress), get(BCStyle.O), get(BCStyle.C))
}

fun personOf(c: X509Certificate): Person = personOf(X509CertificateHolder(c.encoded).subject)

private fun readAll(s: InputStream): ByteArray {
    val out = ByteArrayOutputStream()
    val buf = ByteArray(64 * 1024)
    while (true) {
        val n = s.read(buf)
        if (n <= 0) break
        out.write(buf, 0, n)
    }
    return out.toByteArray()
}

/**
 * Signs with a private key and its certificate chain, the way PDF readers expect: a detached CMS
 * (PKCS#7) signature over the bytes MuPDF hands over, SHA-256, with the chain included and the
 * signing time among the signed attributes.
 */
class P12Signer(private val key: PrivateKey, private val chain: List<X509Certificate>) : PKCS7Signer() {
    override fun name(): PKCS7DistinguishedName {
        val p = personOf(chain.first())
        return PKCS7DistinguishedName().apply { cn = p.name; o = p.org; email = p.email; c = p.country; ou = "" }
    }

    override fun sign(stm: FitzInputStream): ByteArray {
        val data = readAll(stm)
        val algorithm = if (key.algorithm.equals("EC", true)) "SHA256withECDSA" else "SHA256withRSA"
        val signer = JcaContentSignerBuilder(algorithm).setProvider(Crypto).build(key)
        val gen = CMSSignedDataGenerator()
        gen.addSignerInfoGenerator(JcaSignerInfoGeneratorBuilder(JcaDigestCalculatorProviderBuilder().setProvider(Crypto).build()).build(signer, chain.first()))
        gen.addCertificates(JcaCertStore(chain))
        return gen.generate(CMSProcessableByteArray(data), false).encoded
    }

    /** Room MuPDF keeps in the file for the signature: generous, for long chains. */
    override fun maxDigest(): Int = 7000 + chain.sumOf { it.encoded.size }

    companion object {
        /** Opens a .p12 / .pfx file. Throws when the password is wrong or there is no key in it. */
        fun open(bytes: ByteArray, password: CharArray): P12Signer {
            val ks = KeyStore.getInstance("PKCS12", Crypto)
            ks.load(bytes.inputStream(), password)
            val alias = ks.aliases().toList().firstOrNull { ks.isKeyEntry(it) } ?: error("This file has no private key to sign with.")
            val key = ks.getKey(alias, password) as PrivateKey
            val chain = ks.getCertificateChain(alias)?.map { it as X509Certificate } ?: listOf(ks.getCertificate(alias) as X509Certificate)
            return P12Signer(key, chain)
        }
    }
}

/** What a signature in a file says, and whether it holds. */
data class SignatureCheck(
    val signer: Person,
    val issuer: Person,
    /** When it was signed, from the signature itself, in milliseconds; null when it does not say. */
    val time: Long?,
    /** The signed bytes are unchanged. */
    val intact: Boolean,
    /** The signer's certificate is one the phone trusts (through a known authority). */
    val trusted: Boolean,
    /** The certificate signed itself: who it claims to be is not vouched for by anyone. */
    val selfSigned: Boolean,
    /** The file was changed (more saved on top) after this signature. */
    val changedAfter: Boolean,
    val validFrom: Long = 0, val validTo: Long = 0,
    /** Form entries the signature locked were changed afterwards. */
    val lockedChanged: Boolean = false,
) {
    val state: SignState get() = when {
        !intact -> SignState.BROKEN
        trusted && !lockedChanged -> SignState.VALID
        else -> SignState.UNVERIFIED
    }
}

/** Valid: unchanged and vouched for. Unverified: unchanged, but nobody the phone trusts vouches for the signer. Broken: altered. */
enum class SignState { VALID, UNVERIFIED, BROKEN }

/** A signed signature field in an open file. */
data class DocSignature(
    val page: Int,
    val box: Box,
    val field: String,
    val reason: String,
    val location: String,
    val check: SignatureCheck,
)

/** Reads a PDF date, "D:20261102103000+02'00'", into milliseconds. */
fun pdfDate(s: String?): Long? {
    val m = Regex("^D?:?(\\d{4})(\\d{2})?(\\d{2})?(\\d{2})?(\\d{2})?(\\d{2})?([Zz+\\-])?(\\d{2})?'?(\\d{2})?").find(s?.trim() ?: return null) ?: return null
    val g = m.groupValues
    fun n(i: Int, d: Int) = g[i].toIntOrNull() ?: d
    val cal = java.util.Calendar.getInstance(java.util.TimeZone.getTimeZone("UTC"))
    cal.clear()
    cal.set(n(1, 1970), n(2, 1) - 1, n(3, 1), n(4, 0), n(5, 0), n(6, 0))
    var t = cal.timeInMillis
    val sign = g[7]
    if (sign == "+" || sign == "-") {
        val off = (n(8, 0) * 60 + n(9, 0)) * 60_000L
        t = if (sign == "+") t - off else t + off
    }
    return t
}

/** Reads the signer, issuer and signing time out of a CMS signature. */
fun readSignature(sig: ByteArray): Triple<X509Certificate?, Long?, List<X509Certificate>> = runCatching {
    val cms = CMSSignedData(trimmed(sig))
    val conv = JcaX509CertificateConverter().setProvider(Crypto)
    val certs = cms.certificates.getMatches(null).map { conv.getCertificate(it) }
    val info = cms.signerInfos.signers.firstOrNull()
    val holder = info?.let { si -> cms.certificates.getMatches(selectorOf(si.sid)).firstOrNull() }
    val cert = holder?.let { conv.getCertificate(it) } ?: certs.firstOrNull()
    val t = info?.signedAttributes?.get(CMSAttributes.signingTime)?.attrValues?.getObjectAt(0)?.let { v ->
        when (v) {
            is ASN1UTCTime -> v.adjustedDate.time
            is ASN1GeneralizedTime -> v.date.time
            else -> null
        }
    }
    Triple(cert, t, certs)
}.getOrDefault(Triple(null, null, emptyList()))

/** Signatures sit in a fixed-size hole padded with zeros; CMS wants just the signature. */
private fun trimmed(sig: ByteArray): ByteArray {
    if (sig.size < 4 || sig[0] != 0x30.toByte()) return sig
    // DER length of the outer SEQUENCE.
    val b1 = sig[1].toInt() and 0xFF
    val (len, head) = if (b1 < 0x80) b1 to 2 else {
        val n = b1 and 0x7F
        var l = 0
        for (i in 0 until n) l = (l shl 8) or (sig[2 + i].toInt() and 0xFF)
        l to 2 + n
    }
    val total = head + len
    return if (total in 1..sig.size) sig.copyOf(total) else sig
}

/** Is the certificate one the phone trusts, through the authorities Android ships with? */
fun isTrusted(cert: X509Certificate, chain: List<X509Certificate>): Boolean = runCatching {
    val anchors = KeyStore.getInstance("AndroidCAStore").apply { load(null) }
    val params = PKIXParameters(anchors).apply { isRevocationEnabled = false }
    val path = CertificateFactory.getInstance("X.509").generateCertPath(listOf(cert) + chain.filter { it != cert && it.subjectX500Principal != it.issuerX500Principal })
    CertPathValidator.getInstance("PKIX").validate(path, params)
    true
}.getOrDefault(false)

fun isSelfSigned(c: X509Certificate): Boolean = c.subjectX500Principal == c.issuerX500Principal

/** Checks signatures for MuPDF: the bytes against the signature, and the certificate against the phone's trust. */
class CmsVerifier : PKCS7Verifier() {
    override fun checkDigest(stream: FitzInputStream, signature: ByteArray): Int = runCatching {
        val data = readAll(stream)
        val cms = CMSSignedData(CMSProcessableByteArray(data), trimmed(signature))
        val si = cms.signerInfos.signers.firstOrNull() ?: return@runCatching PKCS7VerifierNoSignature
        val holder = cms.certificates.getMatches(selectorOf(si.sid)).firstOrNull() ?: return@runCatching PKCS7VerifierNoCertificate
        val cert = JcaX509CertificateConverter().setProvider(Crypto).getCertificate(holder)
        if (si.verify(JcaSimpleSignerInfoVerifierBuilder().setProvider(Crypto).build(cert))) PKCS7VerifierOK else PKCS7VerifierDigestFailure
    }.getOrDefault(PKCS7VerifierDigestFailure)

    override fun checkCertificate(signature: ByteArray): Int {
        val (cert, _, chain) = readSignature(signature)
        cert ?: return PKCS7VerifierNoCertificate
        return when {
            isSelfSigned(cert) -> PKCS7VerifierSelfSigned
            isTrusted(cert, chain) -> PKCS7VerifierOK
            else -> PKCS7VerifierNotTrusted
        }
    }
}

/** A signer's id picks out its certificate; BouncyCastle declares it untyped. */
@Suppress("UNCHECKED_CAST")
private fun selectorOf(id: org.bouncycastle.cms.SignerId): org.bouncycastle.util.Selector<X509CertificateHolder> =
    id as org.bouncycastle.util.Selector<X509CertificateHolder>
