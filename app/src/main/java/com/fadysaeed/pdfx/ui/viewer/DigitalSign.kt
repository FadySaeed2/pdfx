package com.fadysaeed.pdfx.ui.viewer

import android.net.Uri
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.fadysaeed.pdfx.PdfxApp
import com.fadysaeed.pdfx.data.SavedCert
import com.fadysaeed.pdfx.engine.DocSignature
import com.fadysaeed.pdfx.engine.P12Signer
import com.fadysaeed.pdfx.engine.SignState
import com.fadysaeed.pdfx.ui.components.AmberButton
import com.fadysaeed.pdfx.ui.components.Chip
import com.fadysaeed.pdfx.ui.components.GhostButton
import com.fadysaeed.pdfx.ui.components.InkField
import com.fadysaeed.pdfx.ui.components.Corner
import com.fadysaeed.pdfx.ui.theme.Ink
import com.fadysaeed.pdfx.ui.theme.Type
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.DateFormat
import java.util.Date

/** Colours for how a signature stands: holds, unverified signer, broken. */
internal val SignGood = Color(0xFF4FB477)
internal val SignWarn = Color(0xFFE0A43B)
internal val SignBad = Color(0xFFE5484D)

internal fun SignState.color() = when (this) { SignState.VALID -> SignGood; SignState.UNVERIFIED -> SignWarn; SignState.BROKEN -> SignBad }

private fun day(t: Long): String = DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(t))
private fun moment(t: Long): String = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(t))

/** A certificate to sign with, by the name it carries; a long press offers to delete it. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun CertChip(c: SavedCert, selected: Boolean, onPick: () -> Unit, onLongPress: () -> Unit) {
    Column(
        Modifier.heightIn(min = 44.dp).background(if (selected) Ink.Bone else Color.Transparent, Corner)
            .border(1.dp, if (selected) Ink.Bone else Ink.Rule, Corner)
            .combinedClickable(onClick = onPick, onLongClick = onLongPress).padding(horizontal = 12.dp, vertical = 5.dp),
        verticalArrangement = Arrangement.Center,
    ) {
        Text(c.name.ifBlank { "Certificate" }, style = Type.body.copy(fontSize = 14.sp, color = if (selected) Ink.Black else Ink.Sand), maxLines = 1)
        Text(if (c.selfSigned) "Your own · until ${day(c.validTo)}" else "${c.issuer.ifBlank { "Issued" }} · until ${day(c.validTo)}",
            style = Type.tiny.copy(color = if (selected) Ink.Slate else Ink.Ash), maxLines = 1)
    }
}

/** The dialogs of digital signing: adding a certificate, deleting one, signing, and the signatures in the file. */
@Composable
internal fun DigitalSignHost(reader: Reader) {
    val context = LocalContext.current
    val app = context.applicationContext as? PdfxApp ?: return
    val scope = rememberCoroutineScope()
    LaunchedEffect(reader) {
        if (reader.certs.isEmpty()) {
            val saved = withContext(Dispatchers.IO) { runCatching { app.certificates.list() }.getOrDefault(emptyList()) }
            reader.certs.addAll(saved)
        }
    }
    if (reader.certSheet) {
        var busy by remember { mutableStateOf(false) }
        var error by remember { mutableStateOf<String?>(null) }
        var picked by remember { mutableStateOf<Uri?>(null) }
        val open = androidx.activity.compose.rememberLauncherForActivityResult(androidx.activity.result.contract.ActivityResultContracts.OpenDocument()) { uri -> if (uri != null) { picked = uri; error = null } }
        fun added(c: SavedCert) {
            reader.certs.removeAll { it.id == c.id }; reader.certs.add(c); reader.pickCert(c.id)
            reader.certSheet = false
            reader.problem = "Certificate added: ${c.name}"
        }
        CertSheet(
            picked = picked != null,
            busy = busy,
            error = error,
            onDismiss = { reader.certSheet = false },
            onChooseFile = { runCatching { open.launch(arrayOf("application/x-pkcs12", "application/pkcs12", "application/x-pkcs12-certificates", "application/octet-stream", "*/*")) } },
            onImport = { pw, remember ->
                val uri = picked ?: return@CertSheet
                busy = true; error = null
                scope.launch {
                    val r = withContext(Dispatchers.IO) { runCatching { app.certificates.import(context, uri, pw.toCharArray(), remember) } }
                    busy = false
                    r.fold(::added) { error = if (it is java.io.IOException) "That password doesn't open this certificate." else (it.message ?: "This file isn't a certificate PDFx can sign with.") }
                }
            },
            onCreate = { name, email, org, pw, remember ->
                busy = true; error = null
                scope.launch {
                    val r = withContext(Dispatchers.Default) { runCatching { app.certificates.create(name.trim(), email.trim(), org.trim(), pw.toCharArray(), remember) } }
                    busy = false
                    r.fold(::added) { error = it.message ?: "The certificate could not be made." }
                }
            },
        )
    }
    reader.deleteCertAsk?.let { c ->
        AlertDialog(
            onDismissRequest = { reader.deleteCertAsk = null },
            containerColor = Ink.Field, shape = RectangleShape,
            title = { Text("Delete this certificate?", style = Type.title) },
            text = { Text("${c.name} is removed from this phone, with its key. Files you have signed stay signed. If you made it here, it cannot be got back.", style = Type.note) },
            confirmButton = {
                AmberButton("Delete", {
                    reader.deleteCertAsk = null
                    reader.certs.remove(c)
                    if (reader.certId == c.id) reader.pickCert(null)
                    scope.launch(Dispatchers.IO) { runCatching { app.certificates.delete(c.id) } }
                })
            },
            dismissButton = {
                if (c.remembered) GhostButton("Forget its password", {
                    reader.deleteCertAsk = null
                    scope.launch {
                        withContext(Dispatchers.IO) { runCatching { app.certificates.forget(c.id) } }
                        val i = reader.certs.indexOf(c); if (i >= 0) reader.certs[i] = c.copy(remembered = false)
                    }
                }, minHeight = 52.dp) else GhostButton("Cancel", { reader.deleteCertAsk = null }, minHeight = 52.dp)
            },
        )
    }
    reader.digitalAsk?.let { ask ->
        val cert = reader.cert ?: run { reader.digitalAsk = null; return@let }
        var busy by remember(ask) { mutableStateOf(false) }
        var error by remember(ask) { mutableStateOf<String?>(null) }
        val sign = reader.signs.firstOrNull { it.id == reader.signId } ?: reader.signs.firstOrNull()
        DigitalSignDialog(
            cert = cert,
            hasPicture = sign != null,
            busy = busy,
            error = error,
            onDismiss = { reader.digitalAsk = null },
            onSign = { pw, remember, showPicture, reason, location ->
                busy = true; error = null
                scope.launch {
                    val prepared = withContext(Dispatchers.IO) {
                        runCatching {
                            val (bytes, kept) = app.certificates.open(cert.id)
                            val password = if (pw.isNotEmpty()) pw.toCharArray() else kept ?: CharArray(0)
                            val signer = try { P12Signer.open(bytes, password) } catch (t: java.io.IOException) { throw WrongPassword() }
                            if (remember && !cert.remembered) runCatching { app.certificates.remember(cert.id, password) }
                            val picture = if (showPicture && sign != null) app.signatures.pictureOf(sign) else null
                            signer to picture
                        }
                    }
                    prepared.onFailure {
                        busy = false
                        error = if (it is WrongPassword) "That password doesn't open ${cert.name}'s certificate." else (it.message ?: "The certificate could not be opened.")
                    }.onSuccess { (signer, picture) ->
                        val ok = runCatching { reader.signDigitally(ask, signer, picture, reason, location) }
                        busy = false
                        ok.onFailure { error = it.message ?: "The file could not be signed." }
                        if (remember && !cert.remembered) { val i = reader.certs.indexOf(cert); if (i >= 0) reader.certs[i] = cert.copy(remembered = true) }
                    }
                }
            },
        )
    }
    if (reader.signaturesOpen) {
        AlertDialog(
            onDismissRequest = { reader.signaturesOpen = false },
            containerColor = Ink.Field, shape = RectangleShape,
            title = { Text(if (reader.docSignatures.size == 1) "Signature in this file" else "${reader.docSignatures.size} signatures in this file", style = Type.title) },
            text = { Column(Modifier.verticalScroll(rememberScrollState())) { SignaturesList(reader.docSignatures) } },
            confirmButton = { AmberButton("Close", { reader.signaturesOpen = false }) },
        )
    }
}

private class WrongPassword : Exception()

/** Adding a certificate: a .p12 / .pfx file from elsewhere, or one made here. */
@Composable
internal fun CertSheet(
    picked: Boolean,
    busy: Boolean,
    error: String?,
    onDismiss: () -> Unit,
    onChooseFile: () -> Unit,
    onImport: (password: String, remember: Boolean) -> Unit,
    onCreate: (name: String, email: String, org: String, password: String, remember: Boolean) -> Unit,
    startMaking: Boolean = false,
) {
    var making by remember { mutableStateOf(startMaking) }
    var password by remember { mutableStateOf("") }
    var again by remember { mutableStateOf("") }
    var keep by remember { mutableStateOf(true) }
    var name by remember { mutableStateOf("") }
    var email by remember { mutableStateOf("") }
    var org by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        containerColor = Ink.Field, shape = RectangleShape,
        title = { Text("Add a certificate", style = Type.title) },
        text = { CertSheetBody(making, { making = it }, picked, error, onChooseFile, password, { password = it }, again, { again = it }, keep, { keep = it }, name, { name = it }, email, { email = it }, org, { org = it }) },
        confirmButton = {
            if (making) AmberButton(if (busy) "Making…" else "Make certificate", { onCreate(name, email, org, password, keep) }, enabled = !busy && name.isNotBlank() && password.length >= 6 && password == again)
            else AmberButton(if (busy) "Opening…" else "Add", { onImport(password, keep) }, enabled = !busy && picked)
        },
        dismissButton = { GhostButton("Cancel", onDismiss, minHeight = 52.dp) },
    )
}

/** What the certificate dialog shows; kept apart so it can be pictured in tests. */
@Composable
internal fun CertSheetBody(
    making: Boolean, onMaking: (Boolean) -> Unit,
    picked: Boolean, error: String?, onChooseFile: () -> Unit,
    password: String, onPassword: (String) -> Unit,
    again: String, onAgain: (String) -> Unit,
    remember: Boolean, onRemember: (Boolean) -> Unit,
    name: String, onName: (String) -> Unit,
    email: String, onEmail: (String) -> Unit,
    org: String, onOrg: (String) -> Unit,
) {
    Column(Modifier.verticalScroll(rememberScrollState())) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Chip("I have one", { onMaking(false) }, selected = !making)
            Chip("Make my own", { onMaking(true) }, selected = making)
        }
        Spacer(Modifier.height(14.dp))
        if (!making) {
            Text("A .p12 or .pfx file, from your hospital, employer or a certificate authority. Signatures made with it are trusted wherever that authority is.", style = Type.note)
            Spacer(Modifier.height(12.dp))
            GhostButton(if (picked) "File chosen · choose another" else "Choose the file", onChooseFile, minHeight = 48.dp)
            Spacer(Modifier.height(12.dp))
            InkField(password, onPassword, "Its password", label = "Password", secret = true)
        } else {
            Text("Made here and kept on this phone. Readers see your name and that the file is unchanged, and mark the signer as not verified by an authority.", style = Type.note)
            Spacer(Modifier.height(12.dp))
            InkField(name, onName, "As it should appear", label = "Your name")
            Spacer(Modifier.height(10.dp))
            InkField(email, onEmail, "Optional", label = "Email")
            Spacer(Modifier.height(10.dp))
            InkField(org, onOrg, "Optional, e.g. your clinic", label = "Organisation")
            Spacer(Modifier.height(10.dp))
            InkField(password, onPassword, "At least 6 characters", label = "Password for it", secret = true)
            Spacer(Modifier.height(10.dp))
            InkField(again, onAgain, "The same again", label = "Password again", secret = true)
            if (again.isNotEmpty() && again != password) Text("The two passwords differ.", style = Type.small.copy(color = SignBad), modifier = Modifier.padding(top = 6.dp))
        }
        Spacer(Modifier.height(12.dp))
        CheckLine("Remember the password on this phone", remember, onRemember)
        if (error != null) Text(error, style = Type.note.copy(color = SignBad), modifier = Modifier.padding(top = 10.dp))
    }
}

@Composable
private fun CheckLine(text: String, on: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(role = Role.Checkbox) { onChange(!on) }.padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(22.dp).background(if (on) Ink.Amber else Color.Transparent, Corner).border(1.5.dp, if (on) Ink.Amber else Ink.Edge, Corner), contentAlignment = Alignment.Center) {
            if (on) Text("✓", style = Type.bodyStrong.copy(color = Ink.OnAmber, fontSize = 14.sp))
        }
        Spacer(Modifier.width(10.dp))
        Text(text, style = Type.body.copy(fontSize = 14.sp))
    }
}

/** Signing with a certificate: what shows on the page, why and where, and the password. */
@Composable
internal fun DigitalSignDialog(
    cert: SavedCert,
    hasPicture: Boolean,
    busy: Boolean,
    error: String?,
    onDismiss: () -> Unit,
    onSign: (password: String, remember: Boolean, showPicture: Boolean, reason: String, location: String) -> Unit,
) {
    var password by remember { mutableStateOf("") }
    var keep by remember { mutableStateOf(false) }
    var picture by remember { mutableStateOf(hasPicture) }
    var reason by remember { mutableStateOf("") }
    var location by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        containerColor = Ink.Field, shape = RectangleShape,
        title = { Text("Sign digitally", style = Type.title) },
        text = { DigitalSignBody(cert, hasPicture, error, password, { password = it }, keep, { keep = it }, picture, { picture = it }, reason, { reason = it }, location, { location = it }) },
        confirmButton = { AmberButton(if (busy) "Signing…" else "Sign and save", { onSign(password, keep, picture, reason, location) }, enabled = !busy && (cert.remembered || password.isNotEmpty())) },
        dismissButton = { GhostButton("Cancel", onDismiss, minHeight = 52.dp) },
    )
}

@Composable
internal fun DigitalSignBody(
    cert: SavedCert, hasPicture: Boolean, error: String?,
    password: String, onPassword: (String) -> Unit,
    remember: Boolean, onRemember: (Boolean) -> Unit,
    picture: Boolean, onPicture: (Boolean) -> Unit,
    reason: String, onReason: (String) -> Unit,
    location: String, onLocation: (String) -> Unit,
) {
    Column(Modifier.verticalScroll(rememberScrollState())) {
        Text(cert.name, style = Type.bodyStrong)
        Text(listOf(cert.email, cert.org).filter { it.isNotBlank() }.joinToString(" · ").ifBlank { if (cert.selfSigned) "Your own certificate" else cert.issuer }, style = Type.small)
        Spacer(Modifier.height(12.dp))
        if (hasPicture) {
            Text("On the page", style = Type.small, modifier = Modifier.padding(bottom = 6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Chip("My signature", { onPicture(true) }, selected = picture)
                Chip("My name", { onPicture(false) }, selected = !picture)
            }
            Spacer(Modifier.height(12.dp))
        }
        InkField(reason, onReason, "Optional, e.g. I approve this report", label = "Reason")
        Spacer(Modifier.height(10.dp))
        InkField(location, onLocation, "Optional, e.g. Cairo", label = "Place")
        if (!cert.remembered) {
            Spacer(Modifier.height(10.dp))
            InkField(password, onPassword, "The certificate's password", label = "Password", secret = true)
            CheckLine("Remember it on this phone", remember, onRemember)
        }
        Spacer(Modifier.height(8.dp))
        Text("The file is saved as soon as it is signed. Any change after that shows to readers as made after signing.", style = Type.small)
        if (error != null) Text(error, style = Type.note.copy(color = SignBad), modifier = Modifier.padding(top = 10.dp))
    }
}

/** A thin line under the top bar: who signed the file and whether it holds; a tap shows the details. */
@Composable
internal fun SignaturesBanner(signatures: List<DocSignature>, onOpen: () -> Unit) {
    if (signatures.isEmpty()) return
    val worst = signatures.maxOf { it.check.state.ordinal }.let { SignState.entries[it] }
    val text = when {
        signatures.size > 1 -> when (worst) {
            SignState.VALID -> "${signatures.size} signatures · all valid"
            SignState.UNVERIFIED -> "${signatures.size} signatures · not all signers verified"
            SignState.BROKEN -> "${signatures.size} signatures · one was altered"
        }
        else -> {
            val s = signatures[0]
            val who = s.check.signer.name.ifBlank { "Signed" }
            when (worst) {
                SignState.VALID -> "Signed by $who · valid"
                SignState.UNVERIFIED -> "Signed by $who · not verified"
                SignState.BROKEN -> "Signed by $who · altered since"
            }
        }
    }
    Row(
        Modifier.fillMaxWidth().clickable(role = Role.Button, onClick = onOpen).padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(9.dp).background(worst.color(), CircleShape))
        Spacer(Modifier.width(10.dp))
        Text(text, style = Type.small.copy(color = Ink.Sand), maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        Text("Details", style = Type.small.copy(color = Ink.Amber))
    }
}

/** Each signature: who, when, by whose word, and what the checks found, in plain words. */
@Composable
internal fun SignaturesList(signatures: List<DocSignature>) {
    signatures.forEachIndexed { i, s ->
        if (i > 0) Box(Modifier.fillMaxWidth().padding(vertical = 12.dp).height(1.dp).background(Ink.Rule))
        val c = s.check
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(10.dp).background(c.state.color(), CircleShape))
            Spacer(Modifier.width(10.dp))
            Text(c.signer.name.ifBlank { "Unknown signer" }, style = Type.bodyStrong)
        }
        val sub = listOf(c.signer.email, c.signer.org).filter { it.isNotBlank() }.joinToString(" · ")
        if (sub.isNotBlank()) Text(sub, style = Type.small, modifier = Modifier.padding(start = 20.dp))
        Column(Modifier.padding(start = 20.dp, top = 8.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            c.time?.let { Line("Signed", moment(it)) }
            Line("Page", "${s.page + 1}")
            if (s.reason.isNotBlank()) Line("Reason", s.reason)
            if (s.location.isNotBlank()) Line("Place", s.location)
            Line("The file", if (c.intact) "Unchanged since signing" else "Altered after signing: this signature no longer holds", if (c.intact) SignGood else SignBad)
            Line("The signer", when {
                c.trusted -> "Vouched for by ${c.issuer.name.ifBlank { c.issuer.org.ifBlank { "a trusted authority" } }}"
                c.selfSigned -> "Their own certificate: the name is not verified by anyone"
                else -> "Issued by ${c.issuer.name.ifBlank { c.issuer.org.ifBlank { "an authority" } }}, which this phone does not know"
            }, if (c.trusted) SignGood else SignWarn)
            if (c.validTo > 0) Line("Certificate", "${day(c.validFrom)} to ${day(c.validTo)}", if (c.time != null && (c.time > c.validTo || c.time < c.validFrom)) SignBad else Ink.Sand)
            if (c.lockedChanged) Line("Note", "Form entries it locked were changed afterwards", SignWarn)
            else if (c.changedAfter) Line("Note", "More was saved after signing, such as notes or entries; the signed version is kept inside", Ink.Sand)
        }
    }
}

@Composable
private fun Line(label: String, value: String, color: Color = Ink.Sand) {
    Row {
        Text(label, style = Type.small, modifier = Modifier.width(78.dp))
        Text(value, style = Type.note.copy(color = color), modifier = Modifier.widthIn(max = 420.dp))
    }
}
