# Makes the signing test files in app/src/androidTest/assets (needs pymupdf, cryptography, pyhanko).
# signable.pdf: a letter with an empty signature field; doctor.p12 (password secret1): a doctor's
# certificate from a test authority phones do not know; signed-elsewhere.pdf: signed by pyHanko; tampered.pdf.
import fitz, datetime
from cryptography import x509
from cryptography.x509.oid import NameOID
from cryptography.hazmat.primitives import hashes, serialization
from cryptography.hazmat.primitives.asymmetric import rsa
from cryptography.hazmat.primitives.serialization import pkcs12

# 1. A letter with an empty signature field.
doc = fitz.open()
page = doc.new_page(width=595, height=842)
page.insert_text((60, 90), "Referral letter", fontsize=20)
y = 130
for line in ["Dear colleague,", "", "I would be grateful if you could see this patient for assessment.",
             "Low mood for six weeks, poor sleep, no risk concerns at present.", "", "Kind regards,"]:
    page.insert_text((60, y), line, fontsize=12); y += 20
w = fitz.Widget()
w.field_type = fitz.PDF_WIDGET_TYPE_TEXT; w.field_name = "patient_name"; w.rect = fitz.Rect(60, 330, 300, 352); w.field_value = ""
page.add_widget(w)
w = fitz.Widget()
w.field_type = fitz.PDF_WIDGET_TYPE_SIGNATURE; w.field_name = "doctor_signature"; w.rect = fitz.Rect(60, 380, 280, 450)
page.add_widget(w)
page.insert_text((60, 470), "Signature of referring doctor", fontsize=9)
doc.save("signable.pdf")
doc.close()

# 2. A certificate authority unknown to phones, and a doctor's certificate from it.
def name(cn, o):
    return x509.Name([x509.NameAttribute(NameOID.COMMON_NAME, cn), x509.NameAttribute(NameOID.ORGANIZATION_NAME, o)])
now = datetime.datetime.now(datetime.timezone.utc)
ca_key = rsa.generate_private_key(public_exponent=65537, key_size=2048)
ca = (x509.CertificateBuilder().subject_name(name("PDFx Test Authority", "PDFx Tests")).issuer_name(name("PDFx Test Authority", "PDFx Tests"))
      .public_key(ca_key.public_key()).serial_number(1).not_valid_before(now - datetime.timedelta(days=1)).not_valid_after(now + datetime.timedelta(days=3650))
      .add_extension(x509.BasicConstraints(ca=True, path_length=None), critical=True)
      .add_extension(x509.KeyUsage(True, False, False, False, False, True, True, False, False), critical=True)
      .sign(ca_key, hashes.SHA256()))
key = rsa.generate_private_key(public_exponent=65537, key_size=2048)
leaf = (x509.CertificateBuilder().subject_name(x509.Name([x509.NameAttribute(NameOID.COMMON_NAME, "Dr Elsewhere"), x509.NameAttribute(NameOID.ORGANIZATION_NAME, "Elsewhere Clinic"), x509.NameAttribute(NameOID.EMAIL_ADDRESS, "elsewhere@example.org")]))
      .issuer_name(ca.subject).public_key(key.public_key()).serial_number(2).not_valid_before(now - datetime.timedelta(days=1)).not_valid_after(now + datetime.timedelta(days=730))
      .add_extension(x509.KeyUsage(True, True, False, False, False, False, False, False, False), critical=True)
      .sign(ca_key, hashes.SHA256()))
open("doctor.p12", "wb").write(pkcs12.serialize_key_and_certificates(b"doctor", key, leaf, [ca], serialization.BestAvailableEncryption(b"secret1")))
open("ca.pem", "wb").write(ca.public_bytes(serialization.Encoding.PEM))
from pyhanko.sign import signers, fields
from pyhanko.pdf_utils.incremental_writer import IncrementalPdfFileWriter
from pyhanko.sign.fields import SigFieldSpec
s = signers.SimpleSigner.load_pkcs12("doctor.p12", passphrase=b"secret1")
with open("signable.pdf", "rb") as inf:
    w = IncrementalPdfFileWriter(inf)
    out = signers.sign_pdf(w, signers.PdfSignatureMetadata(field_name="doctor_signature", reason="Referral approved", location="Perth"), signer=s)
    open("signed-elsewhere.pdf", "wb").write(out.getbuffer())

# 3. The same file with one letter changed inside the signed bytes.
b = bytearray(open("signed-elsewhere.pdf", "rb").read())
i = b.find(b"/Producer")
j = b.find(b"(", i) + 1
b[j] = ord('X') if b[j] != ord('X') else ord('Y')
open("tampered.pdf", "wb").write(b)
