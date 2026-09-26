import type { Horizon, Point } from '../api'

const fmt = (n: number) => Math.round(n).toLocaleString('ru-RU')
const MONTHS = ['янв', 'фев', 'мар', 'апр', 'май', 'июн', 'июл', 'авг', 'сен', 'окт', 'ноя', 'дек']

/** Подпись точки по горизонту: час, число месяца или месяц. */
function label(t: string, h: Horizon): string {
  if (h === 'day') return String(Number(t.slice(11, 13)))
  if (h === 'month') return String(Number(t.slice(8, 10)))
  return MONTHS[Number(t.slice(5, 7)) - 1]
}

// Один график на все горизонты: столбцы — прогноз, штрих — обычный уровень, точки — факт.
// Значение пика подписано прямо на столбце: на планшете ховера нет.
// highlight — выделенный час (ползунок времени на карте).
export function SeriesChart({ points, color, horizon, highlight, height = 190 }: {
  points: Point[]; color: string; horizon: Horizon; highlight?: number | null; height?: number
}) {
  const W = 720, H = height, padL = 52, padB = 22, padT = 16
  const n = Math.max(points.length, 1)
  const max = Math.max(1, ...points.map(p => Math.max(p.forecast, p.baseline, p.actual ?? 0)))
  const bw = (W - padL) / n
  const y = (v: number) => H - padB - (v / max) * (H - padB - padT)
  const peak = points.reduce((a, p, i) => (p.forecast > points[a].forecast ? i : a), 0)
  const base = points.map((p, i) => `${i === 0 ? 'M' : 'L'}${padL + i * bw + bw / 2},${y(p.baseline)}`).join(' ')
  const step = horizon === 'day' ? 3 : horizon === 'month' ? 5 : 1
  const unit = horizon === 'day' ? 'пасс./ч' : horizon === 'month' ? 'пасс./сут' : 'пасс./мес'
  return (
    <svg viewBox={`0 0 ${W} ${H}`} className="chart" role="img" aria-label={`Прогноз, ${unit}`}>
      {[0, 0.5, 1].map(f => (
        <g key={f}>
          <line x1={padL} x2={W} y1={y(max * f)} y2={y(max * f)} className="grid" />
          <text x={padL - 6} y={y(max * f) + 4} className="axis" textAnchor="end">{fmt(max * f)}</text>
        </g>
      ))}
      {points.map((p, i) => (
        <g key={p.t}>
          <rect x={padL + i * bw + Math.min(2, bw * 0.15)} y={y(p.forecast)} width={Math.max(1, bw - Math.min(4, bw * 0.3))}
                height={Math.max(0, H - padB - y(p.forecast))}
                fill={color} opacity={i === highlight ? 1 : i === peak ? 0.95 : 0.68}
                stroke={i === highlight ? 'var(--ink)' : 'none'} strokeWidth={1.5}>
            <title>{`${p.t} — прогноз ${fmt(p.forecast)}, обычно ${fmt(p.baseline)}${p.actual != null ? `, факт ${fmt(p.actual)}` : ''}`}</title>
          </rect>
          {p.actual != null && <circle cx={padL + i * bw + bw / 2} cy={y(p.actual)} r={Math.min(3, bw / 3)} className="actual" />}
          {i % step === 0 && <text x={padL + i * bw + bw / 2} y={H - 6} className="axis" textAnchor="middle">{label(p.t, horizon)}</text>}
        </g>
      ))}
      <path d={base} className="baseline" />
      {points.length > 0 && (
        <text x={padL + peak * bw + bw / 2} y={y(points[peak].forecast) - 4} className="peak" textAnchor="middle">
          {fmt(points[peak].forecast)}
        </text>
      )}
    </svg>
  )
}
