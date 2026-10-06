// TEACHER role — roster, lesson authoring, assign-homework flow, submissions review

const CLASS_ROSTER = [
  { name: "Leo Alvarez", age: 11, piece: "n", tone: "green", rating: 1284, delta: "+46", last: "2h ago", streak: 17, hw: "5/6", hwStatus: "progress", trend: [1200,1215,1230,1240,1255,1270,1284] },
  { name: "Sofia Okafor", age: 12, piece: "b", tone: "amber", rating: 1412, delta: "+82", last: "Yesterday", streak: 9, hw: "6/6", hwStatus: "done", trend: [1310,1330,1345,1368,1380,1396,1412] },
  { name: "Henry Park", age: 10, piece: "p", tone: "plum", rating: 1056, delta: "+12", last: "3d ago", streak: 0, hw: "2/6", hwStatus: "late", trend: [1028,1034,1042,1040,1048,1050,1056] },
  { name: "Amelia Chen", age: 13, piece: "q", tone: "rust", rating: 1588, delta: "+31", last: "1h ago", streak: 22, hw: "6/6", hwStatus: "done", trend: [1540,1548,1560,1565,1572,1580,1588] },
  { name: "Matteo Rossi", age: 9, piece: "p", tone: "sky", rating: 902, delta: "−14", last: "5d ago", streak: 0, hw: "0/6", hwStatus: "overdue", trend: [940,928,920,918,908,900,902] },
  { name: "Priya Raman", age: 14, piece: "r", tone: "ink", rating: 1721, delta: "+18", last: "30m ago", streak: 41, hw: "6/6", hwStatus: "done", trend: [1680,1694,1702,1708,1712,1718,1721] },
  { name: "Jonas Weber", age: 11, piece: "n", tone: "amber", rating: 1168, delta: "+24", last: "Yesterday", streak: 6, hw: "4/6", hwStatus: "progress", trend: [1140,1145,1150,1155,1160,1165,1168] },
  { name: "Zara Ahmed", age: 12, piece: "b", tone: "green", rating: 1342, delta: "+52", last: "4h ago", streak: 12, hw: "5/6", hwStatus: "progress", trend: [1288,1298,1310,1320,1328,1334,1342] },
];

function TeacherDashboard({ go }) {
  return (
    <>
      <div className="flex items-center justify-between mb-6">
        <div>
          <div className="eyebrow mb-2">Bishop Class · 24 students · Year 2025–26</div>
          <h1 className="h-display">Good afternoon, Elena.</h1>
          <p className="deck mt-3">3 submissions are waiting for your review. Thursday's lesson is ready — don't forget to assign the Evergreen exercises.</p>
        </div>
        <div className="flex items-center gap-3">
          <button className="btn btn-ghost" onClick={() => go("assign")}><Icon name="plus"/> Assign homework</button>
          <button className="btn" onClick={() => go("author")}><Icon name="edit"/> Author a lesson</button>
        </div>
      </div>

      {/* KPIs */}
      <div className="grid-4 mb-6">
        <KPI label="Active students" value="22" delta="+3 this month"/>
        <KPI label="Avg. class rating" value="1284" delta="+38"/>
        <KPI label="Homework on time" value="84%" delta="−4%" neg/>
        <KPI label="Your review queue" value="3" delta="oldest: 2 days" neutral/>
      </div>

      <div className="grid gap-5" style={{ gridTemplateColumns: "1.4fr 1fr" }}>
        <div className="card">
          <div className="flex items-center justify-between mb-3">
            <div className="h-section" style={{ margin: 0 }}>Class roster</div>
            <div className="flex items-center gap-2">
              <div className="seg">
                <button className="on">All</button>
                <button>Needs attention</button>
                <button>Tournament team</button>
              </div>
              <button className="btn btn-sm btn-ghost"><Icon name="filter" size={12}/> Filter</button>
            </div>
          </div>
          <table className="roster">
            <thead><tr>
              <th>Student</th>
              <th>Rating</th>
              <th style={{ width: 100 }}>Trend</th>
              <th>Homework</th>
              <th>Streak</th>
              <th>Last active</th>
              <th/>
            </tr></thead>
            <tbody>
              {CLASS_ROSTER.map((s, i) => (
                <tr key={i}>
                  <td>
                    <div className="flex items-center gap-3">
                      <PieceAvatar piece={s.piece} tone={s.tone} size={36}/>
                      <div>
                        <div style={{ fontWeight: 500 }}>{s.name}</div>
                        <div className="text-xs text-ink-4">Age {s.age}</div>
                      </div>
                    </div>
                  </td>
                  <td>
                    <div className="mono fw-600" style={{ fontSize: 14 }}>{s.rating}</div>
                    <div className="text-xs mono" style={{ color: s.delta.startsWith("−") ? "var(--rust)" : "var(--green)" }}>{s.delta}</div>
                  </td>
                  <td><MiniTrend data={s.trend}/></td>
                  <td>
                    {s.hwStatus === "done" && <span className="chip chip-green"><span className="dot"/>{s.hw}</span>}
                    {s.hwStatus === "progress" && <span className="chip chip-amber"><span className="dot"/>{s.hw}</span>}
                    {s.hwStatus === "late" && <span className="chip chip-rust"><span className="dot"/>{s.hw} late</span>}
                    {s.hwStatus === "overdue" && <span className="chip chip-rust"><span className="dot"/>overdue</span>}
                  </td>
                  <td className="mono text-sm">{s.streak > 0 ? `🔥 ${s.streak}` : "—"}</td>
                  <td className="text-xs text-ink-3">{s.last}</td>
                  <td><button className="btn btn-sm btn-ghost"><Icon name="chevron-right" size={14}/></button></td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>

        <div className="flex-col gap-5" style={{ display: "flex" }}>
          <div className="card">
            <div className="h-section">Review queue · 3 submissions</div>
            <SubmissionRow name="Leo Alvarez" piece="n" tone="green"
              task="Italian game — 5 puzzles" detail="Solved 4/5 · stumbled on #3" flagged/>
            <SubmissionRow name="Matteo Rossi" piece="p" tone="sky"
              task="Mate in 2 · Set 13" detail="Submitted 2 days ago · overdue return" overdue/>
            <SubmissionRow name="Zara Ahmed" piece="b" tone="green"
              task="Endgame quiz: rook + pawn" detail="Score 78% · 1 blunder flagged"/>
            <button className="btn btn-ghost btn-sm w-full mt-3" style={{ justifyContent: "center" }}>Review all submissions</button>
          </div>

          <div className="card">
            <div className="h-section">This week's schedule</div>
            <div className="flex-col" style={{ display: "flex", gap: 0 }}>
              <ScheduleRow date="Wed 19" time="16:00" title="Bishop Class · Group lesson" sub="The Evergreen Game · Anderssen–Dufresne"/>
              <ScheduleRow date="Thu 20" time="15:30" title="1:1 with Amelia Chen" sub="Tournament prep · French Defence"/>
              <ScheduleRow date="Fri 21" time="17:00" title="Club simul event" sub="12 students signed up · 8 seats left"/>
              <ScheduleRow date="Sat 22" time="10:00" title="Spring Invitational — Round 1" sub="Tournament · 6 of your students competing"/>
            </div>
          </div>
        </div>
      </div>

      {/* Activity insight */}
      <div className="grid gap-5 mt-5" style={{ gridTemplateColumns: "1fr 1fr" }}>
        <div className="card">
          <div className="h-section">Class performance · last 30 days</div>
          <div className="flex gap-6 items-center">
            <RatingChart height={160} data={[
              { x: "W1", y: 1232, avg: 1215 },
              { x: "W2", y: 1248, avg: 1220 },
              { x: "W3", y: 1260, avg: 1224 },
              { x: "W4", y: 1284, avg: 1228 },
            ]}/>
          </div>
        </div>

        <div className="card">
          <div className="h-section">Weak themes this month</div>
          <p className="deck mb-4" style={{ fontSize: 14 }}>Students are struggling with these themes. Consider assigning them.</p>
          <div className="flex-col" style={{ display: "flex", gap: 10 }}>
            <WeakTheme title="Back-rank mates" solved="41%" delta="−12% vs last month"/>
            <WeakTheme title="Pawn endgames: opposition" solved="52%" delta="−3%"/>
            <WeakTheme title="Pinned pieces" solved="68%" delta="+2%"/>
            <WeakTheme title="Queen & pawn endings" solved="29%" delta="New"/>
          </div>
        </div>
      </div>
    </>
  );
}

function KPI({ label, value, delta, neg, neutral }) {
  return (
    <div className="card stat">
      <div className="label">{label}</div>
      <div className="value">{value}</div>
      <div className={`delta ${neg ? "neg" : ""}`} style={{ color: neutral ? "var(--ink-4)" : undefined }}>{delta}</div>
    </div>
  );
}

function MiniTrend({ data }) {
  const W = 80, H = 28;
  const min = Math.min(...data), max = Math.max(...data);
  const step = W / (data.length - 1);
  const path = data.map((v, i) => `${i === 0 ? "M" : "L"} ${i * step} ${H - ((v - min) / (max - min || 1)) * H}`).join(" ");
  const rising = data[data.length - 1] >= data[0];
  return (
    <svg viewBox={`0 0 ${W} ${H}`} width={W} height={H}>
      <path d={path} fill="none" stroke={rising ? "var(--green)" : "var(--rust)"} strokeWidth="1.5"/>
    </svg>
  );
}

function SubmissionRow({ name, piece, tone, task, detail, flagged, overdue }) {
  return (
    <div className="flex items-center gap-3" style={{ padding: "12px 0", borderBottom: "1px dashed var(--rule)" }}>
      <PieceAvatar piece={piece} tone={tone} size={34}/>
      <div className="flex-1">
        <div className="flex items-center gap-2">
          <span className="fw-500 text-sm">{name}</span>
          {flagged && <span className="chip chip-amber">1 flag</span>}
          {overdue && <span className="chip chip-rust">Overdue</span>}
        </div>
        <div className="text-xs text-ink-3">{task}</div>
        <div className="text-xs text-ink-4">{detail}</div>
      </div>
      <button className="btn btn-sm btn-ghost">Review</button>
    </div>
  );
}

function ScheduleRow({ date, time, title, sub }) {
  const [dow, d] = date.split(" ");
  return (
    <div className="flex items-center gap-3" style={{ padding: "12px 0", borderBottom: "1px dashed var(--rule)" }}>
      <div className="date-block" style={{ width: 46 }}>
        <div className="dow">{dow}</div>
        <div className="d">{d}</div>
      </div>
      <div className="flex-1">
        <div className="flex items-center gap-2">
          <span className="fw-500 text-sm">{title}</span>
          <span className="mono text-xs text-ink-4">· {time}</span>
        </div>
        <div className="text-xs text-ink-3">{sub}</div>
      </div>
      <Icon name="chevron-right" size={14} className="text-ink-4"/>
    </div>
  );
}

function WeakTheme({ title, solved, delta }) {
  return (
    <div className="flex items-center gap-3" style={{ padding: "10px 0", borderBottom: "1px dashed var(--rule)" }}>
      <div style={{ width: 36, height: 36, borderRadius: 8, background: "var(--paper-2)", display: "grid", placeItems: "center" }}>
        <Icon name="target" size={18} className="text-ink-3"/>
      </div>
      <div className="flex-1">
        <div className="fw-500 text-sm">{title}</div>
        <div className="text-xs text-ink-3">Solve rate <b className="mono">{solved}</b> · {delta}</div>
      </div>
      <button className="btn btn-sm btn-ghost"><Icon name="plus" size={12}/> Assign</button>
    </div>
  );
}

// --------------- LESSON AUTHORING ---------------
function LessonAuthor({ go }) {
  const [title, setTitle] = React.useState("The Evergreen Game — Anderssen vs Dufresne, 1852");
  const [annotation, setAnnotation] = React.useState("Here Anderssen plays e5, not to win material but to cut Black's queen from its defenders.");

  return (
    <>
      <div className="flex items-center justify-between mb-5">
        <div className="flex items-center gap-3 text-sm text-ink-3">
          <a onClick={() => go("dashboard")} style={{ cursor: "pointer" }}>Lessons</a>
          <Icon name="chevron-right" size={14}/>
          <span>Classical Sacrifice</span>
          <Icon name="chevron-right" size={14}/>
          <span className="text-ink">Editing · move 9</span>
        </div>
        <div className="flex items-center gap-3">
          <span className="chip"><span className="dot" style={{ background: "var(--green)" }}/>Saved just now</span>
          <button className="btn btn-ghost btn-sm">Preview</button>
          <button className="btn">Publish to class</button>
        </div>
      </div>

      <div className="grid gap-5" style={{ gridTemplateColumns: "280px 1fr 340px" }}>
        {/* Left: chapter tree */}
        <div className="card" style={{ padding: 16 }}>
          <div className="h-section">Chapters</div>
          <ChapterItem idx="1" title="Introduction" moves={12}/>
          <ChapterItem idx="2" title="The Italian attack" moves={28}/>
          <ChapterItem idx="3" title="Evans Gambit basics" moves={34}/>
          <ChapterItem idx="4" title="The Evergreen Game" moves={42} active/>
          <ChapterItem idx="5" title="Morphy's sacrifice" moves={31}/>
          <ChapterItem idx="6" title="Modern refutations" moves={18} draft/>
          <button className="btn btn-ghost btn-sm w-full mt-3" style={{ justifyContent: "center" }}><Icon name="plus" size={12}/> New chapter</button>
        </div>

        {/* Center: board + move editor */}
        <div className="flex-col" style={{ display: "flex", gap: 16 }}>
          <input
            value={title}
            onChange={e => setTitle(e.target.value)}
            style={{
              font: "inherit", fontFamily: "var(--serif)", fontSize: 28, fontWeight: 500,
              letterSpacing: "-0.015em",
              border: 0, background: "transparent", outline: "none",
              borderBottom: "1px dashed var(--rule)",
              padding: "8px 0",
              color: "var(--ink)", width: "100%",
            }}
          />

          <div className="flex gap-4">
            <FramedBoard
              fen={LESSON.boardFEN}
              size={440}
              lastMove={LESSON.lastMove}
              arrows={[
                { from: "b3", to: "f7", color: "oklch(60% 0.17 35)" },
                { from: "e4", to: "e5", color: "oklch(65% 0.15 140)" },
              ]}
            />

            {/* Annotation tools */}
            <div className="flex-col" style={{ display: "flex", gap: 12, flex: 1 }}>
              <div className="h-section">Drawing tools</div>
              <div className="flex gap-2">
                <button className="btn btn-ghost btn-sm" style={{ background: "var(--paper-2)" }}>
                  <span style={{ width: 14, height: 14, borderRadius: 3, background: "oklch(65% 0.15 140)" }}/> Arrow
                </button>
                <button className="btn btn-ghost btn-sm">
                  <span style={{ width: 14, height: 14, borderRadius: 3, background: "oklch(60% 0.17 35)" }}/> Danger
                </button>
                <button className="btn btn-ghost btn-sm">
                  <span style={{ width: 14, height: 14, borderRadius: 3, background: "oklch(72% 0.15 75)" }}/> Idea
                </button>
                <button className="btn btn-ghost btn-sm"><Icon name="target" size={12}/> Circle</button>
              </div>

              <div className="h-section mt-2">This move · annotation</div>
              <textarea
                value={annotation}
                onChange={e => setAnnotation(e.target.value)}
                style={{
                  font: "inherit", fontFamily: "var(--serif)", fontSize: 15, lineHeight: 1.5,
                  padding: 14, minHeight: 120,
                  background: "var(--card)", border: "1px solid var(--rule)", borderRadius: 8,
                  color: "var(--ink-2)", resize: "vertical", outline: "none",
                }}
              />
              <div className="flex gap-2">
                <span className="chip chip-green"><Icon name="sparkle" size={12}/> !! Brilliant</span>
                <span className="chip"><Icon name="check" size={12}/> ! Good</span>
                <span className="chip chip-amber">?! Dubious</span>
                <span className="chip chip-rust">?? Blunder</span>
              </div>

              <div className="h-section mt-2">After this move, ask students:</div>
              <div className="card" style={{ padding: 12, background: "var(--paper-2)", borderStyle: "dashed" }}>
                <div className="text-xs text-ink-3 mb-2 fw-500">Comprehension check</div>
                <div className="fw-500 text-sm mb-2">"What piece did e5 just disconnect?"</div>
                <div className="flex-col" style={{ display: "flex", gap: 6 }}>
                  <div className="flex items-center gap-2 text-xs"><span className="letter" style={{ width: 18, height: 18, borderRadius: 999, background: "var(--paper-3)", display: "grid", placeItems: "center", fontFamily: "var(--mono)" }}>A</span> The bishop on g6</div>
                  <div className="flex items-center gap-2 text-xs"><span className="letter" style={{ width: 18, height: 18, borderRadius: 999, background: "var(--green)", color: "#fff", display: "grid", placeItems: "center", fontFamily: "var(--mono)" }}>B</span> The queen on f6 ✓</div>
                  <div className="flex items-center gap-2 text-xs"><span className="letter" style={{ width: 18, height: 18, borderRadius: 999, background: "var(--paper-3)", display: "grid", placeItems: "center", fontFamily: "var(--mono)" }}>C</span> The knight on c6</div>
                </div>
                <button className="btn btn-ghost btn-sm mt-3"><Icon name="edit" size={12}/> Edit question</button>
              </div>
            </div>
          </div>

          <div className="flex gap-2">
            <button className="btn btn-ghost"><Icon name="upload" size={14}/> Import PGN</button>
            <button className="btn btn-ghost"><Icon name="video" size={14}/> Attach video</button>
            <button className="btn btn-ghost"><Icon name="puzzle" size={14}/> Add exercise</button>
            <div style={{ flex: 1 }}/>
            <button className="btn btn-ghost"><Icon name="arrow-left" size={14}/> Prev</button>
            <button className="btn">Next move <Icon name="arrow-right" size={14}/></button>
          </div>
        </div>

        {/* Right: move list */}
        <div className="card card-flush">
          <div style={{ padding: "14px 18px", borderBottom: "1px solid var(--rule)" }}>
            <div className="h-section" style={{ marginBottom: 0 }}>Move list (21)</div>
          </div>
          <div style={{ padding: "10px 8px", maxHeight: 500, overflow: "auto" }}>
            <div className="moves">
              {LESSON.moves.map(m => (
                <div key={m.n} className="move-row">
                  <span className="move-num">{m.n}.</span>
                  <span className="move">{m.w}</span>
                  <span className={`move ${m.tag === "brilliant" ? "brilliant" : ""} ${m.n === 9 ? "active" : ""}`}>{m.b}</span>
                  {m.wn && <span className="annotation">{m.wn}</span>}
                </div>
              ))}
              <div className="move-row" style={{ opacity: 0.5 }}>
                <span className="move-num">10.</span>
                <span className="mono text-xs text-ink-4">... more moves</span>
              </div>
            </div>
          </div>
        </div>
      </div>
    </>
  );
}

function ChapterItem({ idx, title, moves, active, draft }) {
  return (
    <div className="flex items-center gap-3" style={{
      padding: "10px 8px", borderRadius: 6,
      background: active ? "var(--ink)" : "transparent",
      color: active ? "var(--paper)" : "var(--ink-2)",
      cursor: "pointer",
    }}>
      <span className="mono text-xs" style={{ color: active ? "var(--paper-3)" : "var(--ink-4)", minWidth: 18 }}>{idx}</span>
      <span className="text-sm flex-1 fw-500">{title}</span>
      {draft && <span className="chip" style={{ padding: "1px 6px", fontSize: 9 }}>Draft</span>}
      <span className="mono text-xs" style={{ color: active ? "var(--paper-3)" : "var(--ink-4)" }}>{moves}</span>
    </div>
  );
}

// --------------- ASSIGN HOMEWORK FLOW ---------------
function AssignHomework({ go }) {
  const [selected, setSelected] = React.useState({ "Leo Alvarez": true, "Sofia Okafor": true, "Henry Park": false, "Amelia Chen": true, "Matteo Rossi": false, "Priya Raman": true, "Jonas Weber": true, "Zara Ahmed": true });
  const picks = Object.keys(selected).filter(k => selected[k]);

  return (
    <>
      <div className="flex items-center justify-between mb-5">
        <div>
          <div className="eyebrow">New assignment</div>
          <h1 className="h-display mt-2">Assign homework</h1>
        </div>
        <div className="flex items-center gap-3">
          <button className="btn btn-ghost" onClick={() => go("dashboard")}>Cancel</button>
          <button className="btn btn-ghost">Save as draft</button>
          <button className="btn btn-green">Assign to {picks.length} student{picks.length === 1 ? "" : "s"}</button>
        </div>
      </div>

      <div className="grid gap-5" style={{ gridTemplateColumns: "1.3fr 1fr" }}>
        <div className="flex-col" style={{ display: "flex", gap: 20 }}>
          {/* Step 1: Pick content */}
          <div className="card">
            <div className="flex items-center gap-3 mb-4">
              <StepBadge n="1" active/>
              <div>
                <div className="fw-600">Choose the content</div>
                <div className="text-xs text-ink-3">Pick a lesson, puzzle pack, or build a custom set</div>
              </div>
            </div>

            <div className="grid-3 gap-3">
              <ContentTile icon="book" title="Lesson" sub="The Evergreen Game" picked/>
              <ContentTile icon="puzzle" title="Puzzle pack" sub="Mate in 2 · Set 14"/>
              <ContentTile icon="video" title="Video lesson" sub="King & pawn endgames"/>
            </div>

            <div className="rule"/>

            <div className="flex items-center justify-between mb-3">
              <div className="text-sm fw-500">Attached exercises · 5</div>
              <button className="btn btn-ghost btn-sm"><Icon name="plus" size={12}/> Add from library</button>
            </div>
            <div className="flex-col" style={{ display: "flex", gap: 6 }}>
              <ExerciseRow n="1" title="White to move — find the break" theme="Positional" mini="r1bqk2r/pp1nbppp/2p1pn2/3p4/2PP4/2N1PN2/PP3PPP/R1BQKB1R"/>
              <ExerciseRow n="2" title="Mate in 2 (white)" theme="Tactics" mini="6k1/5ppp/8/8/8/8/5PPP/R3R1K1"/>
              <ExerciseRow n="3" title="Which piece was disconnected?" theme="MCQ" mini="r1b1k1nr/ppppqppp/1Bn3b1/4P3/2B5/1QP2N2/P4PPP/RNB2RK1"/>
              <ExerciseRow n="4" title="Annotate Black's best plan" theme="Annotation" mini="r1bq1rk1/pp1nbppp/2p1pn2/3p4/2PP4/2N1PN2/PP2BPPP/R1BQ1RK1"/>
              <ExerciseRow n="5" title="Play it out vs bot" theme="Practice" mini="4r1k1/5ppp/8/4N3/8/8/5PPP/4R1K1"/>
            </div>
          </div>

          {/* Step 2: Settings */}
          <div className="card">
            <div className="flex items-center gap-3 mb-4">
              <StepBadge n="2" active/>
              <div>
                <div className="fw-600">Set due date & rules</div>
                <div className="text-xs text-ink-3">Homework can be timed, hint-locked, or open-book</div>
              </div>
            </div>

            <div className="grid-2 gap-4">
              <Field label="Due date">
                <input type="text" defaultValue="Friday, 21 April · 18:00" style={inputStyle}/>
              </Field>
              <Field label="Time limit per exercise">
                <div className="seg">
                  <button>None</button>
                  <button className="on">5 min</button>
                  <button>10 min</button>
                  <button>Custom</button>
                </div>
              </Field>
              <Field label="Hints allowed?">
                <div className="seg">
                  <button className="on">Yes · −5 XP</button>
                  <button>Yes · free</button>
                  <button>No hints</button>
                </div>
              </Field>
              <Field label="Submission mode">
                <div className="seg">
                  <button className="on">Auto-graded</button>
                  <button>Coach reviews</button>
                </div>
              </Field>
            </div>

            <div className="rule"/>
            <Field label="Message to students (optional)">
              <textarea placeholder="Hey Bishop Class — revisit the Evergreen game and try the exercises after each move…"
                style={{ ...inputStyle, fontFamily: "var(--serif)", fontStyle: "italic", minHeight: 70, resize: "vertical" }}/>
            </Field>
          </div>
        </div>

        {/* Step 3: Recipients */}
        <div className="card" style={{ position: "sticky", top: 100, alignSelf: "start" }}>
          <div className="flex items-center gap-3 mb-4">
            <StepBadge n="3" active/>
            <div>
              <div className="fw-600">Who gets this homework?</div>
              <div className="text-xs text-ink-3">Groups or individuals</div>
            </div>
          </div>

          <div className="seg mb-3">
            <button className="on">Individuals</button>
            <button>Groups</button>
            <button>Tournament team</button>
          </div>

          <div className="flex items-center gap-2 mb-3">
            <button className="btn btn-ghost btn-sm" onClick={() => {
              const next = {}; CLASS_ROSTER.forEach(s => next[s.name] = true); setSelected(next);
            }}>Select all</button>
            <button className="btn btn-ghost btn-sm" onClick={() => setSelected({})}>Clear</button>
            <span className="text-xs text-ink-3" style={{ marginLeft: "auto" }}>{picks.length} of {CLASS_ROSTER.length} selected</span>
          </div>

          <div className="flex-col" style={{ display: "flex", gap: 4, maxHeight: 360, overflow: "auto", paddingRight: 4 }}>
            {CLASS_ROSTER.map((s, i) => (
              <div key={i} className="flex items-center gap-3" style={{
                padding: "8px 10px", borderRadius: 6,
                background: selected[s.name] ? "var(--green-soft)" : "transparent",
                cursor: "pointer",
              }} onClick={() => setSelected({ ...selected, [s.name]: !selected[s.name] })}>
                <div style={{
                  width: 18, height: 18, borderRadius: 4,
                  background: selected[s.name] ? "var(--green)" : "transparent",
                  border: "1.5px solid " + (selected[s.name] ? "var(--green)" : "var(--rule-2)"),
                  display: "grid", placeItems: "center", color: "#fff",
                }}>
                  {selected[s.name] && <Icon name="check" size={12}/>}
                </div>
                <PieceAvatar piece={s.piece} tone={s.tone} size={28}/>
                <div className="flex-1">
                  <div className="text-sm fw-500">{s.name}</div>
                  <div className="text-xs text-ink-4 mono">{s.rating} · Age {s.age}</div>
                </div>
                {s.hwStatus === "overdue" && <span className="chip chip-rust">⚠ Behind</span>}
              </div>
            ))}
          </div>

          <div className="rule"/>
          <div className="text-xs text-ink-3">
            <div className="flex justify-between mb-1"><span>Estimated time</span><span className="mono text-ink">~ 35 min</span></div>
            <div className="flex justify-between mb-1"><span>Avg. difficulty</span><span className="text-ink">Intermediate</span></div>
            <div className="flex justify-between"><span>XP on completion</span><span className="mono text-ink">+120 XP</span></div>
          </div>
        </div>
      </div>
    </>
  );
}

const inputStyle = {
  font: "inherit", fontFamily: "var(--sans)", fontSize: 14,
  padding: "10px 12px",
  background: "var(--card)", border: "1px solid var(--rule)", borderRadius: 8,
  color: "var(--ink)", outline: "none", width: "100%",
};

function Field({ label, children }) {
  return (
    <div className="flex-col" style={{ display: "flex", gap: 6 }}>
      <label className="text-xs fw-600 text-ink-3" style={{ letterSpacing: "0.06em", textTransform: "uppercase" }}>{label}</label>
      {children}
    </div>
  );
}

function StepBadge({ n, active }) {
  return (
    <div style={{
      width: 32, height: 32, borderRadius: 999,
      background: active ? "var(--ink)" : "var(--paper-2)",
      color: active ? "var(--paper)" : "var(--ink-3)",
      display: "grid", placeItems: "center",
      fontFamily: "var(--serif)", fontWeight: 600, fontSize: 15,
    }}>{n}</div>
  );
}

function ContentTile({ icon, title, sub, picked }) {
  return (
    <div style={{
      padding: 14, borderRadius: 10,
      background: picked ? "var(--green-soft)" : "var(--card)",
      border: `1px solid ${picked ? "var(--green-mid)" : "var(--rule)"}`,
      cursor: "pointer",
    }}>
      <div className="flex items-center gap-2 mb-2">
        <Icon name={icon} size={18} className={picked ? "text-green" : "text-ink-3"}/>
        <span className="fw-600 text-sm">{title}</span>
        {picked && <span style={{ marginLeft: "auto" }}><Icon name="check" size={16} className="text-green"/></span>}
      </div>
      <div className="text-xs text-ink-3">{sub}</div>
    </div>
  );
}

function ExerciseRow({ n, title, theme, mini }) {
  return (
    <div className="flex items-center gap-3" style={{ padding: "8px 10px", borderRadius: 6, background: "var(--paper-2)" }}>
      <span className="mono text-xs text-ink-4">{n.padStart(2, "0")}</span>
      <MiniBoard fen={mini} size={42}/>
      <div className="flex-1">
        <div className="text-sm fw-500">{title}</div>
        <div className="text-xs text-ink-3">{theme}</div>
      </div>
      <button className="btn btn-ghost btn-sm"><Icon name="x" size={12}/></button>
    </div>
  );
}

Object.assign(window, { TeacherDashboard, LessonAuthor, AssignHomework });
