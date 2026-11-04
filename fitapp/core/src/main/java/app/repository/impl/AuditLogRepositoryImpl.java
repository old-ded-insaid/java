package app.repository.impl;

import app.model.AuditEntry;
import app.repository.AuditLogRepository;
import jakarta.persistence.EntityManager;

import java.util.List;

public class AuditLogRepositoryImpl extends AbstractJpaRepository<AuditEntry, Long>
        implements AuditLogRepository {

    public AuditLogRepositoryImpl(EntityManager em) {
        super(em, AuditEntry.class);
    }

    @Override
    public List<AuditEntry> findByEntity(String entityType, long entityId) {
        return em.createQuery("""
                        select a from AuditEntry a
                        where a.entityType = :type and a.entityId = :entityId
                        order by a.createdAt desc, a.id desc
                        """, AuditEntry.class)
                .setParameter("type", entityType)
                .setParameter("entityId", entityId)
                .getResultList();
    }

    @Override
    public List<AuditEntry> findByUserId(long userId) {
        return em.createQuery("""
                        select a from AuditEntry a
                        where a.user.id = :userId
                        order by a.createdAt desc, a.id desc
                        """, AuditEntry.class)
                .setParameter("userId", userId)
                .getResultList();
    }

    @Override
    public List<AuditEntry> findRecent(int limit) {
        return em.createQuery("""
                        select a from AuditEntry a
                        order by a.createdAt desc, a.id desc
                        """, AuditEntry.class)
                .setMaxResults(limit)
                .getResultList();
    }
}
