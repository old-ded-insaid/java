-- =============================================================================
-- V3: картинки у замеров.
--
-- Раньше media мог принадлежать только тренировке или упражнению тренировки
-- (CHECK media_owner_present). Теперь владельцем может быть и замер:
-- скриншот пульса с часов, фото наклона вперёд и т.п.
--
-- Связь двусторонняя:
--   measurements.media_id -> media.id      (ON DELETE SET NULL, уже есть)
--   media.measurement_id  -> measurements  (ON DELETE CASCADE, новая)
-- =============================================================================

ALTER TABLE media
    ADD COLUMN measurement_id BIGINT;

ALTER TABLE media
    ADD CONSTRAINT media_measurement_fk
        FOREIGN KEY (measurement_id) REFERENCES measurements (id) ON DELETE CASCADE;

CREATE INDEX idx_media_measurement ON media (measurement_id);

-- расширяем проверку «у файла есть владелец»
ALTER TABLE media DROP CONSTRAINT media_owner_present;

ALTER TABLE media
    ADD CONSTRAINT media_owner_present CHECK (
        workout_id IS NOT NULL
        OR workout_exercise_id IS NOT NULL
        OR measurement_id IS NOT NULL);

COMMENT ON COLUMN media.measurement_id IS 'Замер, к которому приложена картинка';
