package com.example.demo.repository;

import com.example.demo.model.Annotation;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface AnnotationRepository extends MongoRepository<Annotation, String> {
    List<Annotation> findByProductIdAndView(String productId, String view);
}
