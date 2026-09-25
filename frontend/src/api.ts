// Типы ответов бэкенда и загрузчик. Все ошибки API — RFC 9457 с русским detail.

export type Route = {
  id: number; shortName: string; longName: string | null; color: string; order: number
  serviceHourStart: number; serviceHourEnd: number; hasGeometry: boolean
}
export type Point = { t: string; forecast: number; baseline: number; actual: number | null; deviationPct: number | null }
export type RouteSeries = {
  routeId: number; shortName: string; color: string; points: Point[]
  forecastTotal: number; baselineTotal: number; deviationPct: number | null
}
export type Attention = {
  routeId: number; shortName: string; color: string; direction: 'above' | 'below'
  forecast: number; baseline: number; delta: number; deviationPct: number; peakHour: number; reason: string | null
}
export type Regime = { routeId: number; from: string; to: string | null; kind: string; note: string; sourceUrl: string | null }
export type Dashboard = {
  date: string; dayOfWeek: number
  scenario: Record<string, number>
  model: { modelVersion: string; horizon: string; from: string; to: string } | null
  routes: Route[]; series: RouteSeries[]; attention: Attention[]; regimes: Regime[]
  network: { forecastTotal: number; baselineTotal: number; deviationPct: number }
}
export type Meta = {
  actualFrom: string; actualTo: string; defaultDate: string
  shortTerm: { from: string; to: string; modelVersion: string } | null
  year: { from: string; to: string; modelVersion: string } | null
}
export type Scenario = { kWeather: number; kEvent: number; kSeason: number; kTraffic: number; kManual: number }

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
