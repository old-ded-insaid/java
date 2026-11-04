package app.service;

import app.model.AuditEntry;
import app.model.User;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import jakarta.persistence.EntityManager;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Objects;

/**
 * Журнал изменений (audit_log).
 *
 * Пишем «было» и «стало» в JSON. Если объект не изменился — запись не создаём,
 * иначе журнал превратится в мусор из пустых UPDATE.
 *
 * Метод принимает EntityManager: запись должна попасть в ту же транзакцию,
 * что и само изменение. Иначе получится «данные сохранились, а следов нет».
 */
public class AuditService {

    private final ObjectMapper mapper;

    public AuditService() {
        this.mapper = JsonMapper.builder()
                .addModule(new JavaTimeModule())
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
                // сущности без полей не должны валить журнал
                .disable(SerializationFeature.FAIL_ON_EMPTY_BEANS)
                .build();
    }

    /**
     * Записывает изменение.
     *
     * @param action INSERT | UPDATE | DELETE
     * @return true, если запись в журнал действительно добавлена
     */
    public boolean record(EntityManager em,
                          Long userId,
                          String entityType,
                          long entityId,
                          String action,
                          Object oldValue,
                          Object newValue) {

        String oldJson = toJson(oldValue);
        String newJson = toJson(newValue);

        if ("UPDATE".equals(action) && Objects.equals(oldJson, newJson)) {
            return false; // ничего не поменялось — не засоряем журнал
        }

        AuditEntry entry = AuditEntry.builder()
                .user(userId == null ? null : em.getReference(User.class, userId))
                .entityType(entityType)
                .entityId(entityId)
                .action(action)
                .oldValue(oldJson)
                .newValue(newJson)
                .createdAt(LocalDateTime.now(ZoneOffset.UTC))
                .build();

        em.persist(entry);
        return true;
    }

    /** JSON-снимок значения. Строки пишем как есть, null остаётся null. */
    public String toJson(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof String text) {
            return text;
        }
        try {
            return mapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            // журнал не должен ронять бизнес-операцию
            return String.valueOf(value);
        }
    }

    /** Совпадают ли два снимка (по JSON). */
    public boolean sameJson(Object first, Object second) {
        return Objects.equals(toJson(first), toJson(second));
    }
}
