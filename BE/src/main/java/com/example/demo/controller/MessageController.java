package com.example.demo.controller;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.example.demo.model.BrandMessage;
import com.example.demo.model.StrangerMessage;
import com.example.demo.model.SysMessage;
import com.example.demo.repository.BrandMessageRepository;
import com.example.demo.repository.BrandRepository;
import com.example.demo.repository.StrangerMessageRepository;
import com.example.demo.repository.SysMessageRepository;

import lombok.Data;
import lombok.RequiredArgsConstructor;

@RestController
@RequestMapping("/api/messages")
@RequiredArgsConstructor
public class MessageController {

    private final SysMessageRepository sysMessageRepository;
    private final BrandMessageRepository brandMessageRepository;
    private final BrandRepository brandRepository;
    private final StrangerMessageRepository strangerMessageRepository;

    @GetMapping("/summary")
    public ResponseEntity<List<Map<String, Object>>> getSummary(@RequestParam String userId) {
        if (isBlank(userId)) {
            return ResponseEntity.status(401).body(List.of());
        }

        List<Map<String, Object>> result = new ArrayList<>();

        // System summary
        List<SysMessage> sysMessages = sysMessageRepository.findByToUserIdOrderByCreatedAtDesc(userId);
        long sysUnread = sysMessageRepository.countByToUserIdAndReadAtIsNull(userId);
        SysMessage latestSys = sysMessages.isEmpty() ? null : sysMessages.get(0);
        Map<String, Object> sysItem = new LinkedHashMap<>();
        sysItem.put("id", "system");
        sysItem.put("type", "system");
        sysItem.put("title", "系统消息");
        sysItem.put("subtitle", latestSys != null && !isBlank(latestSys.getContent()) ? latestSys.getContent() : "暂无消息");
        sysItem.put("preview", sysItem.get("subtitle"));
        sysItem.put("unread", sysUnread);
        sysItem.put("timestamp", toEpochMillis(latestSys != null ? latestSys.getCreatedAt() : null));
        result.add(sysItem);
        if (sysMessages.isEmpty()) {
            result.remove(sysItem);
        }

        // Brand summary
        List<BrandMessage> brandMessages = brandMessageRepository.findByToUserIdOrderByCreatedAtDesc(userId);
        Map<String, BrandSummary> brandThreads = new LinkedHashMap<>();
        for (BrandMessage msg : brandMessages) {
            String threadId = firstNonBlank(msg.getBrandId(), msg.getBrandName());
            if (isBlank(threadId)) {
                threadId = "brand";
            }
            BrandSummary summary = brandThreads.get(threadId);
            if (summary == null) {
                summary = new BrandSummary();
                summary.threadId = threadId;
                summary.title = firstNonBlank(msg.getBrandName(), "品牌消息");
                summary.latest = msg;
                brandThreads.put(threadId, summary);
            }
            if (msg.getReadAt() == null) {
                summary.unread++;
            }
        }

        for (BrandSummary summary : brandThreads.values()) {
            BrandMessage latest = summary.latest;
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", summary.threadId);
            item.put("type", "brand");
            item.put("title", summary.title);
            item.put("subtitle", latest != null ? safeText(latest.getContent()) : "");
            item.put("preview", item.get("subtitle"));
            item.put("unread", summary.unread);
            item.put("timestamp", toEpochMillis(latest != null ? latest.getCreatedAt() : null));
            item.put("icon", resolveBrandLogo(latest));
            result.add(item);
        }

        return ResponseEntity.ok(result);
    }

    @GetMapping("/thread")
    public ResponseEntity<List<Map<String, Object>>> getThread(
            @RequestParam String userId,
            @RequestParam String type,
            @RequestParam String threadId) {
        if (isBlank(userId) || isBlank(type) || isBlank(threadId)) {
            return ResponseEntity.badRequest().body(List.of());
        }

        String normalized = type.trim().toLowerCase();
        List<Map<String, Object>> result = new ArrayList<>();

        if ("system".equals(normalized)) {
            List<SysMessage> list = sysMessageRepository.findByToUserIdOrderByCreatedAtDesc(userId);
            list.sort(Comparator.comparing(SysMessage::getCreatedAt, Comparator.nullsLast(Comparator.naturalOrder())));
            for (SysMessage msg : list) {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("id", msg.getId());
                row.put("role", "other");
                row.put("text", safeText(msg.getContent()));
                row.put("timestamp", toEpochMillis(msg.getCreatedAt()));
                result.add(row);
            }
            return ResponseEntity.ok(result);
        }

        if ("brand".equals(normalized)) {
            List<BrandMessage> list = brandMessageRepository.findByToUserIdOrderByCreatedAtDesc(userId);
            List<BrandMessage> filtered = new ArrayList<>();
            for (BrandMessage msg : list) {
                if (isBrandThreadMatch(msg, threadId)) {
                    filtered.add(msg);
                }
            }
            filtered.sort(Comparator.comparing(BrandMessage::getCreatedAt, Comparator.nullsLast(Comparator.naturalOrder())));
            for (BrandMessage msg : filtered) {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("id", msg.getId());
                row.put("role", "other");
                row.put("avatar", resolveBrandLogo(msg));
                row.put("text", safeText(msg.getContent()));
                row.put("timestamp", toEpochMillis(msg.getCreatedAt()));
                result.add(row);
            }
            return ResponseEntity.ok(result);
        }

        if ("stranger".equals(normalized)) {
            List<StrangerMessage> list = strangerMessageRepository.findByToUserIdOrderByCreatedAtDesc(userId);
            List<StrangerMessage> filtered = new ArrayList<>();
            for (StrangerMessage msg : list) {
                String senderKey = firstNonBlank(msg.getFromUserId(), msg.getFromNickname());
                if (threadId.equals(senderKey)) {
                    filtered.add(msg);
                }
            }
            filtered.sort(Comparator.comparing(StrangerMessage::getCreatedAt, Comparator.nullsLast(Comparator.naturalOrder())));
            for (StrangerMessage msg : filtered) {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("id", msg.getId());
                row.put("role", userId.equals(msg.getFromUserId()) ? "me" : "other");
                row.put("text", safeText(msg.getContent()));
                row.put("timestamp", toEpochMillis(msg.getCreatedAt()));
                result.add(row);
            }
            return ResponseEntity.ok(result);
        }

        return ResponseEntity.ok(result);
    }

    @PostMapping("/read")
    public ResponseEntity<Map<String, Object>> markRead(@RequestBody ReadRequest req) {
        if (req == null || isBlank(req.getUserId())) {
            return ResponseEntity.status(401).body(Map.of("updated", 0));
        }

        String type = req.getType() != null ? req.getType().trim().toLowerCase() : "";
        int updated = 0;

        if ("all".equals(type) || "system".equals(type)) {
            updated += markSysRead(req.getUserId());
        }
        if ("all".equals(type) || "brand".equals(type)) {
            updated += markBrandRead(req.getUserId(), req.getThreadId());
        }
        if ("all".equals(type) || "stranger".equals(type)) {
            updated += markStrangerRead(req.getUserId(), req.getThreadId());
        }

        return ResponseEntity.ok(Map.of("updated", updated));
    }

    @DeleteMapping("/thread")
    public ResponseEntity<Map<String, Object>> deleteThread(
            @RequestParam String userId,
            @RequestParam String type,
            @RequestParam(required = false) String threadId) {
        if (isBlank(userId) || isBlank(type)) {
            return ResponseEntity.status(401).body(Map.of("deleted", 0));
        }

        String normalized = type.trim().toLowerCase();
        int deleted = 0;

        if ("system".equals(normalized)) {
            List<SysMessage> list = sysMessageRepository.findByToUserIdOrderByCreatedAtDesc(userId);
            sysMessageRepository.deleteAll(list);
            deleted = list.size();
        } else if ("brand".equals(normalized)) {
            List<BrandMessage> list = brandMessageRepository.findByToUserIdOrderByCreatedAtDesc(userId);
            List<BrandMessage> filtered = list.stream()
                    .filter(msg -> isBlank(threadId) || isBrandThreadMatch(msg, threadId))
                    .toList();
            brandMessageRepository.deleteAll(filtered);
            deleted = filtered.size();
        } else if ("stranger".equals(normalized)) {
            List<StrangerMessage> list = strangerMessageRepository.findByToUserIdOrderByCreatedAtDesc(userId);
            List<StrangerMessage> filtered = list.stream()
                    .filter(msg -> {
                        String senderKey = firstNonBlank(msg.getFromUserId(), msg.getFromNickname());
                        return isBlank(threadId) || threadId.equals(senderKey);
                    })
                    .toList();
            strangerMessageRepository.deleteAll(filtered);
            deleted = filtered.size();
        }

        return ResponseEntity.ok(Map.of("deleted", deleted));
    }

    private int markSysRead(String userId) {
        List<SysMessage> list = sysMessageRepository.findByToUserIdOrderByCreatedAtDesc(userId);
        if (list.isEmpty()) {
            return 0;
        }
        Instant now = Instant.now();
        int updated = 0;
        for (SysMessage msg : list) {
            if (msg.getReadAt() == null) {
                msg.setReadAt(now);
                updated++;
            }
        }
        if (updated > 0) {
            sysMessageRepository.saveAll(list);
        }
        return updated;
    }

    private int markBrandRead(String userId, String threadId) {
        List<BrandMessage> list = brandMessageRepository.findByToUserIdOrderByCreatedAtDesc(userId);
        if (list.isEmpty()) {
            return 0;
        }
        Instant now = Instant.now();
        int updated = 0;
        for (BrandMessage msg : list) {
            if (!isBlank(threadId) && !isBrandThreadMatch(msg, threadId)) {
                continue;
            }
            if (msg.getReadAt() == null) {
                msg.setReadAt(now);
                updated++;
            }
        }
        if (updated > 0) {
            brandMessageRepository.saveAll(list);
        }
        return updated;
    }

    private int markStrangerRead(String userId, String threadId) {
        List<StrangerMessage> list = strangerMessageRepository.findByToUserIdOrderByCreatedAtDesc(userId);
        if (list.isEmpty()) {
            return 0;
        }
        Instant now = Instant.now();
        int updated = 0;
        for (StrangerMessage msg : list) {
            String senderKey = firstNonBlank(msg.getFromUserId(), msg.getFromNickname());
            if (!isBlank(threadId) && !threadId.equals(senderKey)) {
                continue;
            }
            if (msg.getReadAt() == null) {
                msg.setReadAt(now);
                updated++;
            }
        }
        if (updated > 0) {
            strangerMessageRepository.saveAll(list);
        }
        return updated;
    }

    private static boolean isBrandThreadMatch(BrandMessage msg, String threadId) {
        if (msg == null || isBlank(threadId)) {
            return false;
        }
        String brandId = firstNonBlank(msg.getBrandId(), "");
        String brandName = firstNonBlank(msg.getBrandName(), "");
        return threadId.equals(brandId) || threadId.equals(brandName);
    }

    private String resolveBrandLogo(BrandMessage msg) {
        if (msg == null) {
            return "";
        }
        if (!isBlank(msg.getBrandId())) {
            var brand = brandRepository.findById(msg.getBrandId());
            if (brand.isPresent() && !isBlank(brand.get().getLogoUrl())) {
                return brand.get().getLogoUrl();
            }
        }
        if (!isBlank(msg.getBrandName())) {
            var brand = brandRepository.findByName(msg.getBrandName());
            if (brand.isPresent() && !isBlank(brand.get().getLogoUrl())) {
                return brand.get().getLogoUrl();
            }
        }
        return "";
    }

    private static long toEpochMillis(Instant value) {
        return value == null ? 0 : value.toEpochMilli();
    }

    private static String safeText(String value) {
        return isBlank(value) ? "" : value.trim();
    }

    private static boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }

    private static String firstNonBlank(String value, String fallback) {
        return isBlank(value) ? fallback : value.trim();
    }

    @Data
    public static class ReadRequest {

        private String userId;
        private String type;
        private String threadId;
    }

    private static class BrandSummary {

        private String threadId;
        private String title;
        private BrandMessage latest;
        private int unread;
    }
}
