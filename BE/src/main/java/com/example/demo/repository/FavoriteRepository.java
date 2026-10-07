package com.example.demo.repository;

import com.example.demo.model.Favorite;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface FavoriteRepository extends MongoRepository<Favorite, String> {
    List<Favorite> findByTargetTypeAndTargetId(String targetType, String targetId);
    List<Favorite> findByFromUserId(String fromUserId);
    List<Favorite> findByToUserId(String toUserId);
    Optional<Favorite> findByTargetTypeAndTargetIdAndFromUserId(String targetType, String targetId, String fromUserId);
    long countByTargetTypeAndTargetId(String targetType, String targetId);
}
