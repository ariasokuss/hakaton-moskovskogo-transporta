import { useId, useLayoutEffect, useRef, useState, type ReactNode } from 'react'

// Подсказка «?»: раскрывается при наведении, по фокусу с клавиатуры и по нажатию на планшете.
// Окно позиционируется относительно экрана (position: fixed), поэтому его не обрезают прокручиваемые панели;
// помещается в видимую область и открывается вверх, если снизу не хватает места.
export function Help({ label, children }: { label: string; children: ReactNode }) {
  const [open, setOpen] = useState(false)
  const [pos, setPos] = useState<{ left: number; top: number } | null>(null)
  const btn = useRef<HTMLButtonElement>(null)
  const pop = useRef<HTMLSpanElement>(null)
  const id = useId()

  useLayoutEffect(() => {
    if (!open || !btn.current || !pop.current) { setPos(null); return }
    const b = btn.current.getBoundingClientRect(), p = pop.current.getBoundingClientRect(), gap = 8, edge = 12
    const left = Math.min(Math.max(edge, b.left + b.width / 2 - p.width / 2), innerWidth - p.width - edge)
    const below = b.bottom + gap
    const top = below + p.height <= innerHeight - edge ? below : Math.max(edge, b.top - gap - p.height)
    setPos({ left, top })
  }, [open])

  return (
    <span className="help" onMouseEnter={() => setOpen(true)} onMouseLeave={() => setOpen(false)}>
      <button ref={btn} type="button" className={`help-btn${open ? ' on' : ''}`} aria-label={label} aria-describedby={id}
              aria-expanded={open} onClick={() => setOpen(o => !o)} onFocus={() => setOpen(true)} onBlur={() => setOpen(false)}>?</button>
      <span ref={pop} role="tooltip" id={id} className={`help-pop${open && pos ? ' open' : ''}`}
            style={pos ? { left: pos.left, top: pos.top } : undefined}>{children}</span>
    </span>
  )
}
