-- =============================================================================
-- V13: принудительно проставляем целевой вес в шаблонах.
--
-- Проблема: колонка «Вес, кг» в шаблоне была пустой — target_weight_kg
-- оставался NULL, поэтому и в подходы новой тренировки вес не попадал.
--
-- Порядок UPDATE важен: более общие правила идут первыми, исключения — после,
-- чтобы они перекрыли общее значение.
-- =============================================================================

-- 1. Гантели 1, 2, 3, 5 — 8 кг
UPDATE plan_exercises pe
SET target_weight_kg = 8.00
FROM exercises e
WHERE pe.exercise_id = e.id
  AND e.name ~ '^Гантели\s+(1|2|3|5)\.';

-- 2. Гантели 6..11 — 6 кг
UPDATE plan_exercises pe
SET target_weight_kg = 6.00
FROM exercises e
WHERE pe.exercise_id = e.id
  AND e.name ~ '^Гантели\s+(6|7|8|9|10|11)\.';

-- 3. Исключение: разведения в наклоне — 4 кг
UPDATE plan_exercises pe
SET target_weight_kg = 4.00
FROM exercises e
WHERE pe.exercise_id = e.id
  AND e.name LIKE 'Гантели 4. Разведения%';

-- 4. Исключение: «Велосипед» — упражнение на пресс, вес не нужен.
--    В базе есть номера 4, 5, 10 и 12 — снимаем вес у всех.
UPDATE plan_exercises pe
SET target_weight_kg = NULL
FROM exercises e
WHERE pe.exercise_id = e.id
  AND e.name LIKE 'Гантели %'
  AND e.name ILIKE '%Велосипед%';

-- 5. «Взрывная тяга одной рукой с опорой на скамью» — 6 кг
UPDATE plan_exercises pe
SET target_weight_kg = 6.00
FROM exercises e
WHERE pe.exercise_id = e.id
  AND e.name ILIKE '%Взрывная тяга одной рукой с опорой на скамью%';

-- 6. ЛФК и растяжка — без веса
UPDATE plan_exercises pe
SET target_weight_kg = NULL
FROM exercises e
WHERE pe.exercise_id = e.id
  AND (e.name ILIKE '%ЛФК%' OR e.name ILIKE '%Растяжка%');

COMMENT ON COLUMN plan_exercises.target_weight_kg IS
    'Целевой вес шаблона, кг; копируется в sets.weight_kg при создании тренировки';
