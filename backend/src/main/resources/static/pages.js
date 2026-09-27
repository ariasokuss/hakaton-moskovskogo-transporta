// Общие функции служебных страниц: подсветка JSON и безопасная вставка текста.
window.esc = s => String(s).replace(/[&<>"]/g, c => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;' }[c]))

// JSON → HTML с подсветкой. Большие ответы режутся по строкам, чтобы страница не зависала.
window.highlight = (value, maxLines = 400) => {
  let text = JSON.stringify(value, null, 2)
  const lines = text.split('\n')
  let tail = ''
  if (lines.length > maxLines) {
    text = lines.slice(0, maxLines).join('\n')
    tail = `\n… ещё ${lines.length - maxLines} строк — откройте ссылку, чтобы увидеть ответ целиком`
  }
  return esc(text).replace(
    /("(?:\\.|[^"\\])*")(\s*:)?|\b(true|false|null)\b|(-?\d+(?:\.\d+)?(?:[eE][+-]?\d+)?)/g,
    (m, str, colon, lit, num) =>
      str ? (colon ? `<span class="j-key">${str}</span>${colon}` : `<span class="j-str">${str}</span>`)
        : lit ? `<span class="j-lit">${lit}</span>` : `<span class="j-num">${num}</span>`) + esc(tail)
}

// JSON этого сервиса: явный Accept, чтобы не получить HTML-страницу вместо данных.
window.getJson = async url => {
  const r = await fetch(url, { headers: { Accept: 'application/json' }, cache: 'no-store' })
  return r.json()
}
