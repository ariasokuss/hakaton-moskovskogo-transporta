import { useEffect, useMemo, useState } from 'react'
import {
  ApiError, get, horizonWindow, scenarioQuery, sumSeries,
  type Dashboard, type Horizon, type Meta, type Point, type Route, type RouteSeries, type Scenario, type StopShare,
} from './api'
import { RouteBadge } from './components/RouteBadge'
import { Deviation } from './components/Deviation'
import { SeriesChart } from './components/SeriesChart'
import { ScenarioPanel } from './components/ScenarioPanel'
import { MapView } from './components/MapView'
import { DayContext } from './components/DayContext'

const DOW = ['', 'понедельник', 'вторник', 'среда', 'четверг', 'пятница', 'суббота', 'воскресенье']
const NEUTRAL: Scenario = { kWeather: 1, kEvent: 1, kSeason: 1, kTraffic: 1, kManual: 1 }
const HORIZONS: { key: Horizon; label: string; period: string }[] = [
  { key: 'day', label: 'День', period: 'За сутки' },
  { key: 'month', label: 'Месяц', period: 'За месяц' },
  { key: 'year', label: 'Год', period: 'За год' },
]
const fmt = (n: number) => Math.round(n).toLocaleString('ru-RU')
const DEFAULT_HOUR = 8

// Состояние экрана живёт в адресной строке: любую картину можно передать ссылкой.
function readUrl() {
  const q = new URLSearchParams(location.search)
  const sc = { ...NEUTRAL }
  for (const k of Object.keys(NEUTRAL) as (keyof Scenario)[]) if (q.get(k)) sc[k] = Number(q.get(k))
  const h = q.get('h')
  return {
    date: q.get('date'),
    route: q.get('route') ? Number(q.get('route')) : null,
    stop: q.get('stop'),
    horizon: (h === 'month' || h === 'year' ? h : 'day') as Horizon,
    hour: q.get('hour') ? Number(q.get('hour')) : DEFAULT_HOUR,
    scenario: sc,
  }
}

function shiftDate(date: string, h: Horizon, dir: number): string {
  const d = new Date(date + 'T12:00:00')
  if (h === 'day') d.setDate(d.getDate() + dir)
  else d.setMonth(d.getMonth() + dir, 1)
  return d.toISOString().slice(0, 10)
}

export default function App() {
  const init = useMemo(readUrl, [])
  const [meta, setMeta] = useState<Meta | null>(null)
  const [routes, setRoutes] = useState<Route[]>([])
  const [geo, setGeo] = useState<any>(null)
  const [shares, setShares] = useState<StopShare[]>([])
  const [date, setDate] = useState<string | null>(init.date)
  const [route, setRoute] = useState<number | null>(init.route)
  const [stop, setStop] = useState<string | null>(init.stop)
  const [horizon, setHorizon] = useState<Horizon>(init.horizon)
  const [hour, setHour] = useState<number>(init.hour)
  const [playing, setPlaying] = useState(false)
  const [scenario, setScenario] = useState<Scenario>(init.scenario)
  const [dash, setDash] = useState<Dashboard | null>(null)
  const [detail, setDetail] = useState<RouteSeries[] | null>(null)
  const [error, setError] = useState<ApiError | null>(null)
  const [loading, setLoading] = useState(false)

  // Справочники — один раз при входе.
  useEffect(() => {
    Promise.all([get<Meta>('/api/meta'), get<Route[]>('/api/routes'), get<any>('/api/geometry'), get<StopShare[]>('/api/stops')])
      .then(([m, r, g, s]) => { setMeta(m); setRoutes(r); setGeo(g); setShares(s); setDate(d => d ?? m.defaultDate) })
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

  // Месяц, год или остановка — прогноз по параметрам ТЗ (/api/forecast). День по маршруту уже есть в главном экране.
  useEffect(() => {
    if (!date || (horizon === 'day' && !stop)) { setDetail(null); return }
    const ctl = new AbortController()
    const t = setTimeout(() => {
      const q = `horizon=${horizon}&date=${date}${route != null ? `&route=${route}` : ''}${stop ? `&stop=${encodeURIComponent(stop)}` : ''}${scenarioQuery(scenario)}`
      get<RouteSeries[]>(`/api/forecast?${q}`, ctl.signal)
        .then(d => { setDetail(d); setError(null) })
        .catch(e => { if (e.name !== 'AbortError') { setDetail(null); setError(e) } })
    }, 120)
    return () => { clearTimeout(t); ctl.abort() }
  }, [date, horizon, route, stop, scenario])

  useEffect(() => {
    const q = new URLSearchParams()
    if (date) q.set('date', date)
    if (horizon !== 'day') q.set('h', horizon)
    if (route != null) q.set('route', String(route))
    if (stop) q.set('stop', stop)
    if (hour !== DEFAULT_HOUR) q.set('hour', String(hour))
    for (const [k, v] of Object.entries(scenario)) if (v !== 1) q.set(k, String(v))
    history.replaceState(null, '', `?${q}`)
  }, [date, horizon, route, stop, hour, scenario])

  // Проигрывание суток на карте: час за часом.
  useEffect(() => {
    if (!playing) return
    const t = setInterval(() => setHour(h => (h + 1) % 24), 700)
    return () => clearInterval(t)
  }, [playing])

  const lastDate = [meta?.shortTerm?.to, meta?.year?.to].filter(Boolean).sort().pop() ?? null
  const shift = (dir: number) => {
    if (!date || !meta?.shortTerm) return
    const iso = shiftDate(date, horizon, dir)
    const min = horizon === 'day' ? meta.shortTerm.from : meta.shortTerm.from.slice(0, 8) + '01'
    if (iso >= min && (!lastDate || iso <= lastDate)) setDate(iso < meta.shortTerm.from ? meta.shortTerm.from : iso)
  }
  const pickRoute = (id: number | null) => { setRoute(id); setStop(null) }

  const selected = routes.find(r => r.id === route) ?? null
  const selSeries = dash?.series.find(s => s.routeId === route) ?? null
  const dayPoints: Point[] = useMemo(() => (selSeries ? selSeries.points : dash ? sumSeries(dash.series) : []), [dash, selSeries])
  const points: Point[] = detail ? sumSeries(detail) : dayPoints
  const totals = points.reduce((a, p) => ({ f: a.f + p.forecast, b: a.b + p.baseline, l: a.l + p.load }), { f: 0, b: 0, l: 0 })
  const totalDev = totals.b > 0 ? (totals.f - totals.b) / totals.b * 100 : null
  const chartHorizon: Horizon = detail ? horizon : 'day'

  // Динамика по точкам маршрута: посадки на остановке в выбранный час = Σ доля остановки × прогноз маршрута.
  const stopValues = useMemo(() => {
    const out: Record<string, number> = {}
    if (!dash) return out
    const byRoute = new Map(dash.series.map(s => [s.routeId, s.points[hour]?.forecast ?? 0]))
    for (const s of shares) {
      if (route != null && s.routeId !== route) continue
      out[s.stop] = (out[s.stop] ?? 0) + s.share * (byRoute.get(s.routeId) ?? 0)
    }
    return out
  }, [dash, shares, hour, route])

  // Решение по выпуску (критерий 5): пиковый час против обычного уровня того же часа.
  const advice = useMemo(() => {
    if (chartHorizon !== 'day' || dayPoints.length === 0 || stop) return null
    const i = dayPoints.reduce((a, p, j) => (p.forecast > dayPoints[a].forecast ? j : a), 0)
    const p = dayPoints[i]
    if (p.baseline <= 0) return null
    const pct = (p.forecast - p.baseline) / p.baseline * 100
    return { hour: i, load: p.load, forecast: p.forecast, baseline: p.baseline, pct }
  }, [chartHorizon, dayPoints, stop])

  const exportUrl = (format: string) => {
    if (!date) return '#'
    const w = horizonWindow(horizon, date, meta)
    return `/api/export?format=${format}&from=${w.from}&to=${w.to}&granularity=${w.granularity}` +
      `${route != null ? `&route=${route}` : ''}${stop ? `&stop=${encodeURIComponent(stop)}` : ''}${scenarioQuery(scenario)}`
  }
  const periodLabel = HORIZONS.find(h => h.key === horizon)!.period
  const color = selected?.color ?? '#3f7cac'

  return (
    <div className="app">
      <header className="top">
        <div className="brand">Пантограф<small>Из данных — энергия, из энергии — прогноз</small></div>
        <div className="tabs" role="tablist" aria-label="Горизонт прогноза">
          {HORIZONS.map(h => (
            <button key={h.key} role="tab" aria-selected={horizon === h.key} className={horizon === h.key ? 'on' : ''}
                    onClick={() => setHorizon(h.key)}>{h.label}</button>
          ))}
        </div>
        <div className="datebar">
          <button onClick={() => shift(-1)} aria-label="Назад">◀</button>
          <input type="date" value={date ?? ''} min={meta?.shortTerm?.from} max={lastDate ?? undefined}
                 onChange={e => e.target.value && setDate(e.target.value)} />
          <button onClick={() => shift(1)} aria-label="Вперёд">▶</button>
          <span className="dow">{dash ? DOW[dash.dayOfWeek] : ''}</span>
        </div>
        {dash && (
          <div className="kpi">
            <span className="kpi-label">Сеть за сутки</span>
            <span className="kpi-val">{fmt(dash.network.forecastTotal)}</span>
            <span className="kpi-sub">обычно {fmt(dash.network.baselineTotal)} <Deviation pct={dash.network.deviationPct} /></span>
          </div>
        )}
        <div className="exports" title="Выгрузка того, что на экране: горизонт, маршрут, остановка, поправки">
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
            <button key={a.routeId} className={`alert alert-${a.direction} ${route === a.routeId ? 'on' : ''}`} onClick={() => pickRoute(a.routeId)}>
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
            {route != null && <button className="link" onClick={() => pickRoute(null)}>вся сеть</button>}
          </header>
          <table className="routes">
            <thead><tr><th></th><th>Прогноз</th><th>Обычно</th><th>Δ</th></tr></thead>
            <tbody>
              {dash?.series.map(s => (
                <tr key={s.routeId} className={route === s.routeId ? 'on' : ''} onClick={() => pickRoute(route === s.routeId ? null : s.routeId)}>
                  <td><RouteBadge short={s.shortName} color={s.color} size="sm" /></td>
                  <td className="num">{fmt(s.forecastTotal)}</td>
                  <td className="num muted">{fmt(s.baselineTotal)}</td>
                  <td className="num"><Deviation pct={s.deviationPct} /></td>
                </tr>
              ))}
            </tbody>
          </table>
        </section>

        {dash?.external && <DayContext ctx={dash.external} />}
      </aside>

      <main className="center">
        <MapView geo={geo} routes={routes} selected={route} onSelect={pickRoute}
                 stopValues={stopValues} hourLabel={`${hour}:00–${hour + 1}:00`}
                 selectedStop={stop} onSelectStop={setStop} />
        <div className="timebar">
          <button onClick={() => setPlaying(p => !p)} aria-label={playing ? 'Пауза' : 'Проиграть сутки'}>{playing ? '❚❚' : '▶'}</button>
          <input type="range" min={0} max={23} step={1} value={hour} aria-label="Час суток"
                 onChange={e => { setPlaying(false); setHour(Number(e.target.value)) }} />
          <span className="timebar-val">{String(hour).padStart(2, '0')}:00</span>
          <span className="timebar-hint">размер остановки — прогноз посадок в этот час</span>
        </div>
      </main>

      <aside className="right">
        <section className="panel">
          <header className="panel-head">
            {selected
              ? <><RouteBadge short={selected.shortName} color={selected.color} size="lg" />
                  <h2 className="route-name">{selected.longName ?? `Маршрут ${selected.shortName}`}</h2></>
              : <h2>Вся сеть</h2>}
          </header>
          {stop && (
            <p className="stopline">
              Остановка <b>{stop}</b> <button className="link" onClick={() => setStop(null)}>весь маршрут</button>
              <span className="muted small block">оценка: доля прогноза маршрута по остановкам (OSM), в валидациях остановки нет</span>
            </p>
          )}
          <p className="legend">
            <i className="lg-bar" style={{ background: color }} />прогноз
            <i className="lg-base" />обычный уровень
            <i className="lg-fact" />факт
          </p>
          {points.length > 0 && <SeriesChart points={points} color={color} horizon={chartHorizon}
                                             highlight={chartHorizon === 'day' ? hour : null} />}
          {points.length > 0 && (
            <p className="totals">{detail ? periodLabel : 'За сутки'}: <b>{fmt(totals.f)}</b> · обычно {fmt(totals.b)} <Deviation pct={totalDev} /></p>
          )}
          {totals.l > totals.f * 1.001 && (
            <p className="small">Нагрузка на вагоны с пересадками: <b>{fmt(totals.l)}</b>
              <span className="muted"> (+{fmt(totals.l - totals.f)} пересаживающихся без оплаты)</span></p>
          )}
          {advice && (
            <p className={`advice ${Math.abs(advice.pct) >= 10 ? (advice.pct > 0 ? 'advice-up' : 'advice-down') : ''}`}>
              Пик в <b>{advice.hour}:00</b>: {fmt(advice.forecast)} посадок/ч (с пересадками {fmt(advice.load)}), обычно {fmt(advice.baseline)}.{' '}
              {advice.pct >= 10 ? <>Чтобы наполнение не выросло, провозную способность в этот час нужно поднять на <b>{Math.round(advice.pct)}%</b>.</>
                : advice.pct <= -10 ? <>Спрос ниже обычного на <b>{Math.round(-advice.pct)}%</b> — выпуск можно сократить или перераспределить.</>
                : <>В пределах ±10% — выпуск по обычному расписанию.</>}
            </p>
          )}
          {selected && <p className="muted small">Работает с {selected.serviceHourStart}:00 до {selected.serviceHourEnd}:59</p>}
          {horizon === 'year' && <p className="muted small">Год — качественный сценарий: сезонный профиль 2025 года, помесячные объёмы от ML-модели.</p>}
          {(dash?.regimes.filter(r => route == null || r.routeId === route) ?? []).length > 0 && (
            <div className="regimes">
              {dash!.regimes.filter(r => route == null || r.routeId === route).map((r, i) => (
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
