import type { ExternalDay, ExternalEvent } from '../api'

const DAY_TYPE: Record<string, string> = {
  workday: 'рабочий день', saturday: 'суббота', sunday: 'воскресенье', weekend: 'выходной', holiday: 'праздник',
  short_workday: 'сокращённый рабочий день', working_weekend: 'рабочая суббота',
}
const CATEGORY: Record<string, string> = {
  tram_works: 'работы на путях', tram_route_change: 'изменение маршрута', closure: 'перекрытие',
  mass_event: 'мероприятие', new_line: 'новая линия', tram_incident: 'сбой на маршруте', weather_alert: 'погода',
}
const t = (v: number | null) => (v == null ? '—' : `${v > 0 ? '+' : ''}${Math.round(v)}`)

// Внешние источники на выбранные сутки (схема external): календарь, архив прогноза погоды,
// посты Дептранса. Каждое событие — со ссылкой на источник.
export function DayContext({ ctx }: { ctx: { day: ExternalDay | null; events: ExternalEvent[] } }) {
  const d = ctx.day
  if (!d && ctx.events.length === 0) return null
  return (
    <section className="panel context">
      <header className="panel-head"><h2>Внешние факторы суток</h2></header>
      {d && (
        <ul className="facts">
          {d.dayType && (
            <li>
              <span>Календарь</span>
              <b>{DAY_TYPE[d.dayType] ?? d.dayType}{d.holidayName ? ` · ${d.holidayName}` : ''}{d.schoolHoliday ? ' · школьные каникулы' : ''}</b>
              <a href="https://github.com/xmlcalendar/data" target="_blank" rel="noreferrer">xmlcalendar</a>
            </li>
          )}
          {d.tempMin != null && (
            <li>
              <span>Погода (прогноз)</span>
              <b>{t(d.tempMin)}…{t(d.tempMax)} °C · {d.weatherKind}{d.precipitationMm ? `, ${d.precipitationMm} мм` : ''}</b>
              <a href="https://open-meteo.com/en/docs/historical-forecast-api" target="_blank" rel="noreferrer">Open-Meteo</a>
            </li>
          )}
        </ul>
      )}
      {ctx.events.map((e, i) => (
        <p key={i} className="event">
          <b>{CATEGORY[e.category] ?? e.category}</b>{e.routes.length > 0 && <> · маршруты {e.routes.join(', ')}</>}
          {e.title && <span className="muted"> — {e.title.length > 140 ? e.title.slice(0, 139) + '…' : e.title}</span>}
          {' '}<a href={e.url} target="_blank" rel="noreferrer">источник</a>
        </p>
      ))}
    </section>
  )
}
