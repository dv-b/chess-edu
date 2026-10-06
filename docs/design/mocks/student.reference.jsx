// STUDENT role — dashboard, homework player (tactics/mate/mcq), lesson viewer

const STUDENT_USER = {
  name: "Leo Alvarez",
  age: 11,
  grade: "Grade 6 · Bishop Class",
  rating: 1284,
  rank: "Silver Knight III",
  streak: 17,
  xp: 2840,
  xpToNext: 3500,
  piece: "n",
  tone: "green",
};

// Tactics puzzle fixture
const TACTIC = {
  title: "Spot the tactic",
  theme: "Double attack · White to move",
  difficulty: "Intermediate",
  fen: "r1bqkb1r/pppp1ppp/2n2n2/4p3/2B1P3/5N2/PPPP1PPP/RNBQ1RK1",
  orientation: "white",
  solutionFrom: "c4",
  solutionTo: "f7",
  hint: "Look for an undefended square near the enemy king.",
  options: [
    { label: "Nxe5 — capture the pawn", correct: false },
    { label: "Bxf7+ — sacrifice the bishop", correct: true, note: "Opens the king and wins material after …Kxf7 Nxe5+ forking the king & queen." },
    { label: "d4 — challenge the center", correct: false },
    { label: "Qd3 — prepare kingside", correct: false },
  ],
};

// Lesson (annotated game) fixture
const LESSON = {
  title: "The Evergreen Game — Anderssen vs Dufresne, 1852",
  chapter: "Chapter 3 · Classical Sacrifice",
  teacher: "GM Elena Vasquez",
  moves: [
    { n: 1, w: "e4",   wn: "King's pawn opening — claims the center.", b: "e5"   },
    { n: 2, w: "Nf3",  b: "Nc6", bn: "A sound classical defence." },
    { n: 3, w: "Bc4",  b: "Bc5"  },
    { n: 4, w: "b4",   wn: "The Evans Gambit. White offers a pawn for rapid development.", b: "Bxb4" },
    { n: 5, w: "c3",   b: "Ba5"  },
    { n: 6, w: "d4",   wn: "Central break. Notice White's harmonious pieces.", b: "exd4" },
    { n: 7, w: "O-O",  b: "d3",  bn: "Black returns the pawn to slow down White's initiative.", tag: "mistake" },
    { n: 8, w: "Qb3",  b: "Qf6", tag: "good" },
    { n: 9, w: "e5",   wn: "Sharp! Aims to break Black's coordination.", b: "Qg6", tag: "brilliant" },
  ],
  currentPly: 8, // pointer into move list
  boardFEN: "r1b1k1nr/ppppqppp/1Bn3b1/4P3/2B5/1QP2N2/P4PPP/RNB2RK1",
  lastMove: ["e4", "e5"],
  arrows: [{ from: "b3", to: "f7", color: "oklch(60% 0.17 35)" }, { from: "c4", to: "f7", color: "oklch(60% 0.15 75)" }],
};

// ---------------- STUDENT DASHBOARD ----------------
function StudentDashboard({ go }) {
  return (
    <>
      <div className="flex items-center justify-between mb-6">
        <div>
          <div className="eyebrow mb-2">Tuesday, 18 April · Good afternoon</div>
          <h1 className="h-display">Welcome back, Leo.</h1>
          <p className="deck mt-3">You're on a 17-day streak. Two assignments due this week — one from Coach Elena, one from the tournament prep group.</p>
        </div>
        <div className="flex items-center gap-4">
          <button className="btn btn-ghost"><Icon name="flame"/> 17-day streak</button>
          <button className="btn btn-green"><Icon name="lightning"/> Daily puzzle</button>
        </div>
      </div>

      {/* Hero banner + quick stats */}
      <div className="grid gap-5 mb-6" style={{ gridTemplateColumns: "2fr 1fr" }}>
        <div className="banner">
          <div className="flag">Weekly challenge · ends Sunday</div>
          <div className="head">Solve 30 puzzles. Unlock a Queen emblem.</div>
          <div style={{ display: "flex", gap: 24, marginTop: 18, alignItems: "end" }}>
            <div>
              <div style={{ fontFamily: "var(--mono)", fontSize: 44, fontWeight: 600, letterSpacing: "-0.02em" }}>19<span style={{ opacity: .5 }}>/30</span></div>
              <div style={{ fontSize: 12, opacity: .7, letterSpacing: ".12em", textTransform: "uppercase" }}>Puzzles solved</div>
            </div>
            <div style={{ flex: 1 }}>
              <div style={{ background: "rgba(255,255,255,0.15)", height: 8, borderRadius: 999, overflow: "hidden" }}>
                <div style={{ width: "63%", height: "100%", background: "oklch(82% 0.14 140)" }}/>
              </div>
              <div style={{ fontSize: 12, marginTop: 8, opacity: .7 }}>11 puzzles to go · your class average: 14</div>
            </div>
            <button className="btn" style={{ background: "oklch(82% 0.14 140)", color: "#1d2817" }} onClick={() => go("homework")}>Continue <Icon name="arrow-right"/></button>
          </div>
        </div>

        <div className="card flex-col gap-4" style={{ padding: 20 }}>
          <div className="flex items-center gap-4">
            <PieceAvatar piece={STUDENT_USER.piece} tone={STUDENT_USER.tone} size={64}/>
            <div>
              <div style={{ fontFamily: "var(--serif)", fontSize: 20, fontWeight: 500 }}>{STUDENT_USER.name}</div>
              <div className="text-xs text-ink-3">{STUDENT_USER.grade}</div>
              <div className="rank-badge mt-2"><PieceIcon p="n" size={14} color="#f5f1e8"/>{STUDENT_USER.rank}</div>
            </div>
          </div>
          <div>
            <div className="flex justify-between text-xs text-ink-3 mb-2">
              <span>XP · {STUDENT_USER.xp.toLocaleString()}</span>
              <span>{STUDENT_USER.xpToNext.toLocaleString()}</span>
            </div>
            <div className="progress green"><div style={{ width: `${(STUDENT_USER.xp/STUDENT_USER.xpToNext)*100}%` }}/></div>
            <div className="text-xs text-ink-4 mt-2">660 XP to <b className="text-ink-2">Gold Knight I</b></div>
          </div>
        </div>
      </div>

      {/* Three panels */}
      <div className="grid-3 mb-6">
        <div className="card">
          <div className="flex items-center justify-between mb-4">
            <div className="h-section" style={{ margin: 0 }}>Due this week</div>
            <a className="text-xs text-ink-3" style={{ textDecoration: "underline" }}>See all (4)</a>
          </div>
          <AssignmentRow date="Wed 19" title="Evans Gambit traps" kind="Lesson + 5 exercises" due="In 2 days" status="todo" onClick={() => go("lesson")}/>
          <AssignmentRow date="Fri 21" title="Mate in 2 — Set 14" kind="Puzzle pack · 8 problems" due="In 4 days" status="progress" progress={3} total={8} onClick={() => go("homework")}/>
          <AssignmentRow date="Sun 23" title="Endgame: King + Pawn" kind="Video + quiz" due="In 6 days" status="todo"/>
        </div>

        <div className="card">
          <div className="h-section">Rating — last 6 months</div>
          <div className="stat mb-3" style={{ flexDirection: "row", alignItems: "baseline", gap: 10 }}>
            <span className="value" style={{ fontSize: 36 }}>1284</span>
            <span className="delta">+46 this month</span>
          </div>
          <RatingChart height={140} data={[
            { x: "Nov", y: 1078, avg: 1100 },
            { x: "Dec", y: 1122, avg: 1130 },
            { x: "Jan", y: 1154, avg: 1155 },
            { x: "Feb", y: 1190, avg: 1178 },
            { x: "Mar", y: 1238, avg: 1202 },
            { x: "Apr", y: 1284, avg: 1228 },
          ]}/>
          <div className="flex gap-4 text-xs text-ink-3 mt-2">
            <span className="flex items-center gap-2"><span style={{ width: 10, height: 2, background: "var(--green)" }}/>Your rating</span>
            <span className="flex items-center gap-2"><span style={{ width: 10, height: 2, background: "var(--ink-4)", borderTop: "1px dashed" }}/>Class average</span>
          </div>
        </div>

        <div className="card">
          <div className="h-section">Study streak</div>
          <div className="flex items-baseline gap-3 mb-4">
            <span className="mono" style={{ fontFamily: "var(--serif)", fontSize: 36, fontWeight: 500 }}>17</span>
            <span className="text-xs text-ink-3">days in a row · longest: 31</span>
          </div>
          <div className="heatmap" style={{ gridTemplateColumns: "repeat(13, 1fr)" }}>
            {Array.from({ length: 91 }).map((_, i) => {
              const r = Math.sin(i * 1.7) * 0.5 + 0.5;
              const lvl = i >= 91 - 17 ? 3 + Math.floor(r * 2) : Math.floor(r * 4);
              return <div key={i} className={`cell l${Math.max(0, Math.min(4, lvl))}`}/>;
            })}
          </div>
          <div className="flex justify-between text-xs text-ink-4 mt-3">
            <span>13 weeks ago</span>
            <span>Today</span>
          </div>
        </div>
      </div>

      {/* Achievements + from the coach */}
      <div className="grid gap-5 mb-5" style={{ gridTemplateColumns: "1.3fr 1fr" }}>
        <div className="card">
          <div className="h-section">Achievements · 12 of 38</div>
          <div className="grid gap-3" style={{ gridTemplateColumns: "repeat(6, 1fr)" }}>
            <Achievement icon="flame" title="Hot streak" sub="14 days"/>
            <Achievement icon="target" title="Marksman" sub="50 puzzles solved"/>
            <Achievement icon="star" title="Scholar" sub="3 lessons finished"/>
            <Achievement icon="trophy" title="Tournament" sub="Top 4"/>
            <Achievement icon="lightning" title="Blitzed" sub="Solve under 30s" locked/>
            <Achievement icon="sparkle" title="Brilliant" sub="Find a !! move" locked/>
          </div>
        </div>

        <div className="card">
          <div className="h-section">From your coach</div>
          <div className="flex items-start gap-3">
            <div className="avatar lg" style={{ background: "var(--ink)", color: "var(--paper)" }}>EV</div>
            <div>
              <div style={{ fontFamily: "var(--serif)", fontSize: 16 }}>GM Elena Vasquez</div>
              <div className="text-xs text-ink-4 mb-2">Yesterday · 18:42</div>
              <p style={{ fontFamily: "var(--serif)", fontSize: 15, lineHeight: 1.5, color: "var(--ink-2)", margin: 0 }}>
                "Lovely work on the Italian exercises, Leo. Before our Thursday session, please replay the Anderssen–Dufresne game and note where Black's pieces stop coordinating."
              </p>
              <div className="flex gap-2 mt-3">
                <button className="btn btn-sm btn-ghost"><Icon name="message" size={12}/> Reply</button>
                <button className="btn btn-sm" onClick={() => go("lesson")}>Open the lesson <Icon name="arrow-right" size={12}/></button>
              </div>
            </div>
          </div>
        </div>
      </div>
    </>
  );
}

function AssignmentRow({ date, title, kind, due, status, progress, total, onClick }) {
  const [dow, d] = date.split(" ");
  return (
    <div className="assign-item" onClick={onClick} style={{ cursor: onClick ? "pointer" : "default" }}>
      <div className="date-block">
        <div className="dow">{dow}</div>
        <div className="d">{d}</div>
      </div>
      <div>
        <div style={{ fontWeight: 500, fontSize: 14, color: "var(--ink)" }}>{title}</div>
        <div className="text-xs text-ink-3 mt-1">{kind} · due {due}</div>
        {progress !== undefined && (
          <div className="flex items-center gap-2 mt-2">
            <div className="progress" style={{ flex: 1, maxWidth: 120 }}><div style={{ width: `${(progress/total)*100}%` }}/></div>
            <span className="text-xs text-ink-3 mono">{progress}/{total}</span>
          </div>
        )}
      </div>
      <div>
        {status === "todo" && <span className="chip">To do</span>}
        {status === "progress" && <span className="chip chip-amber"><span className="dot"/>In progress</span>}
        {status === "done" && <span className="chip chip-green"><span className="dot"/>Done</span>}
      </div>
    </div>
  );
}

function Achievement({ icon, title, sub, locked }) {
  return (
    <div className={`achievement ${locked ? "locked" : ""}`}>
      <div className="badge-ic"><Icon name={icon} size={22}/></div>
      <div className="title">{title}</div>
      <div className="sub">{sub}</div>
    </div>
  );
}

// ---------------- STUDENT — HOMEWORK PLAYER ----------------
function HomeworkPlayer({ go }) {
  const [answer, setAnswer] = React.useState(null);
  const [showHint, setShowHint] = React.useState(false);
  const selected = answer !== null ? TACTIC.options[answer] : null;
  const isCorrect = selected?.correct;

  return (
    <>
      <div className="flex items-center justify-between mb-5">
        <div className="flex items-center gap-3 text-sm text-ink-3">
          <a onClick={() => go("dashboard")} style={{ cursor: "pointer" }}>Homework</a>
          <Icon name="chevron-right" size={14}/>
          <span>Mate in 2 · Set 14</span>
          <Icon name="chevron-right" size={14}/>
          <span className="text-ink">Puzzle 4 of 8</span>
        </div>
        <div className="flex items-center gap-3">
          <div className="mono text-xs text-ink-3">03:12 on this puzzle</div>
          <button className="btn btn-ghost btn-sm" onClick={() => go("dashboard")}>Save &amp; exit</button>
        </div>
      </div>

      <div className="grid gap-6" style={{ gridTemplateColumns: "minmax(0, auto) 1fr" }}>
        {/* Board side */}
        <div className="flex gap-3">
          <EvalBar value={+2.4} height={520}/>
          <FramedBoard
            fen={TACTIC.fen}
            size={520}
            lastMove={["f6","f5"]}
            hints={isCorrect ? [TACTIC.solutionFrom, TACTIC.solutionTo] : []}
            goodSquares={isCorrect ? [TACTIC.solutionTo] : []}
            badSquares={selected && !isCorrect ? [] : []}
            arrows={isCorrect ? [{ from: TACTIC.solutionFrom, to: TACTIC.solutionTo, color: "oklch(60% 0.17 145)" }] : []}
          />
        </div>

        <div className="flex-col gap-4" style={{ display: "flex" }}>
          <div>
            <div className="eyebrow">{TACTIC.theme}</div>
            <h2 className="h-title mt-2">{TACTIC.title}</h2>
            <p className="deck mt-2">Play the move that wins material. Think two moves ahead.</p>
          </div>

          <div className="flex gap-2">
            <span className="chip"><Icon name="puzzle" size={12}/> {TACTIC.difficulty}</span>
            <span className="chip"><Icon name="clock" size={12}/> avg. 2:45</span>
            <span className="chip chip-green"><Icon name="target" size={12}/> 62% solve rate</span>
          </div>

          <div className="flex-col gap-2" style={{ display: "flex" }}>
            {TACTIC.options.map((o, i) => {
              let cls = "mcq-opt";
              if (answer !== null) {
                if (i === answer) cls += o.correct ? " correct" : " wrong";
                else if (o.correct) cls += " correct";
              }
              return (
                <div key={i} className={cls} onClick={() => answer === null && setAnswer(i)}>
                  <div className="letter">{String.fromCharCode(65 + i)}</div>
                  <div className="flex-1">
                    <div className="mono fw-500">{o.label}</div>
                    {answer !== null && o.correct && (
                      <div className="text-xs mt-1" style={{ fontFamily: "var(--serif)", fontStyle: "italic", color: "var(--green-deep)" }}>{o.note}</div>
                    )}
                  </div>
                  {answer !== null && o.correct && <Icon name="check" size={18}/>}
                  {answer !== null && !o.correct && i === answer && <Icon name="x" size={18}/>}
                </div>
              );
            })}
          </div>

          {!answer && !showHint && (
            <button className="btn btn-ghost" style={{ alignSelf: "flex-start" }} onClick={() => setShowHint(true)}>
              <Icon name="sparkle" size={14}/> Give me a hint <span className="text-xs text-ink-4" style={{ marginLeft: 4 }}>(-5 XP)</span>
            </button>
          )}
          {showHint && answer === null && (
            <div className="card" style={{ padding: 14, background: "var(--green-soft)", borderColor: "transparent" }}>
              <div className="text-xs" style={{ color: "var(--green-deep)", fontFamily: "var(--serif)", fontStyle: "italic", fontSize: 14 }}>
                💡 {TACTIC.hint}
              </div>
            </div>
          )}

          {answer !== null && (
            <div className="flex gap-3 mt-2">
              <button className="btn btn-ghost" onClick={() => { setAnswer(null); setShowHint(false); }}><Icon name="skip-bwd" size={14}/> Retry</button>
              <button className="btn btn-green" style={{ marginLeft: "auto" }}>
                Next puzzle <Icon name="arrow-right" size={14}/>
              </button>
            </div>
          )}

          {/* progress strip */}
          <div>
            <div className="h-section">Puzzle set · Mate in 2 — Set 14</div>
            <div className="flex gap-2">
              {[true, true, true, null, null, null, null, null].map((v, i) => (
                <div key={i} style={{
                  flex: 1,
                  height: 36,
                  borderRadius: 6,
                  display: "grid", placeItems: "center",
                  background: v === true ? "var(--green-soft)" : i === 3 ? "var(--paper-3)" : "var(--paper-2)",
                  border: i === 3 ? "2px solid var(--ink)" : "1px solid var(--rule)",
                  color: v === true ? "var(--green-deep)" : "var(--ink-3)",
                  fontFamily: "var(--mono)", fontSize: 12, fontWeight: 600,
                }}>
                  {v === true ? <Icon name="check" size={14}/> : i + 1}
                </div>
              ))}
            </div>
          </div>
        </div>
      </div>
    </>
  );
}

// ---------------- STUDENT — LESSON / ANNOTATED GAME ----------------
function LessonViewer({ go }) {
  const [ply, setPly] = React.useState(6);
  const [annotationVisible, setAnnotationVisible] = React.useState(true);

  return (
    <>
      <div className="flex items-center justify-between mb-5">
        <div className="flex items-center gap-3 text-sm text-ink-3">
          <a onClick={() => go("dashboard")} style={{ cursor: "pointer" }}>Lessons</a>
          <Icon name="chevron-right" size={14}/>
          <span>Classical Sacrifice</span>
          <Icon name="chevron-right" size={14}/>
          <span className="text-ink">The Evergreen Game</span>
        </div>
        <div className="flex items-center gap-3">
          <span className="chip chip-green"><Icon name="mortarboard" size={12}/> Assigned by Coach Elena</span>
          <button className="btn btn-ghost btn-sm"><Icon name="edit" size={12}/> Add note</button>
        </div>
      </div>

      <div className="grid gap-5 mb-5" style={{ gridTemplateColumns: "minmax(0, auto) 1fr" }}>
        <div className="flex gap-3">
          <EvalBar value={+3.2} height={520}/>
          <FramedBoard
            fen={LESSON.boardFEN}
            size={520}
            lastMove={LESSON.lastMove}
            arrows={LESSON.arrows}
          />
        </div>

        <div className="flex-col" style={{ display: "flex", gap: 16 }}>
          <div>
            <div className="eyebrow">{LESSON.chapter}</div>
            <h2 className="h-title mt-2">{LESSON.title}</h2>
            <div className="flex items-center gap-3 mt-3">
              <div className="avatar sm" style={{ background: "var(--ink)", color: "var(--paper)" }}>EV</div>
              <div className="text-xs text-ink-3">Annotated by {LESSON.teacher} · 14 minutes · 6 diagrams</div>
            </div>
          </div>

          {/* Move list with annotations */}
          <div className="card card-flush" style={{ maxHeight: 340, overflow: "auto" }}>
            <div style={{ padding: "14px 10px" }}>
              <div className="moves">
                {LESSON.moves.map(m => (
                  <div key={m.n} className="move-row">
                    <span className="move-num">{m.n}.</span>
                    <span className={`move ${m.n === 9 ? "brilliant" : ""} ${m.n === 7 && m.tag === "mistake" ? "" : ""}`} >{m.w}</span>
                    <span className={`move ${m.tag === "mistake" ? "mistake" : ""} ${m.tag === "brilliant" ? "brilliant" : ""}`}>{m.b}</span>
                    {m.wn && <span className="annotation">{m.wn}</span>}
                    {m.bn && <span className="annotation">→ {m.bn}</span>}
                  </div>
                ))}
              </div>
            </div>
          </div>

          {annotationVisible && (
            <div className="card" style={{ padding: 16, borderLeft: "3px solid var(--green)" }}>
              <div className="eyebrow mb-2">Coach's note · move 9</div>
              <p style={{ fontFamily: "var(--serif)", fontSize: 16, lineHeight: 1.55, margin: 0, color: "var(--ink-2)" }}>
                "Here Anderssen plays e5, not to win material but to <em>cut</em> Black's queen from its defenders. When you see a central push like this, always ask: <em>what piece did it just disconnect?</em>"
              </p>
              <div className="flex gap-2 mt-3">
                <button className="btn btn-sm btn-ghost"><Icon name="check" size={12}/> I understand</button>
                <button className="btn btn-sm btn-ghost"><Icon name="message" size={12}/> Ask Coach Elena</button>
              </div>
            </div>
          )}

          {/* Transport controls */}
          <div className="flex items-center gap-3">
            <button className="btn btn-ghost btn-sm"><Icon name="skip-bwd" size={14}/></button>
            <button className="btn btn-ghost btn-sm" onClick={() => setPly(Math.max(0, ply - 1))}><Icon name="arrow-left" size={14}/></button>
            <button className="btn btn-green">
              <Icon name="play" size={14}/> Auto-play
            </button>
            <button className="btn btn-ghost btn-sm" onClick={() => setPly(Math.min(LESSON.moves.length * 2 - 1, ply + 1))}><Icon name="arrow-right" size={14}/></button>
            <button className="btn btn-ghost btn-sm"><Icon name="skip-fwd" size={14}/></button>
            <div className="flex-1"/>
            <button className="btn btn-ghost btn-sm" onClick={() => setAnnotationVisible(!annotationVisible)}>
              {annotationVisible ? "Hide" : "Show"} annotations
            </button>
          </div>
        </div>
      </div>

      {/* After-lesson exercise + chapter nav */}
      <div className="grid-3 gap-5">
        <div className="card">
          <div className="h-section">After this lesson</div>
          <div className="flex items-start gap-3">
            <div style={{ width: 54, height: 54, borderRadius: 8, background: "var(--green-soft)", display: "grid", placeItems: "center", color: "var(--green-deep)", flexShrink: 0 }}>
              <Icon name="puzzle" size={24}/>
            </div>
            <div>
              <div className="fw-500">3 exercises to practice the theme</div>
              <div className="text-xs text-ink-3 mt-1">Recognize the "disconnect" idea in new positions</div>
              <button className="btn btn-green btn-sm mt-3" onClick={() => go("homework")}>Start <Icon name="arrow-right" size={12}/></button>
            </div>
          </div>
        </div>

        <div className="card">
          <div className="h-section">Your notes</div>
          <div style={{ fontFamily: "var(--serif)", fontSize: 14, lineHeight: 1.5, color: "var(--ink-2)" }}>
            <p style={{ margin: "0 0 8px" }}>Move 9. e5 — <em>this is the key move</em>. Black's queen on f6 is now a long way from its king.</p>
            <p style={{ margin: 0, color: "var(--ink-3)" }}>— 17 April, during Coach Elena's Thursday class.</p>
          </div>
          <button className="btn btn-ghost btn-sm mt-3"><Icon name="plus" size={12}/> Add a note</button>
        </div>

        <div className="card">
          <div className="h-section">Chapter progress</div>
          <div className="flex-col" style={{ display: "flex", gap: 10 }}>
            {[
              { t: "Introduction", done: true },
              { t: "The Italian attack", done: true },
              { t: "Evans Gambit basics", done: true },
              { t: "The Evergreen game", active: true },
              { t: "Morphy's sacrifice", done: false },
              { t: "Modern refutations", done: false },
            ].map((c, i) => (
              <div key={i} className="flex items-center gap-3" style={{
                padding: "6px 8px",
                borderRadius: 6,
                background: c.active ? "var(--paper-2)" : "transparent",
                fontWeight: c.active ? 500 : 400,
              }}>
                <div style={{
                  width: 20, height: 20, borderRadius: 999,
                  border: "1.5px solid " + (c.done ? "var(--green)" : "var(--rule-2)"),
                  background: c.done ? "var(--green)" : "transparent",
                  display: "grid", placeItems: "center", color: "#fff",
                }}>
                  {c.done && <Icon name="check" size={12}/>}
                </div>
                <span className="text-sm">{c.t}</span>
                {c.active && <span className="chip chip-amber" style={{ marginLeft: "auto" }}>You are here</span>}
              </div>
            ))}
          </div>
        </div>
      </div>
    </>
  );
}

Object.assign(window, { StudentDashboard, HomeworkPlayer, LessonViewer });
