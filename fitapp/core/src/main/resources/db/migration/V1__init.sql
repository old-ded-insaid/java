-- =============================================================================
-- fitapp — начальная схема (этап 1)
-- СУБД: PostgreSQL 18, кодировка UTF8
-- Все TIMESTAMP хранятся в UTC (см. hibernate.jdbc.time_zone=UTC)
-- Порядок таблиц = порядок зависимостей по FOREIGN KEY
-- =============================================================================

-- -----------------------------------------------------------------------------
-- 1. users — пользователи. password_hash IS NULL => вход без пароля
-- -----------------------------------------------------------------------------
CREATE TABLE users (
    id            BIGSERIAL   PRIMARY KEY,
    username      TEXT        NOT NULL UNIQUE,
    password_hash TEXT,
    created_at    TIMESTAMP   NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT users_username_not_blank CHECK (length(btrim(username)) > 0)
);

-- -----------------------------------------------------------------------------
-- 2. app_settings — оформление, один набор настроек на пользователя
-- -----------------------------------------------------------------------------
CREATE TABLE app_settings (
    id           BIGSERIAL PRIMARY KEY,
    user_id      BIGINT    NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    theme_name   TEXT      NOT NULL DEFAULT 'gray_blue',
    accent_color TEXT      NOT NULL DEFAULT '#4F8EF7',
    CONSTRAINT app_settings_user_unique UNIQUE (user_id),
    CONSTRAINT app_settings_accent_color_format CHECK (accent_color ~ '^#[0-9A-Fa-f]{6}$')
);

-- -----------------------------------------------------------------------------
-- 3. equipment — инвентарь: гантель 6 кг, длинный меч 1.5 кг, резинка чёрная...
-- -----------------------------------------------------------------------------
CREATE TABLE equipment (
    id           BIGSERIAL PRIMARY KEY,
    name         TEXT      NOT NULL UNIQUE,
    weight_grams INT,
    description  TEXT,
    CONSTRAINT equipment_weight_non_negative CHECK (weight_grams IS NULL OR weight_grams >= 0)
);

-- -----------------------------------------------------------------------------
-- 4. exercises — справочник упражнений
-- setup=ИП, movement=Движение, muscles=Мышцы, avoid=Чего избегать
-- -----------------------------------------------------------------------------
CREATE TABLE exercises (
    id         BIGSERIAL PRIMARY KEY,
    name       TEXT      NOT NULL UNIQUE,
    setup      TEXT,
    movement   TEXT,
    muscles    TEXT,
    avoid      TEXT,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

-- -----------------------------------------------------------------------------
-- 5. exercise_equipment — M:N упражнение <-> инвентарь с количеством
-- -----------------------------------------------------------------------------
CREATE TABLE exercise_equipment (
    exercise_id  BIGINT NOT NULL REFERENCES exercises (id) ON DELETE CASCADE,
    equipment_id BIGINT NOT NULL REFERENCES equipment (id) ON DELETE RESTRICT,
    quantity     INT    NOT NULL DEFAULT 1,
    PRIMARY KEY (exercise_id, equipment_id),
    CONSTRAINT exercise_equipment_quantity_positive CHECK (quantity > 0)
);
CREATE INDEX idx_exercise_equipment_equipment ON exercise_equipment (equipment_id);

-- -----------------------------------------------------------------------------
-- 6. workout_plans — шаблоны тренировок A/B/C/D
-- -----------------------------------------------------------------------------
CREATE TABLE workout_plans (
    id          BIGSERIAL PRIMARY KEY,
    user_id     BIGINT    NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    name        TEXT      NOT NULL,
    description TEXT,
    active      BOOLEAN   NOT NULL DEFAULT TRUE,
    created_at  TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT workout_plans_user_name_unique UNIQUE (user_id, name)
);
CREATE INDEX idx_workout_plans_user ON workout_plans (user_id);

-- -----------------------------------------------------------------------------
-- 7. plan_exercises — состав плана (упражнения по порядку)
-- -----------------------------------------------------------------------------
CREATE TABLE plan_exercises (
    id               BIGSERIAL    PRIMARY KEY,
    plan_id          BIGINT       NOT NULL REFERENCES workout_plans (id) ON DELETE CASCADE,
    exercise_id      BIGINT       NOT NULL REFERENCES exercises (id)     ON DELETE RESTRICT,
    order_index      INT          NOT NULL,
    target_sets      INT,
    target_reps      INT,
    target_weight_kg NUMERIC(6,2),
    target_time_sec  INT,
    CONSTRAINT plan_exercises_order_unique     UNIQUE (plan_id, order_index),
    CONSTRAINT plan_exercises_order_positive   CHECK (order_index > 0),
    CONSTRAINT plan_exercises_sets_positive    CHECK (target_sets IS NULL OR target_sets > 0),
    CONSTRAINT plan_exercises_reps_positive    CHECK (target_reps IS NULL OR target_reps > 0),
    CONSTRAINT plan_exercises_weight_non_neg   CHECK (target_weight_kg IS NULL OR target_weight_kg >= 0),
    CONSTRAINT plan_exercises_time_positive    CHECK (target_time_sec IS NULL OR target_time_sec > 0)
);
CREATE INDEX idx_plan_exercises_exercise ON plan_exercises (exercise_id);

-- -----------------------------------------------------------------------------
-- 8. schedule — расписание: цикл тренировок с шагом в днях
-- -----------------------------------------------------------------------------
CREATE TABLE schedule (
    id            BIGSERIAL PRIMARY KEY,
    user_id       BIGINT    NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    name          TEXT      NOT NULL,
    start_date    DATE      NOT NULL,
    interval_days INT       NOT NULL,
    active        BOOLEAN   NOT NULL DEFAULT TRUE,
    CONSTRAINT schedule_interval_positive CHECK (interval_days > 0)
);
CREATE INDEX idx_schedule_user ON schedule (user_id);

-- -----------------------------------------------------------------------------
-- 9. schedule_items — очередь планов внутри цикла (1=A, 2=B, 3=C, 4=D)
-- -----------------------------------------------------------------------------
CREATE TABLE schedule_items (
    id          BIGSERIAL PRIMARY KEY,
    schedule_id BIGINT    NOT NULL REFERENCES schedule (id)      ON DELETE CASCADE,
    position    INT       NOT NULL,
    plan_id     BIGINT    NOT NULL REFERENCES workout_plans (id) ON DELETE RESTRICT,
    CONSTRAINT schedule_items_position_unique UNIQUE (schedule_id, position),
    CONSTRAINT schedule_items_position_positive CHECK (position > 0)
);
CREATE INDEX idx_schedule_items_plan ON schedule_items (plan_id);

-- -----------------------------------------------------------------------------
-- 10. workouts — фактические тренировки (история)
-- -----------------------------------------------------------------------------
CREATE TABLE workouts (
    id             BIGSERIAL PRIMARY KEY,
    user_id        BIGINT    NOT NULL REFERENCES users (id)         ON DELETE CASCADE,
    plan_id        BIGINT             REFERENCES workout_plans (id) ON DELETE SET NULL,
    workout_date   DATE      NOT NULL,
    name           TEXT,
    started_at     TIMESTAMP,
    ended_at       TIMESTAMP,
    duration_sec   INT,
    note           TEXT,
    avg_pulse      INT,
    total_calories INT,
    created_at     TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at     TIMESTAMP,
    CONSTRAINT workouts_period_valid       CHECK (ended_at IS NULL OR started_at IS NULL OR ended_at >= started_at),
    CONSTRAINT workouts_duration_non_neg   CHECK (duration_sec IS NULL OR duration_sec >= 0),
    CONSTRAINT workouts_avg_pulse_positive CHECK (avg_pulse IS NULL OR avg_pulse > 0),
    CONSTRAINT workouts_calories_non_neg   CHECK (total_calories IS NULL OR total_calories >= 0)
);
CREATE INDEX idx_workouts_user_date ON workouts (user_id, workout_date DESC);
CREATE INDEX idx_workouts_plan ON workouts (plan_id);

-- -----------------------------------------------------------------------------
-- 11. workout_exercises — упражнения конкретной тренировки
-- -----------------------------------------------------------------------------
CREATE TABLE workout_exercises (
    id          BIGSERIAL PRIMARY KEY,
    workout_id  BIGINT    NOT NULL REFERENCES workouts (id)  ON DELETE CASCADE,
    exercise_id BIGINT    NOT NULL REFERENCES exercises (id) ON DELETE RESTRICT,
    order_index INT       NOT NULL,
    CONSTRAINT workout_exercises_order_unique   UNIQUE (workout_id, order_index),
    CONSTRAINT workout_exercises_order_positive CHECK (order_index > 0)
);
CREATE INDEX idx_workout_exercises_exercise ON workout_exercises (exercise_id);

-- -----------------------------------------------------------------------------
-- 12. sets — подходы внутри упражнения тренировки
-- -----------------------------------------------------------------------------
CREATE TABLE sets (
    id                  BIGSERIAL    PRIMARY KEY,
    workout_exercise_id BIGINT       NOT NULL REFERENCES workout_exercises (id) ON DELETE CASCADE,
    set_number          INT          NOT NULL,
    reps                INT,
    weight_kg           NUMERIC(6,2),
    time_marker_sec     INT,
    duration_sec        INT,
    pulse_bpm           INT,
    calories            INT,
    note                TEXT,
    CONSTRAINT sets_number_unique    UNIQUE (workout_exercise_id, set_number),
    CONSTRAINT sets_number_positive  CHECK (set_number > 0),
    CONSTRAINT sets_reps_positive    CHECK (reps IS NULL OR reps > 0),
    CONSTRAINT sets_weight_non_neg   CHECK (weight_kg IS NULL OR weight_kg >= 0),
    CONSTRAINT sets_marker_non_neg   CHECK (time_marker_sec IS NULL OR time_marker_sec >= 0),
    CONSTRAINT sets_duration_non_neg CHECK (duration_sec IS NULL OR duration_sec >= 0),
    CONSTRAINT sets_pulse_positive   CHECK (pulse_bpm IS NULL OR pulse_bpm > 0),
    CONSTRAINT sets_calories_non_neg CHECK (calories IS NULL OR calories >= 0)
);

-- -----------------------------------------------------------------------------
-- 13. media — метаданные файлов, сами файлы на диске (file_path)
-- -----------------------------------------------------------------------------
CREATE TABLE media (
    id                  BIGSERIAL PRIMARY KEY,
    workout_id          BIGINT           REFERENCES workouts (id)          ON DELETE CASCADE,
    workout_exercise_id BIGINT           REFERENCES workout_exercises (id) ON DELETE CASCADE,
    purpose             TEXT      NOT NULL,
    file_path           TEXT      NOT NULL,
    uploaded_at         TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT media_purpose_allowed CHECK (purpose IN ('pulse_graph','mobility_photo','form_check','other')),
    CONSTRAINT media_owner_present CHECK (workout_id IS NOT NULL OR workout_exercise_id IS NOT NULL)
);
CREATE INDEX idx_media_workout ON media (workout_id);
CREATE INDEX idx_media_workout_exercise ON media (workout_exercise_id);

-- -----------------------------------------------------------------------------
-- 14. comments — заметки/комментарии/фидбек
-- -----------------------------------------------------------------------------
CREATE TABLE comments (
    id                  BIGSERIAL PRIMARY KEY,
    user_id             BIGINT    NOT NULL REFERENCES users (id)             ON DELETE CASCADE,
    workout_id          BIGINT             REFERENCES workouts (id)          ON DELETE CASCADE,
    workout_exercise_id BIGINT             REFERENCES workout_exercises (id) ON DELETE CASCADE,
    comment_type        TEXT      NOT NULL,
    text                TEXT      NOT NULL,
    created_at          TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT comments_type_allowed CHECK (comment_type IN ('note','comment','feedback')),
    CONSTRAINT comments_text_not_blank CHECK (length(btrim(text)) > 0)
);
CREATE INDEX idx_comments_workout ON comments (workout_id);
CREATE INDEX idx_comments_workout_exercise ON comments (workout_exercise_id);
CREATE INDEX idx_comments_user ON comments (user_id);

-- -----------------------------------------------------------------------------
-- 15. measurements — измерения: подвижность, пульс покоя, вес тела
-- -----------------------------------------------------------------------------
CREATE TABLE measurements (
    id          BIGSERIAL    PRIMARY KEY,
    user_id     BIGINT       NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    measured_at TIMESTAMP    NOT NULL,
    kind        TEXT         NOT NULL,
    name        TEXT         NOT NULL,
    value_num   NUMERIC(8,2),
    value_unit  TEXT,
    note        TEXT,
    media_id    BIGINT                REFERENCES media (id) ON DELETE SET NULL,
    CONSTRAINT measurements_kind_allowed CHECK (kind IN ('mobility','pulse_rest','body_weight','other'))
);
CREATE INDEX idx_measurements_user_kind_time ON measurements (user_id, kind, measured_at DESC);
CREATE INDEX idx_measurements_media ON measurements (media_id);

-- -----------------------------------------------------------------------------
-- 16. audit_log — журнал изменений
-- -----------------------------------------------------------------------------
CREATE TABLE audit_log (
    id          BIGSERIAL PRIMARY KEY,
    user_id     BIGINT             REFERENCES users (id) ON DELETE SET NULL,
    entity_type TEXT      NOT NULL,
    entity_id   BIGINT    NOT NULL,
    action      TEXT      NOT NULL,
    old_value   TEXT,
    new_value   TEXT,
    created_at  TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX idx_audit_log_entity ON audit_log (entity_type, entity_id);
CREATE INDEX idx_audit_log_created_at ON audit_log (created_at DESC);

-- -----------------------------------------------------------------------------
-- 17. app_error_log — технические ошибки приложения
-- -----------------------------------------------------------------------------
CREATE TABLE app_error_log (
    id         BIGSERIAL PRIMARY KEY,
    level      TEXT      NOT NULL,
    message    TEXT      NOT NULL,
    stack      TEXT,
    context    TEXT,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT app_error_log_level_allowed CHECK (level IN ('TRACE','DEBUG','INFO','WARN','ERROR','FATAL'))
);
CREATE INDEX idx_app_error_log_created_at ON app_error_log (created_at DESC);

-- =============================================================================
-- Комментарии к таблицам (видны в pgAdmin / DBeaver)
-- =============================================================================
COMMENT ON TABLE users              IS 'Пользователи (модель поддерживает нескольких, приложение — один)';
COMMENT ON TABLE app_settings       IS 'Оформление: тема и акцентный цвет, одна строка на пользователя';
COMMENT ON TABLE equipment          IS 'Инвентарь: гантели, мечи, резинки, петли, скамья';
COMMENT ON TABLE exercises          IS 'Справочник упражнений: ИП, движение, мышцы, чего избегать';
COMMENT ON TABLE exercise_equipment IS 'M:N упражнение-инвентарь с количеством (3 резинки = quantity 3)';
COMMENT ON TABLE workout_plans      IS 'Шаблоны тренировок A/B/C/D';
COMMENT ON TABLE plan_exercises     IS 'Состав шаблона: упражнения по порядку с целевыми параметрами';
COMMENT ON TABLE schedule           IS 'Расписание: старт + шаг в днях + цикл планов';
COMMENT ON TABLE schedule_items     IS 'Очередь планов внутри цикла расписания';
COMMENT ON TABLE workouts           IS 'История фактических тренировок';
COMMENT ON TABLE workout_exercises  IS 'Упражнения конкретной тренировки';
COMMENT ON TABLE sets               IS 'Подходы: повторы, вес, пульс, калории, метка времени';
COMMENT ON TABLE media              IS 'Метаданные файлов; сами файлы на диске (file_path)';
COMMENT ON TABLE comments           IS 'Заметки, комментарии, фидбек';
COMMENT ON TABLE measurements       IS 'Измерения: подвижность, пульс покоя, вес тела';
COMMENT ON TABLE audit_log          IS 'Журнал изменений сущностей';
COMMENT ON TABLE app_error_log      IS 'Технические ошибки приложения';
