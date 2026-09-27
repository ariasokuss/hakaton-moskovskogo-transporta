import type { Route } from '../api'
import { routeColor } from '../routeColor'

// Переход с заставки на рабочее место: через весь экран справа налево проходят линии маршрутов
// (цвета и номера — со справочника, как на карте и в таблице). Впереди каждой линии — её номер, как вагон,
// выходящий на маршрут; над головным — искра пантографа. В середине прохода линии закрывают экран целиком:
// в этот момент App снимает заставку, и уходящие линии открывают рабочее место.
export const WIPE_MS = 1500
export const WIPE_COVER_MS = 700

const FALLBACK = ['#E4572E', '#17A398', '#3A6EA5', '#8E44EF', '#C0392B', '#F4A259', '#5B8E55', '#C2185B', '#2E3440']

export function TramWipe({ routes }: { routes: Route[] }) {
  const lines = routes.length > 0
    ? routes.map(r => ({ id: r.id, short: r.shortName, color: routeColor(r.color, document.documentElement.dataset.theme === 'dark') }))
    : FALLBACK.map((c, i) => ({ id: i, short: '', color: c }))
  const n = lines.length
  return (
    <div className="tram-wipe" aria-hidden="true" style={{ ['--n' as string]: n }}>
      {lines.map((l, i) => {
        // Ступенчатый фронт «клином»: средние линии впереди, крайние чуть отстают — как голова состава.
        const lag = Math.abs(i - (n - 1) / 2) / ((n - 1) / 2 || 1)
        return (
          <div key={l.id} className="tw-line" style={{ ['--c' as string]: l.color, ['--lag' as string]: lag.toFixed(3), ['--i' as string]: i }}>
            <span className="tw-badge">{l.short}</span>
            <span className="tw-rail" />
            {i === Math.floor((n - 1) / 2) && <span className="tw-spark" />}
          </div>
        )
      })}
    </div>
  )
}
