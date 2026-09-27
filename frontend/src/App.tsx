import { useEffect, useMemo, useState } from 'react'
import {
  ApiError, get, horizonWindow, scenarioQuery, sumSeries,
  type Dashboard, type Horizon, type PeriodContext as PeriodContextData, type Meta, type Point, type Route, type RouteSeries, type Scenario, type StopShare,
} from './api'
import { RouteBadge } from './components/RouteBadge'
import { Deviation } from './components/Deviation'
import { SeriesChart } from './components/SeriesChart'
import { ScenarioPanel } from './components/ScenarioPanel'
import { MapView } from './components/MapView'
import { DayContext, PeriodContext } from './components/DayContext'
import { Logo } from './components/Logo'
import { Splash } from './components/Splash'
import { TramWipe, WIPE_COVER_MS, WIPE_MS } from './components/TramWipe'
import { Help } from './components/Help'
import { ThemeToggle } from './components/ThemeToggle'
import { TrafficModal } from './components/TrafficModal'
import { useTheme } from './theme'
import { routeColor } from './routeColor'

// Заставка — на «голый» адрес один раз за сессию. Ссылка с параметрами (демо, передача смене) открывает экран сразу.
function splashOnStart(): boolean {
  if (location.search.length > 1) return false
  try { return sessionStorage.getItem('splashSeen') !== '1' } catch { return true }
}

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

const MONTHS_FULL = ['январь', 'февраль', 'март', 'апрель', 'май', 'июнь', 'июль', 'август', 'сентябрь', 'октябрь', 'ноябрь', 'декабрь']
const MONTHS_GEN = ['января', 'февраля', 'марта', 'апреля', 'мая', 'июня', 'июля', 'августа', 'сентября', 'октября', 'ноября', 'декабря']
const MONTHS_SHORT = ['янв', 'фев', 'мар', 'апр', 'май', 'июн', 'июл', 'авг', 'сен', 'окт', 'ноя', 'дек']

// Подпись периода рядом с выбором: день недели, название месяца или границы годового периода.
function periodCaption(h: Horizon, date: string, dow: number | undefined, last: string | null): string {
  const [y, m] = date.split('-').map(Number)
  if (h === 'day') return dow ? DOW[dow] : ''
  if (h === 'month') return `${MONTHS_FULL[m - 1]} ${y}`
  const endIdx = (m - 1 + 11) % 12, endY = y + (m - 1 + 11 >= 12 ? 1 : 0)
  let end = `${MONTHS_SHORT[endIdx]} ${endY}`
  if (last && `${endY}-${String(endIdx + 1).padStart(2, '0')}` > last.slice(0, 7)) {
    const [ly, lm] = last.split('-').map(Number); end = `${MONTHS_SHORT[lm - 1]} ${ly}`
  }
  return `${MONTHS_SHORT[m - 1]} ${y} — ${end}`
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
  const [splash, setSplash] = useState<boolean>(splashOnStart)
  const { choice: themeChoice, theme, setChoice: setThemeChoice } = useTheme()
  const [trafficOpen, setTrafficOpen] = useState(false)
  const [trafficDay, setTrafficDay] = useState<string | null>(null)
  const [entering, setEntering] = useState(false)
  const [wiping, setWiping] = useState(false)
  const closeSplash = () => {
    try { sessionStorage.setItem('splashSeen', '1') } catch { /* приватный режим */ }
    if (window.matchMedia('(prefers-reduced-motion: reduce)').matches) { setSplash(false); return }
    // Линии маршрутов закрывают экран → снимаем заставку под ними → уходя, линии открывают рабочее место.
    setWiping(true)
    setTimeout(() => { setSplash(false); setEntering(true) }, WIPE_COVER_MS)
    setTimeout(() => setWiping(false), WIPE_MS)
    setTimeout(() => setEntering(false), WIPE_COVER_MS + 1400)
  }
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

  useEffect(() => { if (horizon !== 'day') setPlaying(false) }, [horizon])

  // Месяц и год: итоги по всем маршрутам за период — для «Требует внимания», таблицы маршрутов и итога сети.
  const [periodRoutes, setPeriodRoutes] = useState<RouteSeries[] | null>(null)
  useEffect(() => {
    if (!date || horizon === 'day') { setPeriodRoutes(null); return }
    const ctl = new AbortController()
    const t = setTimeout(() => {
      get<RouteSeries[]>(`/api/forecast?horizon=${horizon}&date=${date}${scenarioQuery(scenario)}`, ctl.signal)
        .then(setPeriodRoutes)
        .catch(e => { if (e.name !== 'AbortError') setPeriodRoutes(null) })
    }, 120)
    return () => { clearTimeout(t); ctl.abort() }
  }, [date, horizon, scenario])

  // Месяц и год: внешние факторы за весь период, а не за его первые сутки.
  const [periodCtx, setPeriodCtx] = useState<PeriodContextData | null>(null)
  useEffect(() => {
    if (!date || horizon === 'day') { setPeriodCtx(null); return }
    const ctl = new AbortController()
    const w = horizonWindow(horizon, date, meta)
    get<PeriodContextData>(`/api/external/period?from=${w.from}&to=${w.to}`, ctl.signal)
      .then(setPeriodCtx)
      .catch(e => { if (e.name !== 'AbortError') setPeriodCtx(null) })
    return () => ctl.abort()
  }, [date, horizon, meta])

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

  // Динамика по точкам маршрута: посадки на остановке = Σ доля остановки × прогноз маршрута
  // (день — в выбранный час, месяц и год — за весь период).
  const stopValues = useMemo(() => {
    const out: Record<string, number> = {}
    const src = horizon === 'day' ? dash?.series.map(s => [s.routeId, s.points[hour]?.forecast ?? 0] as const)
      : periodRoutes?.map(s => [s.routeId, s.forecastTotal] as const)
    if (!src) return out
    const byRoute = new Map(src)
    for (const s of shares) {
      if (route != null && s.routeId !== route) continue
      out[s.stop] = (out[s.stop] ?? 0) + s.share * (byRoute.get(s.routeId) ?? 0)
    }
    return out
  }, [dash, periodRoutes, horizon, shares, hour, route])

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
  const reportUrl = () => date
    ? `/api/report?horizon=${horizon}&date=${date}${route != null ? `&route=${route}` : ''}${stop ? `&stop=${encodeURIComponent(stop)}` : ''}${scenarioQuery(scenario)}`
    : '#'
  const periodLabel = HORIZONS.find(h => h.key === horizon)!.period
  // День — главный экран (/api/dashboard); месяц и год — итоги по маршрутам за весь период.
  const periodWord = horizon === 'day' ? 'за сутки' : horizon === 'month' ? 'за месяц' : 'за год'
  const routeRows = horizon === 'day' ? dash?.series : periodRoutes ?? undefined
  const network = useMemo(() => {
    if (horizon === 'day') return dash?.network ?? null
    if (!periodRoutes) return null
    const f = periodRoutes.reduce((a, s) => a + s.forecastTotal, 0), b = periodRoutes.reduce((a, s) => a + s.baselineTotal, 0)
    return { forecastTotal: f, baselineTotal: b, deviationPct: b > 0 ? Math.round((f - b) / b * 1000) / 10 : 0 }
  }, [horizon, dash, periodRoutes])
  const periodAttention = useMemo(() => {
    if (horizon === 'day' || !periodRoutes) return undefined
    const peakLabel = (t: string) => horizon === 'month'
      ? `${Number(t.slice(8, 10))} ${MONTHS_GEN[Number(t.slice(5, 7)) - 1]}` : `${MONTHS_SHORT[Number(t.slice(5, 7)) - 1]} ${t.slice(0, 4)}`
    return periodRoutes
      .filter(s => s.deviationPct != null && Math.abs(s.deviationPct) > 10)
      .sort((a, b) => Math.abs(b.deviationPct!) - Math.abs(a.deviationPct!))
      .map(s => {
        const pk = s.points.reduce((m, p) => (p.forecast > m.forecast ? p : m), s.points[0])
        return { routeId: s.routeId, shortName: s.shortName, color: s.color, direction: s.deviationPct! < 0 ? 'below' as const : 'above' as const,
                 forecast: s.forecastTotal, baseline: s.baselineTotal, deviationPct: s.deviationPct!, peak: pk ? peakLabel(pk.t) : '—' }
      })
  }, [horizon, periodRoutes])
  const attention = horizon === 'day' ? dash?.attention : periodAttention
  const color = routeColor(selected?.color ?? '#3f7cac', theme === 'dark')

  return (
    <div className={`app${entering ? ' app-enter' : ''}`}>
      <header className="top">
        <button className="brand" onClick={() => setSplash(true)} title="О сервисе и как пользоваться">
          <Logo size={34} subtitle="Из данных — энергия, из энергии — прогноз" />
        </button>
        <div className="tabs" role="tablist" aria-label="Горизонт прогноза">
          {HORIZONS.map(h => (
            <button key={h.key} role="tab" aria-selected={horizon === h.key} className={horizon === h.key ? 'on' : ''}
                    onClick={() => setHorizon(h.key)}>{h.label}</button>
          ))}
        </div>
        {/* Выбор под горизонт: день — дата, месяц — месяц, год — месяц начала 12-месячного периода. */}
        <div className="datebar">
          <button onClick={() => shift(-1)} aria-label={horizon === 'day' ? 'Предыдущий день' : 'Предыдущий месяц'}>◀</button>
          {horizon === 'day'
            ? <input type="date" value={date ?? ''} min={meta?.shortTerm?.from} max={lastDate ?? undefined} aria-label="Дата"
                     onChange={e => e.target.value && setDate(e.target.value)} />
            : <input type="month" value={date?.slice(0, 7) ?? ''} min={meta?.shortTerm?.from.slice(0, 7)} max={lastDate?.slice(0, 7)}
                     aria-label={horizon === 'month' ? 'Месяц' : 'Начало годового периода'}
                     onChange={e => e.target.value && setDate(`${e.target.value}-01` < (meta?.shortTerm?.from ?? '') ? meta!.shortTerm!.from : `${e.target.value}-01`)} />}
          <button onClick={() => shift(1)} aria-label={horizon === 'day' ? 'Следующий день' : 'Следующий месяц'}>▶</button>
          <span className="dow">{date ? periodCaption(horizon, date, dash?.dayOfWeek, lastDate) : ''}</span>
        </div>
        {network && (
          <div className="kpi">
            <span className="kpi-label">Сеть {periodWord}</span>
            <span className="kpi-val">{fmt(network.forecastTotal)}</span>
            <span className="kpi-sub">обычно {fmt(network.baselineTotal)} <Deviation pct={network.deviationPct} /></span>
          </div>
        )}
        <div className="exports" title="Выгрузка того, что на экране: горизонт, маршрут, остановка, поправки">
          <a className="pdf" href={reportUrl()} title="Отчёт диспетчера в PDF: сводка, графики, маршруты, зоны внимания, внешние факторы">PDF-отчёт</a>
          <a href={exportUrl('xlsx')}>XLSX</a>
          <a href={exportUrl('csv')}>CSV</a>
          <a href={`/api/export?format=submission&from=${meta?.shortTerm?.from}&to=${meta?.shortTerm?.to}${scenarioQuery(scenario)}`}>Сабмит</a>
        </div>
        <ThemeToggle choice={themeChoice} onChange={setThemeChoice} />
        {loading && <span className="busy" aria-live="polite">обновление…</span>}
      </header>

      {error && <div className="error"><b>{error.title}.</b> {error.message}</div>}

      <aside className="left">
        <section className="panel">
          <header className="panel-head"><h2>Требует внимания{horizon !== 'day' && <small className="h2-note"> · {periodWord}</small>}</h2><span className="count">{attention?.length ?? 0}</span></header>
          {attention?.length === 0 && <p className="empty">Все маршруты в пределах ±10% от обычного уровня{horizon !== 'day' ? ` ${periodWord}` : ''}.</p>}
          {periodAttention?.map(a => (
            <button key={a.routeId} className={`alert alert-${a.direction} ${route === a.routeId ? 'on' : ''}`} onClick={() => pickRoute(a.routeId)}>
              <RouteBadge short={a.shortName} color={a.color} />
              <span className="alert-body">
                <span className="alert-main">{a.direction === 'below' ? 'Провал' : 'Превышение'} <Deviation pct={a.deviationPct} /></span>
                <span className="alert-sub">{fmt(a.forecast)} {periodWord} при обычных {fmt(a.baseline)} · пик — {a.peak}</span>
              </span>
            </button>
          ))}
          {horizon === 'day' && dash?.attention.map(a => (
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
              {routeRows?.map(s => (
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

        {horizon === 'day'
          ? dash?.external && <DayContext ctx={dash.external} onOpenTraffic={() => { setTrafficDay(null); setTrafficOpen(true) }} />
          : periodCtx && <PeriodContext ctx={periodCtx} title={horizon === 'month' ? 'за месяц' : 'за год'}
                                        onOpenTraffic={() => { setTrafficDay(periodCtx.traffic.maxScoreDay ?? null); setTrafficOpen(true) }} />}
      </aside>

      <main className="center">
        {/* Карта (WebGL) создаётся после заставки: её инициализация на старте давала рывки анимации. */}
        {!splash && <MapView key={theme} dark={theme === 'dark'} geo={geo} routes={routes} selected={route} onSelect={pickRoute}
                 stopValues={stopValues} hourLabel={horizon === 'day' ? `${hour}:00–${hour + 1}:00` : horizon === 'month' ? 'за месяц' : 'за год'}
                 selectedStop={stop} onSelectStop={setStop} />}
        {route != null && <button className="to-network map-back" onClick={() => pickRoute(null)}>← Вся сеть</button>}
        {/* Час суток — только для прогноза на день; в месяце и годе часовой ползунок не имеет смысла. */}
        {horizon === 'day' && <div className="timebar">
          <button onClick={() => setPlaying(p => !p)} aria-label={playing ? 'Пауза' : 'Проиграть сутки'}>{playing ? '❚❚' : '▶'}</button>
          <input type="range" min={0} max={23} step={1} value={hour} aria-label="Час суток"
                 onChange={e => { setPlaying(false); setHour(Number(e.target.value)) }} />
          <span className="timebar-val">{String(hour).padStart(2, '0')}:00</span>
          <span className="timebar-hint">размер остановки — прогноз посадок в этот час</span>
        </div>}
      </main>

      <aside className="right">
        <section className="panel">
          <header className="panel-head">
            {selected
              ? <><RouteBadge short={selected.shortName} color={selected.color} size="lg" />
                  <h2 className="route-name">{selected.longName ?? `Маршрут ${selected.shortName}`}</h2>
                  <button className="to-network" onClick={() => pickRoute(null)} title="Вернуться к прогнозу по всей сети">← Вся сеть</button></>
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
            {points.some(p => p.actual != null) && <><i className="lg-fact" />факт</>}
            <Help label="Как читать график">
              <b>Столбцы</b> — прогноз модели на каждый {chartHorizon === 'day' ? 'час' : chartHorizon === 'month' ? 'день' : 'месяц'} с учётом поправок справа.
              Над самым высоким столбцом подписано его значение.<br />
              <b>Пунктир</b> — обычный уровень: сколько обычно ездит в этот час в такой же день недели
              (медиана 8 последних таких дней до 31.10). Столбец выше пунктира — загрузка выше обычной, ниже — ниже.<br />
              <b>Точки</b> — фактические посадки. Они есть только для дней с историей (январь–октябрь 2025);
              ноябрь–декабрь — прогнозный период, факта по нему ещё нет, поэтому точек нет.
              {chartHorizon === 'day' && <><br /><b>Обведённый столбец</b> — час, выбранный ползунком под картой.</>}
            </Help>
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
          {selected && <p className="muted small">
            Работает ≈ с {selected.serviceHourStart}:00 до {(selected.serviceHourEnd + 1) % 24}:00
            {selected.serviceHourEnd < selected.serviceHourStart && ' (последние рейсы — после полуночи)'}
            <Help label="Как определены часы работы">
              Часы работы определены по посадкам сентября–октября 2025: час считается рабочим, если в нём больше 0.1%
              суточного объёма маршрута. В 2–3 часа ночи посадок нет ни на одном маршруте; первые пассажиры — около 5 утра,
              последние рейсы у части маршрутов идут после полуночи. Технические валидации в ночные часы в прогноз не входят.
            </Help>
          </p>}
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
        <ScenarioPanel value={scenario} onChange={setScenario} horizon={horizon}
                       dayType={dash?.external?.day?.dayType ?? null} dow={dash?.dayOfWeek ?? 1}
                       onOpenTraffic={() => setTrafficOpen(true)} />
        {dash?.model && <p className="model muted small">Модель: {dash.model.modelVersion} · прогноз {dash.model.from} — {dash.model.to}</p>}
      </aside>
      {trafficOpen && date && <TrafficModal date={trafficDay ?? date} routes={routes} route={route} onClose={() => setTrafficOpen(false)} />}
      {splash && <Splash onStart={closeSplash} />}
      {wiping && <TramWipe routes={routes} />}
    </div>
  )
}
