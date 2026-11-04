package app.repository;

import app.model.Measurement;

import java.time.LocalDateTime;
import java.util.List;

/** Доступ к таблице measurements. */
public interface MeasurementRepository extends CrudRepository<Measurement, Long> {

    List<Measurement> findByUserId(long userId);

    /** То же, но картинка (media) уже подгружена — можно читать file_path вне транзакции. */
    List<Measurement> findByUserIdWithMedia(long userId);

    /** Измерения одного вида: mobility, pulse_rest, body_weight, other. */
    List<Measurement> findByUserIdAndKind(long userId, String kind);

    List<Measurement> findByUserAndPeriod(long userId, LocalDateTime from, LocalDateTime to);

    /**
     * Замеры одного типа за период ДЛЯ ГРАФИКА: по возрастанию даты,
     * чтобы линия рисовалась слева направо (в таблице — наоборот, новые сверху).
     */
    List<Measurement> findForChart(long userId, String kind, LocalDateTime from, LocalDateTime to);
}
