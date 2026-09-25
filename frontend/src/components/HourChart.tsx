import type { Point } from '../api'

const fmt = (n: number) => Math.round(n).toLocaleString('ru-RU')

// Почасовой профиль: столбцы — прогноз, штрих — обычный уровень, точки — факт.
// Значения подписаны прямо на столбцах пиков: на планшете ховера нет.
export function HourChart({ points, color, height = 190 }: { points: Point[]; color: string; height?: number }) {
  const W = 720, H = height, padL = 44, padB = 22, padT = 16
  const max = Math.max(1, ...points.map(p => Math.max(p.forecast, p.baseline, p.actual ?? 0)))
  const bw = (W - padL) / 24
  const y = (v: number) => H - padB - (v / max) * (H - padB - padT)
  const peak = points.reduce((a, p, i) => (p.forecast > points[a].forecast ? i : a), 0)
  const base = points.map((p, i) => `${i === 0 ? 'M' : 'L'}${padL + i * bw + bw / 2},${y(p.baseline)}`).join(' ')
  return (
    <svg viewBox={`0 0 ${W} ${H}`} className="chart" role="img" aria-label="Почасовой прогноз">
      {[0, 0.5, 1].map(f => (
        <g key={f}>
          <line x1={padL} x2={W} y1={y(max * f)} y2={y(max * f)} className="grid" />
          <text x={padL - 6} y={y(max * f) + 4} className="axis" textAnchor="end">{fmt(max * f)}</text>
        </g>
      ))}
      {points.map((p, i) => (
        <g key={i}>
          <rect x={padL + i * bw + 2} y={y(p.forecast)} width={bw - 4} height={Math.max(0, H - padB - y(p.forecast))}
                fill={color} opacity={i === peak ? 1 : 0.72}><title>{`${i}:00 — прогноз ${fmt(p.forecast)}, обычно ${fmt(p.baseline)}`}</title></rect>
          {p.actual != null && <circle cx={padL + i * bw + bw / 2} cy={y(p.actual)} r={3} className="actual" />}
          {i % 3 === 0 && <text x={padL + i * bw + bw / 2} y={H - 6} className="axis" textAnchor="middle">{i}</text>}
        </g>
      ))}
      <path d={base} className="baseline" />
      <text x={padL + peak * bw + bw / 2} y={y(points[peak]?.forecast ?? 0) - 4} className="peak" textAnchor="middle">
        {fmt(points[peak]?.forecast ?? 0)}
      </text>
    </svg>
  )
}
