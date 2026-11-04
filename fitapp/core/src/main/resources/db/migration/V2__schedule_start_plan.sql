-- =============================================================================
-- V2: с какого шаблона начинается ротация расписания.
--
-- NULL = начинаем с первого шаблона в списке правила (position = 1).
-- ON DELETE SET NULL: если шаблон удалили, правило остаётся рабочим
-- и просто начинает цикл с первого доступного шаблона.
-- =============================================================================

ALTER TABLE schedule
    ADD COLUMN start_plan_id BIGINT;

ALTER TABLE schedule
    ADD CONSTRAINT schedule_start_plan_fk
        FOREIGN KEY (start_plan_id) REFERENCES workout_plans (id) ON DELETE SET NULL;

CREATE INDEX idx_schedule_start_plan ON schedule (start_plan_id);

COMMENT ON COLUMN schedule.start_plan_id IS
    'С какого шаблона начинается ротация; NULL = с первого по order (position = 1)';
