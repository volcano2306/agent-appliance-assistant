package com.example.demo.repository;

import com.example.demo.model.UserDevice;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface UserDeviceRepository extends MongoRepository<UserDevice, String> {
    List<UserDevice> findByUserOpenId(String openId);
}
