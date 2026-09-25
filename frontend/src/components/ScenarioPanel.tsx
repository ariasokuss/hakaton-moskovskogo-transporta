import type { Scenario } from '../api'

const KNOBS: { key: keyof Scenario; label: string; hint: string }[] = [
  { key: 'kWeather', label: 'Погода', hint: 'осадки, мороз' },
  { key: 'kEvent', label: 'Событие', hint: 'мероприятие, перекрытие' },
  { key: 'kSeason', label: 'Сезон', hint: 'каникулы, праздники' },
  { key: 'kTraffic', label: 'Трафик', hint: 'пробки на дорогах' },
  { key: 'kManual', label: 'Ручная', hint: 'решение диспетчера' },
]

// Корректирующие коэффициенты (критерий 2в): пересчёт сразу, модель не трогается.
export function ScenarioPanel({ value, onChange }: { value: Scenario; onChange: (s: Scenario) => void }) {
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
          <span className="knob-label">{k.label}<small>{k.hint}</small></span>
          <input type="range" min={0.5} max={1.5} step={0.05} value={value[k.key]}
                 onChange={e => onChange({ ...value, [k.key]: Number(e.target.value) })} />
          <span className="knob-val">×{value[k.key].toFixed(2)}</span>
        </label>
      ))}
    </section>
  )
}
