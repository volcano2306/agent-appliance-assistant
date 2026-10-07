package com.example.demo.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.stereotype.Repository;

import com.example.demo.model.Like;

@Repository
public interface LikeRepository extends MongoRepository<Like, String> {

    List<Like> findByTargetTypeAndTargetId(String targetType, String targetId);
    List<Like> findByTargetTypeAndTargetIdInOrderByCreatedAtDesc(String targetType, List<String> targetIds);

    List<Like> findByFromUserId(String fromUserId);

    List<Like> findByToUserId(String toUserId);

    List<Like> findByToUserIdOrderByCreatedAtDesc(String toUserId);

    Optional<Like> findByTargetTypeAndTargetIdAndFromUserId(String targetType, String targetId, String fromUserId);

    long countByTargetTypeAndTargetId(String targetType, String targetId);
}
