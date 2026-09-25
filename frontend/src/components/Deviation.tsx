// Отклонение от обычного уровня: знак, цифра и цвет. Цвет дублируется
// стрелкой — смысл не держится на одном цвете.
export function Deviation({ pct }: { pct: number | null }) {
  if (pct == null) return <span className="dev dev-na">—</span>
  const cls = Math.abs(pct) < 10 ? 'dev-ok' : pct > 0 ? 'dev-up' : 'dev-down'
  const arrow = Math.abs(pct) < 10 ? '•' : pct > 0 ? '▲' : '▼'
  return <span className={`dev ${cls}`}>{arrow} {pct > 0 ? '+' : ''}{pct.toFixed(0)}%</span>
}
