-- =============================================================================
-- V10: чистка старых резинок, вес жёлтой, переименование петли
--      и точный состав инвентаря у «Тяга резины сидя».
--
-- Порядок важен:
--   * сначала даём вес жёлтой — иначе она попадёт под удаление «без веса»;
--   * связи удаляемых предметов снимаем ДО удаления из equipment
--     (FK exercise_equipment.equipment_id стоит ON DELETE RESTRICT);
--   * петлю не удаляем, а переименовываем.
-- =============================================================================

-- 1. Вес жёлтой резинки (4.6 кг)
UPDATE equipment
SET weight_grams = 4600
WHERE name = 'Резинка желтая';

-- 2. Снимаем связи со старыми резинками без веса
DELETE FROM exercise_equipment ee
USING equipment eq
WHERE eq.id = ee.equipment_id
  AND eq.weight_grams IS NULL
  AND (eq.name ILIKE '%резин%' OR eq.name ILIKE '%петл%')
  AND eq.name <> 'Петля (эспандер)';

-- 3. Удаляем сами старые записи (CRUD-справочник: «Резинка черная» без веса и т.п.)
DELETE FROM equipment
WHERE weight_grams IS NULL
  AND (name ILIKE '%резин%' OR name ILIKE '%петл%')
  AND name <> 'Петля (эспандер)';

-- 4. Петля становится фиолетовой резинкой-петлёй (id не меняется, связи остаются)
UPDATE equipment
SET name = 'Резинка фиолетовая (петля)'
WHERE name = 'Петля (эспандер)';

-- 5. Pull-apart: оставляем только зелёную 13.6 кг
DELETE FROM exercise_equipment ee
USING exercises e, equipment eq
WHERE e.id = ee.exercise_id
  AND eq.id = ee.equipment_id
  AND e.name ILIKE '%Pull-apart%'
  AND eq.name = 'Резинка красная 9.1 кг';

-- 6. «Тяга резины сидя» — ровно три связи: 22.7, 27.2, жёлтая.
--    «…в быстром темпе» не трогаем: у неё своя зелёная резинка.
DELETE FROM exercise_equipment ee
USING exercises e
WHERE e.id = ee.exercise_id
  AND e.name ILIKE '%Тяга резины сидя%'
  AND e.name NOT ILIKE '%в быстром темпе%';

INSERT INTO exercise_equipment (exercise_id, equipment_id, quantity)
SELECT e.id, eq.id, 1
FROM exercises e
JOIN equipment eq ON eq.name IN (
        'Резинка черная 22.7 кг',
        'Резинка черная 27.2 кг',
        'Резинка желтая')
WHERE e.name ILIKE '%Тяга резины сидя%'
  AND e.name NOT ILIKE '%в быстром темпе%'
ON CONFLICT (exercise_id, equipment_id) DO NOTHING;
