package app.repository.impl;

import app.model.User;
import app.repository.UserRepository;
import jakarta.persistence.EntityManager;

import java.util.Optional;

public class UserRepositoryImpl extends AbstractJpaRepository<User, Long> implements UserRepository {

    public UserRepositoryImpl(EntityManager em) {
        super(em, User.class);
    }

    @Override
    public Optional<User> findByUsername(String username) {
        return em.createQuery("select u from User u where u.username = :name", User.class)
                .setParameter("name", username)
                .getResultList()
                .stream()
                .findFirst();
    }

    @Override
    public boolean existsByUsername(String username) {
        Long found = em.createQuery("select count(u) from User u where u.username = :name", Long.class)
                .setParameter("name", username)
                .getSingleResult();
        return found > 0;
    }
}
