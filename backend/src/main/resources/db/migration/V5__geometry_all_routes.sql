-- Геометрия теперь есть для всех 9 маршрутов: снимок OpenStreetMap (data/geo/routes_osm.json).
-- Справочник организаторов покрывал только 1, 7, 11, 12.
UPDATE core.route SET has_geometry = true;
