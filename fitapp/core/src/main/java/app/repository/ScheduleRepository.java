package app.repository;

import app.model.Schedule;

import java.util.List;

/** Доступ к таблице schedule (расписания). */
public interface ScheduleRepository extends CrudRepository<Schedule, Long> {

    List<Schedule> findByUserId(long userId);

    List<Schedule> findActiveByUserId(long userId);

    /** Мягкое удаление: active = false. */
    boolean archive(long scheduleId);
}
