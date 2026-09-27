// Единый визуальный токен маршрута: одна форма, один цвет, одна типографика
// на карте, в таблице, в графике и в списках. Цвет приходит с бэкенда;
// слишком тёмный цвет (маршрут 50) в тёмной теме CSS заменяет светлым — см. routeColor.ts.
import { isTooDark } from '../routeColor'

export function RouteBadge({ short, color, size = 'md' }: { short: string; color: string; size?: 'sm' | 'md' | 'lg' }) {
  return <span className={`badge badge-${size}${isTooDark(color) ? ' badge-low' : ''}`} style={{ background: color }} aria-label={`Маршрут ${short}`}>{short}</span>
}
