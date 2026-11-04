package app.service;

import app.db.Database;
import app.model.Exercise;
import app.model.WorkoutPlan;
import app.repository.Repositories;

import java.util.List;

/**
 * Справочники для экранов: упражнения и шаблоны тренировок.
 * Только чтение — ничего не меняет.
 */
public class CatalogService {

    private final Database database;

    public CatalogService(Database database) {
        this.database = database;
    }

    /** Все упражнения по алфавиту. */
    public List<Exercise> exercises() {
        return database.inTransaction(em -> new Repositories(em).exercises().findAllOrdered());
    }

    /** Поиск упражнений по названию и мышцам. */
    public List<Exercise> searchExercises(String fragment) {
        return database.inTransaction(em -> new Repositories(em).exercises().search(fragment));
    }

    /** Активные шаблоны (A/B/C/D) — их можно ставить в расписание. */
    public List<WorkoutPlan> activePlans(long userId) {
        return database.inTransaction(em -> new Repositories(em).plans().findActiveByUserId(userId));
    }

    /** Все шаблоны, включая скрытые (для истории). */
    public List<WorkoutPlan> allPlans(long userId) {
        return database.inTransaction(em -> new Repositories(em).plans().findByUserId(userId));
    }
}
