package app.repository;

import app.model.ScheduleItem;

import java.util.List;

/** Доступ к таблице schedule_items (цикл планов). */
public interface ScheduleItemRepository extends CrudRepository<ScheduleItem, Long> {

    /** Шаги цикла по возрастанию position: 1 = A, 2 = B, ... */
    List<ScheduleItem> findByScheduleIdOrdered(long scheduleId);
}
