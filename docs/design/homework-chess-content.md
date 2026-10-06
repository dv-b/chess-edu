# Chess content in homework — design notes

**Status:** exploratory. Not committed; this doc exists so we can settle on a
shape before writing any code.

## The gap

Today `homework` has exactly one authored field: `instructions TEXT`. A teacher
writing "solve this Bxh7+ sacrifice" has no way to actually attach the
position they mean. The product is a chess-education platform; the thing
chess teachers put in front of students is, almost by definition, a chess
position or a move sequence. Plain prose is the wrong primary field.

What we need is a way for a teacher to author — and for a student (and
parent, later) to view — structured chess content inside a homework: at
minimum a starting position, optionally a sequence of moves, optionally an
expected solution.

## What chess homework actually is

Before reaching for a format, it's worth enumerating what teachers in a
chess school actually assign. From watching real chess curricula:

- **"Study this position."** One FEN. Often "White to move, understand the
  threats." Passive — student reads, doesn't input anything.
- **"Solve this tactic."** Same shape as above, but with a correct answer
  the student is expected to find. "Mate in 2", "win material", etc.
- **"Memorise this line."** A starting position (usually the initial
  position, sometimes a specific opening) plus a sequence of moves. No
  single right answer — just "play through it and understand each move."
- **"Annotate this game."** A full PGN the student reads, thinks about, and
  writes back notes. Much closer to an essay prompt.
- **"Play this endgame against me / the computer."** Requires an interactive
  opponent. Out of scope for v1 — we're not shipping an engine.
- **"Watch this lesson video and do these three puzzles."** Mixed media;
  homework is more than one block.

The dominant patterns for the next six months are the first three. The
fourth is common but largely text-driven from the teacher's side. The fifth
and sixth are nice-to-haves we can park.

Two observations:

1. **Position alone** and **position + moves** are the same primitive with
   the moves list empty in one case. We don't need two separate concepts.
2. **Multi-exercise homework** is normal. "Do five puzzles" is one
   assignment. The data model must not assume one block per homework.

## The standard chess data formats

- **FEN** (Forsyth–Edwards Notation) — one line that fully describes a
  position: piece placement, side to move, castling rights, en passant
  target, halfmove clock, fullmove number. Example:
  `rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1`. Universally
  supported. This is what we store for "study this position".
- **SAN** (Standard Algebraic Notation) — individual moves as chess players
  write them: `Nf3`, `O-O`, `Bxf7+`, `e8=Q#`. Short, human-readable, but
  *context-dependent*: `Nf3` only resolves once you know which position
  it's played from. Storing SAN without a starting FEN is a trap.
- **UCI** move notation — `e2e4`, `g1f3`, `e7e8q`. Context-free and therefore
  safer to store. Engine-friendly. Less human-readable. Useful as the
  canonical wire format, with SAN generated for display.
- **PGN** (Portable Game Notation) — the whole-game envelope: tagged header
  (Event, Site, White, Black, Result, optional `FEN`/`SetUp` for non-initial
  start positions), then a SAN movelist with optional `{comments}`, NAGs
  (`$1`), and parenthesised variations. This is the lingua franca: every
  tool (Lichess, Chess.com, ChessBase, Stockfish output) speaks PGN.
  Importantly, PGN *can* embed non-initial start positions via the
  `[FEN "..."]` + `[SetUp "1"]` tags, so a well-formed PGN covers both
  "a position" and "a position + moves" without us having to invent anything.

There is a strong argument for PGN as the canonical authored format:
teachers already know it, every external tool exports it, and we can
import a single `.pgn` blob and extract `(starting_fen, moves[])` from it.
Lichess uses PGN as the import/export currency for its Study feature,
which is the closest existing product to what we're building — and its
authors clearly landed on PGN for good reason
([Lichess paste](https://lichess.org/paste),
[Study PGN import](https://lichess.org/forum/lichess-feedback/pgn-in-studies)).

The right split is: **PGN is the authoring/import format, but we normalise
to `(starting_fen, uci_moves[])` at rest** — cheaper to index, easier to
render, and we don't have to re-parse SAN on every read.

## Library landscape

### Frontend (TypeScript / React 19)

- [**chess.js**](https://www.npmjs.com/package/chess.js) — the de facto
  logic library. FEN + PGN parsing, legal move generation, SAN↔UCI,
  check/checkmate detection. ~70k weekly downloads, ~4k stars; still the
  reference. Zero UI. We'll want this on both the author (validation) and
  student (play-through) side.
- [**chessground**](https://github.com/lichess-org/chessground) — Lichess's
  own board UI, extracted as a standalone component. 10 KB gzipped, no
  dependencies, no chess logic (pairs with chess.js). Premium look and
  feel, supports arrows / drawing / custom shapes, mobile drag-drop.
  Framework-agnostic; there are React wrappers but they're thin.
- [**react-chessboard**](https://www.npmjs.com/package/react-chessboard) —
  all-in-one React component. Smaller community (~3.5k weekly downloads,
  ~1.3k stars) but far less plumbing than wiring chessground up yourself.
  Reasonable for v1.

For v1 I'd reach for **chess.js + react-chessboard**. If the board
interactions or theming prove limiting we can swap in **chessground** later;
the `(starting_fen, uci_moves[])` representation is framework-neutral.

### Backend (Java 21 / Spring Boot)

- [**bhlangonijr/chesslib**](https://github.com/bhlangonijr/chesslib) —
  Apache 2.0, Maven coordinates
  `com.github.bhlangonijr:chesslib:1.3.6`. Bitboard-based move generation,
  FEN + PGN parsing, move validation. Actively maintained; handles large
  PGN streams without blowing the heap
  ([Maven Central](https://mvnrepository.com/artifact/com.github.bhlangonijr/chesslib)).

We want server-side validation, not just client-side. A teacher pasting a
FEN should get it rejected at the API if the position is illegal (two
white kings, pawn on the first rank, black to move but white in check,
etc.). Likewise we should re-verify that every move in `uci_moves[]` is
legal from the resulting position. chesslib gives us that for free.

## Data model — four options

### Option A — attachment table (one-to-many, typed)

```
CREATE TABLE homework_resource (
    id            UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    homework_id   UUID NOT NULL REFERENCES homework(id) ON DELETE CASCADE,
    position      INT  NOT NULL,                      -- order within homework
    kind          VARCHAR(24) NOT NULL,               -- 'BOARD' | 'TEXT' | ...
    title         VARCHAR(200),
    payload       JSONB NOT NULL,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),

    UNIQUE (homework_id, position)
);
```

For a `BOARD` resource, `payload` carries:

```json
{
  "startingFen": "rnbqkb1r/ppp2ppp/5n2/3pp3/3P4/2N2N2/PPP1PPPP/R1BQKB1R w KQkq - 0 4",
  "moves": ["d4e5", "f6g4"],
  "orientation": "white",
  "solution": { "uci": ["g4e5", "f3e5"], "kind": "first-move" }
}
```

Pros: structurally clean, easy to add new `kind`s (quiz, video, image)
later, easy to query ("homeworks that use position X"). Cons: extra table,
extra round-trips unless we batch-fetch.

### Option B — inline JSONB blocks on `homework.content`

Add a single `content JSONB` column holding a document:

```json
{
  "blocks": [
    { "type": "text", "text": "Warm-up." },
    { "type": "board", "startingFen": "...", "moves": [] },
    { "type": "text", "text": "Now solve this:" },
    { "type": "board", "startingFen": "...", "solution": ["Bxh7+", "Kxh7", "Ng5+"] }
  ]
}
```

Pros: single row, atomic edits, feels document-like (good if we want a
rich-text editor). Cons: reporting and filtering become harder ("count
homeworks using the King's Indian" requires JSONB path queries), and
validation sprawls across block types inside a single handler.

### Option C — typed columns on `homework`

Just add `starting_fen TEXT`, `moves TEXT[]`, `orientation VARCHAR(8)`,
`solution JSONB` to the existing table.

Pros: simplest possible migration; the cheapest v1. Cons: hard-caps us at
one board per homework. Teachers *will* ask for multi-puzzle homeworks
and we'll have to migrate anyway.

### Option D — markdown with embedded references

`instructions` stays, but we teach it special tokens like
`[[board:<id>]]` pointing at rows in `homework_resource`. The editor
renders them inline.

Pros: feels like Notion / Medium for the teacher. Cons: the parsing /
rendering pipeline is surprisingly deep (Markdown + custom tokens + XSS
story), and we don't have a rich-text editor yet. Revisit later.

### Recommendation

**Ship Option A**, with `kind = 'BOARD'` as the only type we handle in v1.
It doesn't box us in, it doesn't require inventing a document model before
we've committed to one, and it leaves `homework.instructions` alone as
free-form prose that sits above the board blocks. When we eventually do
want a quiz block or a video block, we add a `kind` and a payload schema
— no migration.

The `instructions` field stays as-is: it's the prose that frames the
exercise ("warm-up before we meet on Thursday"), not the exercise itself.

## Concrete v1 scope

- Migration `V007__homework_resource.sql`: the table above.
- Entity `HomeworkResource` + repo `HomeworkResourceRepository`.
- DTO additions:
  - `CreateHomeworkResource { position, kind, title, payload }`
  - `HomeworkResourceView` in `HomeworkDetail`, ordered by `position`.
  - `CreateHomeworkRequest` / `UpdateHomeworkRequest` gain an optional
    `resources: List<CreateHomeworkResource>` that, when present,
    *replaces* the full list (simpler than per-resource CRUD for v1).
- Server-side validation with chesslib:
  - Parse `startingFen`; reject if illegal.
  - Fold `moves[]` through `Board.doMove(...)`; reject on the first illegal
    move with `ErrorCode.INVALID` and a detail carrying the index.
  - Same for `solution.uci` if present.
- Admin UI (`homework-panels.tsx`):
  - In the new-homework form and edit form, add a "Chess content" section
    under Instructions.
  - Each board row: a small chessground/react-chessboard in setup mode,
    a "Paste FEN" field, a "Paste PGN" field (we parse with chess.js and
    split into `startingFen` + `moves`), orientation toggle, and an
    optional "expected first move" picker for solve-type exercises.
  - Add/remove/reorder buttons. Keep it flat — no nested resources.
- Student UI (not yet built; Task #21-ish): a read-only board viewer with
  next/prev arrows for the move list, flipped to the right orientation.
  No "solve" interaction in v1 — the student still marks DONE/SKIPPED
  themselves from the current sheet. Grading comes later.
- Parent UI: same read-only viewer, embedded in the homework tile on the
  parent dashboard.

## Author UX — where most of the effort goes

Teachers are not developers. FEN strings are not something they'll type.
The three entry points that matter:

1. **Paste a PGN.** This is the 80% case. Lichess, Chess.com, ChessBase,
   and every analysis tool export PGN on demand. Our input is a textarea;
   we parse with chess.js and derive `(startingFen, moves[])`. We also
   show the resulting position on the board so they can sanity-check
   before saving.
2. **Paste a FEN.** The 15% case — pasting from an FEN-giving source
   (a puzzle site URL, an engine output). Again, preview on the board.
3. **Set up a position on the board.** The 5% case where the teacher is
   inventing a position from scratch. This is the heaviest lift — it
   requires a palette of pieces, drag-and-drop, and a "set side to move /
   castling / en passant" strip. chessground has a supported setup mode;
   react-chessboard has a less polished one. If this is slow to build we
   can ship without it and rely on "paste FEN from Lichess's board editor"
   as a workaround. Lichess's editor is URL-shareable, so the teacher can
   set the position there, copy the FEN from the URL, and paste it back.

I'd ship (1) and (2) in v1 and defer (3) until we have a design partner
telling us they need it.

## Student UX

A student opens a homework and sees:

- The instructions prose (unchanged).
- A stack of board cards, one per `HomeworkResource`.
- Each card has:
  - The board, rendered at the starting position and oriented per the
    resource's `orientation`.
  - A move strip with `◀ prev`, `▶ next`, `⏭ to end` arrow controls,
    showing the SAN move list (we generate SAN at render time from the
    UCI list and starting FEN).
  - An optional "Show solution" reveal if `solution` is set.
- The existing DONE/SKIPPED chip stays where it is (the student still
  self-reports). Solve-and-grade is a later story.

## Backwards compatibility

Everything we've built for Task #20 keeps working: `instructions` stays,
the existing submission flow stays, the existing homework rows render
unchanged. A homework with zero resources renders exactly as today. The
tally on the admin row (`done / skipped / unmarked`) doesn't change.

The only touch point for the existing code is the `HomeworkDetail` DTO
growing a `resources` array, and the server actions / forms knowing how
to send / receive it. That's a purely additive change.

## Open questions

- **Notation locale.** Russian chess books use Russian piece letters
  (`Кf3`, `Сg2`). Do we offer a locale toggle on SAN display? For v1 I'd
  render in English SAN everywhere — it's what every Uzbek chess coach I
  know uses in practice, and it matches the PGN they'll be pasting in.
  Revisit if users push back.
- **Variations and comments in PGN.** Real teaching PGNs have sidelines
  (`(4. Nxe5 d6 5. Nf3)`) and `{commentary}`. chess.js + chessground
  handle them; our `(startingFen, uci_moves[])` normalisation does not.
  Decision: in v1 we parse the mainline only and silently drop sidelines
  + comments, with a warning in the import UI. If this proves too
  restrictive we migrate to a tree representation later (payload becomes
  a move-tree, not a move-list).
- **Mobile boards.** Parents will view this on phones. The default
  chessground / react-chessboard sizes do fine down to ~320px; we just
  need responsive CSS on the card so it doesn't overflow.
- **Interactive solve / grading.** Teachers will want "did the student
  find the right move?" This is a bigger product decision — do we ship
  a grading engine? With what authority? For now, keep homework as
  self-report (DONE / SKIPPED) and add grading as a separate epic once
  we've validated the read-only flow with real classes.
- **Puzzle reuse.** Eventually the school will want a library of
  positions that multiple homeworks can reference, not ad-hoc per-row.
  Option A's `homework_resource` + a future `resource_template` table
  handles that cleanly.

## Rough effort

Call it a two-task epic:

- **Task: "Homework resources schema + backend"** — migration, entity,
  repo, DTOs, service wiring, chesslib validation, integration test.
  ~1 day.
- **Task: "Homework chess content UI"** — admin board editor (paste
  FEN / PGN + preview), student read-only viewer, CSS. ~2 days.

The multi-exercise-per-homework, PGN import, and server-side validation
are the meat. Setup-mode board editing is where it could grow if we let
it, so we should scope it explicitly out of v1.

## Sources

- [chessground (Lichess)](https://github.com/lichess-org/chessground)
- [chess.js (npm)](https://www.npmjs.com/package/chess.js)
- [react-chessboard (npm)](https://www.npmjs.com/package/react-chessboard)
- [bhlangonijr/chesslib (GitHub)](https://github.com/bhlangonijr/chesslib)
- [chesslib on Maven Central](https://mvnrepository.com/artifact/com.github.bhlangonijr/chesslib)
- [Lichess Paste PGN](https://lichess.org/paste)
- [Lichess Study PGN import thread](https://lichess.org/forum/lichess-feedback/pgn-in-studies)
