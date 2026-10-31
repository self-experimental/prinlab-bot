UPDATE filament_colors
SET surface = 'Матовый'
WHERE LOWER(TRIM(surface)) IN ('матовая', 'матовое', 'матовый', 'matte');

UPDATE filament_colors
SET surface = 'Глянцевый'
WHERE LOWER(TRIM(surface)) IN ('глянцевая', 'глянцевое', 'глянцевый', 'glossy');
