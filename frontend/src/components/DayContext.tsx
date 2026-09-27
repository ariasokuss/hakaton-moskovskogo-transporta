import type { ExternalDay, ExternalEvent, PeriodContext as Period, TrafficHour } from '../api'

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
// посты Дептранса, баллы пробок ЦОДД. Каждое — со ссылкой на источник.
// Пробки — контекст: в прогноз не входят, эффект не подтверждён (docs/traffic-operational-test.md).
export function DayContext({ ctx, onOpenTraffic }: { ctx: { day: ExternalDay | null; events: ExternalEvent[]; traffic?: TrafficHour[] }; onOpenTraffic: () => void }) {
  const d = ctx.day
  const traffic = ctx.traffic ?? []
  if (!d && ctx.events.length === 0 && traffic.length === 0) return null
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
          {traffic.length > 0 && (
            <li className="wide">
              <span>Пробки ЦОДД, баллы по часам <span className="small">(контекст, в прогноз не входит)</span></span>
              <div className="chips">
                {traffic.map(x => (
                  <a key={x.hour} className={`chip s${Math.min(10, Math.max(0, x.score))}`} href={x.url} target="_blank" rel="noreferrer"
                     title={`Пост ЦОДД: ${x.score} баллов в ${x.hour}:00`}>{x.hour}:00 <b>{x.score}</b></a>
                ))}
              </div>
              <button className="more-btn" onClick={onOpenTraffic}>Подробнее о пробках: посты ЦОДД и как с этим работать →</button>
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

const MONTHS_GEN = ['января', 'февраля', 'марта', 'апреля', 'мая', 'июня', 'июля', 'августа', 'сентября', 'октября', 'ноября', 'декабря']
const dm = (iso: string) => `${Number(iso.slice(8, 10))} ${MONTHS_GEN[Number(iso.slice(5, 7)) - 1]}`
const period = (e: ExternalEvent) => e.to && e.to !== e.from ? `${dm(e.from)} – ${dm(e.to)}` : dm(e.from)
const plural = (n: number, one: string, few: string, many: string) => {
  const a = n % 10, b = n % 100
  return `${n} ${a === 1 && b !== 11 ? one : a >= 2 && a <= 4 && (b < 12 || b > 14) ? few : many}`
}

// Месяц и год: сводка внешних факторов за весь период, а не за первые сутки.
// У каждого источника — покрытие: погода и посты есть только за прошедшие и ближайшие даты.
export function PeriodContext({ ctx, title, onOpenTraffic }: { ctx: Period; title: string; onOpenTraffic: () => void }) {
  const { calendar: c, weather: w, traffic: tr } = ctx
  const incidents = ctx.eventCounts['tram_incident'] ?? 0
  const shown = ctx.events.filter(e => e.category !== 'tram_incident')
  return (
    <section className="panel context">
      <header className="panel-head"><h2>Внешние факторы {title}</h2></header>
      <ul className="facts">
        <li>
          <span>Календарь</span>
          {c.coveredDays > 0
            ? <b>{plural(c.workdays, 'рабочий день', 'рабочих дня', 'рабочих дней')} · {plural(c.daysOff, 'выходной', 'выходных', 'выходных')}
                {c.schoolHolidayDays > 0 && <> · каникулы {plural(c.schoolHolidayDays, 'день', 'дня', 'дней')}</>}
                {c.coveredDays < ctx.days && <span className="muted"> (календарь на {plural(c.coveredDays, 'день', 'дня', 'дней')} из {ctx.days})</span>}</b>
            : <b className="muted">календаря на эти даты ещё нет</b>}
          <a href="https://github.com/xmlcalendar/data" target="_blank" rel="noreferrer">xmlcalendar</a>
        </li>
        {(c.holidays.length > 0 || c.workingWeekends.length > 0) && (
          <li className="wide">
            <span>Праздники и переносы</span>
            <b className="period-list">
              {c.holidays.map(h => <span key={h.date}>{dm(h.date)} — {h.name}</span>)}
              {c.workingWeekends.map(d => <span key={d}>{dm(d)} — рабочая суббота</span>)}
            </b>
          </li>
        )}
        <li>
          <span>Погода (прогноз)</span>
          {w.coveredDays > 0
            ? <b>{t(w.tempMin ?? null)}…{t(w.tempMax ?? null)} °C · осадки {w.precipitationMm} мм, {plural(w.rainyDays ?? 0, 'день', 'дня', 'дней')} от 1 мм
                {(w.heavyDays ?? 0) > 0 && <>, ливней {w.heavyDays}</>}{(w.snowDays ?? 0) > 0 && <>, снег {w.snowDays} дн.</>}{(w.frostDays ?? 0) > 0 && <>, мороз ниже −10 °C {w.frostDays} дн.</>}
                {w.coveredDays < ctx.days && <span className="muted"> (есть на {plural(w.coveredDays, 'день', 'дня', 'дней')} из {ctx.days})</span>}</b>
            : <b className="muted">прогноза погоды на эти даты ещё нет</b>}
          <a href="https://open-meteo.com/en/docs/historical-forecast-api" target="_blank" rel="noreferrer">Open-Meteo</a>
        </li>
        <li className="wide">
          <span>Пробки ЦОДД <span className="small">(контекст, в прогноз не входит)</span></span>
          {tr.coveredDays > 0
            ? <b>{plural(tr.jamDays, 'день', 'дня', 'дней')} с баллом 7+ из {tr.coveredDays} с постами
                {tr.maxScore != null && tr.maxScoreDay && <> · максимум {tr.maxScore} б. — {dm(tr.maxScoreDay)}</>}</b>
            : <b className="muted">постов ЦОДД за эти даты нет</b>}
          {tr.coveredDays > 0 && <button className="more-btn" onClick={onOpenTraffic}>Подробнее о пробках: посты ЦОДД и как с этим работать →</button>}
        </li>
      </ul>
      {shown.map((e, i) => (
        <p key={i} className="event">
          <b>{CATEGORY[e.category] ?? e.category}</b> · {period(e)}{e.routes.length > 0 && <> · маршруты {e.routes.join(', ')}</>}
          {e.title && <span className="muted"> — {e.title.length > 120 ? e.title.slice(0, 119) + '…' : e.title}</span>}
          {' '}<a href={e.url} target="_blank" rel="noreferrer">источник</a>
        </p>
      ))}
      {incidents > 0 && <p className="event muted">Сбоев движения за период: {incidents} — по суткам видно в режиме «День».</p>}
      {shown.length === 0 && incidents === 0 && <p className="event muted">Событий и режимов на путях за период нет.</p>}
    </section>
  )
}
