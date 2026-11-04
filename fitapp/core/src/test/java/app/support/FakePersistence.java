package app.support;

import app.db.Database;
import app.model.Equipment;
import app.model.Exercise;
import app.model.ExerciseEquipment;
import app.model.ExerciseEquipmentId;
import app.model.Measurement;
import app.model.MediaItem;
import app.model.PlanExercise;
import app.model.Schedule;
import app.model.ScheduleItem;
import app.model.User;
import app.model.Workout;
import app.model.WorkoutExercise;
import app.model.WorkoutPlan;
import app.model.WorkoutSet;
import jakarta.persistence.EntityManager;
import jakarta.persistence.TypedQuery;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import java.util.function.Function;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Мини-«persistence context» на Mockito для unit-тестов сервисов.
 *
 * Сервисы создают репозитории сами ({@code new Repositories(em)}), поэтому
 * подменить репозиторий нельзя — зато можно подменить EntityManager.
 * Этот класс отвечает на четыре группы вызовов, которых достаточно сервисам:
 *
 * <ul>
 *   <li>{@code em.find(...)} — из зарегистрированных сущностей;</li>
 *   <li>{@code em.merge/persist/remove/flush} — собирает статистику для assert'ов;</li>
 *   <li>{@code em.createQuery(...)} — возвращает заглушку TypedQuery, у которой
 *       getResultList()/getSingleResult() берут данные из зарегистрированных правил;</li>
 *   <li>{@code Database.inTransaction/runInTransaction} — сразу выполняет лямбду
 *       с этим EntityManager.</li>
 * </ul>
 *
 * Это не эмуляция SQL: правила для запросов задаются в каждом тесте явно,
 * поэтому тест видит ровно те строки, которые ему нужны.
 */
public final class FakePersistence {

    private final EntityManager em = mock(EntityManager.class);

    /** Class -> (id -> entity) для em.find. */
    private final Map<Class<?>, Map<Object, Object>> byId = new LinkedHashMap<>();

    private final List<Object> merged = new ArrayList<>();
    private final List<Object> persisted = new ArrayList<>();
    private final List<Object> removed = new ArrayList<>();

    private final List<QueryRule> rules = new ArrayList<>();
    private final AtomicLong generatedIds = new AtomicLong(1_000);

    public FakePersistence() {
        when(em.find(any(Class.class), any())).thenAnswer(invocation -> {
            Class<?> type = invocation.getArgument(0);
            Object id = invocation.getArgument(1);
            Map<Object, Object> rows = byId.get(type);
            return rows == null ? null : rows.get(id);
        });

        // В реальном EntityManager сущности из find() управляемые.
        // Для delete() этого достаточно: репозиторий вызовет em.remove() без merge.
        when(em.contains(any())).thenReturn(true);

        when(em.merge(any())).thenAnswer(invocation -> {
            Object entity = invocation.getArgument(0);
            assignGeneratedId(entity);
            merged.add(entity);
            return entity;
        });

        doAnswer(invocation -> {
            Object entity = invocation.getArgument(0);
            assignGeneratedId(entity);
            persisted.add(entity);
            return null;
        }).when(em).persist(any());

        doAnswer(invocation -> {
            removed.add(invocation.getArgument(0));
            return null;
        }).when(em).remove(any());

        doNothing().when(em).flush();

        when(em.createQuery(anyString(), any(Class.class))).thenAnswer(invocation -> {
            String jpql = invocation.getArgument(0);
            Class<?> type = invocation.getArgument(1);
            return newQuery(jpql, type);
        });
    }

    // --------------------------------------------------------------- фабрика

    /** Database, которая «выполняет» транзакции на этом EntityManager. */
    public static Database mockDatabase(EntityManager em) {
        Database database = mock(Database.class);

        when(database.inTransaction(any())).thenAnswer(invocation -> {
            Function<EntityManager, ?> work = invocation.getArgument(0);
            return work.apply(em);
        });

        doAnswer(invocation -> {
            Consumer<EntityManager> work = invocation.getArgument(0);
            work.accept(em);
            return null;
        }).when(database).runInTransaction(any());

        return database;
    }

    // ------------------------------------------------------------ регистрация

    /** Регистрирует сущность для em.find(entityClass, id). */
    public <T> T register(Class<T> entityClass, Object id, T entity) {
        byId.computeIfAbsent(entityClass, key -> new LinkedHashMap<>()).put(id, entity);
        return entity;
    }

    /**
     * Правило для {@code em.createQuery(...).getResultList()}.
     *
     * @param type     тип, переданный вторым аргументом createQuery
     * @param fragment кусок JPQL, по которому узнаём запрос
     */
    public <T> void onQuery(Class<T> type,
                            String fragment,
                            Function<Map<String, Object>, List<T>> answer) {
        // Повторная регистрация того же запроса заменяет правило:
        // тест может переопределить ответ, заданный в @BeforeEach.
        rules.removeIf(rule -> rule.type.equals(type) && rule.fragment.equals(fragment));
        rules.add(new QueryRule(type, fragment, toRawList(answer), null));
    }

    /**
     * Правило для {@code em.createQuery(...).getSingleResult()}
     * (например, {@code select max(s.setNumber) ...}).
     */
    public <T> void onSingle(Class<T> type,
                             String fragment,
                             Function<Map<String, Object>, Object> answer) {
        rules.removeIf(rule -> rule.type.equals(type) && rule.fragment.equals(fragment));
        rules.add(new QueryRule(type, fragment, null, answer));
    }

    // -------------------------------------------------------------- доступ

    public EntityManager em() {
        return em;
    }

    public List<Object> merged() {
        return List.copyOf(merged);
    }

    public <T> List<T> mergedOf(Class<T> type) {
        return merged.stream().filter(type::isInstance).map(type::cast).toList();
    }

    public List<Object> persisted() {
        return List.copyOf(persisted);
    }

    public <T> List<T> persistedOf(Class<T> type) {
        return persisted.stream().filter(type::isInstance).map(type::cast).toList();
    }

    public List<Object> removed() {
        return List.copyOf(removed);
    }

    public boolean wasRemoved(Object entity) {
        return removed.stream().anyMatch(item -> item == entity);
    }

    // ------------------------------------------------------------ внутреннее

    @SuppressWarnings({"rawtypes", "unchecked"})
    private TypedQuery newQuery(String jpql, Class<?> type) {
        Map<String, Object> parameters = new HashMap<>();

        TypedQuery query = mock(TypedQuery.class);

        when(query.setParameter(anyString(), any())).thenAnswer(invocation -> {
            parameters.put(invocation.getArgument(0), invocation.getArgument(1));
            return query;
        });
        when(query.setMaxResults(anyInt())).thenReturn(query);

        when(query.getResultList()).thenAnswer(invocation ->
                resolveList(jpql, type, parameters));
        when(query.getSingleResult()).thenAnswer(invocation ->
                resolveSingle(jpql, type, parameters));

        return query;
    }

    private List<?> resolveList(String jpql, Class<?> type, Map<String, Object> parameters) {
        for (QueryRule rule : rules) {
            if (rule.type.equals(type) && jpql.contains(rule.fragment) && rule.listAnswer != null) {
                return rule.listAnswer.apply(parameters);
            }
        }
        return List.of();
    }

    private Object resolveSingle(String jpql, Class<?> type, Map<String, Object> parameters) {
        for (QueryRule rule : rules) {
            if (rule.type.equals(type) && jpql.contains(rule.fragment) && rule.singleAnswer != null) {
                return rule.singleAnswer.apply(parameters);
            }
        }
        return null;
    }

    private void assignGeneratedId(Object entity) {
        if (entity instanceof Workout value) {
            if (value.getId() == null) {
                value.setId(generatedIds.getAndIncrement());
            }
        } else if (entity instanceof WorkoutExercise value) {
            if (value.getId() == null) {
                value.setId(generatedIds.getAndIncrement());
            }
        } else if (entity instanceof WorkoutSet value) {
            if (value.getId() == null) {
                value.setId(generatedIds.getAndIncrement());
            }
        } else if (entity instanceof WorkoutPlan value) {
            if (value.getId() == null) {
                value.setId(generatedIds.getAndIncrement());
            }
        } else if (entity instanceof PlanExercise value) {
            if (value.getId() == null) {
                value.setId(generatedIds.getAndIncrement());
            }
        } else if (entity instanceof Exercise value) {
            if (value.getId() == null) {
                value.setId(generatedIds.getAndIncrement());
            }
        } else if (entity instanceof Measurement value) {
            if (value.getId() == null) {
                value.setId(generatedIds.getAndIncrement());
            }
        } else if (entity instanceof MediaItem value) {
            if (value.getId() == null) {
                value.setId(generatedIds.getAndIncrement());
            }
        } else if (entity instanceof User value) {
            if (value.getId() == null) {
                value.setId(generatedIds.getAndIncrement());
            }
        } else if (entity instanceof Equipment value) {
            if (value.getId() == null) {
                value.setId(generatedIds.getAndIncrement());
            }
        } else if (entity instanceof Schedule value) {
            if (value.getId() == null) {
                value.setId(generatedIds.getAndIncrement());
            }
        } else if (entity instanceof ScheduleItem value) {
            if (value.getId() == null) {
                value.setId(generatedIds.getAndIncrement());
            }
        } else if (entity instanceof ExerciseEquipment value) {
            if (value.getId() == null) {
                Long exerciseId = value.getExercise() == null ? null : value.getExercise().getId();
                Long equipmentId = value.getEquipment() == null ? null : value.getEquipment().getId();
                value.setId(new ExerciseEquipmentId(exerciseId, equipmentId));
            }
        }
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static Function<Map<String, Object>, List<?>> toRawList(
            Function<Map<String, Object>, ?> answer) {
        return (Function) answer;
    }

    private record QueryRule(Class<?> type,
                             String fragment,
                             Function<Map<String, Object>, List<?>> listAnswer,
                             Function<Map<String, Object>, Object> singleAnswer) {
    }
}
