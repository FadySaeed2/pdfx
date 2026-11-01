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

### Phase 1 — library and file management (this build)
All PDFs on the phone found automatically (All files access; personal app, not on Play), folders,
favourites, tags, thumbnails, rename, move, copy, delete to a recycle bin, sort and filter, and a
full-text index so search finds words inside documents, not only file names.

### Phase 2 — the reader
Tiled rendering so zoom is sharp at any level, fast scrubber for long files, single-page and
two-page landscape modes, outline (table of contents), bookmarks, search with highlighted hits,
text selection and copy, links, night mode for pages that keeps photos natural, reflow mode for
small print.

### Phase 3 — annotation and S Pen
Highlight, underline and strike that snap to text; notes; freehand ink with pressure and palm
rejection; shapes, arrows, text boxes, stamps; eraser; unlimited undo and redo; a panel listing every
annotation; flatten.

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
