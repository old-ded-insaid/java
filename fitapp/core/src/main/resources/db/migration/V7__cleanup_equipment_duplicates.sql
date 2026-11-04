-- =============================================================================
-- V7: чистка дублей инвентаря.
--
-- V6 привязала «Гантель 8 кг» и «Гантель 6 кг» ко всем упражнениям с гантелями,
-- а резинки — по четыре варианта. Для реальной тренировки нужен ОДИН предмет.
--
-- Правило выбора:
--   1) если в названии есть «резин» и среди связей есть «Резинка черная» —
--      оставляем именно её;
--   2) иначе оставляем связь с наименьшим equipment_id (детерминированно:
--      для гантелей это «Гантель 8 кг»).
--
-- Остальные строки удаляются. quantity у оставшейся связи не меняется.
-- =============================================================================

WITH ranked AS (
    SELECT ee.exercise_id,
           ee.equipment_id,
           row_number() OVER (
               PARTITION BY ee.exercise_id
               ORDER BY
                   CASE
                       WHEN e.name ILIKE '%резин%' AND eq.name = 'Резинка черная' THEN 0
                       ELSE 1
                   END,
                   ee.equipment_id
           ) AS rn
    FROM exercise_equipment ee
    JOIN exercises e  ON e.id = ee.exercise_id
    JOIN equipment eq ON eq.id = ee.equipment_id
)
DELETE FROM exercise_equipment ee
USING ranked r
WHERE ee.exercise_id = r.exercise_id
  AND ee.equipment_id = r.equipment_id
  AND r.rn > 1;

COMMENT ON TABLE exercise_equipment IS
    'Инвентарь упражнения: один предмет на упражнение (V7), при желании правится в тренировке';
