package app.repository.impl;

import app.model.AppSettings;
import app.repository.AppSettingsRepository;
import jakarta.persistence.EntityManager;

import java.util.Optional;

public class AppSettingsRepositoryImpl extends AbstractJpaRepository<AppSettings, Long>
        implements AppSettingsRepository {

    public AppSettingsRepositoryImpl(EntityManager em) {
        super(em, AppSettings.class);
    }

    @Override
    public Optional<AppSettings> findByUserId(long userId) {
        return em.createQuery("select s from AppSettings s where s.user.id = :userId", AppSettings.class)
                .setParameter("userId", userId)
                .getResultList()
                .stream()
                .findFirst();
    }
}
