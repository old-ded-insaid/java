package app.repository;

import app.model.User;

import java.util.Optional;

/** Доступ к таблице users. */
public interface UserRepository extends CrudRepository<User, Long> {

    Optional<User> findByUsername(String username);

    boolean existsByUsername(String username);
}
