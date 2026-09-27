import { useEffect, useState } from 'react'

// Тема интерфейса: светлая, тёмная или как в системе. Выбор хранится в браузере диспетчера;
// итоговая тема — атрибут data-theme на <html>, от него зависят все цвета (styles.css) и подложка карты.
export type ThemeChoice = 'auto' | 'light' | 'dark'
export type Theme = 'light' | 'dark'

const KEY = 'theme'
const media = () => window.matchMedia('(prefers-color-scheme: dark)')

export function readChoice(): ThemeChoice {
  try {
    const v = localStorage.getItem(KEY)
    return v === 'light' || v === 'dark' ? v : 'auto'
  } catch { return 'auto' }
}

export const resolve = (c: ThemeChoice): Theme => (c === 'auto' ? (media().matches ? 'dark' : 'light') : c)

/** До первой отрисовки — чтобы экран не мигал светлой темой. */
export function applyInitialTheme() {
  document.documentElement.dataset.theme = resolve(readChoice())
}

export function useTheme() {
  const [choice, setChoice] = useState<ThemeChoice>(readChoice)
  const [theme, setTheme] = useState<Theme>(() => resolve(choice))
  useEffect(() => {
    const update = () => {
      const t = resolve(choice)
      setTheme(t)
      document.documentElement.dataset.theme = t
      document.querySelector('meta[name="theme-color"]')?.setAttribute('content', t === 'dark' ? '#0f141b' : '#0B2545')
    }
    update()
    try { if (choice === 'auto') localStorage.removeItem(KEY); else localStorage.setItem(KEY, choice) } catch { /* приватный режим */ }
    const m = media()
    m.addEventListener('change', update)
    return () => m.removeEventListener('change', update)
  }, [choice])
  return { choice, theme, setChoice }
}
