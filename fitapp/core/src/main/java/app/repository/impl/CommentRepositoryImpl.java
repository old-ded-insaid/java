package app.repository.impl;

import app.model.Comment;
import app.repository.CommentRepository;
import jakarta.persistence.EntityManager;

import java.util.List;

public class CommentRepositoryImpl extends AbstractJpaRepository<Comment, Long>
        implements CommentRepository {

    public CommentRepositoryImpl(EntityManager em) {
        super(em, Comment.class);
    }

    @Override
    public List<Comment> findByWorkoutId(long workoutId) {
        return em.createQuery("""
                        select c from Comment c
                        where c.workout.id = :id
                        order by c.createdAt desc
                        """, Comment.class)
                .setParameter("id", workoutId)
                .getResultList();
    }

    @Override
    public List<Comment> findByWorkoutExerciseId(long workoutExerciseId) {
        return em.createQuery("""
                        select c from Comment c
                        where c.workoutExercise.id = :id
                        order by c.createdAt desc
                        """, Comment.class)
                .setParameter("id", workoutExerciseId)
                .getResultList();
    }

    @Override
    public List<Comment> findByUserId(long userId) {
        return em.createQuery("""
                        select c from Comment c
                        where c.user.id = :id
                        order by c.createdAt desc
                        """, Comment.class)
                .setParameter("id", userId)
                .getResultList();
    }
}
