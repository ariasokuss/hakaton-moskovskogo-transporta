// Типы ответов бэкенда и загрузчик. Все ошибки API — RFC 9457 с русским detail.

export type Route = {
  id: number; shortName: string; longName: string | null; color: string; order: number
  serviceHourStart: number; serviceHourEnd: number; hasGeometry: boolean
}
export type Point = { t: string; forecast: number; load: number; baseline: number; actual: number | null; deviationPct: number | null }
export type RouteSeries = {
  routeId: number; shortName: string; color: string; points: Point[]
  forecastTotal: number; loadTotal: number; baselineTotal: number; deviationPct: number | null
}
export type Attention = {
  routeId: number; shortName: string; color: string; direction: 'above' | 'below'
  forecast: number; baseline: number; delta: number; deviationPct: number; peakHour: number; reason: string | null
}
export type Regime = { routeId: number; from: string; to: string | null; kind: string; note: string; sourceUrl: string | null }
export type ExternalDay = {
  dayType: string | null; dayOff: boolean; holidayName: string | null; schoolHoliday: boolean
  tempMin: number | null; tempMax: number | null; precipitationMm: number | null; snowfallCm: number | null; weatherKind: string | null
}
export type TrafficHour = { hour: number; score: number; url: string }
export type PeriodContext = {
  from: string; to: string; days: number
  calendar: { coveredDays: number; workdays: number; daysOff: number; schoolHolidayDays: number
    holidays: { date: string; name: string }[]; workingWeekends: string[] }
  weather: { coveredDays: number; tempMin?: number; tempMax?: number; precipitationMm?: number
    rainyDays?: number; heavyDays?: number; snowDays?: number; frostDays?: number }
  traffic: { coveredDays: number; jamDays: number; maxScore?: number; maxScoreDay?: string }
  eventCounts: Record<string, number>
  events: ExternalEvent[]
}
export type ExternalEvent = { from: string; to: string | null; category: string; routes: number[]; title: string | null; url: string }
export type Dashboard = {
  date: string; dayOfWeek: number
  scenario: Record<string, number>
  model: { modelVersion: string; horizon: string; from: string; to: string } | null
  routes: Route[]; series: RouteSeries[]; attention: Attention[]; regimes: Regime[]
  network: { forecastTotal: number; baselineTotal: number; deviationPct: number }
  external?: { day: ExternalDay | null; events: ExternalEvent[]; traffic?: TrafficHour[] } | null
}
export type Period = { from: string; to: string; modelVersion: string }
export type Meta = {
  actualFrom: string; actualTo: string; defaultDate: string; routes?: number
  shortTerm: Period | null
  year: Period | null
}
export type StopShare = { routeId: number; stop: string; lat: number; lon: number; tramRoutesAtStop: number; share: number }
export type Scenario = { kWeather: number; kEvent: number; kSeason: number; kTraffic: number; kManual: number }
export type Horizon = 'day' | 'month' | 'year'

export class ApiError extends Error {
  constructor(public title: string, detail: string) { super(detail) }
}

export async function get<T>(path: string, signal?: AbortSignal): Promise<T> {
  let res: Response
  try {
    res = await fetch(path, { signal })
  } catch (e) {
    if ((e as Error).name === 'AbortError') throw e
    throw new ApiError('Нет связи с сервером', 'Сервис прогноза недоступен. Проверьте, что бэкенд запущен.')
  }
  if (!res.ok) {
    const p = await res.json().catch(() => null)
    throw new ApiError(p?.title ?? `Ошибка ${res.status}`, p?.detail ?? 'Сервис вернул ошибку.')
  }
  return res.json()
}

export function scenarioQuery(s: Scenario): string {
  return Object.entries(s).filter(([, v]) => v !== 1).map(([k, v]) => `&${k}=${v}`).join('')
}

/** Сумма рядов по точкам — сеть целиком или остановка на нескольких маршрутах. */
export function sumSeries(series: RouteSeries[]): Point[] {
  if (series.length === 0) return []
  return series[0].points.map((p, i) => ({
    t: p.t, deviationPct: null, actual: null,
    forecast: series.reduce((a, s) => a + (s.points[i]?.forecast ?? 0), 0),
    load: series.reduce((a, s) => a + (s.points[i]?.load ?? 0), 0),
    baseline: series.reduce((a, s) => a + (s.points[i]?.baseline ?? 0), 0),
  }))
}

/** Интервал горизонта — тот же, что считает бэкенд (ForecastController.Window): для экспорта «как на экране». */
export function horizonWindow(h: Horizon, date: string, meta: Meta | null): { from: string; to: string; granularity: string } {
  if (h === 'day') return { from: date, to: date, granularity: 'hour' }
  const [y, m] = date.split('-').map(Number)
  const first = `${y}-${String(m).padStart(2, '0')}-01`
  if (h === 'month') {
    const last = new Date(Date.UTC(y, m, 0)).getUTCDate()
    return { from: first, to: `${y}-${String(m).padStart(2, '0')}-${last}`, granularity: 'day' }
  }
  const end = new Date(Date.UTC(y + 1, m - 1, 0)).toISOString().slice(0, 10)
  const lastForecast = [meta?.shortTerm?.to, meta?.year?.to].filter(Boolean).sort().pop()
  return { from: first, to: lastForecast && lastForecast < end ? lastForecast : end, granularity: 'month' }
}
