import { useEffect, useState } from 'react'
import { LogoMark } from './Logo'

// Заставка при входе: видео московского трамвая и название. Показывается на «голый» адрес один раз за сессию;
// ссылки с параметрами (демо, передача смене) открывают рабочий экран сразу.
// Атрибуция видео (CC BY 4.0) — frontend/public/media/ATTRIBUTION.txt и README.
export function Splash({ onStart }: { onStart: () => void }) {
  const [leaving, setLeaving] = useState(false)
  const [playing, setPlaying] = useState(false)
  // Переход на рабочее место — полноэкранный проход линий маршрутов (TramWipe в App).
  const start = () => {
    if (leaving) return
    setLeaving(true)
    onStart()
  }
  useEffect(() => {
    const onKey = (e: KeyboardEvent) => { if (e.key === 'Enter' || e.key === 'Escape') start() }
    window.addEventListener('keydown', onKey)
    return () => window.removeEventListener('keydown', onKey)
  })

  return (
    <div className={`splash${leaving ? ' leaving' : ''}`} role="dialog" aria-modal="true" aria-label="Пантограф — начало работы">
      {/* Видео проявляется, когда реально пошло воспроизведение: до этого — кадр-постер, без рывка на первом кадре. */}
      <video className={`splash-video${playing ? ' on' : ''}`} src="/media/tram-parade.mp4" poster="/media/tram-parade-poster.jpg"
             autoPlay muted loop playsInline preload="auto" aria-hidden="true" onPlaying={() => setPlaying(true)} />
      <div className="splash-shade" />
      <div className="splash-body">
        <LogoMark size={88} animated title="Логотип Пантограф" />
        <h1 className="splash-title" aria-label="Пантограф">
          {'ПАНТОГРАФ'.split('').map((ch, i) => <span key={i} style={{ animationDelay: `${0.1 + i * 0.05}s` }}>{ch}</span>)}
        </h1>
        <p className="splash-slogan">Из данных — энергия, из энергии — прогноз</p>
        <p className="splash-lead">ИИ-прогноз загрузки трамвайных маршрутов Москвы по часам, дням и месяцам — рабочее место диспетчера</p>
        <button className="splash-go" onClick={start} autoFocus>Начать работу <span className="arrow" aria-hidden="true">→</span></button>
        <p className="splash-team">
          <span>Команда «майнкрафт абманка»</span>
          <span className="sep" aria-hidden="true" />
          <span>Университет ИТМО</span>
        </p>
      </div>
    </div>
  )
}
