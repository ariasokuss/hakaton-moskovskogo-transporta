"""Привязка мест затруднений из постов ЦОДД к трамвайным маршрутам по улицам вдоль трасс (OSM).

    python tools/route_traffic_match.py        # примеры совпадений и замер связи с посадками маршрута

Тот же алгоритм, что в сервисе (backend .../ingest/RouteStreets.java): название улицы → значимые слова
без типа улицы и номеров → основы слов; место затруднения относится к маршруту, если в его тексте
есть все основы названия хотя бы одной улицы вдоль трассы.
"""
import csv
import json
import re
import statistics
from collections import defaultdict
from datetime import date, timedelta
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
EXT = ROOT / 'mostrans_handoff/mostrans/external_data'
TYPES = {'улица', 'проспект', 'шоссе', 'переулок', 'проезд', 'бульвар', 'площадь', 'набережная', 'тупик', 'аллея',
         'дублёр', 'дублер', 'тоннель', 'мост', 'путепровод', 'эстакада', 'вал', 'линия', 'просек', 'кольцо'}
# Кольцевые магистрали трамваи только пересекают (по ним не ездят) — к трассе маршрута не относим.
RINGS = ('кольцевая', 'транспортное кольцо', 'садовое кольцо', 'бульварное кольцо')
# Общие слова названий: сами по себе не отличают улицу («Внутренний проезд» ловился на «внутренней стороне»).
GENERIC = {'внутренний', 'внешний', 'большой', 'большая', 'малый', 'малая', 'новый', 'новая', 'старый', 'старая', 'верхний',
           'верхняя', 'нижний', 'нижняя', 'средний', 'средняя', 'северный', 'южный', 'западный', 'восточный', 'первый', 'второй',
           'лесной', 'лесная', 'полевой', 'полевая', 'парковая', 'садовая', 'новослободская', 'поперечный'}
TYPE_STEMS = {'улица': 'улиц', 'проспект': 'проспект', 'шоссе': 'шоссе', 'переулок': 'переул', 'проезд': 'проезд',
              'бульвар': 'бульвар', 'площадь': 'площад', 'набережная': 'набережн', 'тупик': 'тупик', 'аллея': 'алле',
              'тоннель': 'тоннел', 'мост': 'мост', 'путепровод': 'путепровод', 'эстакада': 'эстакад', 'вал': 'вал'}


def norm(s):
    return s.lower().replace('ё', 'е')


def stems(name):
    """Название улицы → [(основы значимых слов, основа типа улицы)]; пусто для колец и слишком общих названий."""
    n = norm(re.sub(r'\(.*?\)', '', name))
    if any(r in n for r in RINGS):
        return []
    tokens = [w for w in re.split(r'[\s\-–—,.«»]+', n) if w]
    kind = next((TYPE_STEMS[w] for w in tokens if w in TYPE_STEMS), None)
    words = [w for w in tokens if w not in TYPES and not re.match(r'^\d', w) and len(w) >= 4]
    if not words or all(w in GENERIC for w in words) or (len(words) == 1 and len(words[0]) < 7):
        return []
    return [([w[:max(4, len(w) - 2)] for w in words], kind)]


def route_index():
    rs = json.load(open(ROOT / 'data/geo/route_streets.json', encoding='utf-8'))['routes']
    return {int(r): [(n, st) for n in names for st in stems(n)] for r, names in rs.items()}


# Затруднение на самой кольцевой магистрали («МКАД в районе Ленинского проспекта», «ТТК в районе улицы Сущевский Вал»):
# улица названа лишь как ориентир развязки, трамвая там нет — к маршруту не относим.
ON_RING = re.compile(r'(?<![а-я])(мкад|ттк|садов\w* кольц|третье\w* транспортн\w* кольц)')


def match(spot, index):
    t = norm(spot)
    out = {}
    if ON_RING.search(t):
        return out
    for route, streets in index.items():
        # основы — только с начала слова («Ленская» не ловится внутри «Смоленской»); тип улицы должен совпасть
        hit = [n for n, (st, kind) in streets
               if all(re.search(r'(?<![а-я])' + re.escape(s), t) for s in st) and (kind is None or re.search(r'(?<![а-я])' + kind, t))]
        if hit:
            out[route] = sorted(set(hit))
    return out


if __name__ == '__main__':
    import sys
    sys.path.insert(0, str(ROOT / 'tools'))
    index = route_index()
    posts = {}
    for f in ['DtOperativno_posts_raw.jsonl', 'deptrans_posts_raw.jsonl']:
        for line in open(EXT / f, encoding='utf-8'):
            o = json.loads(line)
            posts[o['id']] = o.get('text') or ''
    scores = list(csv.DictReader(open(EXT / 'DtOperativno_traffic_scores_2025.csv', encoding='utf-8'), delimiter=';'))
    spot_re = re.compile(r'^\s*(?:🔹|🔸|•|-)\s*(.+)$', re.M)
    hits = defaultdict(set)          # (date, hour) -> routes
    shown = 0
    for r in scores:
        if r['kind'] != 'fact':
            continue
        for sp in spot_re.findall(posts.get(int(r['post_id']), '')):
            m = match(sp, index)
            for route in m:
                hits[(r['date'], int(r['hour']))].add(route)
            if m and shown < 12:
                print(f"{r['date']} {r['hour']:>2}ч  {sp.strip()[:70]:<70} → {m}")
                shown += 1
    print(f'\nчасов с затруднением на трассе хотя бы одного маршрута: {len(hits)}; маршрут-часов: {sum(len(v) for v in hits.values())}')
    per_route = defaultdict(int)
    for v in hits.values():
        for r in v:
            per_route[r] += 1
    print('по маршрутам:', dict(sorted(per_route.items())))

    # Связь с посадками: в тот же час у маршрута с затруднением на трассе против остальных маршрутов (к обычному уровню)
    fact = {}
    for r in csv.DictReader(open(ROOT / 'data/load/load_hourly.csv'), delimiter=';'):
        fact[(int(r['route']), r['date'], int(r['hour']))] = int(r['boardings'])

    def base(rt, d, h):
        dd = date.fromisoformat(d)
        v = [fact.get((rt, str(dd - timedelta(weeks=w)), h)) for w in range(1, 9)]
        v = [x for x in v if x is not None]
        return statistics.median(v) if len(v) >= 4 else None
    on, off = [0, 0], [0, 0]
    for (d, h), rs in hits.items():
        if d > '2025-10-31':
            continue
        for rt in index:
            y, b = fact.get((rt, d, h)), base(rt, d, h)
            if y is None or not b:
                continue
            acc = on if rt in rs else off
            acc[0] += y
            acc[1] += b
    if on[1] and off[1]:
        print(f'посадки к обычному уровню в те же часы: маршрут с затруднением на трассе {on[0] / on[1]:.3f}, '
              f'остальные {off[0] / off[1]:.3f} → {on[0] / on[1] / (off[0] / off[1]) - 1:+.1%} (маршрут-часов с затруднением: '
              f'{sum(1 for (d, h), rs in hits.items() if d <= "2025-10-31" for _ in rs)})')
