package com.example.demo.controller;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.example.demo.model.User;
import com.example.demo.repository.UserRepository;

import lombok.Data;
import lombok.RequiredArgsConstructor;

/**
 * 认证接口 POST /api/auth/login — 微信登录换取 userId（Demo 模式：以 "demo_"+code 作为 openid）
 * POST /api/auth/phone — 获取并绑定用户手机号（Demo 模式：生成模拟号码）
 */
@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class AuthController {

    private final UserRepository userRepository;

    /**
     * 微信登录 请求体：{ "code": "微信临时登录凭证" } 响应体：{ "userId": "...", "openid": "...",
     * "points": 0 }
     */
    @PostMapping("/login")
    public ResponseEntity<LoginResp> login(@RequestBody LoginReq req) {
        String code = req.getCode();
        String openid = (code == null || code.isBlank())
                ? "demo_" + UUID.randomUUID()
                : "demo_" + code.trim();

        User user = userRepository.findByOpenid(openid).orElseGet(() -> {
            Instant now = Instant.now();
            return userRepository.save(User.builder()
                    .openid(openid)
                    .joinAt(now)
                    .points(0)
                    .createdAt(now)
                    .updatedAt(now)
                    .build());
        });

        LoginResp resp = new LoginResp();
        resp.setUserId(user.getId());
        resp.setOpenid(user.getOpenid());
        resp.setPoints(user.getPoints());
        resp.setJoinAt(user.getJoinAt());
        return ResponseEntity.ok(resp);
    }

    /**
     * 绑定手机号 请求体：{ "code": "微信手机号授权凭证", "userId": "..." } 响应体：{ "phone":
     * "138xxxxxxxx" }
     */
    @PostMapping("/phone")
    public ResponseEntity<?> bindPhone(@RequestBody PhoneReq req) {
        if (req == null || req.getUserId() == null || req.getUserId().isBlank()) {
            return ResponseEntity.badRequest().body("userId is required");
        }
        if (req.getCode() == null || req.getCode().isBlank()) {
            return ResponseEntity.badRequest().body("code is required");
        }

        Optional<User> opt = userRepository.findById(req.getUserId().trim());
        if (opt.isEmpty()) {
            return ResponseEntity.status(404).body("user not found");
        }

        // Demo 模式：根据 code 生成稳定的演示手机号
        String phone = toDemoPhone(req.getCode());
        User user = opt.get();
        user.setPhone(phone);
        user.setUpdatedAt(Instant.now());
        userRepository.save(user);

        PhoneResp resp = new PhoneResp();
        resp.setPhone(phone);
        return ResponseEntity.ok(resp);
    }

    /**
     * 根据 code 生成一个合法格式的演示手机号（每次相同 code 返回相同结果）
     */
    private String toDemoPhone(String code) {
        String hash = String.valueOf(Math.abs(code.hashCode()));
        String digits = (hash + "0000000000").substring(0, 9);
        int prefixOffset = (int) (Math.abs(code.hashCode()) % 7L);
        return (13 + prefixOffset) + digits;
    }

    @Data
    public static class LoginReq {

        private String code;
    }

    @Data
    public static class LoginResp {

        private String userId;
        private String openid;
        private int points;
        private Instant joinAt;
    }

    @Data
    public static class PhoneReq {

        private String code;
        private String userId;
    }

    @Data
    public static class PhoneResp {

        private String phone;
    }
}
