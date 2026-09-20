# Bookies — working notes

Android EPUB reader built around annotations. A shelf of covers; tapping one opens the
cover on its spine and reveals that book's annotation index; tapping an annotation jumps
into the passage. Books archive to the user's own Google Drive to free phone space and
restore on demand. Physical (paper) books live on the same shelf with the same index.

Personal app for one reader — the user is building it for a friend. **No Play Store**
(sideload via GitHub Releases + Obtainium), **$0/month**, no billing account.

## Build situation — read this first

**There is no Android SDK, no Android Studio and no Gradle on this machine.** System JDK
is 24, which AGP does not support. You therefore **cannot run `./gradlew` anything**.
Do not try to install the SDK; a working setup is ~12-14 GB (Studio, SDK, an emulator
image, Gradle caches) on a volume with ~14 GB free as of 2026-09-20, and it is the
user's call.

There is also no Gradle wrapper committed — Android Studio generates it on first open.
**`.github/workflows/build.yml` is the real compiler**: it installs Gradle 8.9 itself
(so the missing wrapper does not matter), runs KSP, compiles Compose and uploads a debug
APK. Anything that cannot be checked locally gets checked by pushing.

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
curl -sSfLO $M/junit/junit/4.13.2/junit-4.13.2.jar
curl -sSfLO $M/org/hamcrest/hamcrest-core/1.3/hamcrest-core-1.3.jar
```

Compile the schema and logic layers (needs the serialization plugin for `@Serializable`):

```bash
PLUGIN=/opt/homebrew/Cellar/kotlin/2.4.20/libexec/lib/kotlinx-serialization-compiler-plugin.jar
kotlinc <files...> -Xplugin=$PLUGIN -cp "$(ls *.jar | tr '\n' ':')" -d out.jar
```

Run the unit tests (`TextAnchoring` needs no plugin; `Anchor` only needs its two
`@Serializable` annotations stripped into a temp copy — the annotation has no bearing on
the logic):

```bash
sed -e '/^import kotlinx.serialization.Serializable$/d' -e '/^@Serializable$/d' \
    app/src/main/java/com/bookies/reader/data/model/Anchor.kt > /tmp/Anchor.kt
kotlinc app/src/main/java/com/bookies/reader/epub/TextAnchoring.kt /tmp/Anchor.kt \
        app/src/test/java/com/bookies/reader/epub/*.kt \
        -cp "junit-4.13.2.jar:hamcrest-core-1.3.jar" -include-runtime -d t.jar
java -cp "t.jar:junit-4.13.2.jar:hamcrest-core-1.3.jar" org.junit.runner.JUnitCore \
     com.bookies.reader.epub.TextAnchoringTest \
     com.bookies.reader.epub.AnchoringPropertyTest \
     com.bookies.reader.epub.EditionDriftTest \
     com.bookies.reader.epub.FalsePositiveTest
```

Expected: `OK (11 tests)`.

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
data/repo/      FileStore (app-private paths, SHA-256), PhysicalBooks
epub/           TextAnchoring (fuzzy re-anchor), BookBundle (.bookies zip),
                BundleFormat (on-disk contract), EpubImporter (hand-rolled OPF parser)
drive/          DriveAuth (AuthorizationClient), DriveClient (REST v3 over OkHttp),
                ArchiveManager (archive/restore state machine)
export/         MarkdownExporter
ui/shelf/       ShelfScreen, ShelfViewModel, BookOpenAnimation (the hinge)
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
tests passing, mutation-tested), bundle pack/unpack, Markdown export, EPUB import/OPF
parsing, Drive client, archive/restore, physical books, the hinge animation.

**Not started:** annotation index screen (what the cover opens onto), barcode scan +
Open Library lookup, ML Kit page OCR, Kindle `My Clippings.txt` import.

**Stubbed:** `ui/reader/ReaderScreen.kt` — the Readium navigator. Its six-step sketch has
now been **verified signature by signature against Readium 3.0.3** (see the AAR recipe
above), so what is left is Compose/fragment glue that needs a compiler, not guesswork.
Write it once CI is green rather than before.

Dependency versions in `gradle/libs.versions.toml` all resolve as of 2026-09-20 — every
pin was checked against Google's Maven and Maven Central. They are no longer guesses,
though Readium is pinned at 3.0.3 while 3.4.0 is current.

## Gotchas already hit

- Room has no `@Fts5`; FTS5 is not guaranteed present in system SQLite. Use `@Fts4`.
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
