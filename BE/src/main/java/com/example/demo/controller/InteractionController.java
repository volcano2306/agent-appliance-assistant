package com.example.demo.controller;

import com.example.demo.model.Comment;
import com.example.demo.model.Like;
import com.example.demo.model.Manual;
import com.example.demo.model.MyManual;
import com.example.demo.model.User;
import com.example.demo.repository.CommentRepository;
import com.example.demo.repository.LikeRepository;
import com.example.demo.repository.ManualRepository;
import com.example.demo.repository.MyManualRepository;
import com.example.demo.repository.UserRepository;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@RestController
@RequestMapping("/api/interactions")
@RequiredArgsConstructor
public class InteractionController {

    private final CommentRepository commentRepository;
    private final LikeRepository likeRepository;
    private final ManualRepository manualRepository;
    private final MyManualRepository myManualRepository;
    private final UserRepository userRepository;

    @GetMapping("/comments")
    public ResponseEntity<Map<String, Object>> listComments(
            @RequestParam String targetType,
            @RequestParam String targetId,
            @RequestParam(required = false) String userId) {
        if (isBlank(targetType) || isBlank(targetId)) {
            return ResponseEntity.badRequest().body(Map.of("items", List.of()));
        }

        List<Map<String, Object>> items = commentRepository
                .findByTargetTypeAndTargetIdOrderByCreatedAtAsc(normalizeTargetType(targetType), targetId.trim())
                .stream()
                .map(comment -> toCommentMap(comment, userId, true))
                .toList();

        return ResponseEntity.ok(Map.of("items", items));
    }

    @PostMapping("/comments")
    public ResponseEntity<?> createComment(@RequestBody CreateCommentRequest req) {
        if (req == null || isBlank(req.getUserId())) {
            return ResponseEntity.status(401).body(Map.of("error", "login_required"));
        }
        if (isBlank(req.getTargetType()) || isBlank(req.getTargetId()) || isBlank(req.getContent())) {
            return ResponseEntity.badRequest().body(Map.of("error", "targetType, targetId and content are required"));
        }
        if (!userRepository.existsById(req.getUserId().trim())) {
            return ResponseEntity.status(401).body(Map.of("error", "invalid_user"));
        }

        String targetType = normalizeTargetType(req.getTargetType());
        String targetId = req.getTargetId().trim();
        if (!targetExists(targetType, targetId)) {
            return ResponseEntity.badRequest().body(Map.of("error", "target_not_found"));
        }

        Comment saved = commentRepository.save(Comment.builder()
                .targetType(targetType)
                .targetId(targetId)
                .fromUserId(req.getUserId().trim())
                .fromUserName(cleanDisplayName(req.getFromUserName()))
                .fromAvatar(safeText(req.getFromAvatar()).trim())
                .toUserId(resolveTargetOwner(targetType, targetId).orElse(null))
                .content(req.getContent().trim())
                .createdAt(Instant.now())
                .build());

        return ResponseEntity.status(201).body(toCommentMap(saved, req.getUserId(), true));
    }

    @PostMapping("/comments/{commentId}/replies")
    public ResponseEntity<?> replyComment(
            @PathVariable String commentId,
            @RequestBody ReplyRequest req) {
        if (req == null || isBlank(req.getUserId())) {
            return ResponseEntity.status(401).body(Map.of("error", "login_required"));
        }
        if (isBlank(req.getContent())) {
            return ResponseEntity.badRequest().body(Map.of("error", "content is required"));
        }
        if (!userRepository.existsById(req.getUserId().trim())) {
            return ResponseEntity.status(401).body(Map.of("error", "invalid_user"));
        }

        Optional<Comment> parentOpt = commentRepository.findById(commentId);
        if (parentOpt.isEmpty()) {
            return ResponseEntity.notFound().build();
        }

        Comment parent = parentOpt.get();
        Comment saved = commentRepository.save(Comment.builder()
                .targetType("comment")
                .targetId(parent.getId())
                .fromUserId(req.getUserId().trim())
                .fromUserName(cleanDisplayName(req.getFromUserName()))
                .fromAvatar(safeText(req.getFromAvatar()).trim())
                .toUserId(parent.getFromUserId())
                .content(req.getContent().trim())
                .createdAt(Instant.now())
                .build());

        return ResponseEntity.status(201).body(toReplyMap(saved, parent, req.getUserId()));
    }

    @PostMapping("/comments/{commentId}/likes/toggle")
    public ResponseEntity<?> toggleCommentLike(
            @PathVariable String commentId,
            @RequestBody UserActionRequest req) {
        if (req == null || isBlank(req.getUserId())) {
            return ResponseEntity.status(401).body(Map.of("error", "login_required"));
        }
        Optional<Comment> commentOpt = commentRepository.findById(commentId);
        if (commentOpt.isEmpty()) {
            return ResponseEntity.notFound().build();
        }

        ToggleResult result = toggleLike(
                "comment",
                commentId,
                req.getUserId().trim(),
                commentOpt.get().getFromUserId(),
                cleanDisplayName(req.getFromUserName()),
                safeText(req.getFromAvatar()).trim()
        );
        return ResponseEntity.ok(Map.of(
                "liked", result.liked,
                "likeCount", result.likeCount
        ));
    }

    @DeleteMapping("/comments/{commentId}")
    public ResponseEntity<?> deleteComment(
            @PathVariable String commentId,
            @RequestParam String userId) {
        if (isBlank(userId)) {
            return ResponseEntity.status(401).body(Map.of("error", "login_required"));
        }
        Optional<Comment> commentOpt = commentRepository.findById(commentId);
        if (commentOpt.isEmpty()) {
            return ResponseEntity.notFound().build();
        }

        Comment comment = commentOpt.get();
        if (!userId.trim().equals(comment.getFromUserId())) {
            return ResponseEntity.status(403).body(Map.of("error", "forbidden"));
        }

        int deleted = deleteCommentTree(comment);
        return ResponseEntity.ok(Map.of("deleted", deleted));
    }

    @GetMapping("/targets/likes/status")
    public ResponseEntity<Map<String, Object>> targetLikeStatus(
            @RequestParam String targetType,
            @RequestParam String targetId,
            @RequestParam(required = false) String userId) {
        if (isBlank(targetType) || isBlank(targetId)) {
            return ResponseEntity.badRequest().body(Map.of("liked", false, "likeCount", 0));
        }
        String normalizedType = normalizeTargetType(targetType);
        String normalizedId = targetId.trim();
        boolean liked = !isBlank(userId) && likeRepository
                .findByTargetTypeAndTargetIdAndFromUserId(normalizedType, normalizedId, userId.trim())
                .isPresent();
        long likeCount = likeRepository.countByTargetTypeAndTargetId(normalizedType, normalizedId);
        return ResponseEntity.ok(Map.of("liked", liked, "likeCount", likeCount));
    }

    @PostMapping("/targets/likes/toggle")
    public ResponseEntity<?> toggleTargetLike(@RequestBody TargetLikeRequest req) {
        if (req == null || isBlank(req.getUserId())) {
            return ResponseEntity.status(401).body(Map.of("error", "login_required"));
        }
        if (isBlank(req.getTargetType()) || isBlank(req.getTargetId())) {
            return ResponseEntity.badRequest().body(Map.of("error", "targetType and targetId are required"));
        }

        String targetType = normalizeTargetType(req.getTargetType());
        String targetId = req.getTargetId().trim();
        if (!targetExists(targetType, targetId)) {
            return ResponseEntity.badRequest().body(Map.of("error", "target_not_found"));
        }

        ToggleResult result = toggleLike(
                targetType,
                targetId,
                req.getUserId().trim(),
                resolveTargetOwner(targetType, targetId).orElse(null),
                cleanDisplayName(req.getFromUserName()),
                safeText(req.getFromAvatar()).trim()
        );
        return ResponseEntity.ok(Map.of(
                "liked", result.liked,
                "likeCount", result.likeCount
        ));
    }

    @GetMapping("/received/comments")
    public ResponseEntity<List<Map<String, Object>>> receivedComments(
            @RequestParam(required = false) String userId,
            @RequestHeader(value = "X-User-Id", required = false) String headerUserId) {
        String resolvedUserId = firstNonBlank(userId, headerUserId);
        if (isBlank(resolvedUserId)) {
            return ResponseEntity.status(401).body(List.of());
        }

        String uid = resolvedUserId.trim();
        List<String> myCommentIds = commentRepository.findByFromUserId(uid)
                .stream()
                .map(Comment::getId)
                .filter(id -> !isBlank(id))
                .toList();

        List<String> ownedManualIds = manualRepository.findByUploaderId(uid)
                .stream()
                .map(Manual::getId)
                .filter(id -> !isBlank(id))
                .toList();

        List<String> ownedPublicMyManualIds = myManualRepository.findByOwnerUserIdAndVisibility(uid, "public")
                .stream()
                .map(MyManual::getId)
                .filter(id -> !isBlank(id))
                .toList();

        Map<String, Comment> candidates = new LinkedHashMap<>();
        addComments(candidates, commentRepository.findByToUserIdOrderByCreatedAtDesc(uid));
        if (!myCommentIds.isEmpty()) {
            addComments(candidates, commentRepository.findByTargetTypeAndTargetIdInOrderByCreatedAtDesc("comment", myCommentIds));
        }
        if (!ownedManualIds.isEmpty()) {
            addComments(candidates, commentRepository.findByTargetTypeAndTargetIdInOrderByCreatedAtDesc("manual", ownedManualIds));
        }
        if (!ownedPublicMyManualIds.isEmpty()) {
            addComments(candidates, commentRepository.findByTargetTypeAndTargetIdInOrderByCreatedAtDesc("myManual", ownedPublicMyManualIds));
        }

        List<Map<String, Object>> rows = candidates.values().stream()
                .filter(comment -> isReceivedCommentFor(uid, comment))
                .sorted((a, b) -> compareCreatedAtDesc(a.getCreatedAt(), b.getCreatedAt()))
                .map(this::toReceivedCommentMap)
                .toList();

        return ResponseEntity.ok(rows);
    }

    @GetMapping("/received/likes")
    public ResponseEntity<List<Map<String, Object>>> receivedLikes(
            @RequestParam(required = false) String userId,
            @RequestHeader(value = "X-User-Id", required = false) String headerUserId) {
        String resolvedUserId = firstNonBlank(userId, headerUserId);
        if (isBlank(resolvedUserId)) {
            return ResponseEntity.status(401).body(List.of());
        }

        String uid = resolvedUserId.trim();
        List<String> myCommentIds = commentRepository.findByFromUserId(uid)
                .stream()
                .map(Comment::getId)
                .filter(id -> !isBlank(id))
                .toList();

        List<String> ownedManualIds = manualRepository.findByUploaderId(uid)
                .stream()
                .map(Manual::getId)
                .filter(id -> !isBlank(id))
                .toList();

        List<String> ownedPublicMyManualIds = myManualRepository.findByOwnerUserIdAndVisibility(uid, "public")
                .stream()
                .map(MyManual::getId)
                .filter(id -> !isBlank(id))
                .toList();

        Map<String, Like> candidates = new LinkedHashMap<>();
        addLikes(candidates, likeRepository.findByToUserIdOrderByCreatedAtDesc(uid));
        if (!myCommentIds.isEmpty()) {
            addLikes(candidates, likeRepository.findByTargetTypeAndTargetIdInOrderByCreatedAtDesc("comment", myCommentIds));
        }
        if (!ownedManualIds.isEmpty()) {
            addLikes(candidates, likeRepository.findByTargetTypeAndTargetIdInOrderByCreatedAtDesc("manual", ownedManualIds));
        }
        if (!ownedPublicMyManualIds.isEmpty()) {
            addLikes(candidates, likeRepository.findByTargetTypeAndTargetIdInOrderByCreatedAtDesc("myManual", ownedPublicMyManualIds));
        }

        List<Map<String, Object>> rows = candidates.values().stream()
                .filter(like -> isReceivedLikeFor(uid, like))
                .sorted((a, b) -> compareCreatedAtDesc(a.getCreatedAt(), b.getCreatedAt()))
                .map(this::toReceivedLikeMap)
                .toList();

        return ResponseEntity.ok(rows);
    }

    private void addComments(Map<String, Comment> target, List<Comment> list) {
        if (target == null || list == null) {
            return;
        }
        for (Comment item : list) {
            if (item != null && !isBlank(item.getId())) {
                target.putIfAbsent(item.getId(), item);
            }
        }
    }

    private void addLikes(Map<String, Like> target, List<Like> list) {
        if (target == null || list == null) {
            return;
        }
        for (Like item : list) {
            if (item != null && !isBlank(item.getId())) {
                target.putIfAbsent(item.getId(), item);
            }
        }
    }

    private Map<String, Object> toCommentMap(Comment comment, String currentUserId, boolean includeReplies) {
        Map<String, Object> row = baseCommentMap(comment, currentUserId);
        if (includeReplies) {
            row.put("replies", collectReplyMaps(comment, currentUserId));
        }
        return row;
    }

    private Map<String, Object> toReplyMap(Comment reply, Comment parent, String currentUserId) {
        Map<String, Object> row = baseCommentMap(reply, currentUserId);
        row.put("toUserId", parent.getFromUserId());
        row.put("toUserName", displayCommentUser(parent));
        return row;
    }

    private Map<String, Object> baseCommentMap(Comment comment, String currentUserId) {
        long likeCount = likeRepository.countByTargetTypeAndTargetId("comment", comment.getId());
        boolean likedByMe = !isBlank(currentUserId) && likeRepository
                .findByTargetTypeAndTargetIdAndFromUserId("comment", comment.getId(), currentUserId.trim())
                .isPresent();

        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", comment.getId());
        row.put("targetType", comment.getTargetType());
        row.put("targetId", comment.getTargetId());
        row.put("fromUserId", comment.getFromUserId());
        row.put("toUserId", comment.getToUserId());
        row.put("fromUserName", displayCommentUser(comment));
        row.put("fromAvatar", safeText(comment.getFromAvatar()));
        row.put("content", safeText(comment.getContent()));
        row.put("createdAt", comment.getCreatedAt());
        row.put("likeCount", likeCount);
        row.put("likedByMe", likedByMe);
        return row;
    }

    private List<Map<String, Object>> collectReplyMaps(Comment root, String currentUserId) {
        List<Map<String, Object>> result = new ArrayList<>();
        collectReplyMaps(root, currentUserId, result);
        result.sort(Comparator.comparing(row -> (Instant) row.get("createdAt"), Comparator.nullsLast(Comparator.naturalOrder())));
        return result;
    }

    private void collectReplyMaps(Comment parent, String currentUserId, List<Map<String, Object>> result) {
        List<Comment> children = commentRepository.findByTargetTypeAndTargetIdOrderByCreatedAtAsc("comment", parent.getId());
        for (Comment child : children) {
            result.add(toReplyMap(child, parent, currentUserId));
            collectReplyMaps(child, currentUserId, result);
        }
    }

    private Map<String, Object> toReceivedCommentMap(Comment comment) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", comment.getId());
        row.put("fromUserId", comment.getFromUserId());
        row.put("fromName", displayCommentUser(comment));
        row.put("fromAvatar", safeText(comment.getFromAvatar()));
        row.put("content", safeText(comment.getContent()));
        row.put("timestamp", toEpochMillis(comment.getCreatedAt()));

        TargetInfo target = resolveNotificationTarget(comment);
        row.put("tag", target.tag);
        row.put("action", "comment".equals(comment.getTargetType()) ? "回复了你的评论" : "评论了你的说明书");
        row.put("quoted", target.quoted);
        row.put("thumbnail", target.thumbnail);
        row.put("targetType", target.targetType);
        row.put("targetId", target.targetId);
        return row;
    }

    private Map<String, Object> toReceivedLikeMap(Like like) {
        Map<String, Object> row = new LinkedHashMap<>();
        ActorProfile actor = resolveActorProfile(like);
        row.put("id", like.getId());
        row.put("fromUserId", like.getFromUserId());
        row.put("toUserId", like.getToUserId());
        row.put("fromName", actor.name);
        row.put("fromAvatar", actor.avatar);
        row.put("timestamp", toEpochMillis(like.getCreatedAt()));

        TargetInfo target = resolveLikeNotificationTarget(like);
        row.put("action", "comment".equals(like.getTargetType()) ? "赞了你的评论" : "赞了你的说明书");
        row.put("content", target.quoted);
        row.put("thumbnail", target.thumbnail);
        row.put("targetType", target.targetType);
        row.put("targetId", target.targetId);
        return row;
    }

    private boolean isReceivedCommentFor(String userId, Comment comment) {
        if (comment == null) {
            return false;
        }
        // 只要通知目标是自己，就认为应显示（包括“自己回复自己”的场景）。
        if (userId.equals(comment.getToUserId())) {
            return true;
        }
        String targetType = normalizeTargetType(comment.getTargetType());
        String targetId = safeText(comment.getTargetId()).trim();
        if ("comment".equals(targetType)) {
            return commentRepository.findById(targetId)
                    .map(parent -> userId.equals(parent.getFromUserId()))
                    .orElse(false);
        }
        return isPublicManualOwnedBy(userId, targetType, targetId);
    }

    private boolean isReceivedLikeFor(String userId, Like like) {
        if (like == null) {
            return false;
        }
        // 只要通知目标是自己，就认为应显示（包括“自己点赞自己评论”的场景）。
        if (userId.equals(like.getToUserId())) {
            return true;
        }
        if ("comment".equals(like.getTargetType())) {
            Optional<Comment> commentOpt = commentRepository.findById(like.getTargetId());
            if (commentOpt.isPresent()) {
                Comment comment = commentOpt.get();
                return userId.equals(comment.getFromUserId());
            }
            return false;
        }
        return isPublicManualOwnedBy(userId, like.getTargetType(), like.getTargetId());
    }

    private boolean isPublicManualOwnedBy(String userId, String targetType, String targetId) {
        if (isBlank(userId) || isBlank(targetType) || isBlank(targetId)) {
            return false;
        }
        String normalizedType = normalizeTargetType(targetType);
        String normalizedId = targetId.trim();
        if ("manual".equals(normalizedType)) {
            return manualRepository.findById(normalizedId)
                    .map(manual -> userId.equals(manual.getUploaderId()))
                    .orElse(false);
        }
        if ("myManual".equals(normalizedType)) {
            return myManualRepository.findById(normalizedId)
                    .map(myManual -> userId.equals(myManual.getOwnerUserId())
                            && "public".equalsIgnoreCase(safeText(myManual.getVisibility()).trim()))
                    .orElse(false);
        }
        return false;
    }

    private static int compareCreatedAtDesc(Instant a, Instant b) {
        if (a == null && b == null) {
            return 0;
        }
        if (a == null) {
            return 1;
        }
        if (b == null) {
            return -1;
        }
        return b.compareTo(a);
    }

    private TargetInfo resolveLikeNotificationTarget(Like like) {
        TargetInfo info = new TargetInfo();
        info.targetType = like.getTargetType();
        info.targetId = like.getTargetId();
        info.quoted = "";
        info.thumbnail = "";

        if ("comment".equals(like.getTargetType())) {
            commentRepository.findById(like.getTargetId()).ifPresent(comment -> {
                TargetInfo commentTarget = resolveNotificationTarget(comment);
                info.targetType = commentTarget.targetType;
                info.targetId = commentTarget.targetId;
                info.quoted = safeText(comment.getContent());
                info.thumbnail = commentTarget.thumbnail;
            });
            return info;
        }

        if ("manual".equals(like.getTargetType())) {
            manualRepository.findById(like.getTargetId()).ifPresent(manual -> {
                info.quoted = firstNonBlank(manual.getProductName(), manual.getModelName(), manual.getFilename());
                info.thumbnail = firstNonBlank(
                        safeText(manual.getImageUrl()),
                        resolveManualThumbnailFromMyManual(like.getTargetId())
                );
            });
        } else if ("myManual".equals(like.getTargetType())) {
            myManualRepository.findById(like.getTargetId()).ifPresent(myManual -> {
                info.quoted = firstNonBlank(myManual.getNickname(), myManual.getProductName(), myManual.getModelName());
                info.thumbnail = resolveMyManualThumbnail(myManual);
            });
        }
        return info;
    }

    private TargetInfo resolveNotificationTarget(Comment comment) {
        if ("comment".equals(comment.getTargetType())) {
            Optional<Comment> parentOpt = commentRepository.findById(comment.getTargetId());
            if (parentOpt.isPresent()) {
                Comment parent = parentOpt.get();
                TargetInfo info = resolveNotificationTarget(parent);
                info.quoted = safeText(parent.getContent());
                return info;
            }
        }

        TargetInfo info = new TargetInfo();
        info.targetType = comment.getTargetType();
        info.targetId = comment.getTargetId();
        info.tag = "评论";
        info.quoted = "";
        info.thumbnail = "";

        if ("manual".equals(comment.getTargetType())) {
            manualRepository.findById(comment.getTargetId()).ifPresent(manual -> {
                info.tag = "说明书";
                info.quoted = firstNonBlank(manual.getProductName(), manual.getModelName(), manual.getFilename());
                info.thumbnail = firstNonBlank(
                        safeText(manual.getImageUrl()),
                        resolveManualThumbnailFromMyManual(comment.getTargetId())
                );
            });
        } else if ("myManual".equals(comment.getTargetType())) {
            myManualRepository.findById(comment.getTargetId()).ifPresent(myManual -> {
                info.tag = "我的说明书";
                info.quoted = firstNonBlank(myManual.getNickname(), myManual.getProductName(), myManual.getModelName());
                info.thumbnail = resolveMyManualThumbnail(myManual);
            });
        }
        return info;
    }

    private String resolveMyManualThumbnail(MyManual myManual) {
        if (myManual == null) {
            return "";
        }
        if (!isBlank(myManual.getImageUrl())) {
            return myManual.getImageUrl().trim();
        }
        if (isBlank(myManual.getManualId())) {
            return "";
        }
        return manualRepository.findById(myManual.getManualId())
                .map(Manual::getImageUrl)
                .map(InteractionController::safeText)
                .orElse("");
    }

    private String resolveManualThumbnailFromMyManual(String manualId) {
        if (isBlank(manualId)) {
            return "";
        }
        List<MyManual> refs = myManualRepository.findByManualId(manualId.trim());
        for (MyManual ref : refs) {
            if (ref != null && !isBlank(ref.getImageUrl())) {
                return ref.getImageUrl().trim();
            }
        }
        return "";
    }

    private ToggleResult toggleLike(
            String targetType,
            String targetId,
            String userId,
            String toUserId,
            String fromUserName,
            String fromAvatar) {
        Optional<Like> existing = likeRepository.findByTargetTypeAndTargetIdAndFromUserId(targetType, targetId, userId);
        boolean liked;
        if (existing.isPresent()) {
            likeRepository.delete(existing.get());
            liked = false;
        } else {
            likeRepository.save(Like.builder()
                    .targetType(targetType)
                    .targetId(targetId)
                    .fromUserId(userId)
                    .toUserId(toUserId)
                    .fromUserName(cleanDisplayName(fromUserName))
                    .fromAvatar(safeText(fromAvatar).trim())
                    .createdAt(Instant.now())
                    .build());
            liked = true;
        }
        long likeCount = likeRepository.countByTargetTypeAndTargetId(targetType, targetId);
        return new ToggleResult(liked, likeCount);
    }

    private ActorProfile resolveActorProfile(Like like) {
        String fallbackName = displayUser(like != null ? like.getFromUserId() : null);
        String name = cleanDisplayName(like != null ? like.getFromUserName() : null);
        String avatar = safeText(like != null ? like.getFromAvatar() : null).trim();

        if (!isBlank(name) && !isBlank(avatar)) {
            return new ActorProfile(name, avatar);
        }

        String fromUserId = like != null ? like.getFromUserId() : null;
        if (!isBlank(fromUserId)) {
            List<Comment> latestComments = commentRepository.findByFromUserIdOrderByCreatedAtDesc(fromUserId.trim());
            for (Comment comment : latestComments) {
                if (isBlank(name) && !isBlank(comment.getFromUserName())) {
                    name = cleanDisplayName(comment.getFromUserName());
                }
                if (isBlank(avatar) && !isBlank(comment.getFromAvatar())) {
                    avatar = comment.getFromAvatar().trim();
                }
                if (!isBlank(name) && !isBlank(avatar)) {
                    break;
                }
            }
        }

        if (isBlank(name)) {
            name = fallbackName;
        }
        return new ActorProfile(name, avatar);
    }

    private int deleteCommentTree(Comment comment) {
        int deleted = 0;
        List<Comment> children = commentRepository.findByTargetTypeAndTargetIdOrderByCreatedAtAsc("comment", comment.getId());
        for (Comment child : children) {
            deleted += deleteCommentTree(child);
        }
        for (Like like : likeRepository.findByTargetTypeAndTargetId("comment", comment.getId())) {
            likeRepository.delete(like);
        }
        commentRepository.delete(comment);
        return deleted + 1;
    }

    private boolean targetExists(String targetType, String targetId) {
        if ("manual".equals(targetType)) {
            return manualRepository.existsById(targetId);
        }
        if ("myManual".equals(targetType)) {
            return myManualRepository.existsById(targetId);
        }
        if ("comment".equals(targetType)) {
            return commentRepository.existsById(targetId);
        }
        return false;
    }

    private Optional<String> resolveTargetOwner(String targetType, String targetId) {
        if ("manual".equals(targetType)) {
            return manualRepository.findById(targetId).map(Manual::getUploaderId).filter(owner -> !isBlank(owner));
        }
        if ("myManual".equals(targetType)) {
            return myManualRepository.findById(targetId).map(MyManual::getOwnerUserId).filter(owner -> !isBlank(owner));
        }
        if ("comment".equals(targetType)) {
            return commentRepository.findById(targetId).map(Comment::getFromUserId).filter(owner -> !isBlank(owner));
        }
        return Optional.empty();
    }

    private String displayUser(String userId) {
        if (isBlank(userId)) {
            return "用户";
        }
        return userRepository.findById(userId.trim())
                .map(this::displayUser)
                .orElse("用户");
    }

    private String displayCommentUser(Comment comment) {
        if (comment != null && !isBlank(comment.getFromUserName())) {
            return comment.getFromUserName().trim();
        }
        return displayUser(comment != null ? comment.getFromUserId() : null);
    }

    private static String cleanDisplayName(String value) {
        if (isBlank(value)) {
            return "";
        }
        String text = value.trim();
        return text.length() > 24 ? text.substring(0, 24) : text;
    }

    private String displayUser(User user) {
        if (user == null) {
            return "用户";
        }
        if (!isBlank(user.getPhone())) {
            String phone = user.getPhone().trim();
            if (phone.length() >= 4) {
                return "用户" + phone.substring(phone.length() - 4);
            }
            return "用户" + phone;
        }
        return "用户";
    }

    private static String normalizeTargetType(String value) {
        String text = value == null ? "" : value.trim();
        if ("mymanual".equalsIgnoreCase(text) || "my_manual".equalsIgnoreCase(text)) {
            return "myManual";
        }
        if ("comment".equalsIgnoreCase(text)) {
            return "comment";
        }
        return "manual";
    }

    private static String safeText(String value) {
        return value == null ? "" : value;
    }

    private static String firstNonBlank(String... values) {
        if (values == null) {
            return "";
        }
        for (String value : values) {
            if (!isBlank(value)) {
                return value.trim();
            }
        }
        return "";
    }

    private static long toEpochMillis(Instant instant) {
        return instant == null ? 0L : instant.toEpochMilli();
    }

    private static boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }

    private record ToggleResult(boolean liked, long likeCount) {
    }

    private record ActorProfile(String name, String avatar) {
    }

    private static class TargetInfo {
        private String targetType;
        private String targetId;
        private String tag;
        private String quoted;
        private String thumbnail;
    }

    @Data
    public static class CreateCommentRequest {
        private String userId;
        private String targetType;
        private String targetId;
        private String content;
        private String fromUserName;
        private String fromAvatar;
    }

    @Data
    public static class ReplyRequest {
        private String userId;
        private String content;
        private String fromUserName;
        private String fromAvatar;
    }

    @Data
    public static class UserActionRequest {
        private String userId;
        private String fromUserName;
        private String fromAvatar;
    }

    @Data
    public static class TargetLikeRequest {
        private String userId;
        private String targetType;
        private String targetId;
        private String fromUserName;
        private String fromAvatar;
    }
}
