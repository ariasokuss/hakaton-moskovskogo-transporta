import type { ReactElement } from 'react'
import type { ThemeChoice } from '../theme'

const ICONS: Record<ThemeChoice, ReactElement> = {
  light: <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round"><circle cx="12" cy="12" r="4.5" /><path d="M12 2v2.5M12 19.5V22M4.2 4.2l1.8 1.8M18 18l1.8 1.8M2 12h2.5M19.5 12H22M4.2 19.8 6 18M18 6l1.8-1.8" /></svg>,
  auto: <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2"><rect x="3" y="4" width="18" height="13" rx="2" /><path d="M8 21h8M12 17v4" strokeLinecap="round" /></svg>,
  dark: <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinejoin="round"><path d="M20 14.5A8.5 8.5 0 0 1 9.5 4a8.5 8.5 0 1 0 10.5 10.5Z" /></svg>,
}
const TITLES: Record<ThemeChoice, string> = { light: 'Светлая тема', auto: 'Как в системе', dark: 'Тёмная тема' }

// Тема интерфейса: светлая, как в системе, тёмная. Для диспетчерской ночью — тёмная.
export function ThemeToggle({ choice, onChange }: { choice: ThemeChoice; onChange: (c: ThemeChoice) => void }) {
  return (
    <div className="theme-toggle" role="radiogroup" aria-label="Тема оформления">
      {(['light', 'auto', 'dark'] as ThemeChoice[]).map(c => (
        <button key={c} role="radio" aria-checked={choice === c} className={choice === c ? 'on' : ''} title={TITLES[c]}
                aria-label={TITLES[c]} onClick={() => onChange(c)}>{ICONS[c]}</button>
      ))}
    </div>
  )
}
