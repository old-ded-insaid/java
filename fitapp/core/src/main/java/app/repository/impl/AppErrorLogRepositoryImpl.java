package app.repository.impl;

import app.model.AppErrorLogEntry;
import app.repository.AppErrorLogRepository;
import jakarta.persistence.EntityManager;

import java.util.List;

public class AppErrorLogRepositoryImpl extends AbstractJpaRepository<AppErrorLogEntry, Long>
        implements AppErrorLogRepository {

    public AppErrorLogRepositoryImpl(EntityManager em) {
        super(em, AppErrorLogEntry.class);
    }

    @Override
    public List<AppErrorLogEntry> findRecent(int limit) {
        return em.createQuery("""
                        select e from AppErrorLogEntry e
                        order by e.createdAt desc, e.id desc
                        """, AppErrorLogEntry.class)
                .setMaxResults(limit)
                .getResultList();
    }

    @Override
    public List<AppErrorLogEntry> findByLevel(String level) {
        return em.createQuery("""
                        select e from AppErrorLogEntry e
                        where e.level = :level
                        order by e.createdAt desc, e.id desc
                        """, AppErrorLogEntry.class)
                .setParameter("level", level)
                .getResultList();
    }
}
