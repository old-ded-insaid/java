package app.repository;

import app.model.AppErrorLogEntry;

import java.util.List;

/** Доступ к таблице app_error_log. */
public interface AppErrorLogRepository extends CrudRepository<AppErrorLogEntry, Long> {

    List<AppErrorLogEntry> findRecent(int limit);

    List<AppErrorLogEntry> findByLevel(String level);
}
