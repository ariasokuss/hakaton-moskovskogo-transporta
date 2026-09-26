// Единый визуальный токен маршрута: одна форма, один цвет, одна типографика
// на карте, в таблице, в графике и в списках. Цвет приходит с бэкенда.
export function RouteBadge({ short, color, size = 'md' }: { short: string; color: string; size?: 'sm' | 'md' | 'lg' }) {
  return <span className={`badge badge-${size}`} style={{ background: color }} aria-label={`Маршрут ${short}`}>{short}</span>
}
