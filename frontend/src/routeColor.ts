// Цвет маршрута приходит с бэкенда и одинаков везде. Исключение — тёмная тема: почти чёрный цвет
// (маршрут 50, #2D3142) на графитовой подложке не виден, поэтому в ней он заменяется светлым.
// Критерий — яркость цвета, а не номер маршрута: сработает и для нового тёмного маршрута.
const DARK_THEME_LIGHT = '#C5CCD8'

function luminance(hex: string): number {
  const m = /^#?([0-9a-f]{6})$/i.exec(hex)
  if (!m) return 1
  const [r, g, b] = [0, 2, 4].map(i => {
    const c = parseInt(m[1].slice(i, i + 2), 16) / 255
    return c <= 0.03928 ? c / 12.92 : ((c + 0.055) / 1.055) ** 2.4
  })
  return 0.2126 * r + 0.7152 * g + 0.0722 * b
}

export const isTooDark = (hex: string) => luminance(hex) < 0.06

export function routeColor(hex: string, dark: boolean): string {
  return dark && isTooDark(hex) ? DARK_THEME_LIGHT : hex
}
