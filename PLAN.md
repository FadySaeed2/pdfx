# PDFx — the plan

PDFx is Fady's personal Android PDF suite: everything a paid PDF app does that matters day to day,
fast, clean and reliable, with no subscription, no account and no network. This file is the single
reference for what the app is, how it is built and what comes next. Update it with every phase.

## What it is

- Native Android, Kotlin and Jetpack Compose. English interface.
- Fully offline. The manifest removes the INTERNET permission, so no document can leave the phone
  through this app, even by mistake.
- One engine: **MuPDF** (Artifex, `com.artifex.mupdf:fitz`, AGPL; fine for personal use). It renders,
  extracts and searches text, edits annotations and forms, redacts, rearranges and merges pages,
  encrypts, and signs. One engine means a file looks the same in the viewer and after saving.
- `androidx.ink` joins in phase 3 for low-latency S Pen strokes, which are then written into the file
  through MuPDF.
- Visual identity shared with CalPal: true black for AMOLED, warm paper-and-stone neutrals
  (Bone `#ECE4D3`, Sand, Dust, Ash), one amber accent `#E0A43B` for the action that matters, teal
  `#3E9F8F` as the quiet second colour. One typeface, Bricolage Grotesque (SIL OFL, bundled), light
  weights for large type. No cards, no shadows, no gradients, no bottom tab bar; hairlines and type
  do the structuring. Icons are line glyphs drawn in code with one stroke weight.

## Principles

1. **Never damage a file.** Saves go to a temporary file, are reopened and checked, and only then
   replace the original. The previous version is kept so a save can be undone. Saves are
   incremental where possible, which is faster and keeps existing digital signatures valid.
2. **Read in place.** Files are read straight from their file descriptor, never copied into memory.
3. **One document, one thread.** Every call into a document runs on that document's own thread.
4. **Fixed memory.** Rendered pages live within a pixel budget, so a 2,000-page file costs the same
   as a 10-page one.
5. **Tested on a real Android system.** Every build runs the engine on an emulator against generated
   PDFs (plain, Arabic text, password-locked) before a release is published.

## Build and delivery

- GitHub Actions on every push to `main`:
  - `build`: unit tests and screenshot tests (Robolectric + Roborazzi), then the signed release APK.
  - `device`: instrumented tests on an API 34 emulator that run MuPDF for real.
  - `publish`: screenshots of both to the `screenshots` branch; a GitHub Release `build-<run>` with
    the APK, only when both jobs pass.
- The signing key is fixed. It is committed only encrypted (`signing/pdfx-release.p12.enc`); the
  password lives in the `PDFX_SIGNING_PASSWORD` repository secret. CI created the key on its first
  signed run. Never change it: a different key forces an uninstall.
- `versionCode` is the Actions run number. `versionName` is `<phase-major>.<phase>.<run>`.
- Release APK ships arm64-v8a only (the phone); debug builds keep every ABI for the emulator.
- Toolchain: Gradle 8.11.1, AGP 8.7.3, Kotlin 2.1.0, Compose BOM 2024.12.01, MuPDF fitz 1.28.5,
  compileSdk/targetSdk 35, minSdk 30 (All files access needs Android 11).

## Phases

### Phase 0 — foundation (done)
- Project, cloud build, emulator engine tests, fixed signing key, releases.
- MuPDF wired in; documents open from the system picker, from "Open with" and from "Share".
- Password-locked PDFs ask for their password.
- First viewer: continuous scroll, pinch and double-tap zoom with re-rendering at rest, page counter,
  reading-position line, last page remembered per file.
- Library v0: recent files with pages, size, when opened, and the page reached.

### Phase 1 — library and file management (done)
All PDFs on the phone found automatically (All files access; personal app, not on Play), folders,
favourites, tags, thumbnails, rename, move, copy, delete to a recycle bin, sort and filter, and a
full-text index so search finds words inside documents, not only file names.

### Phase 2 — the reader (done)
Sharp deep zoom (past 2x only the part on screen is drawn, at full size), fast scrubber and
go-to-page, table of contents, bookmarks, find in document with every hit highlighted and the
current one outlined (also opened straight from a library search), text selection with handles
and copy, find or share, internal and web links, Paper, Night and Sepia page colours, and a
reflow mode with adjustable text size. Single-page and two-page landscape layouts move to phase 9.

### Phase 3 — annotation and S Pen (this build)
Highlight, underline and strike that snap to words; pen and marker; boxes, ovals, lines and
arrows; text boxes; sticky notes; eraser; undo and redo (MuPDF's journal); tap a mark to recolour
it, give it a note or delete it; a list of every mark with the words it covers; flatten. With an
S Pen the pen draws and fingers scroll and zoom. Saving is safe: written beside the original,
reopened and checked, the original kept as one of the last three versions, then swapped in;
incremental when the file allows, which keeps existing signatures valid. Files that belong to
another app are saved as a copy in Documents/PDFx. Earlier versions can be restored from the
library. Not in this phase: pressure-varying ink (PDF ink has one width per stroke) and stamps.

### Phase 4 — forms and signatures
Fill interactive forms; type, tick and date anywhere on flat forms; saved signature and initials
(drawn or photographed); digital signing with a PKCS#12 certificate; verification of signatures in
received files.

### Phase 5 — page organisation
Thumbnail grid with drag to reorder, rotate, delete, extract, duplicate; insert blank pages, pages
from other PDFs or images; merge many files; split by ranges or size; crop margins.

### Phase 6 — scanner and OCR
ML Kit document scanner (edge detection, deskew, clean-up; runs on the phone through Play services),
multi-page scans to PDF, and English OCR at the highest quality available on-device, written as an
invisible text layer so scans become searchable and copyable.

### Phase 7 — tools
Compress with before and after sizes; password protection with AES-256 and removal; true redaction
that deletes the content underneath, plus metadata scrubbing; images to PDF and back; PDF to text and
basic Word; watermark; page numbers.

### Phase 8 — content editing
Add text and images anywhere; cover and replace text in a matching font; edit existing text in simple
cases (a line, a number, a date).

### Phase 9 — polish
Performance work on very large files, motion, launcher shortcuts and widget, backup of settings and
signatures, and a full pass on every screen for clarity.

## Known limits (stated honestly)
- Acrobat-style reflowing text editing inside paragraphs is not a goal; phase 8 covers the practical
  cases.
- Word, Excel and PowerPoint to PDF stays with the app that opens those files (print or save as PDF).
- XFA forms are not supported by MuPDF.

## Decisions
- 2026-11-01: name PDFx; OCR English only, best quality; no internet permission at all; design
  language follows CalPal.

## Status log
- 2026-11-01 — Plan agreed. Phase 0 started.
- 2026-11-01 — Phase 0 shipped (0.0.2); pinch zoom anchoring fixed (0.0.5). Phase 1 started:
  Room library (documents, tags, full-text index of every page), All files access scan, rename,
  move, copy, trash with undo and 30-day purge, tags, folders, sorting, search inside documents
  that opens at the matching page.
- 2026-11-01 — Phase 1 shipped (0.1.12). Fady: search works well but should highlight the words
  inside the opened file; asked where night mode is (planned for phase 2). Phase 2 started.
- 2026-11-01 — Phase 2 shipped (0.2.14); Fady: all good, carry on. Phase 3 started.
- 2026-11-01 — Phase 3 shipped (0.3.17). Fady asked for: text boxes that move freely, a reflow
  option that is easy to reach, and today's date when signing. Agreed: 3.1 update first (drag to
  move any mark that sits on its own, corner handles to resize, Edit text for text boxes; reflow
  button in the top bar that keeps the reading place both ways, font/spacing/margins, IBM Plex Sans
  Arabic for Arabic paragraphs, headings, search hits, select and copy). The date option goes into
  Phase 4 with signatures: Add today's date (01/11/2026, 1 November 2026, 2026-11-01; remembers the
  last choice), sits under the signature but moves on its own, a standalone Date stamp, and one-tap
  filling of date fields in forms.
- 2026-11-01 — 3.1 shipped (0.3.21). Fady asked for: a floating bar over selected words with
  highlight / underline / strike; a side list of every search result with context; changing a
  highlight's colour after making it, lighter or stronger highlights, a colour picker. He approved
  the extras too: marks tab filters and copy/share as text, the S Pen button erases, Arabic search
  that ignores hamza and short vowels. OCR stays in phase 6 (he did not ask to move it).
  Cross-page selection is left for phase 9. 3.2 shipped as 0.3.25.
- 2026-11-01 — Fady: rotating closed the file; picking handwriting and resizing felt fiddly;
  selection overall not coherent; wants handwriting picked by stroke, word or line. 3.3: rotation
  handled in place (and the open file survives recreation); taps pick the nearest mark within 20dp
  measured to the stroke; tap picks instead of drawing (pens only right on a stroke); Stroke / Word
  / Line for handwriting (default Word); Select tool with lasso; group move, resize, colour, width,
  delete as one step; 20dp handles outside the frame; word-snapped text selection with a loupe;
  haptics.
- 2026-11-01 — 3.3 shipped (0.3.29); Fady: works well. Asked for text box formatting (fonts,
  direction, weight, slant…) on the whole box, and cleaner reflow. 3.4: Sans/Serif/Mono, 6–72 pt,
  bold/italic/underline, align and direction (auto per line), colour, background, border, spacing,
  preview, remembered defaults; stored as the box's rich text (this MuPDF build has no native
  setRichDefaults; FreeText background is /C). Reflow keeps bold/italic, real-size headings, drops
  running heads/page numbers, list items, joins paragraphs across pages, pictures in place,
  justified + hyphens, ~70-character lines, quiet page marks. Per-word styling in a box: phase 9
  if wanted. Shipped as 0.3.32.
- 2026-11-01 — Reflow tables (0.3.38): the engine's table hunt (segment, table-hunt, vectors) read
  through its HTML output, because walking that page crashes on a JNI bug in onVector (a bad
  jboolean). Columns the engine cuts through words are joined back (the page's text decides the
  spaces); anything unclear, ragged or incomplete is shown as a picture of the page region. Every
  picture and table opens full screen to zoom.
- 2026-11-01 — Fady on 0.3.38: table finding is a mess on his files (ordinary text taken for tables
  and the other way round). Switched off at once (0.3.39 restores 0.3.32 reflow, keeps picture
  zoom). Any return needs his real files as test cases and must be strictly conservative.
- 2026-11-01 — Phase 4a shipped (0.3.45): interactive forms (text with Next/Previous, Today in the
  field's own date format read from its AFDate script, checkboxes, radios, choices, signature
  fields), saved signatures and initials (drawn, or photographed with the paper cleared), sign with
  today's date, stamps (standard and custom, with date), tick / cross / dot / date tools. Tested on
  a real AcroForm fixture made with PyMuPDF (same MuPDF 1.28.5). Next: 4b digital signing with a
  PKCS#12 certificate and verifying signatures.
- 2026-11-02 — Phase 4b: digital signing. Certificates (.p12/.pfx imported, or made here: RSA 2048,
  self-signed) kept in app files, passwords optionally sealed with an Android Keystore AES key.
  Signing goes through MuPDF (PDFWidget.sign) with our own BouncyCastle CMS signer (detached,
  SHA-256, chain included) into an empty signature field or a new one, showing the saved signature
  picture or the name, then saves at once (appended, so earlier signatures keep holding) and
  reopens. Opening a signed file shows a banner and details: unchanged / altered, vouched for by a
  phone-trusted authority / own certificate / unknown authority, changed after signing. Tests use
  real-world fixtures (tools/make-signing-fixtures.py): a letter with a signature field, a doctor
  certificate from an unknown CA, a pyHanko-signed file and the same file with one letter changed.
  Found on the emulator: MuPDF's byte stream for signing and checking reads empty on Android, so
  the signer hands MuPDF a marker and the real bytes are signed after the save (completeSignatures,
  which also fills the ByteRange MuPDF leaves as a placeholder in whole-file saves); checks read the
  ByteRange from the file. Split signature fields are merged with their widget before signing, and
  nothing is edited after signing. pyHanko validates the app's signed files (intact, whole file).
  Shipped as 0.3.59.
