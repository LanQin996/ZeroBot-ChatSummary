package cn.zerobot.chatsummary;

import org.slf4j.Logger;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

final class AvatarService {
    enum Type {
        GROUP,
        USER
    }

    private static final Pattern DIGITS = Pattern.compile("\\d+");
    private static final Pattern SAFE_PATH_PART = Pattern.compile("[^a-zA-Z0-9._-]");

    private final Settings settings;
    private final Path root;
    private final Logger logger;
    private final HttpClient client;
    private final Map<String, Optional<BufferedImage>> memoryCache = new ConcurrentHashMap<>();

    AvatarService(Settings settings, Path root, Logger logger) throws IOException {
        this.settings = settings;
        this.root = root;
        this.logger = logger;
        this.client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(timeoutSeconds()))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
        Files.createDirectories(root);
    }

    void warmup(ReportData data) {
        if (!settings.isAvatarEnabled()) {
            return;
        }
        image(Type.GROUP, data.groupId());
        data.topUsers().stream().limit(Math.max(settings.getTopUserLimit(), settings.getProfileLimit()))
                .forEach(user -> image(Type.USER, user.userId()));
        data.profiles().forEach(profile -> image(Type.USER, profile.userId()));
    }

    Optional<BufferedImage> image(Type type, String id) {
        if (!settings.isAvatarEnabled() || !isUsableId(id)) {
            return Optional.empty();
        }
        String key = type.name() + ":" + id;
        return memoryCache.computeIfAbsent(key, ignored -> loadOrDownload(type, id));
    }

    void prune() {
        try {
            Instant cutoff = Instant.now().minus(Duration.ofDays(Math.max(1, settings.getAvatarCacheDays())));
            pruneDirectory(root.resolve("group"), cutoff);
            pruneDirectory(root.resolve("user"), cutoff);
        } catch (Exception e) {
            logger.debug("Failed to prune avatar cache", e);
        }
    }

    private Optional<BufferedImage> loadOrDownload(Type type, String id) {
        Path file = fileFor(type, id);
        try {
            if (isFresh(file)) {
                BufferedImage cached = ImageIO.read(file.toFile());
                if (cached != null) {
                    return Optional.of(cached);
                }
            }
            Optional<BufferedImage> downloaded = download(type, id);
            if (downloaded.isPresent()) {
                Files.createDirectories(file.getParent());
                ImageIO.write(downloaded.get(), "png", file.toFile());
            }
            return downloaded;
        } catch (Exception e) {
            logger.debug("Failed to load avatar, type={}, id={}", type, id, e);
            return Optional.empty();
        }
    }

    private Optional<BufferedImage> download(Type type, String id) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(avatarUri(type, id))
                .timeout(Duration.ofSeconds(timeoutSeconds()))
                .GET()
                .build();
        HttpResponse<InputStream> response = client.send(request, HttpResponse.BodyHandlers.ofInputStream());
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            return Optional.empty();
        }
        try (InputStream body = response.body()) {
            BufferedImage image = ImageIO.read(body);
            return image == null ? Optional.empty() : Optional.of(image);
        }
    }

    private URI avatarUri(Type type, String id) {
        if (type == Type.GROUP) {
            return URI.create("https://p.qlogo.cn/gh/" + id + "/" + id + "/100");
        }
        return URI.create("https://q1.qlogo.cn/g?b=qq&nk=" + id + "&s=100");
    }

    private boolean isFresh(Path file) throws IOException {
        if (!Files.isRegularFile(file)) {
            return false;
        }
        Instant cutoff = Instant.now().minus(Duration.ofDays(Math.max(1, settings.getAvatarCacheDays())));
        return Files.getLastModifiedTime(file).toInstant().isAfter(cutoff);
    }

    private void pruneDirectory(Path directory, Instant cutoff) throws IOException {
        if (!Files.isDirectory(directory)) {
            return;
        }
        try (var files = Files.list(directory)) {
            files.filter(Files::isRegularFile).forEach(file -> {
                try {
                    if (Files.getLastModifiedTime(file).toInstant().isBefore(cutoff)) {
                        Files.deleteIfExists(file);
                    }
                } catch (Exception e) {
                    logger.debug("Failed to delete expired avatar {}", file, e);
                }
            });
        }
    }

    private Path fileFor(Type type, String id) {
        String folder = type == Type.GROUP ? "group" : "user";
        return root.resolve(folder).resolve(safePart(id) + ".png");
    }

    private String safePart(String value) {
        String safe = SAFE_PATH_PART.matcher(SummaryText.nullTo(value, "unknown")).replaceAll("_");
        return safe.isBlank() ? "unknown" : safe;
    }

    private boolean isUsableId(String id) {
        return id != null && DIGITS.matcher(id).matches();
    }

    private int timeoutSeconds() {
        return SummaryText.clamp(settings.getAvatarDownloadTimeoutSeconds(), 1, 30);
    }
}
