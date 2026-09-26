import { useEffect, useState } from 'react'
import { get, type Scenario } from '../api'

const KNOBS: { key: keyof Scenario; label: string; hint: string }[] = [
  { key: 'kWeather', label: 'Погода', hint: 'осадки, мороз' },
  { key: 'kEvent', label: 'Событие', hint: 'мероприятие, перекрытие' },
  { key: 'kSeason', label: 'Сезон', hint: 'каникулы, праздники' },
  { key: 'kTraffic', label: 'Трафик', hint: 'пробки на дорогах' },
  { key: 'kManual', label: 'Ручная', hint: 'решение диспетчера' },
]

// Что измерено ML-моделью по каждому источнику (artifacts/coefficients.json через /api/coefficients).
// Нет артефакта — подписей нет, ползунки работают как прежде.
type Coef = { model?: Record<string, any> | null }
function measured(m: Record<string, any> | null | undefined): Partial<Record<keyof Scenario, string>> {
  if (!m) return {}
  const pct = (x: number) => `${x > 0 ? '+' : ''}${(x * 100).toFixed(1)}%`
  const out: Partial<Record<keyof Scenario, string>> = {}
  const w = m.weather
  if (w?.beta_per_mm_precip_day_if_warm?.[0] != null) {
    const eff = w.effect_pp_day_ahead?.['фев–окт']
    out.kWeather = `дождь при t ≥ ${w.warm_temp_c ?? 10}°: ${pct(w.beta_per_mm_precip_day_if_warm[0])} на мм` +
      (eff != null ? ` · прогноз +${eff.toFixed(2)} п.п.` : '')
  }
  if (m.incident?.K_incident != null) out.kEvent = `сбой на маршруте: ×${m.incident.K_incident.toFixed(2)} на 3 ч; ремонты — в модели`
  if (m.calendar?.K_holiday != null) out.kSeason = `праздник ×${m.calendar.K_holiday.toFixed(2)} к воскресенью`
  if (m.traffic) out.kTraffic = m.traffic.in_submission ? 'учтён в модели' : 'эффект не подтверждён — только ручная поправка'
  return out
}

// Корректирующие коэффициенты (критерий 2в): пересчёт сразу, модель не трогается.
export function ScenarioPanel({ value, onChange }: { value: Scenario; onChange: (s: Scenario) => void }) {
  const [notes, setNotes] = useState<Partial<Record<keyof Scenario, string>>>({})
  useEffect(() => {
    get<Coef>('/api/coefficients').then(c => setNotes(measured(c.model))).catch(() => setNotes({}))
  }, [])
  const total = Object.values(value).reduce((a, b) => a * b, 1)
  const changed = total !== 1
  return (
    <section className="panel scenario">
      <header className="panel-head">
        <h2>Поправки</h2>
        <span className={changed ? 'ktotal changed' : 'ktotal'}>×{total.toFixed(2)}</span>
        {changed && <button className="link" onClick={() => onChange({ kWeather: 1, kEvent: 1, kSeason: 1, kTraffic: 1, kManual: 1 })}>сбросить</button>}
      </header>
      {KNOBS.map(k => (
        <label key={k.key} className="knob">
          <span className="knob-label">{k.label}<small>{k.hint}</small>{notes[k.key] && <small className="measured">{notes[k.key]}</small>}</span>
          <input type="range" min={0.5} max={1.5} step={0.05} value={value[k.key]}
                 onChange={e => onChange({ ...value, [k.key]: Number(e.target.value) })} />
          <span className="knob-val">×{value[k.key].toFixed(2)}</span>
        </label>
      ))}
    </section>
  )
}
