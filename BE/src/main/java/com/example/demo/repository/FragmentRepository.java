package com.example.demo.repository;

import com.example.demo.model.Fragment;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface FragmentRepository extends MongoRepository<Fragment, String> {
    List<Fragment> findByManualId(String manualId);

    void deleteByManualId(String manualId);
}
