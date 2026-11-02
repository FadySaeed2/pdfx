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
    /** A mark unique to this signer, which MuPDF writes into the file in place of the signature. */
    val marker: ByteArray = ("PDFXSIG-" + java.util.UUID.randomUUID().toString().replace("-", "").take(16)).toByteArray()

    override fun name(): PKCS7DistinguishedName {
        val p = personOf(chain.first())
        return PKCS7DistinguishedName().apply { cn = p.name; o = p.org; email = p.email; c = p.country; ou = "" }
    }

    /**
     * MuPDF hands over the bytes to sign while it writes the file, but on Android that stream reads
     * back empty; so it gets a marker here, and [completeSignatures] signs the real bytes after.
     */
    override fun sign(stm: FitzInputStream): ByteArray = marker

    /** A detached CMS (PKCS#7) signature over [data]: SHA-256, the chain included, signing time signed. */
    fun signData(data: ByteArray): ByteArray {
        val algorithm = if (key.algorithm.equals("EC", true)) "SHA256withECDSA" else "SHA256withRSA"
        val signer = JcaContentSignerBuilder(algorithm).setProvider(Crypto).build(key)
        val gen = CMSSignedDataGenerator()
        gen.addSignerInfoGenerator(JcaSignerInfoGeneratorBuilder(JcaDigestCalculatorProviderBuilder().setProvider(Crypto).build()).build(signer, chain.first()))
        gen.addCertificates(JcaCertStore(chain))
        // DER, with definite lengths: readers (and our own check) find where the signature ends.
        return gen.generate(CMSProcessableByteArray(data), false).toASN1Structure().getEncoded(org.bouncycastle.asn1.ASN1Encoding.DER)
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
    /** Why the check failed, when it did: for diagnosis, not for show. */
    val problem: String = "",
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
    if (b1 == 0x80) return sig // indefinite length (BER): the parser stops at its end mark by itself
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
    /** What went wrong in the last digest check, if anything. */
    var problem: String = ""
        private set

    override fun checkDigest(stream: FitzInputStream, signature: ByteArray): Int {
        problem = ""
        var size = -1
        return try {
            val data = readAll(stream)
            size = data.size
            val cms = CMSSignedData(CMSProcessableByteArray(data), trimmed(signature))
            val si = cms.signerInfos.signers.firstOrNull() ?: run { problem = "no signer"; return PKCS7VerifierNoSignature }
            val holder = cms.certificates.getMatches(selectorOf(si.sid)).firstOrNull() ?: run { problem = "no certificate"; return PKCS7VerifierNoCertificate }
            val cert = JcaX509CertificateConverter().setProvider(Crypto).getCertificate(holder)
            if (si.verify(JcaSimpleSignerInfoVerifierBuilder().setProvider(Crypto).build(cert))) PKCS7VerifierOK
            else { problem = "signature does not match (${size} bytes)"; PKCS7VerifierDigestFailure }
        } catch (t: Throwable) {
            problem = "${t.javaClass.simpleName}: ${t.message} (${size} bytes, signature ${signature.size} bytes, trimmed ${runCatching { trimmed(signature).size }.getOrDefault(-1)})"
            PKCS7VerifierDigestFailure
        }
    }

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

/** Where a signature's parts sit in a file: the two signed byte ranges and the hex hole between them. */
private class SigSlot(val a: Long, val la: Long, val b: Long, val lb: Long, val holeStart: Long, val holeEnd: Long, val rangeAt: Int = -1, val rangeEnd: Int = -1)

private fun indexOf(hay: ByteArray, needle: ByteArray, from: Int): Int {
    var i = from.coerceAtLeast(0)
    val last = hay.size - needle.size
    outer@ while (i <= last) {
        for (k in needle.indices) if (hay[i + k] != needle[k]) { i++; continue@outer }
        return i
    }
    return -1
}

/** Every signature slot in [bytes] whose byte ranges are well formed: ByteRange, then Contents. */
private fun slots(bytes: ByteArray): List<SigSlot> {
    val out = ArrayList<SigSlot>()
    val br = "/ByteRange".toByteArray()
    val ct = "/Contents".toByteArray()
    var i = indexOf(bytes, br, 0)
    while (i >= 0) {
        runCatching {
            var j = i + br.size
            while (j < bytes.size && bytes[j] != '['.code.toByte()) j++
            val close = (j until minOf(bytes.size, j + 200)).first { bytes[it] == ']'.code.toByte() }
            val nums = String(bytes, j + 1, close - j - 1, Charsets.ISO_8859_1).trim().split(Regex("\\s+")).map { it.toLong() }
            if (nums.size == 4 && nums.all { it == 2147483647L }) {
                // MuPDF leaves this placeholder when it writes the whole file afresh: the ranges are
                // worked out here from where the hex string sits, as MuPDF does when appending.
                val c = indexOf(bytes, ct, close)
                if (c in 0..(close + 400)) {
                    var lt = c + ct.size
                    while (lt < bytes.size && bytes[lt] != '<'.code.toByte()) lt++
                    var gt = lt + 1
                    while (gt < bytes.size && bytes[gt] != '>'.code.toByte()) gt++
                    if (gt < bytes.size) out += SigSlot(0, lt.toLong(), gt + 1L, bytes.size - (gt + 1L), lt + 1L, gt.toLong(), j, close)
                }
            } else if (nums.size == 4) {
                // The hole is the hex string the first range stops at.
                val lt = nums[0] + nums[1]
                val gt = nums[2] - 1
                if (lt in 0 until bytes.size && gt in 0 until bytes.size && bytes[lt.toInt()] == '<'.code.toByte() && bytes[gt.toInt()] == '>'.code.toByte()) {
                    out += SigSlot(nums[0], nums[1], nums[2], nums[3], lt + 1, gt)
                }
            }
        }
        i = indexOf(bytes, br, i + br.size)
    }
    return out
}

private fun hexDecode(bytes: ByteArray, from: Long, to: Long): ByteArray {
    val out = java.io.ByteArrayOutputStream()
    var hi = -1
    for (p in from until to) {
        val v = Character.digit(bytes[p.toInt()].toInt().toChar(), 16)
        if (v < 0) continue
        if (hi < 0) hi = v else { out.write(hi * 16 + v); hi = -1 }
    }
    return out.toByteArray()
}

/**
 * Finishes signatures MuPDF has just written into [path]: finds each slot still holding a signer's
 * marker (or nothing), signs the file's real bytes around it, and writes the signature in. Throws
 * when a signature cannot be completed, so a half-signed file is never kept.
 */
fun completeSignatures(path: String, signers: List<P12Signer>) {
    if (signers.isEmpty()) return
    val file = java.io.File(path)
    require(file.length() < 400L * 1024 * 1024) { "This file is too large to sign on the phone." }
    val bytes = file.readBytes()
    val left = signers.toMutableList()
    java.io.RandomAccessFile(file, "rw").use { raf ->
        for (slot in slots(bytes)) {
            if (left.isEmpty()) break
            if (slot.b + slot.lb != bytes.size.toLong()) continue // not in the newest part of the file
            val inside = hexDecode(bytes, slot.holeStart, slot.holeEnd)
            val empty = inside.all { it == 0.toByte() }
            val signer = left.firstOrNull { m -> inside.size >= m.marker.size && inside.copyOf(m.marker.size).contentEquals(m.marker) }
                ?: if (empty) left.first() else null
            signer ?: continue
            if (slot.rangeAt >= 0) {
                // Write the real ranges over the placeholder, padded with spaces, before signing.
                val text = "[${slot.a} ${slot.la} ${slot.b} ${slot.lb}]"
                val room = slot.rangeEnd - slot.rangeAt + 1
                require(text.length <= room) { "The signature's place in the file is too small." }
                val fill = (text + " ".repeat(room - text.length)).toByteArray(Charsets.ISO_8859_1)
                System.arraycopy(fill, 0, bytes, slot.rangeAt, room)
                raf.seek(slot.rangeAt.toLong())
                raf.write(fill)
            }
            val data = ByteArray((slot.la + slot.lb).toInt())
            System.arraycopy(bytes, slot.a.toInt(), data, 0, slot.la.toInt())
            System.arraycopy(bytes, slot.b.toInt(), data, slot.la.toInt(), slot.lb.toInt())
            val sig = signer.signData(data)
            val hex = sig.joinToString("") { "%02X".format(it.toInt() and 0xFF) }
            val room = (slot.holeEnd - slot.holeStart).toInt()
            require(hex.length <= room) { "The signature did not fit in the file." }
            raf.seek(slot.holeStart)
            raf.write((hex + "0".repeat(room - hex.length)).toByteArray(Charsets.ISO_8859_1))
            left.remove(signer)
        }
    }
    require(left.isEmpty()) { "The signature could not be completed in the saved file." }
}

/**
 * Checks a signature against the file's own bytes: [range] is its ByteRange, [open] reads the file
 * from the start. True when the signed bytes are unchanged; [problem] says why not.
 */
fun checkRanges(open: () -> InputStream, range: LongArray, signature: ByteArray): Pair<Boolean, String> = try {
    require(range.size == 4 && range.all { it >= 0 }) { "odd byte range" }
    val total = range[1] + range[3]
    require(total < 400L * 1024 * 1024) { "too large" }
    val data = ByteArray(total.toInt())
    open().use { inp ->
        val s = java.io.BufferedInputStream(inp, 1 shl 16)
        fun skipTo(pos: Long, at: Long): Long { var p = at; while (p < pos) { val n = s.skip(pos - p); if (n <= 0) { if (s.read() < 0) error("file ends early"); p++ } else p += n }; return p }
        fun fill(off: Int, len: Int) { var o = off; val end = off + len; while (o < end) { val n = s.read(data, o, end - o); if (n < 0) error("file ends early"); o += n } }
        var p = skipTo(range[0], 0)
        fill(0, range[1].toInt()); p += range[1]
        skipTo(range[2], p)
        fill(range[1].toInt(), range[3].toInt())
    }
    val cms = CMSSignedData(CMSProcessableByteArray(data), trimmed(signature))
    val si = cms.signerInfos.signers.firstOrNull() ?: error("no signer")
    val holder = cms.certificates.getMatches(selectorOf(si.sid)).firstOrNull() ?: error("no certificate")
    val cert = JcaX509CertificateConverter().setProvider(Crypto).getCertificate(holder)
    if (si.verify(JcaSimpleSignerInfoVerifierBuilder().setProvider(Crypto).build(cert))) true to "" else false to "signature does not match"
} catch (t: Throwable) {
    false to "${t.javaClass.simpleName}: ${t.message}"
}
