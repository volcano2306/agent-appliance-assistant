package com.example.demo.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.stereotype.Repository;

import com.example.demo.model.Comment;

@Repository
public interface CommentRepository extends MongoRepository<Comment, String> {

    List<Comment> findByTargetTypeAndTargetId(String targetType, String targetId);

    List<Comment> findByTargetTypeAndTargetIdOrderByCreatedAtAsc(String targetType, String targetId);
    List<Comment> findByTargetTypeAndTargetIdInOrderByCreatedAtDesc(String targetType, List<String> targetIds);

    List<Comment> findByFromUserId(String fromUserId);
    List<Comment> findByFromUserIdOrderByCreatedAtDesc(String fromUserId);

    List<Comment> findByToUserId(String toUserId);

    List<Comment> findByToUserIdOrderByCreatedAtDesc(String toUserId);

    Optional<Comment> findFirstByTargetTypeAndTargetIdAndFromUserIdAndContent(
            String targetType,
            String targetId,
            String fromUserId,
            String content);
}
