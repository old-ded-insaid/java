-- =============================================================================
-- V14: инвентарь для шаблонов, созданных ДО исправления импорта JSON.
--
-- Проблема: в ExerciseImportDto не было поля "inventory", поэтому импорт
-- создавал упражнения и целевые веса, но не связи exercise_equipment.
-- В тренировке из такого шаблона колонка «Инвентарь» показывала
-- «Без инвентаря».
--
-- Что делает миграция:
--   1. удаляет старые связи по «нашему» инвентарю (гантель, скамья, палка,
--      мяч, резинки) — на случай, если прежние правила привязали не то;
--   2. привязывает инвентарь заново по ключевым словам в названии упражнения.
--
-- Имена инвентаря: после V11/V12 в справочнике лежат записи без скобок
-- («Резинка красная 9.1 кг», «Резинка желтая 4.6 кг», «Гантель», ...),
-- поэтому сравнение идёт по префиксу (ILIKE 'Резинка красная%') — так
-- правило работает и для варианта «Резинка красная (9.1 кг)».
--
-- Миграция идемпотентна (ON CONFLICT DO NOTHING) и безопасна для пустой базы:
-- если упражнений нет, вставки просто ничего не делают.
-- =============================================================================

-- -----------------------------------------------------------------------------
-- 1. Чистим старые связи по управляемому инвентарю.
--    Мечи, щит, «одноручную палку» и прочее не трогаем: если пользователь
--    привязал их вручную, это его данные.
-- -----------------------------------------------------------------------------
DELETE FROM exercise_equipment ee
USING equipment eq
WHERE eq.id = ee.equipment_id
  AND (
           eq.name = 'Гантель'
        OR eq.name = 'Скамья'
        OR eq.name = 'Мяч'
        OR eq.name ILIKE 'Палка ЛФК%'
        OR eq.name ILIKE 'Резинка красная%'
        OR eq.name ILIKE 'Резинка зеленая%'
        OR eq.name ILIKE 'Резинка черная%'
        OR eq.name ILIKE 'Резинка желтая%'
        OR eq.name ILIKE 'Резинка фиолетовая%'
      );

-- -----------------------------------------------------------------------------
-- 2. Правила привязки. Каждое правило — отдельный INSERT ... SELECT:
--    так видно, какое ключевое слово даёт какой инвентарь.
--    quantity всегда 1: «2 шт» в правилах — это ДВЕ разные резинки (две строки),
--    именно так инвентарь показывается в тренировке («Резинка А, Резинка Б»).
-- -----------------------------------------------------------------------------

-- 2.1. «Гантели ...» -> Гантель
INSERT INTO exercise_equipment (exercise_id, equipment_id, quantity)
SELECT e.id, eq.id, 1
FROM exercises e
JOIN equipment eq ON eq.name = 'Гантель'
WHERE e.name ILIKE '%гантели%'
ON CONFLICT (exercise_id, equipment_id) DO NOTHING;

-- 2.2. «... скамья / скамью ...», «Зашагивания ...» -> Скамья
INSERT INTO exercise_equipment (exercise_id, equipment_id, quantity)
SELECT e.id, eq.id, 1
FROM exercises e
JOIN equipment eq ON eq.name = 'Скамья'
WHERE e.name ILIKE '%скам%'
   OR e.name ILIKE '%зашагивания%'
ON CONFLICT (exercise_id, equipment_id) DO NOTHING;

-- 2.3. «Палка ...», «ЛФК ...» -> Палка ЛФК
INSERT INTO exercise_equipment (exercise_id, equipment_id, quantity)
SELECT e.id, eq.id, 1
FROM exercises e
JOIN equipment eq ON eq.name ILIKE 'Палка ЛФК%'
WHERE e.name ILIKE '%палк%'
   OR e.name ILIKE '%лфк%'
ON CONFLICT (exercise_id, equipment_id) DO NOTHING;

-- 2.4. «Мячик ...», «Обнимашки ...» -> Мяч
INSERT INTO exercise_equipment (exercise_id, equipment_id, quantity)
SELECT e.id, eq.id, 1
FROM exercises e
JOIN equipment eq ON eq.name = 'Мяч'
WHERE e.name ILIKE '%мячик%'
   OR e.name ILIKE '%обнимашк%'
ON CONFLICT (exercise_id, equipment_id) DO NOTHING;

-- 2.5. «Pull-apart ...» -> красная 9.1 кг + зелёная 13.6 кг (две строки)
INSERT INTO exercise_equipment (exercise_id, equipment_id, quantity)
SELECT e.id, eq.id, 1
FROM exercises e
JOIN equipment eq ON eq.name ILIKE ANY (ARRAY['Резинка красная%', 'Резинка зеленая%'])
WHERE e.name ILIKE '%pull-apart%'
ON CONFLICT (exercise_id, equipment_id) DO NOTHING;

-- 2.6. «Жим резины ...» -> зелёная 13.6 кг
INSERT INTO exercise_equipment (exercise_id, equipment_id, quantity)
SELECT e.id, eq.id, 1
FROM exercises e
JOIN equipment eq ON eq.name ILIKE 'Резинка зеленая%'
WHERE e.name ILIKE '%жим резины%'
ON CONFLICT (exercise_id, equipment_id) DO NOTHING;

-- 2.7. «Монстер-уолк ...» -> фиолетовая петля
INSERT INTO exercise_equipment (exercise_id, equipment_id, quantity)
SELECT e.id, eq.id, 1
FROM exercises e
JOIN equipment eq ON eq.name ILIKE 'Резинка фиолетовая%'
WHERE e.name ILIKE '%монстер-уолк%'
ON CONFLICT (exercise_id, equipment_id) DO NOTHING;

-- 2.8. «Тяга резины сидя ...» -> чёрная 22.7 + чёрная 27.2 + жёлтая 4.6 (три строки)
--      Вариант «... в быстром темпе» исключён: у него своё правило (2.9),
--      иначе он получил бы ещё и три «медленных» резинки.
INSERT INTO exercise_equipment (exercise_id, equipment_id, quantity)
SELECT e.id, eq.id, 1
FROM exercises e
JOIN equipment eq ON eq.name ILIKE ANY (ARRAY[
        'Резинка черная 22.7%',
        'Резинка черная 27.2%',
        'Резинка желтая%'])
WHERE e.name ILIKE '%тяга резины сидя%'
  AND e.name NOT ILIKE '%в быстром темпе%'
ON CONFLICT (exercise_id, equipment_id) DO NOTHING;

-- 2.9. «Тяга резины сидя в быстром темпе» -> зелёная 13.6 кг
INSERT INTO exercise_equipment (exercise_id, equipment_id, quantity)
SELECT e.id, eq.id, 1
FROM exercises e
JOIN equipment eq ON eq.name ILIKE 'Резинка зеленая%'
WHERE e.name ILIKE '%тяга резины сидя в быстром темпе%'
ON CONFLICT (exercise_id, equipment_id) DO NOTHING;

-- 2.10. «Быстрая тяга одной рукой ...» -> чёрная 22.7 кг
INSERT INTO exercise_equipment (exercise_id, equipment_id, quantity)
SELECT e.id, eq.id, 1
FROM exercises e
JOIN equipment eq ON eq.name ILIKE 'Резинка черная 22.7%'
WHERE e.name ILIKE '%быстрая тяга одной рукой%'
ON CONFLICT (exercise_id, equipment_id) DO NOTHING;

-- -----------------------------------------------------------------------------
-- 3. Итог в лог миграции: сколько связей стало и сколько упражнений
--    осталось совсем без инвентаря (это нормально: ЛФК-растяжки, пресс и т.п.
--    действительно не требуют предметов).
-- -----------------------------------------------------------------------------
DO $$
DECLARE
    links      INTEGER;
    bare       INTEGER;
BEGIN
    SELECT count(*) INTO links FROM exercise_equipment;

    SELECT count(*) INTO bare
    FROM exercises e
    WHERE NOT EXISTS (
        SELECT 1 FROM exercise_equipment ee WHERE ee.exercise_id = e.id
    );

    RAISE NOTICE 'V14: связей упражнение-инвентарь = %, упражнений без инвентаря = %',
        links, bare;
END $$;
