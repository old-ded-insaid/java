-- =============================================================================
-- V8: исправления по QA-аудиту.
--
-- BUG-01  workouts.draft — «черновик, созданный кнопкой Новая тренировка».
--         Автоочистка трогает только черновики без ended_at.
-- BUG-07  sets.skipped — пропуск подхода это флаг, а не «0 секунд».
-- BUG-11  workout_exercises.equipment_snapshot — снимок инвентаря на момент
--         тренировки: смена инвентаря в зале не переписывает историю.
-- BUG-09  удаление шаблона убирает его из расписаний (CASCADE вместо RESTRICT).
-- BUG-27  измерения не могут быть отрицательными.
-- BUG-26  чистка заведомо неверных привязок инвентаря из V6.
-- =============================================================================

-- BUG-07: пропущенный подход
ALTER TABLE sets
    ADD COLUMN skipped BOOLEAN NOT NULL DEFAULT FALSE;

-- BUG-01: черновик тренировки
ALTER TABLE workouts
    ADD COLUMN draft BOOLEAN NOT NULL DEFAULT FALSE;

-- BUG-11: снимок инвентаря на момент тренировки
ALTER TABLE workout_exercises
    ADD COLUMN equipment_snapshot TEXT;

-- BUG-09: удалили шаблон — он исчезает из расписаний, а не блокирует удаление
ALTER TABLE schedule_items
    DROP CONSTRAINT IF EXISTS schedule_items_plan_id_fkey;

ALTER TABLE schedule_items
    ADD CONSTRAINT schedule_items_plan_id_fkey
        FOREIGN KEY (plan_id) REFERENCES workout_plans (id) ON DELETE CASCADE;

-- BUG-27: значение замера не может быть отрицательным
ALTER TABLE measurements
    ADD CONSTRAINT measurements_value_non_negative
        CHECK (value_num IS NULL OR value_num >= 0);

-- BUG-26: убираем инвентарь у упражнений, где он не нужен по смыслу.
-- «Гантели 10. Велосипед» и скручивания — упражнения на пресс с собственным весом,
-- но V6 привязала к ним гантели просто по слову в названии.
DELETE FROM exercise_equipment ee
USING exercises e
WHERE e.id = ee.exercise_id
  AND (e.name ILIKE '%велосипед%' OR e.name ILIKE '%скручиван%');

COMMENT ON COLUMN workouts.draft IS 'Черновик: удаляется автоочисткой, если пуст и не завершён';
COMMENT ON COLUMN sets.skipped IS 'Подход пропущен пользователем';
COMMENT ON COLUMN workout_exercises.equipment_snapshot IS 'Инвентарь на момент тренировки (историю не переписываем)';
