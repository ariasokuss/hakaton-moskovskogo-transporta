import { useEffect, useMemo, useState } from 'react'
import { get, type Route } from '../api'
import { RouteBadge } from './RouteBadge'

type Post = { hour: number; score: number; kind: 'fact' | 'forecast'; source: string; url: string
  text: string | null; speedKmh: number | null; forecastScore: number | null; spots: string[]
  routes: Record<string, string[]> }
type Traffic = { date: string; posts: Post[]; byRoute: Record<string, string[]>; latestOnline?: { score: number; measuredAt: string; url: string }
  effect: { freeRoadsVsUsual: number; jamsVsUsual: number; jamsVsFreeRatio: number; jamsVsFreeCi95: number[]; forecastGainPp: number
    routeSpotVsOthers: number; routeSpotHours: number } }

const MONTHS_GEN = ['января', 'февраля', 'марта', 'апреля', 'мая', 'июня', 'июля', 'августа', 'сентября', 'октября', 'ноября', 'декабря']
const human = (iso: string) => `${Number(iso.slice(8))} ${MONTHS_GEN[Number(iso.slice(5, 7)) - 1]} ${iso.slice(0, 4)}`
const pct = (x: number, digits = 1) => `${x > 0 ? '+' : x < 0 ? '−' : ''}${Math.abs(x * 100).toFixed(digits).replace('.', ',')}%`
const level = (s: number) => (s <= 3 ? 'свободно' : s <= 5 ? 'местами затруднено' : s <= 7 ? 'плотно' : 'пробки')

// Окно «Пробки»: баллы ЦОДД за сутки по часам, посты Дептранса в адаптированном виде и справка,
// как диспетчеру пользоваться трафиком. Данные — GET /api/external/traffic?date=.
export function TrafficModal({ date, onClose, routes, route: initialRoute }: {
  date: string; onClose: () => void; routes: Route[]; route: number | null
}) {
  const [route, setRoute] = useState<number | null>(initialRoute)
  const byId = useMemo(() => new Map(routes.map(r => [r.id, r])), [routes])
  const badge = (id: number, size: 'sm' | 'md' = 'sm') => {
    const r = byId.get(id)
    return r ? <RouteBadge short={r.shortName} color={r.color} size={size} /> : <b>{id}</b>
  }
  const [data, setData] = useState<Traffic | null>(null)
  const [error, setError] = useState<string | null>(null)
  const [open, setOpen] = useState<Record<string, boolean>>({})

  useEffect(() => {
    get<Traffic>(`/api/external/traffic?date=${date}`).then(setData).catch(e => setError(e.message))
  }, [date])
  useEffect(() => {
    const onKey = (e: KeyboardEvent) => { if (e.key === 'Escape') onClose() }
    window.addEventListener('keydown', onKey)
    document.body.style.overflow = 'hidden'
    return () => { window.removeEventListener('keydown', onKey); document.body.style.overflow = '' }
  }, [onClose])

  // Один пост часто даёт два балла (текущий и прогноз на вечер) — показываем пост один раз.
  const posts = useMemo(() => {
    const byUrl = new Map<string, Post & { forecasts: number[] }>()
    for (const p of data?.posts ?? []) {
      const cur = byUrl.get(p.url)
      if (!cur) byUrl.set(p.url, { ...p, forecasts: p.kind === 'forecast' ? [p.score] : [] })
      else if (p.kind === 'forecast') cur.forecasts.push(p.score)
      else Object.assign(cur, { ...p, forecasts: cur.forecasts })
    }
    return [...byUrl.values()]
  }, [data])
  const hours = useMemo(() => {
    const h: (number | null)[] = Array(24).fill(null)
    for (const p of data?.posts ?? []) if (p.kind === 'fact') h[p.hour] = p.score
    return h
  }, [data])
  const facts = (data?.posts ?? []).filter(p => p.kind === 'fact').map(p => p.score)
  const e = data?.effect

  return (
    <div className="modal-back" onClick={onClose}>
      <div className="modal" role="dialog" aria-modal="true" aria-labelledby="traffic-title" onClick={ev => ev.stopPropagation()}>
        <header className="modal-head">
          <div>
            <h3 id="traffic-title">Пробки · {human(date)}</h3>
            <p className="muted small">Баллы ЦОДД из постов Дептранса (@DtOperativno) — по городу целиком, 0–10</p>
          </div>
          <button className="modal-x" onClick={onClose} aria-label="Закрыть">✕</button>
        </header>

        <div className="modal-body">
          {error && <p className="error">{error}</p>}
          {!data && !error && <p className="muted">Загрузка…</p>}

          {data && (
            <>
              <div className="tr-summary">
                <div className="fact"><span className="k">Максимум за сутки</span>
                  <span className="v">{facts.length ? `${Math.max(...facts)} б.` : '—'}</span>
                  <span className="muted small">{facts.length ? level(Math.max(...facts)) : 'постов с баллом нет'}</span></div>
                <div className="fact"><span className="k">Постов с баллом</span><span className="v">{posts.length}</span>
                  <span className="muted small">ЦОДД публикует не каждый час</span></div>
                {data.latestOnline && (
                  <div className="fact"><span className="k">Сейчас в Москве (онлайн)</span><span className="v">{data.latestOnline.score} б.</span>
                    <span className="muted small">Яндекс Пробки, {new Date(data.latestOnline.measuredAt).toLocaleString('ru-RU', { dateStyle: 'short', timeStyle: 'short' })}</span></div>
                )}
              </div>

              <div className="tr-hours" aria-label="Баллы по часам">
                {hours.map((s, h) => (
                  <div key={h} className={`tr-hour${s == null ? ' none' : ` s${s}`}`} title={s == null ? `${h}:00 — балла нет` : `${h}:00 — ${s} б., ${level(s)}`}>
                    <span className="tr-score">{s ?? ''}</span><span className="tr-h">{h}</span>
                  </div>
                ))}
              </div>

              <section className="tr-routes">
                <h4>На трассах маршрутов</h4>
                <p className="muted small">Места затруднений из постов, которые называют улицы вдоль трассы маршрута
                  (улицы — OpenStreetMap, до 35 м от путей; кольцевые магистрали трамваи только пересекают — не учитываются).</p>
                <div className="tr-filter" role="radiogroup" aria-label="Маршрут">
                  <button role="radio" aria-checked={route == null} className={route == null ? 'on' : ''} onClick={() => setRoute(null)}>Все</button>
                  {routes.map(r => (
                    <button key={r.id} role="radio" aria-checked={route === r.id} className={route === r.id ? 'on' : ''}
                            onClick={() => setRoute(route === r.id ? null : r.id)} title={`Маршрут ${r.shortName}`}>
                      {badge(r.id)}{data.byRoute[r.id] && <i className="tr-dot" aria-label="есть затруднения" />}
                    </button>
                  ))}
                </div>
                {(route == null ? Object.keys(data.byRoute).map(Number) : [route]).map(id => (
                  <div key={id} className="tr-route-row">
                    {badge(id, 'md')}
                    {data.byRoute[id]?.length
                      ? <ul>{data.byRoute[id].map((s, i) => <li key={i}>{s}</li>)}</ul>
                      : <p className="muted small">В постах этого дня трасса маршрута не упоминается — действует только общий балл по городу.</p>}
                  </div>
                ))}
                {route == null && Object.keys(data.byRoute).length === 0 &&
                  <p className="muted small">В постах этого дня улицы трасс трамвайных маршрутов не упоминаются.</p>}
                {e && <p className="muted small">Что видно в данных 2025 года: в часы, когда затруднение названо на трассе маршрута,
                  посадок у него {pct(e.routeSpotVsOthers)} к остальным маршрутам в тот же час ({e.routeSpotHours} маршрут-часов — выборка мала,
                  это наблюдение, а не доказанный эффект). Похоже на задержку вагонов: рейсов в час меньше, пассажиров в каждом вагоне — больше.</p>}
              </section>

              {posts.length === 0 && <p className="muted">В этот день ЦОДД не публиковал баллы пробок. Баллы есть примерно для 170 дней 2025 года.</p>}
              {route != null && data.byRoute[route] && <h4 className="tr-posts-h">Посты, где упомянута трасса маршрута</h4>}
              {posts.filter(p => route == null || !data.byRoute[route] || p.routes?.[route]).map(p => (
                <article key={p.url} className="tr-post">
                  <div className="tr-post-head">
                    <span className="tr-time">{String(p.hour).padStart(2, '0')}:00</span>
                    <span className={`chip s${p.score}`}><b>{p.score}</b> б. · {level(p.score)}</span>
                    {(p.forecastScore ?? p.forecasts[0]) != null && <span className="tr-tag">прогноз: до {p.forecastScore ?? Math.max(...p.forecasts)} б.</span>}
                    {p.speedKmh != null && <span className="tr-tag">средняя скорость {p.speedKmh} км/ч</span>}
                    {Object.keys(p.routes ?? {}).map(id => <span key={id} className="tr-rbadge">{badge(Number(id))}</span>)}
                    <a className="tr-link" href={p.url} target="_blank" rel="noreferrer">пост в Telegram ↗</a>
                  </div>
                  {p.spots.length > 0 && (
                    <div className="tr-spots"><span className="muted small">Где затруднено:</span>
                      <ul>{p.spots.slice(0, 8).map((s, i) => {
                        const rs = Object.entries(p.routes ?? {}).filter(([, sp]) => sp.includes(s)).map(([id]) => Number(id))
                        return <li key={i} className={rs.length ? 'on-route' : ''}>{s}{rs.length > 0 && <span className="tr-onroute"> — трасса {rs.map(id => byId.get(id)?.shortName ?? id).join(', ')}</span>}</li>
                      })}</ul></div>
                  )}
                  {p.text && (
                    <>
                      <button className="link" onClick={() => setOpen(o => ({ ...o, [p.url]: !o[p.url] }))}>
                        {open[p.url] ? 'Скрыть текст поста' : 'Полный текст поста'}</button>
                      {open[p.url] && <p className="tr-text">{p.text}</p>}
                    </>
                  )}
                </article>
              ))}

              <section className="tr-guide">
                <h4>Как работать с трафиком</h4>
                <ol>
                  <li><b>Что такое балл.</b> Оценка загруженности дорог всего города по шкале 0–10 (ЦОДД, Яндекс Пробки).
                    Он не привязан к улицам маршрута — по уточнению организаторов, в кейсе это глобальный индекс.</li>
                  {e && <li><b>Что показали данные 2025 года.</b> В часы со свободными дорогами (0–5 баллов) посадок на {pct(e.freeRoadsVsUsual)} к обычному
                    уровню, при пробках 6+ — {pct(e.jamsVsUsual)}. Между собой они различаются на {pct(e.jamsVsFreeRatio - 1)}{' '}
                    (95% интервал {pct(e.jamsVsFreeCi95[0] - 1)} … {pct(e.jamsVsFreeCi95[1] - 1)}): при пробках часть водителей пересаживается на трамвай.
                    Эффект небольшой, потому что трамвай в основном идёт по обособленному полотну.</li>}
                  {e && <li><b>Почему балл не входит в прогноз.</b> На ноябрь–декабрь балл заранее неизвестен, а как оперативная поправка часа
                    он точность не улучшил ({e.forecastGainPp.toString().replace('.', ',')} п.п. на истории). Поэтому это контекст для решения диспетчера, а не признак модели.</li>}
                  <li><b>Что делать диспетчеру.</b>
                    <ul>
                      <li>Ожидаются пробки 6+ баллов — отметьте «Сильные пробки» в фильтрах поправок: спрос {e ? pct(e.jamsVsUsual) : '+1,5%'}.</li>
                      <li>Главный риск пробок — не спрос, а <b>скорость</b>: там, где трамвай идёт по проезжей части, он встаёт вместе с машинами,
                        рейсов в час становится меньше, и те же пассажиры едут в меньшем числе вагонов. Проверьте «Требует внимания» и пик часа —
                        при превышении поднимайте выпуск или добавьте «Свою поправку».</li>
                      <li>Перекрытие улиц или ДТП на трассе маршрута — отметьте «Перекрытие улиц» или «Сбой на маршруте».</li>
                    </ul>
                  </li>
                </ol>
                <p className="muted small">Источники: <a href="https://t.me/s/DtOperativno" target="_blank" rel="noreferrer">@DtOperativno</a> ·{' '}
                  <a href="https://export.yandex.ru/bar/reginfo.xml?region=213" target="_blank" rel="noreferrer">Яндекс Пробки (онлайн)</a> · методика — docs/traffic-operational-test.md</p>
              </section>
            </>
          )}
        </div>
      </div>
    </div>
  )
}
