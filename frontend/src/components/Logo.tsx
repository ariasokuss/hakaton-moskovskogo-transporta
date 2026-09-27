// Логотип «Пантограф» (logo/pantograph_*.png) в векторе: контактный провод, точка токосъёма и ромб
// токоприёмника. Цвет знака — currentColor, поэтому логотип читается на светлом и на тёмном фоне.
// animated — по проводу бежит «энергия», в точке контакта пульсирует искра.
const YELLOW = '#FFB703'

export function LogoMark({ size = 32, animated = false, title = 'Пантограф' }: { size?: number; animated?: boolean; title?: string }) {
  return (
    <svg className={`logo-mark${animated ? ' animated' : ''}`} width={size} height={size} viewBox="0 0 128 128" role="img" aria-label={title}>
      <line x1="16" y1="36" x2="56" y2="36" stroke="currentColor" strokeWidth="7" strokeLinecap="round" />
      <line className="logo-wire" x1="74" y1="36" x2="112" y2="36" stroke="currentColor" strokeWidth="6"
            strokeLinecap="round" strokeDasharray="0.1 11" />
      <path d="M64 44 L44 76 L64 112 L84 76 Z" fill="none" stroke="currentColor" strokeWidth="7" strokeLinejoin="round" />
      <circle className="logo-spark" cx="64" cy="36" r="16" fill={YELLOW} opacity="0" />
      <circle cx="64" cy="36" r="9" fill={YELLOW} />
    </svg>
  )
}

export function Logo({ size = 32, animated = false, subtitle }: { size?: number; animated?: boolean; subtitle?: string }) {
  return (
    <span className="logo">
      <LogoMark size={size} animated={animated} />
      <span className="logo-text">
        <span className="logo-word">Пантограф</span>
        {subtitle && <small>{subtitle}</small>}
      </span>
    </span>
  )
}
