# Bookies

An Android EPUB reader built around annotations. Books archive to your own Google Drive
when you want the space back, and come home when you tap them — annotations intact, and
searchable the whole time either way.

## Setup

There is **no Gradle wrapper in this repo yet** — generating it needs a Gradle install.
Opening the project in Android Studio creates it automatically, or run `gradle wrapper`
if you install Gradle separately.

1. Install **Android Studio**. It bundles a compatible JDK 21 — the system JDK here is
   24, which the Android Gradle Plugin does not support.
2. Open this folder. Let it sync and install SDK 35.
3. Set up Drive access (below).
4. Run on a device.

## Google Drive setup

The archive feature needs an OAuth client. Once, in the Google Cloud Console:

1. Create a project and **enable the Google Drive API**.
2. **OAuth consent screen** → External. Add the scope
   `https://www.googleapis.com/auth/drive.file`.
3. **Set publishing status to "In production".** This sounds like it should require
   Google's verification review — it does not, because `drive.file` is a non-sensitive
   scope. Leaving the app in "Testing" instead means your refresh token expires every
   7 days and you re-consent constantly.
4. **Credentials** → OAuth client ID → Android. Supply the package name
   (`com.bookies.reader`) and the SHA-1 of your signing certificate:
   ```
   keytool -list -v -keystore ~/.android/debug.keystore -alias androiddebugkey \
     -storepass android -keypass android
   ```
   Repeat with your release keystore when you start sideloading real builds.

`drive.file` means Bookies can only ever see files it created itself. It cannot read the
rest of your Drive, and that is not a policy — it is enforced by the token.

## What's here

| Area | State |
|---|---|
| Redundant anchoring + fuzzy re-anchor (`TextAnchoring`) | Written · **tested** |
| Anchoring test suite | **11 tests passing, mutation-tested** |
| Room schema, DAOs, FTS4 search | Written · compile-checked |
| Bundle format, pack/unpack (`BookBundle`) | Written · **round-trip tested** |
| Markdown export | Written · tested |
| Physical-book schema + `PhysicalBooks` | Written · **tested** |
| EPUB import + OPF metadata parsing | Written |
| Drive REST client, `drive.file` auth | Written |
| Archive/restore state machine | Written |
| Book-opening hinge animation | Written |
| **Readium reader integration** | **Stub — see `ui/reader/ReaderScreen.kt`** |
| Annotation index screen | Not started |
| Barcode scan + Open Library lookup | Not started |
| Page OCR capture | Not started |
| Kindle `My Clippings.txt` import | Not started |

"Tested" above means compiled and executed standalone with `kotlinc` against real Room
and kotlinx-serialization jars — **not** via Gradle, which has never run here. So Room's
`@Query` SQL is unvalidated, and nothing touching Compose, the Android framework or
Readium has been compiled at all. See `CLAUDE.md` for the standalone verification recipe.

Dependency versions in `gradle/libs.versions.toml` are unverified guesses and will likely
need bumping on the first real sync — check the current Readium release on Maven Central.

## Design notes

**Annotations stay on the device when a book is archived.** They are ~2 KB each, so even
a large library is tens of megabytes, and keeping them is what makes one search index
span everything you have ever read rather than only what is currently on the phone. A
copy also travels inside the bundle, so a bundle is self-describing.

**Nothing is deleted locally until the remote copy is verified.** Drive computes its own
MD5 on upload; `ArchiveManager` compares it against the local bundle before touching the
EPUB. A failed archive costs bandwidth, never data.

**Every bundle contains `annotations.md`.** Open a `.bookies` file on any computer — it
is a plain zip — and the notes are readable without this app existing.

**FTS4, not FTS5.** Room has no `@Fts5` annotation, and FTS5 is not guaranteed to be
compiled into the system SQLite on every device. The only real loss is bm25 ranking.

**Physical books share the books table.** A paper book has a cover, a title and
annotations but no spine items and nothing to archive. The only real difference is that
paper has genuine page numbers while an EPUB does not, so `PhysicalBooks.progressionFor`
converts a page number into the same 0.0–1.0 progression an EPUB annotation carries.
After that conversion the shelf, the index screen, the ordering index, FTS search and
Markdown export cannot tell the two kinds apart.

**Navigation is index-first.** Tapping any book opens its annotation index, not the
reader. EPUB annotations offer a jump into the text from there; physical ones do not.
This is what the app was described as doing originally, and it is also what lets paper
books use the same shelf and the same opening animation with no special casing.

**Anchoring is verified, not assumed.** The fuzzy matcher is measured on edition drift
(annotations restored into a differently-built EPUB) and on false positives (quotes from
a different book entirely). Mis-anchors and false positives must both be zero — silently
pointing an annotation at the wrong passage is worse than admitting failure. See
`app/src/test/`.

## Next

Ordered by what unblocks the most. Items 3–6 can be built and largely verified without an
Android SDK; items 1–2 cannot.

1. **Install Android Studio and run a real Gradle sync.** Everything else is gated on
   this, because it is the first time dependency versions, Room's `@Query` validation and
   the Compose/Readium code get checked at all. Expect version bumps.
2. **Wire the Readium navigator** — `ReaderScreen.kt` carries a six-step sketch. Verify
   the API against the resolved dependency; do not write it from memory.
3. **Selection → annotation**, capturing the *full surrounding paragraphs*, not Readium's
   default narrow context window. That width is what makes an annotation readable years
   later with the EPUB long deleted.
4. **The annotation index screen** — grouped by chapter, ordered by
   `(href, progression)`. This is what the cover opens onto, for both book kinds, and it
   is the heart of the app. Wire `BookOpenTransition` into shelf → index while here.
5. **Physical books**: CameraX + ML Kit barcode scanning for the ISBN, then Open Library
   for title/authors/cover/page count (no API key needed):
   `https://openlibrary.org/isbn/{isbn}.json` and
   `https://covers.openlibrary.org/b/isbn/{isbn}-L.jpg`
6. **Page OCR**: ML Kit Text Recognition, on-device and free, with a crop step and an
   edit pass before saving. Typing passages out by hand will not happen in practice, so
   this is what makes paper books actually usable.
7. **Kindle import**: parse `My Clippings.txt` from the device over USB, then run each
   quote through `TextAnchoring.resolve` against the matching EPUB to synthesise a real
   locator. Kindle gives only location numbers and the quote text, so text matching is
   the only bridge — and the code for it already exists and is tested.

Not planned for now: the multi-device sync engine and the web PWA. Drive archive/restore
plus readable `.bookies` bundles covers most of that value with a fraction of the work.

## A "+" button is missing

SAF picking and the share/open intent filter are both wired in `MainActivity`, but no UI
surfaces `promptForEpub()`. Until that exists, import a book by opening an EPUB from a
browser or file manager and choosing Bookies from the share sheet.
