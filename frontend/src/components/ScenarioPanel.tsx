import { useEffect, useState } from 'react'
import { get, type Horizon, type Scenario } from '../api'
import { Help } from './Help'

// Корректирующие коэффициенты (критерий 2в): пересчёт сразу, модель не трогается.
// Два режима на одних и тех же k*.
// «Фильтры» — ситуации галочками с измеренными множителями, «Коэффициенты» — ползунки 0.3–3.0 (тот же диапазон принимает API). Ссылка и выгрузка одинаковы в обоих.

const NEUTRAL: Scenario = { kWeather: 1, kEvent: 1, kSeason: 1, kTraffic: 1, kManual: 1 }
const r2 = (x: number) => Math.round(x * 100) / 100
// Множитель → понятный процент: ×0.45 → «−55%», ×1.015 → «+1,5%».
const effect = (k: number) => {
  const p = (k - 1) * 100, a = Math.abs(p)
  const txt = a < 10 && Math.abs(a - Math.round(a)) > 0.05 ? a.toFixed(1) : Math.round(a).toString()
  return `${p > 0 ? '+' : p < 0 ? '−' : ''}${txt.replace('.', ',')}%`
}
const same = (a: number, b: number) => Math.abs(a - b) < 0.005

// ---------- режим «Коэффициенты» ----------

const KNOBS: { key: keyof Scenario; label: string; hint: string }[] = [
  { key: 'kWeather', label: 'Погода', hint: 'осадки, мороз' },
  { key: 'kEvent', label: 'Событие', hint: 'мероприятие, перекрытие' },
  { key: 'kSeason', label: 'Сезон', hint: 'каникулы, праздники' },
  { key: 'kTraffic', label: 'Трафик', hint: 'пробки на дорогах' },
  { key: 'kManual', label: 'Ручная', hint: 'решение диспетчера' },
]

// Что измерено ML-моделью по каждому источнику (artifacts/coefficients.json через /api/coefficients).
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

// ---------- режим «Фильтры» ----------
// Множители измерены на истории 2025 года (docs/ablation_external_sources.csv, effects_report.csv):
// праздник в будни 0.45 от обычного будня; суббота 0.66, воскресенье 0.57 от будня (сен–окт, без маршрутов 7/50);
// рабочая суббота 0.85 от будня; дождь при t ≥ 10 °C −1.8% на мм; мороз ниже −10 °C 0.97; сбой 0.83 (3 часа после поста);
// мероприятие 1.015 и перекрытие 1.02 (дни события, будни); пробки 6+ баллов 1.01 (связь слабая, в прогноз не входит).

type Option = { id: string; label: string; k: number; note?: string }
// combos — для групп, где галочки совместимы: набор отмеченных вариантов → итоговый множитель.
// Без combos варианты взаимоисключающие (в группе один множитель).
type Combo = { ids: string[]; k: number }
type Group = { key: keyof Scenario; title: string; hint?: string; options: Option[]; disabled?: string
  combos?: Combo[]; toggle?: (ids: Set<string>, id: string) => Set<string>; comboNote?: (ids: Set<string>) => string | null }

const SAT = 0.66, SUN = 0.57, HOLIDAY = 0.45

function groups(dayType: string | null, dow: number, rainBeta: number, kIncident: number, horizon: Horizon): Group[] {
  const isHoliday = dayType === 'holiday'
  // Рабочая суббота (перенос) — рабочий день: модель прогнозирует её как будний.
  const dayOff = dayType != null && !['workday', 'short_workday', 'working_weekend'].includes(dayType)
  const offRatio = isHoliday ? HOLIDAY : dow === 6 ? SAT : dow === 7 ? SUN : (SAT + SUN) / 2
  const OFF = r2((SAT + SUN) / 2)
  let day: Group
  if (!dayOff) {
    // Рабочий по календарю. Праздник — всегда нерабочий день, поэтому он включает «Выходной»;
    // обе галочки = праздник (×0.45), а не произведение множителей.
    day = { key: 'kSeason', title: 'День', hint: dayType === 'working_weekend' ? 'по календарю рабочая суббота (перенос)' : 'по календарю рабочий',
      options: [
        { id: 'off', label: 'Выходной день', k: OFF, note: 'выходной ≈ 0.62 от будня (суббота 0.66, воскресенье 0.57)' },
        { id: 'holiday', label: 'Праздник', k: HOLIDAY, note: 'праздник ≈ 0.45 от будня — как воскресенье × 0.9' },
      ],
      combos: [{ ids: ['off'], k: OFF }, { ids: ['holiday', 'off'], k: HOLIDAY }],
      toggle: (ids, id) => {
        const n = new Set(ids)
        if (n.has(id)) { n.delete(id); if (id === 'off') n.delete('holiday') } else { n.add(id); if (id === 'holiday') n.add('off') }
        return n
      },
      comboNote: ids => ids.has('holiday') ? 'праздник — нерабочий день, «выходной» включён вместе с ним' : null }
  } else {
    // Выходной или праздник по календарю — модель это уже учла. Можно отметить праздник в выходной
    // (≈ 0.94 от обычного выходного) или перенос рабочего дня; вместе они не бывают.
    const work = { id: 'work', label: 'Сделать рабочим (перенос)', k: r2(0.85 / offRatio), note: 'как рабочая суббота: 0.85 от будня' }
    const opts: Option[] = isHoliday ? [work]
      : [{ id: 'holiday', label: 'Праздник', k: 0.94, note: 'праздник в выходной ≈ 0.94 от обычного выходного' }, work]
    day = { key: 'kSeason', title: 'День', hint: isHoliday ? 'по календарю праздник — модель это уже учла' : 'по календарю выходной — модель это уже учла', options: opts }
  }
  if (horizon !== 'day') day.disabled = 'для отдельных суток — горизонт «День»'
  return [
    day,
    { key: 'kWeather', title: 'Погода', hint: 'в холодный сезон эффект близок к нулю',
      options: [
        { id: 'rain', label: 'Дождь (~5 мм)', k: r2(1 + rainBeta * 5), note: `${(rainBeta * 100).toFixed(1)}% на мм при t ≥ 10 °C` },
        { id: 'downpour', label: 'Ливень (~15 мм)', k: r2(1 + rainBeta * 15), note: `${(rainBeta * 100).toFixed(1)}% на мм при t ≥ 10 °C` },
        { id: 'frost', label: 'Мороз ниже −10 °C', k: 0.97, note: 'измерено по будням 2025' },
      ] },
    { key: 'kEvent', title: 'Событие',
      options: [
        { id: 'incident', label: 'Сбой на маршруте', k: r2(kIncident), note: 'ДТП, контактная сеть: первые 3 часа после сбоя' },
        { id: 'mass', label: 'Массовое мероприятие', k: 1.02, note: 'дни мероприятий: +1.5…2% (измерено по будням)' },
        { id: 'closure', label: 'Перекрытие улиц', k: 1.02, note: 'пересадка с наземного транспорта ≈ +2%' },
      ] },
    // Посадки к обычному уровню в часы с баллом ЦОДД, мар–окт 2025 (docs/traffic-operational-test.md):
    // свободные дороги −2.9%, пробки 6+ +1.5%. Балл по городу целиком, в прогноз модели не входит.
    { key: 'kTraffic', title: 'Пробки', hint: 'по городу, балл ЦОДД / Яндекса',
      options: [
        { id: 'free', label: 'Свободные дороги (0–5 б.)', k: 0.971, note: 'посадок на 2,9% меньше обычного — часть едет на машине' },
        { id: 'jam', label: 'Сильные пробки (6+ б.)', k: 1.015, note: 'посадок на 1,5% больше обычного — часть водителей пересаживается' },
      ] },
  ]
}

function SimpleMode({ value, onChange, dayType, dow, horizon, model, onOpenTraffic }: {
  value: Scenario; onChange: (s: Scenario) => void; dayType: string | null; dow: number; horizon: Horizon; model: Record<string, any> | null
  onOpenTraffic: () => void
}) {
  const rainBeta = model?.weather?.beta_per_mm_precip_day_if_warm?.[0] ?? -0.018
  const kIncident = model?.incident?.K_incident ?? 0.83
  const gs = groups(dayType, dow, rainBeta, kIncident, horizon)
  const pct = Math.round((value.kManual - 1) * 100)
  return (
    <div className="simple">
      {gs.map(g => {
        const cur = value[g.key]
        const onIds = new Set<string>(g.combos
          ? (g.combos.find(c => same(c.k, cur))?.ids ?? [])
          : g.options.filter(o => same(o.k, cur)).slice(0, 1).map(o => o.id))
        const custom = !same(cur, 1) && onIds.size === 0
        const click = (o: Option) => {
          if (!g.combos || !g.toggle) return onChange({ ...value, [g.key]: onIds.has(o.id) ? 1 : o.k })
          const next = g.toggle(onIds, o.id)
          const combo = g.combos.find(c => c.ids.length === next.size && c.ids.every(i => next.has(i)))
          onChange({ ...value, [g.key]: combo ? combo.k : 1 })
        }
        const note = g.comboNote?.(onIds)
        return (
          <fieldset key={g.key} className="sgroup" disabled={!!g.disabled}>
            <legend>{g.title}{(g.disabled || g.hint) && <small>{g.disabled ?? g.hint}</small>}</legend>
            <div className="chips-row">
              {g.options.map(o => {
                const on = onIds.has(o.id)
                return (
                  <label key={o.id} className={`opt${on ? ' on' : ''}`} title={o.note}>
                    <input type="checkbox" checked={on} onChange={() => click(o)} />
                    <span className="opt-box" aria-hidden="true">{on ? '✓' : ''}</span>
                    <span className="opt-label">{o.label}</span>
                    <span className="opt-k">{effect(o.k)}</span>
                  </label>
                )
              })}
            </div>
            {note && <p className="muted small combo-note">{note}</p>}
            {g.key === 'kTraffic' && <button type="button" className="more-btn" onClick={onOpenTraffic}>Подробнее о пробках и как их учитывать →</button>}
            {custom && <p className="custom">задано коэффициентом ×{cur.toFixed(2)} ·{' '}
              <button type="button" className="link inline" onClick={() => onChange({ ...value, [g.key]: 1 })}>убрать</button></p>}
          </fieldset>
        )
      })}
      <fieldset className="sgroup">
        <legend>Своя поправка
          <Help label="Что такое своя поправка">
            <b>Своя поправка</b> — ваш множитель ко всему прогнозу для того, чего модель знать не может:
            на маршрут вышло меньше или больше вагонов, у конечной большое мероприятие, пришло распоряжение, по опыту смены
            пассажиров будет больше.<br />
            <b>+10%</b> — прогноз станет на 10% выше, <b>−20%</b> — на 20% ниже. Шаг 5%, диапазон −50…+50%.<br />
            Действует сразу на всё: карту, график, таблицу маршрутов, «Требует внимания» и выгрузку XLSX/CSV;
            сохраняется в ссылке, так что картину можно передать смене. Модель при этом не меняется —
            «сбросить» вверху панели вернёт исходный прогноз.
          </Help>
          <small>ваш множитель ко всему прогнозу</small></legend>
        <div className="manual">
          <button type="button" onClick={() => onChange({ ...value, kManual: r2(Math.max(0.5, value.kManual - 0.05)) })} aria-label="Минус 5%">−5%</button>
          <input type="range" min={-50} max={50} step={5} value={Math.max(-50, Math.min(50, pct))} aria-label="Своя поправка, %"
                 onChange={e => onChange({ ...value, kManual: r2(1 + Number(e.target.value) / 100) })} />
          <button type="button" onClick={() => onChange({ ...value, kManual: r2(Math.min(1.5, value.kManual + 0.05)) })} aria-label="Плюс 5%">+5%</button>
          <span className={`manual-val${pct !== 0 ? ' changed' : ''}`}>{pct > 0 ? '+' : ''}{pct}%</span>
        </div>
      </fieldset>
    </div>
  )
}

// ---------- панель ----------

type Mode = 'simple' | 'coef'
const readMode = (): Mode => { try { return localStorage.getItem('scenarioMode') === 'coef' ? 'coef' : 'simple' } catch { return 'simple' } }

export function ScenarioPanel({ value, onChange, dayType, dow, horizon, onOpenTraffic }: {
  value: Scenario; onChange: (s: Scenario) => void; dayType: string | null; dow: number; horizon: Horizon; onOpenTraffic: () => void
}) {
  const [model, setModel] = useState<Record<string, any> | null>(null)
  const [mode, setMode] = useState<Mode>(readMode)
  useEffect(() => {
    get<Coef>('/api/coefficients').then(c => setModel(c.model ?? null)).catch(() => setModel(null))
  }, [])
  const pick = (m: Mode) => { setMode(m); try { localStorage.setItem('scenarioMode', m) } catch { /* приватный режим */ } }
  const notes = measured(model)
  const total = Object.values(value).reduce((a, b) => a * b, 1)
  const changed = !same(total, 1) || Object.values(value).some(v => !same(v, 1))
  return (
    <section className="panel scenario">
      <header className="panel-head">
        <h2>Поправки</h2>
        <span className={changed ? 'ktotal changed' : 'ktotal'} title="Итоговый множитель к прогнозу модели">
          {total >= 1 ? '+' : '−'}{Math.abs(Math.round((total - 1) * 100))}%
        </span>
        {changed && <button className="link" onClick={() => onChange(NEUTRAL)}>сбросить</button>}
      </header>
      <div className="modes" role="tablist" aria-label="Режим поправок">
        <button role="tab" aria-selected={mode === 'simple'} className={mode === 'simple' ? 'on' : ''} onClick={() => pick('simple')}>Фильтры</button>
        <button role="tab" aria-selected={mode === 'coef'} className={mode === 'coef' ? 'on' : ''} onClick={() => pick('coef')}>Коэффициенты</button>
      </div>
      {mode === 'simple'
        ? <SimpleMode value={value} onChange={onChange} dayType={dayType} dow={dow} horizon={horizon} model={model} onOpenTraffic={onOpenTraffic} />
        : KNOBS.map(k => (
          <label key={k.key} className="knob">
            <span className="knob-label">{k.label}<small>{k.hint}</small>{notes[k.key] && <small className="measured">{notes[k.key]}</small>}</span>
            <input type="range" min={0.3} max={3} step={0.05} value={value[k.key]}
                   onChange={e => onChange({ ...value, [k.key]: Number(e.target.value) })} />
            <span className="knob-val">×{value[k.key].toFixed(2)}</span>
          </label>
        ))}
      <p className="muted small scenario-note">Множители измерены на истории 2025 года и применяются поверх прогноза модели; модель не меняется.</p>
    </section>
  )
}
