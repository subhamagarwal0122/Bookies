# Handoff — 2026-09-20

Written for the next session. `CLAUDE.md` holds the durable project rules; this file is
the snapshot of where things stood when this one ended. Delete it once it is stale.

## Where the project got to

Bookies went from "a set of components nobody had ever compiled" to **a running app** in
one session. The repo is live at https://github.com/subhamagarwal0122/Bookies (public),
CI is green, and an APK has been installed and launched on an emulator.

The single most useful thing to know: **the shelf → cover-opens → index flow has never
been tapped.** No book has been imported on a device. That is the next task and it is
where the risk is concentrated.

## What was done, in order

1. Built an interactive web prototype of shelf/hinge/index to settle layout before
   writing Compose: https://claude.ai/artifact/5inupfBjTDcdtnJhvEDdo9
2. Verified the Readium API by reading its AAR with `javap` — 3.3 MB of downloads instead
   of a 9 GB SDK. Caught `EpubNavigatorFragment.createFactory` being gone in 3.x, and the
   `progression` vs `totalProgression` trap. `ReaderScreen`'s sketch is now accurate.
3. Set up CI, which is what made everything else checkable.
4. Ran four subagents in parallel: Kindle clippings import, Open Library lookup, the
   annotation index screen, and the app-wiring fixes.
5. Drove CI from red to green over four runs, then installed and launched the APK.

## Test suites — 73 tests, all local-runnable

`CLAUDE.md` has the exact commands. 33 in `epub/` (anchoring + Kindle), 17 for
`IndexModel`, 23 for `OpenLibrary`. CI runs all of them on every push.

Note the pattern worth keeping: **logic that must be verified lives in a Compose-free
file.** `ui/index/IndexModel.kt` exists because Compose cannot be compiled locally, and
invariant 4 was too important to leave unverified until CI. Do the same for the reader.

## Bugs found by reading rather than running

Worth recording because none of these would have shown up in a test:

- The share-sheet target silently dropped every file — the manifest declared `ACTION_SEND`
  but `onCreate` only read `intent.data`, and SEND carries its URI in `EXTRA_STREAM`.
- `promptForEpub()` had zero callers, so a fresh install had no way to add a book.
- A raw NUL byte in a string literal made a 489-line source file binary to git and grep;
  it would have landed in the repo invisible to code review.

## Immediate next steps

1. **Import a book and tap it.** Push a public-domain EPUB, exercise shelf → hinge →
   index. Watch logcat for the `untrusted touch` warning (issue 1 in CLAUDE.md) — if taps
   are being eaten, a dead tap and a broken animation look identical from the outside.
2. Fix whatever that turns up, plus issues 2-4 in CLAUDE.md, which all want the same
   small error-surface (a snackbar).
3. Write `ReaderScreen` against the verified signatures.
4. Drive end to end. Needs user-side setup: Cloud project, consent screen "In production",
   `drive.file`, signing key SHA-1.
5. Release signing + a Releases workflow, so Obtainium can see builds.

## Open design questions nobody has answered

Raised while building the index screen; reasonable defaults are in place, so these are
refinements rather than blockers:

- Chapter headers stick while scrolling. At 200 highlights, is that enough navigation?
- Bookmarks carry no text. Do they earn a row at all?
- Search is per-book, but the FTS table spans the library. Where does library-wide search
  live — the shelf, or only here?
- Paper annotations never had a highlight colour. Should their colour rail be absent
  rather than defaulted to the theme primary?

## Things to be careful about

- The local `kotlinc` workflow is a strong signal, not proof. It does not reproduce the
  coroutines runtime, and a passing local test has already failed in CI for that reason.
- Subagents writing Compose cannot verify their work. It went fine here — ~700 blind lines
  compiled with one error — but only because each was told to report every API it was
  unsure of, and the one failure was on that list. Keep demanding those lists.
- Do not put `gh auth token` into a shell command to work around the artifact-download
  problem. Download from the browser instead.
