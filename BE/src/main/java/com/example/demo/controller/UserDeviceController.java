package com.example.demo.controller;

import com.example.demo.model.UserDevice;
import com.example.demo.repository.UserDeviceRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.List;

@RestController
@RequestMapping("/api/user/devices")
@RequiredArgsConstructor
public class UserDeviceController {

    private final UserDeviceRepository repo;

    @PostMapping
    public UserDevice add(@RequestBody UserDevice device) {
        device.setCreatedAt(Instant.now());
        return repo.save(device);
    }

    @GetMapping
    public List<UserDevice> list(@RequestParam("openid") String openid) {
        return repo.findByUserOpenId(openid);
    }

    @PutMapping("/{id}")
    public ResponseEntity<UserDevice> update(@PathVariable String id, @RequestBody UserDevice device) {
        return repo.findById(id).map(existing -> {
            device.setId(existing.getId());
            UserDevice saved = repo.save(device);
            return ResponseEntity.ok(saved);
        }).orElseGet(() -> ResponseEntity.notFound().build());
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable String id) {
        if (!repo.existsById(id)) {
            return ResponseEntity.notFound().build();
        }
        repo.deleteById(id);
        return ResponseEntity.ok().build();
    }
}
