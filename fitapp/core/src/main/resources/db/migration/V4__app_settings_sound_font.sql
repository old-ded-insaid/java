-- =============================================================================
-- V4: оформление и звук таймера отдыха.
--
-- sound_path — абсолютный путь к mp3/wav, выбранному пользователем.
--              NULL = стандартный системный сигнал (beep).
-- font_size  — базовый размер шрифта интерфейса (12..24, по умолчанию 13).
-- =============================================================================

ALTER TABLE app_settings
    ADD COLUMN sound_path TEXT;

ALTER TABLE app_settings
    ADD COLUMN font_size INT NOT NULL DEFAULT 13;

ALTER TABLE app_settings
    ADD CONSTRAINT app_settings_font_size_range CHECK (font_size BETWEEN 12 AND 24);

COMMENT ON COLUMN app_settings.sound_path IS 'Звук конца отдыха (mp3/wav); NULL = системный beep';
COMMENT ON COLUMN app_settings.font_size  IS 'Базовый размер шрифта интерфейса, px';
