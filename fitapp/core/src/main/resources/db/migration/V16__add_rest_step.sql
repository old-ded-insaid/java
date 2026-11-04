-- =============================================================================
-- V16: шаг стрелок таймера отдыха + снимок инвентаря в шаблоне.
--
-- 1. app_settings.rest_step_seconds — выбранный шаг стрелок (1/5/10/15/30 сек),
--    чтобы он не сбрасывался на 5 при каждом открытии тренировки.
--
-- 2. plan_exercises.equipment_snapshot — инвентарь, зафиксированный в шаблоне
--    на момент его создания/правки. Раньше тренировка брала инвентарь из
--    текущего справочника упражнения, поэтому правка справочника «задним числом»
--    меняла старые тренировки. Теперь снимок берётся из шаблона.
-- =============================================================================

-- -----------------------------------------------------------------------------
-- 1. Шаг таймера отдыха
-- -----------------------------------------------------------------------------
ALTER TABLE app_settings
    ADD COLUMN rest_step_seconds INT NOT NULL DEFAULT 5;

-- Шаг 0 или 1000 секунд — заведомо опечатка; 300 = 5 минут с запасом.
ALTER TABLE app_settings
    ADD CONSTRAINT app_settings_rest_step_range
        CHECK (rest_step_seconds BETWEEN 1 AND 300);

COMMENT ON COLUMN app_settings.rest_step_seconds IS
    'Шаг стрелок таймера отдыха, сек (1/5/10/15/30; по умолчанию 5)';

-- -----------------------------------------------------------------------------
-- 2. Снимок инвентаря в строке шаблона
-- -----------------------------------------------------------------------------
ALTER TABLE plan_exercises
    ADD COLUMN equipment_snapshot TEXT;

COMMENT ON COLUMN plan_exercises.equipment_snapshot IS
    'Инвентарь упражнения на момент правки шаблона; копируется в тренировку';

-- Заполняем существующие строки текущим инвентарём упражнений,
-- чтобы старые шаблоны не остались «без инвентаря» после обновления.
UPDATE plan_exercises pe
SET equipment_snapshot = (
    SELECT string_agg(
               CASE
                   WHEN ee.quantity > 1 THEN eq.name || ' (' || ee.quantity || ' шт)'
                   ELSE eq.name
               END,
               ', ' ORDER BY lower(eq.name))
    FROM exercise_equipment ee
    JOIN equipment eq ON eq.id = ee.equipment_id
    WHERE ee.exercise_id = pe.exercise_id
);
