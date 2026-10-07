package com.example.demo.service;

import com.example.demo.dto.QARequest;
import com.example.demo.model.Manual;
import com.example.demo.model.MyManual;
import com.example.demo.repository.ManualRepository;
import com.example.demo.repository.MyManualRepository;
import com.example.demo.repository.ProductRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/** Resolves client-facing manual references to verified MongoDB records. */
@Service
@RequiredArgsConstructor
public class AgentManualContextResolver {
    private final ManualRepository manuals;
    private final MyManualRepository myManuals;
    private final ProductRepository products;

    public record Target(String platformId, String referenceId, String name, String type,
                         String brand, String model, String productId) {
        static Target empty() {
            return new Target("", "", "", "", "", "", "");
        }
    }

    public Target resolve(QARequest request) {
        if (request == null) {
            return Target.empty();
        }
        String userId = text(request.getUserId());
        Target byManual = resolveId(text(request.getManualId()), userId);
        Target byReference = resolveId(text(request.getManualRefId()), userId);
        if (byManual != null && byReference != null && !sameTarget(byManual, byReference)) {
            throw new IllegalArgumentException("manual_context_conflict");
        }
        Target target = byReference != null && !byReference.referenceId().isEmpty()
                ? byReference : byManual;
        if (target == null) {
            target = Target.empty();
        }
        String requestedProduct = text(request.getProductId());
        if (!requestedProduct.isEmpty() && !target.productId().isEmpty()
                && !target.productId().equals(requestedProduct)) {
            throw new IllegalArgumentException("invalid_product_context");
        }
        if (!target.productId().isEmpty() && !products.existsById(target.productId())) {
            target = new Target(target.platformId(), target.referenceId(), target.name(), target.type(),
                    target.brand(), target.model(), "");
        }
        if (!requestedProduct.isEmpty()) {
            if (!products.existsById(requestedProduct)) {
                throw new IllegalArgumentException("invalid_product_context");
            }
            target = new Target(target.platformId(), target.referenceId(), target.name(), target.type(),
                    target.brand(), target.model(), requestedProduct);
        }
        return target;
    }

    private Target resolveId(String id, String userId) {
        if (id.isEmpty()) {
            return null;
        }
        Manual platform = manuals.findById(id).orElse(null);
        if (platform != null) {
            return fromPlatform(platform, platform.getId());
        }
        MyManual mine = myManuals.findById(id).orElse(null);
        if (mine == null || userId.isEmpty() || !userId.equals(text(mine.getOwnerUserId()))) {
            throw new IllegalArgumentException("invalid_manual_context");
        }
        String linkedId = text(mine.getManualId());
        if (!linkedId.isEmpty()) {
            Manual linked = manuals.findById(linkedId)
                    .orElseThrow(() -> new IllegalArgumentException("invalid_manual_context"));
            return fromPlatform(linked, mine.getId());
        }
        return new Target("", mine.getId(), text(mine.getProductName()), text(mine.getCategoryCode()),
                text(mine.getBrandName()), text(mine.getModelName()), "");
    }

    private static Target fromPlatform(Manual manual, String referenceId) {
        return new Target(manual.getId(), referenceId, text(manual.getProductName()),
                text(manual.getCategoryCode()), text(manual.getBrandName()),
                text(manual.getModelName()), text(manual.getProductId()));
    }

    private static boolean sameTarget(Target first, Target second) {
        if (!first.platformId().isEmpty() || !second.platformId().isEmpty()) {
            return !first.platformId().isEmpty() && first.platformId().equals(second.platformId());
        }
        return first.referenceId().equals(second.referenceId());
    }

    private static String text(String value) {
        return value == null ? "" : value.trim();
    }
}
