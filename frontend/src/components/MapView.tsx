import { useEffect, useRef, useState } from 'react'
import maplibregl from 'maplibre-gl'
import type { GeoJSONSource, LngLatBoundsLike, MapLayerMouseEvent } from 'maplibre-gl'
import type { Route } from '../api'

// Бесплатная подложка без ключа (OpenFreeMap, данные OpenStreetMap).
const STYLE = 'https://tiles.openfreemap.org/styles/positron'

type Geo = { type: 'FeatureCollection'; features: any[] }

/**
 * Карта сети. Трассы окрашены цветом маршрута, номер маршрута подписан вдоль
 * линии и остаётся читаемым при любом масштабе. Выбранный маршрут выделен,
 * остальные приглушены. Клик по трассе выбирает маршрут.
 */
export function MapView({ geo, routes, selected, onSelect }: {
  geo: Geo | null; routes: Route[]; selected: number | null; onSelect: (id: number | null) => void
}) {
  const box = useRef<HTMLDivElement>(null)
  const map = useRef<maplibregl.Map | null>(null)
  const ready = useRef(false)
  const [layersReady, setLayersReady] = useState(false)
  const pending = useRef<(() => void) | null>(null)
  const onSelectRef = useRef(onSelect)
  onSelectRef.current = onSelect

  useEffect(() => {
    const m = new maplibregl.Map({ container: box.current!, style: STYLE, center: [37.62, 55.76], zoom: 10.3, attributionControl: { compact: true } })
    m.addControl(new maplibregl.NavigationControl({ showCompass: false }), 'top-right')
    m.on('load', () => { ready.current = true; pending.current?.(); pending.current = null })
    map.current = m
    return () => { m.remove(); map.current = null; ready.current = false }
  }, [])

  // Данные: трассы и остановки. Цвет и подпись берутся из справочника маршрутов.
  useEffect(() => {
    const m = map.current
    if (!m || !geo || routes.length === 0) return
    const apply = () => {
      const byId = new Map(routes.map(r => [r.id, r]))
      const tracks = { type: 'FeatureCollection', features: geo.features.filter(f => f.properties.kind === 'track').map(f => ({
        ...f, properties: { ...f.properties, color: byId.get(f.properties.routeId)?.color ?? '#888', label: byId.get(f.properties.routeId)?.shortName ?? '' },
      })) }
      const stops = { type: 'FeatureCollection', features: geo.features.filter(f => f.properties.kind === 'stop').map(f => ({
        ...f, properties: { ...f.properties, transfer: f.properties.routes.length > 1, routeList: f.properties.routes.join(', ') },
      })) }
      if (m.getSource('tracks')) {
        (m.getSource('tracks') as GeoJSONSource).setData(tracks as any)
        ;(m.getSource('stops') as GeoJSONSource).setData(stops as any)
        return
      }
      m.addSource('tracks', { type: 'geojson', data: tracks as any })
      m.addSource('stops', { type: 'geojson', data: stops as any })
      m.addLayer({ id: 'tracks', type: 'line', source: 'tracks', layout: { 'line-cap': 'round', 'line-join': 'round' },
        paint: { 'line-color': ['get', 'color'], 'line-width': ['interpolate', ['linear'], ['zoom'], 10, 2.5, 15, 6], 'line-opacity': 0.9 } })
      m.addLayer({ id: 'tracks-hit', type: 'line', source: 'tracks', paint: { 'line-width': 14, 'line-opacity': 0 } })
      m.addLayer({ id: 'stops', type: 'circle', source: 'stops', minzoom: 11,
        paint: { 'circle-radius': ['case', ['get', 'transfer'], 4.5, 3], 'circle-color': '#fff',
          'circle-stroke-color': '#1c2430', 'circle-stroke-width': ['case', ['get', 'transfer'], 2, 1.2] } })
      m.addLayer({ id: 'stop-labels', type: 'symbol', source: 'stops', minzoom: 13.5,
        layout: { 'text-field': ['get', 'name'], 'text-size': 11, 'text-offset': [0, 1.1], 'text-anchor': 'top', 'text-font': ['Noto Sans Regular'] },
        paint: { 'text-color': '#1c2430', 'text-halo-color': '#fff', 'text-halo-width': 1.4 } })
      // Номер маршрута вдоль линии — однозначное обозначение при любом масштабе.
      m.addLayer({ id: 'track-labels', type: 'symbol', source: 'tracks',
        layout: { 'symbol-placement': 'line', 'symbol-spacing': 260, 'text-field': ['get', 'label'], 'text-size': 13,
          'text-font': ['Noto Sans Bold'], 'text-keep-upright': true },
        paint: { 'text-color': '#fff', 'text-halo-color': ['get', 'color'], 'text-halo-width': 3 } })
      m.on('click', 'tracks-hit', (e: MapLayerMouseEvent) => onSelectRef.current(e.features?.[0]?.properties?.routeId ?? null))
      m.on('mouseenter', 'tracks-hit', () => { m.getCanvas().style.cursor = 'pointer' })
      m.on('mouseleave', 'tracks-hit', () => { m.getCanvas().style.cursor = '' })
      m.on('click', 'stops', (e: MapLayerMouseEvent) => {
        const p = e.features?.[0]?.properties
        if (p) new maplibregl.Popup({ closeButton: false }).setLngLat(e.lngLat).setHTML(`<b>${p.name}</b><br/>маршруты: ${p.routeList}`).addTo(m)
      })
      m.fitBounds(bounds(tracks.features), { padding: 40, duration: 0 })
      setLayersReady(true)
    }
    if (ready.current) apply(); else pending.current = apply
  }, [geo, routes])

  // Выделение выбранного маршрута.
  useEffect(() => {
    const m = map.current
    if (!m || !layersReady || !m.getLayer('tracks')) return
    const sel: any = selected == null ? null : ['==', ['get', 'routeId'], selected]
    m.setPaintProperty('tracks', 'line-opacity', (sel ? ['case', sel, 1, 0.18] : 0.9) as any)
    m.setPaintProperty('tracks', 'line-width', (sel
      ? ['case', sel, ['interpolate', ['linear'], ['zoom'], 10, 5, 15, 9], ['interpolate', ['linear'], ['zoom'], 10, 2, 15, 4]]
      : ['interpolate', ['linear'], ['zoom'], 10, 2.5, 15, 6]) as any)
    m.setLayoutProperty('track-labels', 'visibility', 'visible')
    m.setFilter('track-labels', sel)
    if (geo && selected != null) {
      const fs = geo.features.filter(f => f.properties.kind === 'track' && f.properties.routeId === selected)
      if (fs.length) m.fitBounds(bounds(fs), { padding: 60, duration: 500 })
    }
  }, [selected, geo, layersReady])

  return <div ref={box} className="map" />
}

function bounds(features: any[]): LngLatBoundsLike {
  let w = 180, s = 90, e = -180, n = -90
  for (const f of features) for (const line of f.geometry.coordinates) for (const [x, y] of line) {
    if (x < w) w = x; if (x > e) e = x; if (y < s) s = y; if (y > n) n = y
  }
  return [[w, s], [e, n]]
}
