# Bookies — working notes

Android EPUB reader built around annotations. A shelf of covers; tapping one opens the
cover on its spine and reveals that book's annotation index; tapping an annotation jumps
into the passage. Books archive to the user's own Google Drive to free phone space and
restore on demand. Physical (paper) books live on the same shelf with the same index.

Personal app for one reader — the user is building it for a friend. **No Play Store**
(sideload via GitHub Releases + Obtainium), **$0/month**, no billing account.

## Build situation — read this first

**The app builds, and it runs.** As of 2026-09-20 CI is green, there is a working APK,
and it has been installed and launched on an emulator.

Still true: there is **no Android SDK for building, no Android Studio and no Gradle** on
this machine, and the system JDK is 24, which AGP does not support. You **cannot run
`./gradlew` anything**, and there is no wrapper committed.

**`.github/workflows/build.yml` is the compiler.** It installs Gradle 8.9 itself, so the
missing wrapper does not matter, then runs KSP (the only thing that validates Room's
`@Query` SQL), compiles Compose, runs the unit tests and uploads a debug APK. Anything
that cannot be checked locally gets checked by pushing. Watch a run with
`gh run watch <id> --exit-status`; read a failure with `gh run view <id> --log-failed`.

### Running it — the emulator

Installed and working. Deliberately *not* Android Studio: because CI does the building,
only the runtime half is needed, which is ~2.3 GB rather than ~12 GB.

```bash
export ANDROID_HOME=/opt/homebrew/share/android-commandlinetools
$ANDROID_HOME/emulator/emulator -avd bookies        # Pixel 7 / Android 15, arm64
$ANDROID_HOME/platform-tools/adb install -r app-debug.apk
$ANDROID_HOME/platform-tools/adb shell am start -n com.bookies.reader/.MainActivity
$ANDROID_HOME/platform-tools/adb exec-out screencap -p > shot.png
```

Bookies is already installed in that AVD; installs survive emulator restarts. The image
is `system-images;android-35;google_apis;arm64-v8a`, native on this M2 — it boots in
seconds.

**Getting the APK is the awkward part.** Large downloads from GitHub's artifact host
(`*.blob.core.windows.net`) truncate repeatedly here, and `gh run download` hangs; the
Google CDN is fine. Downloading the artifact from the Actions page in a browser works
and is the path of least resistance. Do not work around this by putting `gh auth token`
into a shell command — the user rejected that, correctly, since it exposes the token.

### How to verify work anyway

`kotlinc` 2.4.20 **is** installed (`brew install kotlin`). Most of this codebase is pure
JVM logic and can be compiled and executed standalone. This is the established workflow
here and it has caught real bugs — use it rather than guessing.

Pull the jars you need (Google's Maven for androidx, Maven Central for the rest):

```bash
G=https://dl.google.com/dl/android/maven2
M=https://repo1.maven.org/maven2
curl -sSfLO $G/androidx/room/room-common/2.6.1/room-common-2.6.1.jar
curl -sSfLO $G/androidx/annotation/annotation-jvm/1.7.1/annotation-jvm-1.7.1.jar
curl -sSfLO $M/org/jetbrains/kotlinx/kotlinx-coroutines-core-jvm/1.8.1/kotlinx-coroutines-core-jvm-1.8.1.jar
curl -sSfLO $M/org/jetbrains/kotlinx/kotlinx-serialization-core-jvm/1.7.3/kotlinx-serialization-core-jvm-1.7.3.jar
curl -sSfLO $M/org/jetbrains/kotlinx/kotlinx-serialization-json-jvm/1.7.3/kotlinx-serialization-json-jvm-1.7.3.jar
curl -sSfLO $M/com/squareup/okhttp3/okhttp/4.12.0/okhttp-4.12.0.jar
curl -sSfLO $M/com/squareup/okio/okio-jvm/3.6.0/okio-jvm-3.6.0.jar
curl -sSfLO $M/junit/junit/4.13.2/junit-4.13.2.jar
curl -sSfLO $M/org/hamcrest/hamcrest-core/1.3/hamcrest-core-1.3.jar
```

Compile the schema and logic layers (needs the serialization plugin for `@Serializable`):

```bash
PLUGIN=/opt/homebrew/Cellar/kotlin/2.4.20/libexec/lib/kotlinx-serialization-compiler-plugin.jar
kotlinc <files...> -Xplugin=$PLUGIN -cp "$(ls *.jar | tr '\n' ':')" -d out.jar
```

Run the unit tests. Three suites now, and they need different classpaths — the epub
suite grew past the original "just JUnit" invocation when `KindleClippingsTest` started
needing the Room-annotated entities, so the `*.kt` glob no longer compiles on its own.
`Anchor` still needs its two `@Serializable` annotations stripped into a temp copy; the
annotation has no bearing on the logic.

```bash
JARS="$(ls *.jar | tr '\n' ':')"
sed -e '/^import kotlinx.serialization.Serializable$/d' -e '/^@Serializable$/d' \
    app/src/main/java/com/bookies/reader/data/model/Anchor.kt > /tmp/Anchor.kt

# epub: anchoring + Kindle clippings (33 tests)
kotlinc app/src/main/java/com/bookies/reader/epub/TextAnchoring.kt \
        app/src/main/java/com/bookies/reader/epub/KindleClippings.kt \
        app/src/main/java/com/bookies/reader/data/db/Entities.kt \
        app/src/main/java/com/bookies/reader/data/model/Types.kt /tmp/Anchor.kt \
        app/src/test/java/com/bookies/reader/epub/*.kt \
        -cp "$JARS" -include-runtime -d epub.jar
java -cp "epub.jar:$JARS" org.junit.runner.JUnitCore \
     com.bookies.reader.epub.TextAnchoringTest \
     com.bookies.reader.epub.AnchoringPropertyTest \
     com.bookies.reader.epub.EditionDriftTest \
     com.bookies.reader.epub.FalsePositiveTest \
     com.bookies.reader.epub.KindleClippingsTest

# annotation index logic (17 tests)
kotlinc app/src/main/java/com/bookies/reader/ui/index/IndexModel.kt \
        app/src/main/java/com/bookies/reader/data/db/Entities.kt \
        app/src/main/java/com/bookies/reader/data/model/Types.kt \
        app/src/test/java/com/bookies/reader/ui/index/IndexModelTest.kt \
        -cp "$JARS" -include-runtime -d index.jar
java -cp "index.jar:$JARS" org.junit.runner.JUnitCore \
     com.bookies.reader.ui.index.IndexModelTest

# Open Library lookup (23 tests) — needs the serialization plugin
kotlinc app/src/main/java/com/bookies/reader/data/repo/OpenLibrary.kt \
        app/src/test/java/com/bookies/reader/data/repo/OpenLibraryTest.kt \
        -Xplugin=$PLUGIN -cp "$JARS" -include-runtime -d ol.jar
java -cp "ol.jar:$JARS" org.junit.runner.JUnitCore \
     com.bookies.reader.data.repo.OpenLibraryTest
```

Expected: `OK (33 tests)`, `OK (17 tests)`, `OK (23 tests)` — 73 in all.

**Keep source files free of raw control bytes.** A literal NUL written into a string
literal compiles fine and then makes the whole file binary to git, grep and diff, so it
silently drops out of code review. Write `"\u0000"`, not the byte.

### Reading an Android-only API without the SDK

An AAR is a zip with a `classes.jar` in it, so a library's real signatures can be read
without resolving anything. This is how the Readium integration in `ReaderScreen` was
checked — 3.3 MB of downloads against a 9 GB install, and it caught a method that no
longer exists:

```bash
curl -sSfLO https://repo1.maven.org/maven2/org/readium/kotlin-toolkit/readium-navigator/3.0.3/readium-navigator-3.0.3.aar
unzip -oq readium-navigator-3.0.3.aar classes.jar && mv classes.jar navigator.jar
javap -cp navigator.jar org.readium.r2.navigator.epub.EpubNavigatorFactory
```

Use it before writing against any Android library whose API you cannot otherwise see.
Version pins can be checked even more cheaply, with a `curl` against the POM.

Work in the session scratchpad, never in the project tree. **What this does NOT catch:**
Room's KSP processor never runs, so `@Query` SQL is unvalidated. Anything touching
Compose, Android framework classes or Readium cannot be compiled here at all.

## Invariants — do not break these

1. **Nothing is deleted locally until the remote copy is verified.** `ArchiveManager`
   compares Drive's server-computed MD5 against the local bundle before touching the
   EPUB. A failed archive must cost bandwidth, never data.
2. **Zero mis-anchors, zero false positives.** `TextAnchoring` resolving an annotation to
   the *wrong* passage is worse than failing outright. The test suite asserts both are
   exactly 0 and is mutation-tested — three seeded mutants (offset off-by-one, threshold
   at 0.0, single-probe fuzzy) are all caught. If you "simplify" the multi-probe logic in
   `fuzzy()`, `EditionDriftTest` will fail; that is deliberate.
3. **`drive.file` scope only.** Never widen to `drive` or `drive.readonly` — those are
   sensitive scopes requiring a multi-week Google verification review, and `drive.file`
   already does everything needed. It also means the app provably cannot read the rest of
   the user's Drive.
4. **Physical and digital books share one table.** `PhysicalBooks.progressionFor` turns a
   page number into the same 0.0–1.0 progression an EPUB annotation carries. Never branch
   on `BookFormat` downstream of that conversion — the point is that ordering, search,
   the index screen and export cannot tell them apart.
5. **Tombstones, never hard deletes.** `deletedAt` on books and annotations, so a future
   sync can propagate removals.
6. **Annotations stay on the device when a book is archived.** ~2 KB each; keeping them
   is what makes one search index span the whole library rather than only what is
   currently on the phone. A copy also goes in the bundle.

## Layout

```
data/model/     Anchor (W3C-shaped, redundant), BookFormat, StorageState, enums
data/db/        Room entities, DAOs, FTS4 search, converters
data/repo/      FileStore (app-private paths, SHA-256), PhysicalBooks,
                OpenLibrary (ISBN -> metadata, pure parse behind a Fetcher seam)
epub/           TextAnchoring (fuzzy re-anchor), BookBundle (.bookies zip),
                BundleFormat (on-disk contract), EpubImporter (hand-rolled OPF parser),
                KindleClippings (My Clippings.txt parser + revision dedup)
drive/          DriveAuth (AuthorizationClient), DriveClient (REST v3 over OkHttp),
                ArchiveManager (archive/restore state machine)
export/         MarkdownExporter
ui/shelf/       ShelfScreen, ShelfViewModel, BookOpenAnimation (the hinge)
ui/index/       IndexModel (all the logic, Compose-free so it is testable),
                AnnotationIndexScreen (layout only)
ui/reader/      ReaderScreen — THE ONE REAL STUB
```

## Conventions

- Manual DI in `BookiesApp`; no Hilt. One user, small graph.
- IDs are client-generated UUIDs, always — never database-assigned.
- Enums persist as `name`, not ordinal, so reordering cannot corrupt data.
- Room migrations are real; `fallbackToDestructiveMigration` is never acceptable, because
  this database *is* the library.
- `BundleFormat` is a contract with every bundle ever written. Change it additively only,
  and bump `BUNDLE_FORMAT_VERSION`.
- Comments explain *why*, not *what*. Match the existing density.

## Current state

Written and verified by standalone compile + execution: schema, DAOs, anchoring (11
tests, mutation-tested), bundle pack/unpack, Markdown export, EPUB import/OPF parsing,
Drive client, archive/restore, physical books, the hinge animation, Kindle clippings
import (22 tests, mutation-tested against 16 seeded mutants), the annotation index's
logic (17 tests), and Open Library ISBN lookup (23 tests).

Open Library's fixtures came from its published API docs, not a live response — the
service was unreachable when they were written, so `number_of_pages` in particular is
worth re-checking on the first real run.

**Not started:** the barcode scanner (ML Kit, needs a device), ML Kit page OCR.

**Proven on a device (2026-09-20):** the app launches without crashing, Room builds its
schema (`bookies.db` + WAL present), `Theme.kt` applies (the FAB renders spine brown, not
Material purple), and the empty shelf state reads correctly.

**Written, compiles, but never exercised:** the whole shelf → hinge → index → reader flow.
Nothing has been tapped, because no book has been imported yet. This is the single
biggest untested area and the next thing to do.

**Stubbed:** `ui/reader/ReaderScreen.kt`. Its six-step sketch is verified signature by
signature against Readium 3.0.3 (see the AAR recipe above), so what remains is Compose and
fragment glue. `AndroidFragment` + `FragmentFactory` across configuration changes is the
one genuinely open question.

Dependency versions in `gradle/libs.versions.toml` all resolve and all build. Readium is
pinned at 3.0.3 while 3.4.0 is current.

## Known issues, in rough priority order

1. **`InputDispatcher: Dropping untrusted touch event ... obscuring opacity = 1.00`** fires
   repeatedly on the shelf. Android is refusing touches because something opaque covers the
   target. Prime suspect is `BookOpenTransition`'s full-screen `leaf`, which would make
   tapping a cover silently do nothing. Unconfirmed — needs a book on the shelf to test.
2. **Import outcomes are swallowed.** `EpubImporter.Outcome` is discarded on both the
   picker and intent routes, so `Failed`, `EditionConflict` and `AlreadyPresent` are
   invisible: share a corrupt EPUB and nothing happens, with no explanation.
3. **A failed restore leaves the cover stuck** at 0.3, because `clearTransfer` drops the
   entry on failure as well as success. Carries its own TODO.
4. **The status bar is illegible** — light icons over the cream background, because the
   theme never declares its light/dark appearance.
5. `DriveAuth.accessToken` exceptions are swallowed by a `runCatching` in MainActivity.
6. **Drive has never been exercised end to end.** `ArchiveManager` and `DriveClient` are
   written and have never spoken to Google. Needs a Cloud project, the consent screen set
   to "In production", `drive.file` scope, and the signing key's SHA-1 registered — all
   user tasks.

## Gotchas already hit

- Room has no `@Fts5`; FTS5 is not guaranteed present in system SQLite. Use `@Fts4`.
- All three Readium artifacts declare **core library desugaring required** in their AAR
  metadata. `CheckAarMetadata` refuses to configure the build without it, before compiling
  anything, and it fires regardless of minSdk 26 already providing `java.time`.
- `stickyHeader` is an abstract **member** of `LazyListScope`, not a top-level extension.
  Importing it cannot resolve; no import is needed. Its `key` parameter does exist.
- `lifecycle-viewmodel-compose` does **not** bring `lifecycle-runtime-compose`, which is
  where `collectAsStateWithLifecycle` lives — it only *constrains* it in
  `dependencyManagement`. Reading a POM, strip that block before believing the dep list.
- **The local kotlinc workflow does not reproduce the coroutines runtime Gradle assembles.**
  An exception crossing `withContext` comes back as a *copy* under Gradle (stack-trace
  recovery), so asserting exception identity passes locally and fails in CI. Assert on type
  and message. Treat local green as a strong signal, not proof.
- Readium's `Locator.Locations` has **both** `progression` and `totalProgression`. Ours is
  always the whole-book one — `progression` is progress within the current spine item, and
  using it would put chapter 2 of 12 at 80%, then write that into the db, the bundles and
  every export, and scramble the index's reading-order sort.
- `EpubNavigatorFragment.createFactory(...)` is 2.x and gone. 3.x builds the fragment
  factory from `EpubNavigatorFactory(publication, config).createFragmentFactory(...)`.
- Credential Manager returns an **ID token**, not a Drive access token. Scoped access
  comes from `AuthorizationClient` (play-services-auth). Most tutorials predate the split
  and use the deprecated `GoogleSignInClient`.
- EPUBs have **no page numbers** — reflowable HTML. Never anchor on a page number for a
  digital book. Paper books genuinely do have them.
- `rotationY` past 90° shows a mirrored back face; `BookOpenAnimation` swaps in a
  counter-flipped endpaper at the halfway point.
- Google's OAuth consent screen should be set to **"In production"**, not "Testing" —
  Testing expires refresh tokens every 7 days. `drive.file` being non-sensitive means
  production needs no review.
