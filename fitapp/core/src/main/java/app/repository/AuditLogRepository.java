package app.repository;

import app.model.AuditEntry;

import java.util.List;

/** Доступ к таблице audit_log. */
public interface AuditLogRepository extends CrudRepository<AuditEntry, Long> {

    /** История изменений одной записи: entityType = "workouts", entityId = 15. */
    List<AuditEntry> findByEntity(String entityType, long entityId);

    List<AuditEntry> findByUserId(long userId);

    /** Последние записи журнала (для экрана «История изменений»). */
    List<AuditEntry> findRecent(int limit);
}
