import { useEffect, useMemo, useState } from 'react'
import { ApiError, get, scenarioQuery, type Dashboard, type Meta, type Point, type Route, type Scenario } from './api'
import { RouteBadge } from './components/RouteBadge'
import { Deviation } from './components/Deviation'
import { HourChart } from './components/HourChart'
import { ScenarioPanel } from './components/ScenarioPanel'
import { MapView } from './components/MapView'

const DOW = ['', 'понедельник', 'вторник', 'среда', 'четверг', 'пятница', 'суббота', 'воскресенье']
const NEUTRAL: Scenario = { kWeather: 1, kEvent: 1, kSeason: 1, kTraffic: 1, kManual: 1 }
const fmt = (n: number) => Math.round(n).toLocaleString('ru-RU')

// Состояние экрана живёт в адресной строке: любую картину можно передать ссылкой.
function readUrl() {
  const q = new URLSearchParams(location.search)
  const sc = { ...NEUTRAL }
  for (const k of Object.keys(NEUTRAL) as (keyof Scenario)[]) if (q.get(k)) sc[k] = Number(q.get(k))
  return { date: q.get('date'), route: q.get('route') ? Number(q.get('route')) : null, scenario: sc }
}

export default function App() {
  const init = useMemo(readUrl, [])
  const [meta, setMeta] = useState<Meta | null>(null)
  const [routes, setRoutes] = useState<Route[]>([])
  const [geo, setGeo] = useState<any>(null)
  const [date, setDate] = useState<string | null>(init.date)
  const [route, setRoute] = useState<number | null>(init.route)
  const [scenario, setScenario] = useState<Scenario>(init.scenario)
  const [dash, setDash] = useState<Dashboard | null>(null)
  const [error, setError] = useState<ApiError | null>(null)
  const [loading, setLoading] = useState(false)

  // Справочники — один раз при входе.
  useEffect(() => {
    Promise.all([get<Meta>('/api/meta'), get<Route[]>('/api/routes'), get<any>('/api/geometry')])
      .then(([m, r, g]) => { setMeta(m); setRoutes(r); setGeo(g); setDate(d => d ?? m.defaultDate) })
      .catch(e => setError(e))
  }, [])

  // Главный экран — одним запросом. Для ползунков — короткая задержка и отмена предыдущего запроса.
  useEffect(() => {
    if (!date) return
    const ctl = new AbortController()
    const t = setTimeout(() => {
      setLoading(true)
      get<Dashboard>(`/api/dashboard?date=${date}${scenarioQuery(scenario)}`, ctl.signal)
        .then(d => { setDash(d); setError(null) })
        .catch(e => { if (e.name !== 'AbortError') setError(e) })
        .finally(() => setLoading(false))
    }, 120)
    return () => { clearTimeout(t); ctl.abort() }
  }, [date, scenario])

  useEffect(() => {
    const q = new URLSearchParams()
    if (date) q.set('date', date)
    if (route != null) q.set('route', String(route))
    for (const [k, v] of Object.entries(scenario)) if (v !== 1) q.set(k, String(v))
    history.replaceState(null, '', `?${q}`)
  }, [date, route, scenario])

  const shift = (days: number) => {
    if (!date || !meta?.shortTerm) return
    const d = new Date(date + 'T12:00:00')
    d.setDate(d.getDate() + days)
    const iso = d.toISOString().slice(0, 10)
    if (iso >= meta.shortTerm.from && iso <= meta.shortTerm.to) setDate(iso)
  }

  const selected = routes.find(r => r.id === route) ?? null
  const selSeries = dash?.series.find(s => s.routeId === route) ?? null
  const networkPoints: Point[] = useMemo(() => {
    if (!dash || dash.series.length === 0) return []
    return dash.series[0].points.map((p, i) => ({
      t: p.t, deviationPct: null, actual: null,
      forecast: dash.series.reduce((a, s) => a + s.points[i].forecast, 0),
      load: dash.series.reduce((a, s) => a + s.points[i].load, 0),
      baseline: dash.series.reduce((a, s) => a + s.points[i].baseline, 0),
    }))
  }, [dash])
  const regimes = dash?.regimes.filter(r => route == null || r.routeId === route) ?? []
  const exportUrl = (format: string) =>
    `/api/export?format=${format}&from=${date}&to=${date}&granularity=hour${route != null ? `&route=${route}` : ''}${scenarioQuery(scenario)}`

  return (
    <div className="app">
      <header className="top">
        <div className="brand">Прогноз загрузки<small>трамвайная сеть · диспетчер</small></div>
        <div className="datebar">
          <button onClick={() => shift(-1)} aria-label="Предыдущий день">◀</button>
          <input type="date" value={date ?? ''} min={meta?.shortTerm?.from} max={meta?.shortTerm?.to}
                 onChange={e => e.target.value && setDate(e.target.value)} />
          <button onClick={() => shift(1)} aria-label="Следующий день">▶</button>
          <span className="dow">{dash ? DOW[dash.dayOfWeek] : ''}</span>
        </div>
        {dash && (
          <div className="kpi">
            <span className="kpi-label">Сеть за сутки</span>
            <span className="kpi-val">{fmt(dash.network.forecastTotal)}</span>
            <span className="kpi-sub">обычно {fmt(dash.network.baselineTotal)} <Deviation pct={dash.network.deviationPct} /></span>
          </div>
        )}
        <div className="exports">
          <a href={exportUrl('xlsx')}>XLSX</a>
          <a href={exportUrl('csv')}>CSV</a>
          <a href={`/api/export?format=submission&from=${meta?.shortTerm?.from}&to=${meta?.shortTerm?.to}${scenarioQuery(scenario)}`}>Сабмит</a>
        </div>
        {loading && <span className="busy" aria-live="polite">обновление…</span>}
      </header>

      {error && <div className="error"><b>{error.title}.</b> {error.message}</div>}

      <aside className="left">
        <section className="panel">
          <header className="panel-head"><h2>Требует внимания</h2><span className="count">{dash?.attention.length ?? 0}</span></header>
          {dash?.attention.length === 0 && <p className="empty">Все маршруты в пределах ±10% от обычного уровня.</p>}
          {dash?.attention.map(a => (
            <button key={a.routeId} className={`alert alert-${a.direction} ${route === a.routeId ? 'on' : ''}`} onClick={() => setRoute(a.routeId)}>
              <RouteBadge short={a.shortName} color={a.color} />
              <span className="alert-body">
                <span className="alert-main">{a.direction === 'below' ? 'Провал' : 'Превышение'} <Deviation pct={a.deviationPct} /></span>
                <span className="alert-sub">{fmt(a.forecast)} при обычных {fmt(a.baseline)} · пик в {a.peakHour}:00</span>
                {a.reason && <span className="alert-reason">{a.reason}</span>}
              </span>
            </button>
          ))}
        </section>

        <section className="panel">
          <header className="panel-head">
            <h2>Маршруты</h2>
            {route != null && <button className="link" onClick={() => setRoute(null)}>вся сеть</button>}
          </header>
          <table className="routes">
            <thead><tr><th></th><th>Прогноз</th><th>Обычно</th><th>Δ</th></tr></thead>
            <tbody>
              {dash?.series.map(s => (
                <tr key={s.routeId} className={route === s.routeId ? 'on' : ''} onClick={() => setRoute(route === s.routeId ? null : s.routeId)}>
                  <td><RouteBadge short={s.shortName} color={s.color} size="sm" /></td>
                  <td className="num">{fmt(s.forecastTotal)}</td>
                  <td className="num muted">{fmt(s.baselineTotal)}</td>
                  <td className="num"><Deviation pct={s.deviationPct} /></td>
                </tr>
              ))}
            </tbody>
          </table>
        </section>
      </aside>

      <main className="center"><MapView geo={geo} routes={routes} selected={route} onSelect={setRoute} /></main>

      <aside className="right">
        <section className="panel">
          <header className="panel-head">
            {selected
              ? <><RouteBadge short={selected.shortName} color={selected.color} size="lg" />
                  <h2 className="route-name">{selected.longName ?? `Маршрут ${selected.shortName}`}</h2></>
              : <h2>Вся сеть</h2>}
          </header>
          <p className="legend">
            <i className="lg-bar" style={{ background: selected?.color ?? '#3f7cac' }} />прогноз
            <i className="lg-base" />обычный уровень
          </p>
          {dash && <HourChart points={selSeries?.points ?? networkPoints} color={selected?.color ?? '#3f7cac'} />}
          {selSeries && <p className="totals">За сутки: <b>{fmt(selSeries.forecastTotal)}</b> · обычно {fmt(selSeries.baselineTotal)} <Deviation pct={selSeries.deviationPct} /></p>}
          {selSeries && selSeries.loadTotal > selSeries.forecastTotal && (
            <p className="small">Нагрузка на вагоны с пересадками: <b>{fmt(selSeries.loadTotal)}</b>
              <span className="muted"> (+{fmt(selSeries.loadTotal - selSeries.forecastTotal)} пересаживающихся без оплаты)</span></p>
          )}
          {selected && <p className="muted small">Работает с {selected.serviceHourStart}:00 до {selected.serviceHourEnd}:59</p>}
          {regimes.length > 0 && (
            <div className="regimes">
              {regimes.map((r, i) => (
                <p key={i}>
                  <b>Маршрут {r.routeId}</b> с {r.from}{r.to ? ` по ${r.to}` : ''}: {r.note}
                  {r.sourceUrl && <> · <a href={r.sourceUrl} target="_blank" rel="noreferrer">источник</a></>}
                </p>
              ))}
            </div>
          )}
        </section>
        <ScenarioPanel value={scenario} onChange={setScenario} />
        {dash?.model && <p className="model muted small">Модель: {dash.model.modelVersion} · прогноз {dash.model.from} — {dash.model.to}</p>}
      </aside>
    </div>
  )
}
