"""Улицы вдоль трасс трамвайных маршрутов — для привязки постов ЦОДД о пробках к маршрутам.

    python tools/build_route_streets.py            # → data/geo/route_streets.json

Источник — OpenStreetMap через Overpass API (те же отношения маршрутов, что в data/geo/routes_osm.json):
улицы с названием в пределах 35 м от путей маршрута. Снимок кладётся в репозиторий, чтобы сервис работал без сети.
Данные © участники OpenStreetMap, ODbL.
"""
import json
import time
import urllib.parse
import urllib.request
from collections import defaultdict
from datetime import date
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
URL = 'https://overpass-api.de/api/interpreter'
HIGHWAYS = 'primary|primary_link|secondary|secondary_link|tertiary|tertiary_link|trunk|trunk_link|residential|unclassified|living_street'

routes = json.load(open(ROOT / 'data/geo/routes_osm.json', encoding='utf-8'))['routes']
rels = defaultdict(list)
for r in routes:
    rels[r['route_id']].append(r['osm_relation'])

out = {}
for route_id, ids in sorted(rels.items()):
    q = (f'[out:json][timeout:90];rel(id:{",".join(map(str, ids))});way(r)->.t;'
         f'way(around.t:35)[highway~"^({HIGHWAYS})$"][name];out tags;')
    for attempt in range(4):
        try:
            req = urllib.request.Request(URL, data=urllib.parse.urlencode({'data': q}).encode(),
                                         headers={'User-Agent': 'PantografHackathon/1.0 (tram passenger forecast)'})
            els = json.load(urllib.request.urlopen(req, timeout=120))['elements']
            break
        except Exception as e:  # Overpass ограничивает частоту запросов — повторяем с паузой
            print(f'маршрут {route_id}: {e}, повтор')
            time.sleep(15 * (attempt + 1))
    else:
        raise SystemExit(f'маршрут {route_id}: Overpass недоступен')
    names = sorted({e['tags']['name'] for e in els if e.get('tags', {}).get('name')})
    out[str(route_id)] = names
    print(route_id, len(names), names[:6])
    time.sleep(3)

json.dump({'source': 'OpenStreetMap (Overpass API)', 'license': 'ODbL', 'fetched_at': str(date.today()),
           'method': 'улицы с названием (highway: ' + HIGHWAYS + ') в пределах 35 м от путей маршрута',
           'routes': out}, open(ROOT / 'data/geo/route_streets.json', 'w', encoding='utf-8'), ensure_ascii=False, indent=1)
print('→ data/geo/route_streets.json')
