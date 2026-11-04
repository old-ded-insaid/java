package app.repository;

import app.model.Comment;

import java.util.List;

/** Доступ к таблице comments. */
public interface CommentRepository extends CrudRepository<Comment, Long> {

    List<Comment> findByWorkoutId(long workoutId);

    List<Comment> findByWorkoutExerciseId(long workoutExerciseId);

    List<Comment> findByUserId(long userId);
}
