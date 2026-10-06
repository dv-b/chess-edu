# Student-submitted solution moves — UX brief

**Status:** UX brief for a design model. Not implementation-ready yet.

## Purpose of this document

This document is the **input** to a design pass. It's written for a
design-focused LLM (or a human designer) that has **not seen the
codebase**. The output that comes back — screen layouts, interaction
specs, empty / error / loading states, visual hierarchy — will then be
handed to an engineering model for implementation.

So this doc deliberately:

- Focuses on what the **user experiences**, not the database schema.
- States **product decisions** that are already made (so they don't get
  re-opened), and **technical constraints** that bound the design space.
- Lists **open questions** the design pass should answer.

Where backend detail appears, it's only to frame what the UI can and
can't assume.

## What the product is (10-second version)

Kingside is a B2B SaaS chess-education platform for schools in
Uzbekistan (trilingual: English, Russian, Uzbek). Teachers assign
homework to students through a web app. Parents are part of the loop.
Everything is rendered in a custom look-and-feel (see existing screens
below — the design pass must stay visually consistent with those).

Roles involved in this feature:

- **Student** — ages roughly 7–16. Logs in on a laptop or phone and
  does their homework. Not a power user.
- **Teacher / school admin** — runs a class, authors homework, reviews
  what students did. On a laptop.
- **Parent** — not in scope for this feature yet (called out under
  non-goals).

## Current state — what already ships

A homework assignment can contain one or more **board blocks**. Each
block is authored by the teacher and carries:

- A **starting position** (any legal chess FEN).
- An optional **move prefix** — a sequence of UCI moves the teacher
  plays first, to set the stage. Example: "after `1.e4 e5 2.Nf3`,
  White to play." The prefix can be empty.
- An optional **orientation** ("white at bottom" or "black at bottom")
  — purely a viewing preference.
- An optional **solution** — a sequence of UCI moves the teacher
  considers the correct continuation. Hidden from students on the wire
  (the backend strips it before sending).
- An optional **title** per block.

A homework can also carry free-text **instructions** (one field,
optional).

### Student side — today

When a student opens a homework, they see:

- Title, class name, due / created metadata.
- A **status pill**: Not started / Done / Skipped.
- Three buttons: **Mark as done**, **Mark as skipped**, **Clear status**.
- Instructions (if any).
- Each board block rendered as a **read-only replay viewer**:
  - Title, "To move: White/Black" label.
  - A chessboard showing the current position.
  - A stepper row: **⏮ ◀ Move N of M ▶ ⏭** to walk through the
    teacher's recorded prefix moves.
  - A clickable move list (e.g. `e2e4`, `e7e5`, `g1f3`) to jump
    straight to any ply.
  - **No drag-and-drop.** The student cannot move pieces.
  - No "show solution" disclosure — solutions are stripped server-side.

### Teacher / admin side — today

In the homework panel on a class detail page, the teacher can:

- See a list of homework with tally pills (`3 done · 1 skipped · 8 unmarked`).
- Click **Submissions** on a row to open a sheet that shows:
  - A **Chess content preview** — the same board viewer as the student
    sees, except the teacher can click "Show solution" to step through
    the solution moves.
  - A per-student list of chips: **DONE / SKIPPED / Clear** (the
    teacher can flip a student's status manually).

### What's missing (the gap)

There's no way for a student to **actually play chess moves** as their
answer. Today's homework closes with a binary flip (DONE / SKIPPED /
nothing). The teacher can't see *what the student played*. If the
block is a puzzle ("mate in 2"), the student has no way to say "here's
my attempt"; if it's "memorise this opening", they have no way to
demonstrate they actually played through it.

This is the single biggest missed expectation in user testing: users
see a board and expect to drag pieces. They try. Nothing happens. They
conclude the app is broken.

## Product decisions already made

These are **not open for redesign** — bake them into the UX.

1. **No auto-grading.** The system does not decide whether a student
   got the right answer. Chess tactics often have transpositions; a
   literal string compare against the teacher's solution is strict
   enough to feel punishing and loose enough to miss real errors.
   Instead:
   - The student plays any **legal line** they want (chess.js
     validates legality move-by-move).
   - The teacher **reviews** and marks DONE / SKIPPED manually, same
     as today.
2. **One attempt per student per homework, overwriteable.** Re-playing
   overwrites the previous submission. No attempt history.
3. **Per-block submission.** A homework can have multiple board
   blocks; each block is solved independently. A student can submit
   block 1, leave block 2 untouched, and mark the whole homework
   "done" whenever they choose.
4. **No engine / no hints.** The app doesn't tell the student if their
   move is "good" or "bad". It only confirms the move is legal.
5. **No real-time feedback from the teacher.** Asynchronous only —
   student submits, teacher sees it on their next visit.

## Personas & their jobs-to-be-done

### Student — Amir, age 11, sees homework on a Chromebook

- **Job 1:** "Figure out what the teacher is asking me to do."
  Some blocks are "study this position" (no real action needed),
  others are "solve this" (definitely drag pieces). The UI must make
  this distinction obvious without a chess teacher nearby.
- **Job 2:** "Play my answer on the board." Drag a piece, see it
  move, feel like I did something. Ideally play a few moves in a
  row (a tactic often needs 2–4 ply).
- **Job 3:** "Go back if I mess up." Undo the last move. Start over.
- **Job 4:** "Tell the teacher I'm done." A single action that marks
  the submission as ready for review.
- **Job 5:** "See what the teacher said afterwards." When the teacher
  marks it, I want to know — and I want to be able to look at my own
  moves again to learn from them.

### Teacher — Nargiza, runs a class of 15

- **Job 1:** "See who submitted what, at a glance." A dashboard of
  her class's submissions without clicking into each student.
- **Job 2:** "Walk through a student's moves." Step through their
  line on the same board viewer she already uses for authoring —
  ideally side-by-side with the teacher solution.
- **Job 3:** "Mark it done or send it back." Close the loop with one
  click per student. Optional short comment.
- **Job 4:** "Spot class-wide trends." If 12 of 15 students got the
  same position wrong, she wants to see that without clicking each one.

## Core scenarios (walk them end-to-end)

The design pass should produce mocks for each of these flows.

### Scenario A — Student solves a 2-ply puzzle

1. Amir lands on `/en/student`. Sees a list of homework. Clicks the
   newest one: *"Tactic of the day — white to play and win."*
2. Homework detail page loads: title, instructions, one board block
   titled *"Find the combination"*.
3. The board shows the starting position. It's **clearly interactive**
   — there's a visible affordance (cursor change, highlight on hover,
   a help label like "Your move: drag a piece") that tells Amir he
   can play.
4. Amir drags a bishop to h7. The board animates the move. The move
   is added to a visible **attempt list** under the board
   (`Bxh7+` or `h5h7`). "Black to move" is now shown.
5. Amir plays Black's reply too (he's allowed to play both sides —
   more on this in the open questions). Now it's White again.
6. Amir plays `1...Kxh7` for black, then `2.Ng5+` for white.
7. Amir notices he miscounted and made a wrong 1st move. He clicks
   **Undo** — last move rolls back. Or he clicks **Reset** — the
   board returns to the starting position and the attempt list clears.
8. Satisfied, Amir clicks **Submit my answer** under the board.
   Confirmation toast: "Submitted."
9. The **status pill** on the block flips from *Not started* to
   *Submitted — waiting for review*. The top-level homework status
   chips (Mark as done / skipped) are unchanged — they are the
   student's own self-assessment, separate from the per-block
   submission (see open question #3).

### Scenario B — Student revisits an already-submitted puzzle

1. Later the same day Amir reopens the same homework.
2. The block now shows his earlier attempt already played on the
   board, with the attempt list populated.
3. He's allowed to **re-play** — dragging a new move replaces the
   current attempt from that ply onwards (the "revise my answer"
   affordance must be discoverable).
4. If he edits, a hint appears: "This will replace your submitted
   answer." He can Save (overwrite) or Cancel.

### Scenario C — Student opens a block that's purely "study this"

1. The teacher authored a block with a starting FEN and a **move
   prefix** (e.g. the Najdorf tabiya after 10 ply), but **no expected
   continuation** — the student isn't meant to solve anything, just
   walk through.
2. The student lands on the block. The stepper (⏮ ◀ ▶ ⏭) is the
   primary affordance; the drag-to-move affordance is **absent or
   de-emphasised**.
3. There is no **Submit my answer** button, because there's no answer
   to submit. The block is "complete" as soon as the student opens it.
4. Amir still marks the whole homework **Done** with the existing
   chip at the top of the page.

**Design question for the pass:** how do we visually distinguish
"study this" from "solve this" *without* adding a new per-block type
in the data model? (The backend treats them as the same block — the
only signal available is "does this block have a `solution` field?"
But the student UI never sees `solution`. See open question #1.)

### Scenario D — Teacher reviews a student's attempt

1. Nargiza opens the class detail page, scrolls to Homework.
2. She sees the tally: `3 done · 0 skipped · 12 unmarked` → new pill
   added: `5 submitted` (students who played a line but she hasn't
   reviewed yet).
3. She clicks **Submissions**. The sheet opens.
4. The existing Chess content preview at the top is unchanged.
5. The per-student section is redesigned: each student row now has
   - Their name.
   - A per-block summary — e.g. "Block 1: submitted ✓", "Block 2:
     not attempted".
   - The existing DONE / SKIPPED / Clear chips.
   - An expand arrow → opens an inline viewer that shows the
     student's attempt on the board, side-by-side (or toggle-able)
     with the teacher's solution.
6. Nargiza steps through Amir's attempt. She clicks **Needs work**
   (a new chip? or a free-text comment box?) or just **Done**.
7. Her change revalidates — Amir sees the status on his next visit.

### Scenario E — Multiple blocks, mixed completion

1. A homework has three blocks: (1) study an opening, (2) solve
   a tactic, (3) solve another tactic.
2. A student opens it, walks through block 1 (no submission needed),
   submits block 2, skips block 3.
3. The list page should surface this richer state in some compact way
   — probably on the block-level pill, not just the homework-level
   one. **Design the block-level state badge** (see screen inventory).

## Screen inventory

Existing screens that need redesign:

1. **`/en/student` — homework list.** Today: one status pill per row.
   Tomorrow: probably the same pill, but the possible values grow
   (`Not started` / `In progress` / `Submitted` / `Reviewed` /
   `Skipped`). Design the new pill set.
2. **`/en/student/homework/[id]` — homework detail (student).** Today:
   a status pill + self-mark chips at top, read-only viewer for each
   block. Tomorrow: each block is an interactive solver (when
   appropriate), with submit / undo / reset actions, a per-block
   status pill, and a review-feedback area (if the teacher left a
   comment). Design both the interactive and the passive ("study only")
   variants of the block.
3. **Class detail → Homework → Submissions sheet** (teacher). Today:
   chess content preview + per-student chip row. Tomorrow: the same
   preview, plus an enriched per-student block that shows each
   student's attempt, expandable, with review actions.

No entirely new top-level pages are needed.

## Board interaction rules

This is the crux of the feature. The design pass should nail these.

1. **When is the board interactive?** A board block is "solvable"
   when the teacher authored a `solution` on it. Because the student
   never sees `solution`, the backend must surface an
   `expectsSolution: boolean` flag on each block for the student UI
   to branch on.
2. **Whose turn is it?** After the teacher's move prefix is replayed,
   the side-to-move is whoever the FEN says. In most cases that's the
   side the student should be playing. But the real world: some
   puzzles are "find the best move for Black, then predict White's
   reply" (i.e. the student plays both sides in sequence). The design
   should support both, driven by the board's current turn indicator
   — the student plays **whatever side is to move**, regardless of
   colour, for as many plies as they want.
3. **Illegal move.** A drag that would produce an illegal move is
   refused silently — the piece snaps back to its origin square,
   maybe a subtle shake animation. No error modal.
4. **Undo.** An **Undo** button below the board pops the last move.
   Repeatedly clicking rolls all the way back to the start. Keyboard
   shortcut nice-to-have: backspace / ←.
5. **Reset.** A **Reset** button clears the attempt back to the
   position just after the teacher's prefix. A destructive-ish action
   — confirm only if there are many moves played.
6. **Attempt list.** Underneath the board, a scrollable list of the
   moves the student has played (both SAN and UCI are fine — SAN is
   more familiar to chess players; the authoring editor currently
   shows UCI). Clicking an item jumps to that ply (same pattern as
   the existing replay viewer).
7. **Submit.** A **Submit my answer** button is enabled once the
   student has played at least one move. It persists the attempt and
   flips the block's per-block status. After submit, the button is
   labelled **Update my answer** and works as an overwrite (see
   Scenario B).
8. **"Clear submission" affordance.** Somewhere discoverable (maybe
   inside a "…" menu on the block), the student can delete their
   submission, returning the block to "not attempted". This is
   different from Reset — Reset only clears the in-progress board;
   Clear submission wipes the persisted answer.
9. **Keyboard & touch.** Touch-friendly (students will use Chromebooks
   and phones). Keyboard arrow keys should step through the attempt
   after submission. Drag must work on touch screens.
10. **Arrows / drawings on the board.** Out of scope for v1 — don't
    propose adding a draw/annotation layer.

## States & edge cases

The design must cover the following for each surface:

- **Loading** — initial fetch, board not yet rendered.
- **Empty** — homework has no board blocks (instructions only).
- **Error** — network hiccup on submit.
- **Pending save** — student clicked Submit and we're waiting on the
  server. The button should be disabled / spinnered.
- **Stale** — student opens homework, their status was changed
  elsewhere (e.g. teacher reviewed it). We have server-side
  revalidation; when a student returns they see the new state.
- **Not-logged-in** — session expired mid-solve. What happens to
  their in-progress moves? (Open question #5.)
- **Offline** — no network. Can they play moves locally without
  submitting? (Probably yes, mark as "unsaved", prompt on unload.)
- **Teacher-only states** — if a block has no recorded solution, the
  teacher preview shouldn't offer "Show solution" (this already
  works today; don't regress).
- **Locale** — everything must be translatable into English, Russian,
  Uzbek. Move notation uses Latin letters in all three locales
  (standard chess convention).

## Technical constraints the UI must respect

**The design must not propose anything that breaks these.** If a
proposal conflicts with one of these, call it out as an open question
so we can revisit the backend rather than silently adopting an
unshippable design.

1. **One attempt per (student, block), overwriteable.** We will not
   store an attempt history.
2. **Attempt shape is the same as the authored block:** a list of
   UCI moves. No annotations, no SAN, no per-move comments in v1.
3. **No server-side grading.** The UI can *show* moves, but "right /
   wrong" is a human decision. Don't propose a green ✓ / red ✗ per
   move — the system doesn't know.
4. **Solution is not on the wire for students.** The student UI
   cannot render the teacher's solution, even collapsed. (The teacher
   UI can and does.)
5. **`expectsSolution` flag.** The student's detail endpoint will
   surface a boolean per block that means "the teacher wrote a
   solution for this one" — used to toggle solver mode on/off. Do
   not propose deriving this from the absence/presence of other data.
6. **Per-block status values (proposed):** `NOT_ATTEMPTED`,
   `SUBMITTED`, `REVIEWED_OK`, `REVIEWED_NEEDS_WORK`. The design can
   push back on these names.
7. **Per-homework top-level status stays as today:** `NOT_STARTED`,
   `DONE`, `SKIPPED`. The student uses it as a self-mark ("I've
   looked at this homework"); it's orthogonal to the new per-block
   flow. Don't collapse the two — see open question #3.
8. **No engine, no hints, no evaluation bar.**
9. **No real-time / socket updates.** Revalidation is on the next
   page load.
10. **Look and feel:** stay consistent with the existing Kingside UI
    (see the two "anchor screens" below). Don't redesign buttons,
    cards, or pills in isolation.

### Visual anchors

The design pass should match, not deviate from, the existing look. Key
references in the running app:

- Student homework list (`/en/student`): simple card list, one row
  per homework, left-aligned title + meta, right-aligned status pill.
- Admin class detail → Homework panel: cards-in-a-section, submission
  sheet that expands inline.
- Board viewer: chessboard (react-chessboard v5) + stepper + clickable
  SAN list, on a white card.

Screenshots would normally go here. For now, pull the running app at
`http://localhost:3100` after following the README.

## Non-goals

Explicitly out of scope for this design pass:

- **Parent view of submissions.** Parents do not see student homework
  answers in v1. Filed as a separate future task.
- **Annotations / comments on individual moves.** No NAG support, no
  "?!" on a move. Teacher feedback is one free-text blob per block at
  most.
- **Variations / branches in the student's attempt.** The student's
  line is linear. If they want to try a different move, they Undo
  first.
- **Multiple attempts history.** Only the latest submission is kept.
- **Real-time collaborative solving.** Each student is solo.
- **Engine integration, opening book, evaluation.** No external
  services.
- **Printable / PDF export of a student's submission.** Not v1.
- **Re-opening a reviewed submission for a second round.** If a
  teacher marked it *needs work*, the student can re-submit — but
  the design shouldn't propose a review → revision → re-review cycle
  with separate states per round. Just overwrite.

## Open questions for the design pass

These are the design-model-shaped questions. Answer each with a
concrete proposal (mocks + brief rationale).

1. **"Solve" vs. "study" affordance.** When a block has no solution
   (`expectsSolution=false`), how do we communicate "this is just
   something to look at" so the student doesn't try to drag pieces?
   Tabs? A subtle banner? A different card shape?
2. **Per-block status pill.** Where does it go on the student detail
   page, and in the homework list? Four states (`not attempted /
   submitted / reviewed ok / reviewed needs work`) may be too many
   for a single pill — propose an icon + tooltip pattern if needed.
3. **Top-level `DONE / SKIPPED` chips — keep or kill?** Today the
   student can self-mark a whole homework done without touching any
   block. Is that still useful once we have per-block submissions,
   or does it become confusing? Two reasonable answers; pick one and
   justify.
4. **Teacher review actions.** Today's *DONE / SKIPPED / Clear* row
   is at the homework level. Should review happen at the homework
   level or the block level? If block, the per-student admin block
   UI gets quite busy — propose a layout.
5. **Mid-session session expiry.** Student plays 4 moves, network
   dies, session expires. On next page load they've lost their
   work. Acceptable for v1? Or does the design need "save as draft"
   semantics? (Implementer will flag if the backend needs a draft
   concept.)
6. **Mobile layout.** On a phone, board + attempt list + action
   buttons doesn't fit side by side. Propose a stacked layout with
   sticky action buttons.
7. **Locale of move notation.** Russian chess tradition sometimes
   uses Cyrillic piece letters (Кd5 instead of Nd5). We're shipping
   standard (Latin) SAN for v1 — confirm this is fine in the mocks,
   don't propose Cyrillic piece letters.
8. **Board size / ratio on student detail.** Today the viewer is in a
   responsive grid with other blocks. When the block becomes an
   interactive solver, it's suddenly the primary focus. Should the
   board grow? Should other blocks collapse?
9. **"Show teacher's solution" after review.** Once the teacher has
   marked the block, should the student now be able to see the
   teacher's solution (for learning)? Currently solutions are never
   sent to students. Propose a policy and flag the backend change
   needed.
10. **Empty-attempt state.** The student opens a solve block and
    does nothing. How do we visually communicate "no submission yet"
    without making the board feel broken?
11. **Undo / Reset affordance.** Where do these live spatially?
    Adjacent to the board? Above the attempt list? Below?
12. **Submit button prominence.** It's the most important action on
    the page. How big / where / when does it animate?

## Appendix — vocabulary

- **Block** / **board block** / **resource** — one authored chess
  exercise inside a homework. One homework, many blocks.
- **Prefix** — the moves the teacher plays before handing the board
  to the student. Shown via the existing stepper.
- **Solution** — the teacher's authored correct continuation. Hidden
  from students.
- **Attempt** — the student's played moves. A new concept introduced
  by this feature.
- **Submission** — the persisted attempt, flagged as ready for review.
- **Review** — the teacher's judgement on a submission.

## Appendix — what the implementation model will need from the design

When mocks come back, the engineering model will want:

- A labelled, annotated image (or Figma frame) per scenario above.
- Specific copy for every piece of UI text (in English — translators
  handle Russian and Uzbek).
- Spec for each interactive state (hover / focus / disabled /
  loading) on the new buttons.
- Any new pill / badge colours referenced against the existing
  palette (see `apps/web/src/app/globals.css`).
- If a new icon is introduced, a reference to the icon (ideally from
  lucide-react, which the app already uses).

If the design model can't produce annotated mocks in a single pass,
prose descriptions keyed to the scenarios above are also acceptable —
but state *exactly* what goes where for each screen, in terms an
engineer can translate to JSX without guessing.
