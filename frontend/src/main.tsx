import { StrictMode } from 'react'
import { createRoot } from 'react-dom/client'
// Шрифты встроены в сборку (без обращения к внешним CDN — демо работает и без интернета):
// Onest — интерфейс, кириллица; JetBrains Mono — числа и номера маршрутов (моноширинные цифры в таблицах).
import '@fontsource-variable/onest'
import '@fontsource/jetbrains-mono/400.css'
import '@fontsource/jetbrains-mono/600.css'
import '@fontsource/jetbrains-mono/700.css'
import 'maplibre-gl/dist/maplibre-gl.css'
import './styles.css'
import App from './App'
import { applyInitialTheme } from './theme'

applyInitialTheme()
createRoot(document.getElementById('root')!).render(<StrictMode><App /></StrictMode>)
