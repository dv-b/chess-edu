// Shared UI primitives: icons, cards, chips, charts.

// Minimal icons (hand-authored SVG)
const Icon = ({ name, size = 16, stroke = 1.6, className = "" }) => {
  const s = size;
  const sw = stroke;
  const common = {
    width: s, height: s, viewBox: "0 0 24 24", fill: "none",
    stroke: "currentColor", strokeWidth: sw, strokeLinecap: "round", strokeLinejoin: "round",
    className,
  };
  switch (name) {
    case "search": return <svg {...common}><circle cx="11" cy="11" r="7"/><path d="m20 20-3.5-3.5"/></svg>;
    case "bell":   return <svg {...common}><path d="M6 8a6 6 0 0 1 12 0c0 7 3 9 3 9H3s3-2 3-9"/><path d="M10 21a2 2 0 0 0 4 0"/></svg>;
    case "plus":   return <svg {...common}><path d="M12 5v14M5 12h14"/></svg>;
    case "check":  return <svg {...common}><path d="M20 6 9 17l-5-5"/></svg>;
    case "x":      return <svg {...common}><path d="M18 6 6 18M6 6l12 12"/></svg>;
    case "arrow-right": return <svg {...common}><path d="M5 12h14M13 5l7 7-7 7"/></svg>;
    case "arrow-left":  return <svg {...common}><path d="M19 12H5M11 5l-7 7 7 7"/></svg>;
    case "chevron-right": return <svg {...common}><path d="m9 6 6 6-6 6"/></svg>;
    case "chevron-down":  return <svg {...common}><path d="m6 9 6 6 6-6"/></svg>;
    case "play":   return <svg {...common}><path d="M6 4v16l14-8z" fill="currentColor"/></svg>;
    case "pause":  return <svg {...common}><path d="M6 5v14M18 5v14"/></svg>;
    case "skip-fwd": return <svg {...common}><path d="m6 4 10 8-10 8V4zM18 4v16"/></svg>;
    case "skip-bwd": return <svg {...common}><path d="m18 4-10 8 10 8V4zM6 4v16"/></svg>;
    case "flag":   return <svg {...common}><path d="M4 21V4h13l-2 4 2 4H4"/></svg>;
    case "flame":  return <svg {...common}><path d="M12 3s5 4 5 10a5 5 0 0 1-10 0c0-2 1-3 1-3s-1 0-1-2 2-3 5-5z"/></svg>;
    case "star":   return <svg {...common}><path d="m12 3 3 6 6 .9-4.5 4.3 1 6.3-5.5-3-5.5 3 1-6.3L3 9.9 9 9z"/></svg>;
    case "trophy": return <svg {...common}><path d="M8 3h8v5a4 4 0 0 1-8 0V3zM4 5h4v2a3 3 0 0 1-4-1zM20 5h-4v2a3 3 0 0 0 4-1zM9 14h6l-1 5h-4z"/></svg>;
    case "clock":  return <svg {...common}><circle cx="12" cy="12" r="9"/><path d="M12 7v5l3 2"/></svg>;
    case "calendar":return <svg {...common}><rect x="3" y="5" width="18" height="16" rx="2"/><path d="M16 3v4M8 3v4M3 10h18"/></svg>;
    case "user":   return <svg {...common}><circle cx="12" cy="8" r="4"/><path d="M4 21a8 8 0 0 1 16 0"/></svg>;
    case "users":  return <svg {...common}><circle cx="9" cy="8" r="4"/><path d="M2 21a7 7 0 0 1 14 0"/><path d="M16 4a4 4 0 0 1 0 8M22 21a7 7 0 0 0-5-7"/></svg>;
    case "book":   return <svg {...common}><path d="M4 4h12a3 3 0 0 1 3 3v14l-3-2H4z"/><path d="M4 4v16"/></svg>;
    case "video":  return <svg {...common}><rect x="3" y="6" width="13" height="12" rx="2"/><path d="m16 10 5-3v10l-5-3z"/></svg>;
    case "target": return <svg {...common}><circle cx="12" cy="12" r="9"/><circle cx="12" cy="12" r="5"/><circle cx="12" cy="12" r="1" fill="currentColor"/></svg>;
    case "puzzle": return <svg {...common}><path d="M14 4a2 2 0 1 1 4 0c0 1 0 2 2 2h2v4c0 2-1 2-2 2a2 2 0 1 0 0 4c1 0 2 0 2 2v4h-4c-2 0-2-1-2-2a2 2 0 1 0-4 0c0 1 0 2-2 2H6v-4c0-2 1-2 2-2a2 2 0 1 1 0-4c-1 0-2 0-2-2V6h4c2 0 2-1 2-2z"/></svg>;
    case "board":  return <svg {...common}><rect x="3" y="3" width="18" height="18" rx="1"/><path d="M9 3v18M15 3v18M3 9h18M3 15h18"/></svg>;
    case "edit":   return <svg {...common}><path d="M12 20h9"/><path d="M16.5 3.5a2.1 2.1 0 0 1 3 3L7 19l-4 1 1-4z"/></svg>;
    case "settings": return <svg {...common}><circle cx="12" cy="12" r="3"/><path d="M19.4 15a1.65 1.65 0 0 0 .33 1.82l.06.06a2 2 0 1 1-2.83 2.83l-.06-.06a1.65 1.65 0 0 0-1.82-.33 1.65 1.65 0 0 0-1 1.51V21a2 2 0 1 1-4 0v-.09A1.65 1.65 0 0 0 9 19.4a1.65 1.65 0 0 0-1.82.33l-.06.06a2 2 0 1 1-2.83-2.83l.06-.06a1.65 1.65 0 0 0 .33-1.82 1.65 1.65 0 0 0-1.51-1H3a2 2 0 1 1 0-4h.09A1.65 1.65 0 0 0 4.6 9a1.65 1.65 0 0 0-.33-1.82l-.06-.06a2 2 0 1 1 2.83-2.83l.06.06a1.65 1.65 0 0 0 1.82.33H9a1.65 1.65 0 0 0 1-1.51V3a2 2 0 1 1 4 0v.09a1.65 1.65 0 0 0 1 1.51 1.65 1.65 0 0 0 1.82-.33l.06-.06a2 2 0 1 1 2.83 2.83l-.06.06a1.65 1.65 0 0 0-.33 1.82V9a1.65 1.65 0 0 0 1.51 1H21a2 2 0 1 1 0 4h-.09a1.65 1.65 0 0 0-1.51 1z"/></svg>;
    case "home":   return <svg {...common}><path d="m3 10 9-7 9 7v10a2 2 0 0 1-2 2h-4v-6h-6v6H5a2 2 0 0 1-2-2z"/></svg>;
    case "lightning": return <svg {...common}><path d="M13 2 3 14h7l-1 8 10-12h-7z"/></svg>;
    case "mortarboard": return <svg {...common}><path d="M2 10 12 5l10 5-10 5L2 10z"/><path d="M6 12v5c2 2 10 2 12 0v-5"/></svg>;
    case "chart":  return <svg {...common}><path d="M3 3v18h18"/><path d="M7 15l4-5 4 3 5-7"/></svg>;
    case "sparkle":return <svg {...common}><path d="M12 3v4M12 17v4M3 12h4M17 12h4M5.6 5.6l2.8 2.8M15.6 15.6l2.8 2.8M5.6 18.4l2.8-2.8M15.6 8.4l2.8-2.8"/></svg>;
    case "message":return <svg {...common}><path d="M21 11a8 8 0 0 1-12 7l-6 2 2-5A8 8 0 1 1 21 11z"/></svg>;
    case "filter": return <svg {...common}><path d="M3 4h18l-7 9v7l-4-2v-5z"/></svg>;
    case "upload": return <svg {...common}><path d="M12 15V3M7 8l5-5 5 5M5 21h14"/></svg>;
    case "sun":    return <svg {...common}><circle cx="12" cy="12" r="4"/><path d="M12 2v2M12 20v2M4.9 4.9l1.4 1.4M17.7 17.7l1.4 1.4M2 12h2M20 12h2M4.9 19.1l1.4-1.4M17.7 6.3l1.4-1.4"/></svg>;
    case "moon":   return <svg {...common}><path d="M20 14A8 8 0 1 1 10 4a7 7 0 0 0 10 10z"/></svg>;
    default: return null;
  }
};

// Piece-silhouette avatar (for kid users)
const PieceAvatar = ({ piece = "n", tone = "green", size = 40 }) => {
  const bg = {
    green:  "linear-gradient(135deg, oklch(90% 0.04 140), oklch(82% 0.06 140))",
    amber:  "linear-gradient(135deg, oklch(92% 0.05 75),  oklch(84% 0.08 75))",
    plum:   "linear-gradient(135deg, oklch(90% 0.04 330), oklch(82% 0.07 330))",
    sky:    "linear-gradient(135deg, oklch(90% 0.03 240), oklch(82% 0.05 240))",
    rust:   "linear-gradient(135deg, oklch(90% 0.04 35),  oklch(82% 0.08 35))",
    ink:    "linear-gradient(135deg, #2b2b2b, #1c1d1a)",
  }[tone];
  const color = tone === "ink" ? "#ece7d4" : {
    green: "oklch(35% 0.08 140)",
    amber: "oklch(38% 0.10 75)",
    plum:  "oklch(38% 0.08 330)",
    sky:   "oklch(38% 0.07 240)",
    rust:  "oklch(38% 0.12 35)",
  }[tone];
  return (
    <div className="avatar piece-avatar" style={{ width: size, height: size, background: bg, borderColor: "transparent" }}>
      <PieceIcon p={piece} size={size * 0.7} color={color}/>
    </div>
  );
};

// Rating curve chart
function RatingChart({ data, height = 160, accent = "var(--green)", fill = "oklch(90% 0.04 140)" }) {
  // data: [{x: month label, y: rating}]
  const W = 720, H = height;
  const pad = { l: 40, r: 10, t: 14, b: 22 };
  const ys = data.map(d => d.y);
  const yMin = Math.min(...ys) - 20;
  const yMax = Math.max(...ys) + 20;
  const xStep = (W - pad.l - pad.r) / (data.length - 1);
  const yFor = (y) => pad.t + (1 - (y - yMin) / (yMax - yMin)) * (H - pad.t - pad.b);
  const xFor = (i) => pad.l + i * xStep;
  const path = data.map((d, i) => `${i === 0 ? "M" : "L"} ${xFor(i)} ${yFor(d.y)}`).join(" ");
  const area = `${path} L ${xFor(data.length - 1)} ${H - pad.b} L ${xFor(0)} ${H - pad.b} Z`;

  // y ticks
  const ticks = 4;
  const tickVals = [];
  for (let i = 0; i <= ticks; i++) tickVals.push(Math.round(yMin + (yMax - yMin) * (i / ticks)));

  return (
    <svg viewBox={`0 0 ${W} ${H}`} style={{ width: "100%" }}>
      {tickVals.map((v, i) => (
        <g key={i}>
          <line x1={pad.l} x2={W - pad.r} y1={yFor(v)} y2={yFor(v)} stroke="var(--rule)" strokeDasharray="2 3"/>
          <text x={pad.l - 8} y={yFor(v) + 4} textAnchor="end" fontSize="10" fill="var(--ink-4)" fontFamily="var(--mono)">{v}</text>
        </g>
      ))}
      <path d={area} fill={fill} opacity="0.55"/>
      <path d={path} fill="none" stroke={accent} strokeWidth="2"/>
      {data.map((d, i) => (
        <g key={i}>
          {i % 2 === 0 && <text x={xFor(i)} y={H - 6} textAnchor="middle" fontSize="10" fill="var(--ink-4)" fontFamily="var(--sans)">{d.x}</text>}
          <circle cx={xFor(i)} cy={yFor(d.y)} r={i === data.length - 1 ? 4 : 2.5} fill={accent}/>
        </g>
      ))}
      {/* class avg line */}
      {data[0].avg !== undefined && (
        <path
          d={data.map((d, i) => `${i === 0 ? "M" : "L"} ${xFor(i)} ${yFor(d.avg)}`).join(" ")}
          fill="none" stroke="var(--ink-4)" strokeWidth="1.5" strokeDasharray="4 3"
        />
      )}
    </svg>
  );
}

// Spark bars (small weekly time chart)
function SparkBars({ data, height = 48, color = "var(--green)" }) {
  const max = Math.max(...data, 1);
  return (
    <div className="flex gap-2 items-end" style={{ height }}>
      {data.map((v, i) => (
        <div key={i} style={{
          flex: 1,
          height: `${Math.max(6, (v / max) * height)}px`,
          background: v === 0 ? "var(--paper-3)" : color,
          borderRadius: 3,
        }}/>
      ))}
    </div>
  );
}

Object.assign(window, { Icon, PieceAvatar, RatingChart, SparkBars });
