-- =============================================================================
-- V11: один инвентарь «Гантель» вместо «Гантель 8 кг / 6 кг / 4 кг».
--
-- Смысл: вес гантели — это параметр ПОДХОДА (sets.weight_kg), а не инвентаря.
-- После миграции в справочнике остаётся одна позиция «Гантель», а конкретный
-- вес пользователь вводит в таблице тренировки (или он приходит из шаблона).
--
-- Порядок важен:
--   1. убираем связи, которые стали бы дублями (PK exercise_id + equipment_id);
--   2. переводим остальные связи на новую запись;
--   3. только потом удаляем старые записи справочника (FK ON DELETE RESTRICT).
-- =============================================================================

-- 1. Вес жёлтой резинки (4.6 кг) — на случай, если V10 ещё не применялась
UPDATE equipment
SET weight_grams = 4600
WHERE name = 'Резинка желтая';

-- 2. Единый инвентарь «Гантель» (вес тут не хранится)
INSERT INTO equipment (name)
VALUES ('Гантель')
ON CONFLICT (name) DO NOTHING;

-- 3. Снимаем старые гантельные связи у упражнений, где уже есть «Гантель»
--    (иначе UPDATE упрётся в первичный ключ)
DELETE FROM exercise_equipment ee
WHERE ee.equipment_id IN (SELECT id FROM equipment WHERE name LIKE 'Гантель %')
  AND EXISTS (
        SELECT 1
        FROM exercise_equipment already
        JOIN equipment eq ON eq.id = already.equipment_id
        WHERE already.exercise_id = ee.exercise_id
          AND eq.name = 'Гантель'
      );

-- 4. Если у упражнения несколько старых гантелей — оставляем одну (минимальный id)
DELETE FROM exercise_equipment ee
USING (
        SELECT exercise_id, min(equipment_id) AS keep_id
        FROM exercise_equipment
        WHERE equipment_id IN (SELECT id FROM equipment WHERE name LIKE 'Гантель %')
        GROUP BY exercise_id
     ) duplicates
WHERE ee.exercise_id = duplicates.exercise_id
  AND ee.equipment_id IN (SELECT id FROM equipment WHERE name LIKE 'Гантель %')
  AND ee.equipment_id <> duplicates.keep_id;

-- 5. Переводим оставшиеся связи на «Гантель» (quantity сохраняется)
UPDATE exercise_equipment
SET equipment_id = (SELECT id FROM equipment WHERE name = 'Гантель')
WHERE equipment_id IN (SELECT id FROM equipment WHERE name LIKE 'Гантель %');

-- 6. Удаляем старые записи справочника: «Гантель 8 кг», «Гантель 6 кг», «Гантель 4 кг»
DELETE FROM equipment
WHERE name LIKE 'Гантель %';

-- 7. История тренировок хранит снимок инвентаря текстом — приводим его к новому имени.
--    Вес при этом не теряется: он лежит в sets.weight_kg.
UPDATE workout_exercises
SET equipment_snapshot = btrim(
        replace(
            replace(
                replace(equipment_snapshot, 'Гантель 8 кг', 'Гантель'),
                'Гантель 6 кг', 'Гантель'),
            'Гантель 4 кг', 'Гантель'))
WHERE equipment_snapshot LIKE '%Гантель %';

-- 8. Страховка: после миграции не должно остаться связей со старыми названиями
DO $$
BEGIN
    IF EXISTS (
        SELECT 1
        FROM exercise_equipment ee
        JOIN equipment eq ON eq.id = ee.equipment_id
        WHERE eq.name LIKE 'Гантель %'
    ) THEN
        RAISE EXCEPTION 'V11: остались связи со старыми названиями гантелей';
    END IF;

    IF (SELECT count(*) FROM equipment WHERE name = 'Гантель') <> 1 THEN
        RAISE EXCEPTION 'V11: запись «Гантель» должна быть ровно одна';
    END IF;
END $$;
