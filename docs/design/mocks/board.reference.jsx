// Chess board component — FEN-based, with optional highlights, hints, arrows, coords, last-move.

function parseFEN(fen) {
  const [board] = fen.split(" ");
  const rows = board.split("/");
  const grid = [];
  rows.forEach((row) => {
    const r = [];
    for (const ch of row) {
      if (/\d/.test(ch)) {
        for (let i = 0; i < parseInt(ch, 10); i++) r.push(null);
      } else {
        r.push(ch);
      }
    }
    grid.push(r);
  });
  return grid; // 8 rows, top = rank 8
}

const FILES = ["a","b","c","d","e","f","g","h"];
function sqToFileRank(sq) {
  return { file: FILES.indexOf(sq[0]), rank: 8 - parseInt(sq[1], 10) };
}

function Board({
  fen = "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR",
  size = 420,
  coords = true,
  lastMove = null,          // [from, to] like ['e2','e4']
  hints = [],               // ['e4','d4']
  goodSquares = [],
  badSquares = [],
  arrows = [],              // [{from,to,color}]
  orientation = "white",
  onSquareClick,
}) {
  const grid = parseFEN(fen);
  const isFlipped = orientation === "black";
  const squares = [];
  for (let r = 0; r < 8; r++) {
    for (let f = 0; f < 8; f++) {
      const dispR = isFlipped ? 7 - r : r;
      const dispF = isFlipped ? 7 - f : f;
      const piece = grid[dispR][dispF];
      const sqName = FILES[dispF] + (8 - dispR);
      const isLight = (dispR + dispF) % 2 === 0;
      const classes = ["sq", isLight ? "l" : "d"];
      if (lastMove && (sqName === lastMove[0] || sqName === lastMove[1])) classes.push("highlight-last");
      if (hints.includes(sqName)) {
        classes.push("highlight-hint");
        if (piece) classes.push("has-piece");
      }
      if (goodSquares.includes(sqName)) classes.push("highlight-good");
      if (badSquares.includes(sqName)) classes.push("highlight-bad");

      squares.push(
        <div
          key={`${r}-${f}`}
          className={classes.join(" ")}
          onClick={() => onSquareClick && onSquareClick(sqName)}
        >
          {coords && f === 0 && <span className="rank-lbl">{8 - dispR}</span>}
          {coords && r === 7 && <span className="file-lbl">{FILES[dispF]}</span>}
          <Piece p={piece} />
        </div>
      );
    }
  }

  // Arrows layer
  const arrowLayer = arrows.length > 0 && (
    <svg className="board-svg-layer" viewBox="0 0 800 800" preserveAspectRatio="none">
      <defs>
        <marker id="arrow-head" viewBox="0 0 10 10" refX="6" refY="5" markerWidth="4" markerHeight="4" orient="auto-start-reverse">
          <path d="M 0 0 L 10 5 L 0 10 z" fill="currentColor"/>
        </marker>
      </defs>
      {arrows.map((a, i) => {
        const from = sqToFileRank(a.from);
        const to = sqToFileRank(a.to);
        const fx = (isFlipped ? 7 - from.file : from.file) * 100 + 50;
        const fy = (isFlipped ? 7 - from.rank : from.rank) * 100 + 50;
        const tx = (isFlipped ? 7 - to.file : to.file) * 100 + 50;
        const ty = (isFlipped ? 7 - to.rank : to.rank) * 100 + 50;
        return (
          <g key={i} style={{ color: a.color || "#e8b923" }}>
            <line x1={fx} y1={fy} x2={tx} y2={ty}
              stroke="currentColor" strokeWidth="14" strokeLinecap="round"
              opacity="0.85" markerEnd="url(#arrow-head)"/>
          </g>
        );
      })}
    </svg>
  );

  return (
    <div className="board" style={{ width: size, height: size }}>
      {squares}
      {arrowLayer}
    </div>
  );
}

// A framed board with wood surround
function FramedBoard(props) {
  return (
    <div className="board-wrap" style={{ width: (props.size || 420) + 28 }}>
      <Board {...props} />
    </div>
  );
}

// Mini board — no surround, small, for cards
function MiniBoard({ fen, size = 120, lastMove, orientation = "white" }) {
  return (
    <div style={{ width: size, height: size }}>
      <Board fen={fen} size={size} coords={false} lastMove={lastMove} orientation={orientation}/>
    </div>
  );
}

// Evaluation bar (vertical) — eval in pawn units. Positive = white.
function EvalBar({ value = 0, height = 420 }) {
  const clamped = Math.max(-6, Math.min(6, value));
  const pct = 50 - (clamped / 6) * 50;
  return (
    <div className="eval-bar" style={{ height }}>
      <div className="eval-black" style={{ height: `${pct}%` }}/>
      <div className="eval-num" style={{
        color: pct > 50 ? "#fff" : "#1c1d1a",
        bottom: pct > 50 ? "auto" : 4,
        top: pct > 50 ? 4 : "auto",
      }}>
        {value >= 0 ? `+${value.toFixed(1)}` : value.toFixed(1)}
      </div>
    </div>
  );
}

Object.assign(window, { Board, FramedBoard, MiniBoard, EvalBar, parseFEN, FILES });
