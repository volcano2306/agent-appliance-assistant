package com.example.demo.service;

import com.example.demo.model.Brand;
import com.example.demo.repository.BrandRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

@Service
@RequiredArgsConstructor
public class BrandCatalogService {

    private final BrandRepository brandRepository;

    @Value("${app.data.root:}")
    private String configuredDataRoot;

    private static final Pattern LOGO_SUFFIX = Pattern.compile("(?i)([-_\\s]*logo)$");
    private static final Pattern SIZE_SUFFIX = Pattern.compile("(?i)([-_\\s]*\\d+x\\d+)$");

    public Map<String, Object> importLogoBrands() throws IOException {
        Path logoRoot = resolveDataRoot().resolve("LOGO");
        if (!Files.isDirectory(logoRoot)) {
            return Map.of(
                    "status", "skipped",
                    "message", "LOGO directory not found",
                    "path", logoRoot.toString()
            );
        }

        Map<String, LogoBrand> candidates = new LinkedHashMap<>();
        try (var stream = Files.walk(logoRoot)) {
            stream.filter(Files::isRegularFile)
                    .filter(this::isImage)
                    .sorted(Comparator.comparingInt((Path path) -> path.getNameCount()).thenComparing(Path::toString))
                    .forEach(path -> {
                        LogoBrand candidate = parseLogo(path, logoRoot);
                        if (candidate == null || isBlank(candidate.name())) {
                            return;
                        }
                        candidates.putIfAbsent(normalizeName(candidate.name()), candidate);
                    });
        }

        int created = 0;
        int updated = 0;
        Instant now = Instant.now();
        for (LogoBrand candidate : candidates.values()) {
            String normalized = normalizeName(candidate.name());
            Brand brand = brandRepository.findByNormalizedName(normalized)
                    .or(() -> brandRepository.findByName(candidate.name()))
                    .orElse(null);

            if (brand == null) {
                brand = Brand.builder()
                        .name(candidate.name())
                        .normalizedName(normalized)
                        .createdAt(now)
                        .build();
                created++;
            } else {
                updated++;
            }

            brand.setLogoFilename(candidate.filename());
            brand.setLogoUrl("/api/files/logos/" + encode(candidate.filename()));
            brand.setCategoryCode(candidate.categoryCode());
            brand.setAliases(candidate.aliases());
            brand.setUpdatedAt(now);
            brandRepository.save(brand);
        }

        return Map.of(
                "status", "ok",
                "path", logoRoot.toString(),
                "totalScanned", candidates.size(),
                "created", created,
                "updated", updated
        );
    }

    public Optional<Brand> findBrand(String brandName) {
        if (isBlank(brandName)) {
            return Optional.empty();
        }
        String normalized = normalizeName(brandName);
        Optional<Brand> direct = brandRepository.findByNormalizedName(normalized);
        if (direct.isPresent()) {
            return direct;
        }

        String aliasNormalized = normalizeName(toCanonicalAlias(brandName));
        if (!aliasNormalized.equals(normalized)) {
            direct = brandRepository.findByNormalizedName(aliasNormalized);
            if (direct.isPresent()) {
                return direct;
            }
        }

        return brandRepository.findAll().stream()
                .filter(brand -> brandMatches(brand, normalized, aliasNormalized))
                .findFirst();
    }

    private Path resolveDataRoot() {
        List<Path> candidates = new ArrayList<>();
        if (!isBlank(configuredDataRoot)) {
            candidates.add(Paths.get(configuredDataRoot));
        }

        Path userDir = Paths.get(System.getProperty("user.dir")).toAbsolutePath();
        candidates.add(userDir.resolve("data"));
        Path parent = userDir.getParent();
        if (parent != null) {
            candidates.add(parent.resolve("data"));
        }
        candidates.add(Paths.get("data").toAbsolutePath());
        candidates.add(Paths.get("..", "data").toAbsolutePath());

        for (Path candidate : candidates) {
            if (Files.isDirectory(candidate.resolve("LOGO"))) {
                return candidate;
            }
        }
        for (Path candidate : candidates) {
            if (Files.isDirectory(candidate)) {
                return candidate;
            }
        }
        return candidates.get(0);
    }

    private boolean brandMatches(Brand brand, String normalized, String aliasNormalized) {
        if (brand == null) {
            return false;
        }
        if (normalized.equals(brand.getNormalizedName()) || aliasNormalized.equals(brand.getNormalizedName())) {
            return true;
        }
        if (brand.getAliases() == null) {
            return false;
        }
        for (String alias : brand.getAliases()) {
            String current = normalizeName(alias);
            if (normalized.equals(current) || aliasNormalized.equals(current)) {
                return true;
            }
        }
        return false;
    }

    private LogoBrand parseLogo(Path path, Path logoRoot) {
        String filename = path.getFileName().toString();
        String stem = stripExtension(filename);
        stem = SIZE_SUFFIX.matcher(stem).replaceFirst("");
        stem = LOGO_SUFFIX.matcher(stem).replaceFirst("");
        stem = stem.replace('_', ' ').replace('-', ' ').trim();
        if (isBlank(stem)) {
            return null;
        }

        String canonicalName = toCanonicalAlias(stem);
        String categoryCode = resolveCategory(path, logoRoot);
        List<String> aliases = new ArrayList<>();
        aliases.add(stem);
        aliases.add(canonicalName);
        aliases.addAll(extraAliases(canonicalName));

        return new LogoBrand(canonicalName, filename, categoryCode, aliases.stream()
                .filter(alias -> !isBlank(alias))
                .distinct()
                .toList());
    }

    private String resolveCategory(Path path, Path logoRoot) {
        Path relative = logoRoot.relativize(path);
        String text = relative.toString().replace('\\', '/').toLowerCase(Locale.ROOT);
        if (text.contains("camera")) {
            return "camera";
        }
        if (text.contains("airconditioner") || text.contains("jiadian")) {
            return "jiadian";
        }
        return null;
    }

    private List<String> extraAliases(String canonicalName) {
        return switch (normalizeName(canonicalName)) {
            case "haier" -> List.of("\u6d77\u5c14");
            case "midea" -> List.of("\u7f8e\u7684");
            case "gree" -> List.of("\u683c\u529b");
            case "hisense" -> List.of("\u6d77\u4fe1");
            case "hitachi" -> List.of("\u65e5\u7acb");
            case "samsung" -> List.of("\u4e09\u661f");
            case "xiaomi" -> List.of("\u5c0f\u7c73", "\u7ea2\u7c73", "Redmi");
            case "mitsubishielectric" -> List.of("Mitsubishi-Electric", "\u4e09\u83f1\u7535\u673a");
            case "hasselblad" -> List.of("Hasselblad");
            case "phaseone" -> List.of("Phase One");
            default -> List.of();
        };
    }

    private String toCanonicalAlias(String value) {
        if (isBlank(value)) {
            return "";
        }
        String text = value.trim();
        String normalized = normalizeName(text);
        return switch (normalized) {
            case "\u6d77\u5c14" -> "Haier";
            case "\u7f8e\u7684" -> "Midea";
            case "\u683c\u529b" -> "Gree";
            case "\u6d77\u4fe1" -> "Hisense";
            case "\u65e5\u7acb" -> "Hitachi";
            case "\u4e09\u661f" -> "Samsung";
            case "\u5c0f\u7c73", "\u7ea2\u7c73", "redmi" -> "Xiaomi";
            case "mitsubishielectric" -> "Mitsubishi Electric";
            case "hasselblad" -> "Hasselblad";
            case "phaseone" -> "Phase One";
            default -> text;
        };
    }

    private boolean isImage(Path path) {
        String name = path.getFileName().toString().toLowerCase(Locale.ROOT);
        return name.endsWith(".png") || name.endsWith(".jpg") || name.endsWith(".jpeg") || name.endsWith(".webp");
    }

    private static String stripExtension(String filename) {
        int dot = filename.lastIndexOf('.');
        return dot > 0 ? filename.substring(0, dot) : filename;
    }

    public static String normalizeName(String value) {
        if (value == null) {
            return "";
        }
        return value.trim()
                .toLowerCase(Locale.ROOT)
                .replaceAll("[\\s_\\-]+", "")
                .replaceAll("[^\\p{IsAlphabetic}\\p{IsDigit}\\p{IsHan}]+", "");
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }

    private static boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }

    private record LogoBrand(String name, String filename, String categoryCode, List<String> aliases) {
    }
}
