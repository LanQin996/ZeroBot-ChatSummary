package cn.zerobot.chatsummary;

import org.slf4j.Logger;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageOutputStream;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Pattern;

final class ImageCacheService implements AutoCloseable {
    private static final Pattern SAFE_PATH_PART = Pattern.compile("[^a-zA-Z0-9._-]");
    private static final DateTimeFormatter TIME_FORMAT = DateTimeFormatter.ofPattern("HH:mm");
    private static final int MAX_DOWNLOAD_BYTES = 8 * 1024 * 1024;
    private static final int MAX_THUMBNAIL_SIZE = 640;

    private final Settings settings;
    private final Path root;
    private final ZoneId zoneId;
    private final Logger logger;
    private final HttpClient client;
    private final ExecutorService executor;
    private final Map<String, Optional<BufferedImage>> memoryCache = new ConcurrentHashMap<>();
    private final Set<String> scheduled = ConcurrentHashMap.newKeySet();

    ImageCacheService(Settings settings, Path root, ZoneId zoneId, Logger logger) throws IOException {
        this.settings = settings;
        this.root = root;
        this.zoneId = zoneId;
        this.logger = logger;
        this.client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(timeoutSeconds()))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
        this.executor = Executors.newFixedThreadPool(2, task -> {
            Thread thread = new Thread(task, "chat-summary-image-cache");
            thread.setDaemon(true);
            return thread;
        });
        Files.createDirectories(root);
    }

    void cacheAsync(RecordedMessage message) {
        if (!enabled() || message == null || SummaryText.safeList(message.images()).isEmpty()) {
            return;
        }
        for (ImageRef ref : refs(List.of(message), Integer.MAX_VALUE)) {
            Path file = fileFor(ref.message(), ref.index());
            String key = file.toAbsolutePath().normalize().toString();
            if (!scheduled.add(key)) {
                continue;
            }
            executor.submit(() -> {
                try {
                    image(ref.message(), ref.index(), ref.attachment());
                } finally {
                    scheduled.remove(key);
                }
            });
        }
    }

    void warmup(List<RecordedMessage> messages) {
        if (!enabled()) {
            return;
        }
        int limit = Math.max(settings.getImagePreviewLimit(), settings.getAiImageInputLimit());
        for (ImageRef ref : refs(messages, Math.max(1, limit))) {
            image(ref.message(), ref.index(), ref.attachment());
        }
    }

    List<ReportImage> reportImages(ReportData data) {
        if (!enabled() || data == null) {
            return List.of();
        }
        int limit = Math.max(0, settings.getImagePreviewLimit());
        if (limit == 0) {
            return List.of();
        }
        List<ReportImage> result = new ArrayList<>();
        for (ImageRef ref : refs(data.messages(), limit)) {
            Optional<BufferedImage> image = image(ref.message(), ref.index(), ref.attachment());
            image.ifPresent(buffered -> result.add(new ReportImage(
                    buffered,
                    ref.message().displayName(),
                    ref.message().userId(),
                    ref.message().time(),
                    caption(ref.message(), ref.attachment())
            )));
        }
        return result;
    }

    List<AiImage> aiImages(ReportData data) {
        if (!enabled() || data == null || !settings.isAiImageInputEnabled()) {
            return List.of();
        }
        int limit = Math.max(0, settings.getAiImageInputLimit());
        if (limit == 0) {
            return List.of();
        }
        List<AiImage> result = new ArrayList<>();
        int number = 1;
        for (ImageRef ref : refs(data.messages(), limit)) {
            Optional<BufferedImage> image = image(ref.message(), ref.index(), ref.attachment());
            if (image.isEmpty()) {
                continue;
            }
            try {
                String label = "#" + number + " ["
                        + LocalDateTime.ofInstant(ref.message().time(), zoneId).format(TIME_FORMAT)
                        + "] " + ref.message().displayName() + "：" + caption(ref.message(), ref.attachment());
                result.add(new AiImage(label, toDataUrl(image.get())));
                number++;
            } catch (Exception e) {
                logger.debug("Failed to encode cached image for AI input, groupId={}, messageId={}",
                        ref.message().groupId(), ref.message().messageId(), e);
            }
        }
        return result;
    }

    Optional<BufferedImage> image(RecordedMessage message, int index, ImageAttachment attachment) {
        if (!enabled() || message == null || attachment == null) {
            return Optional.empty();
        }
        Path file = fileFor(message, index);
        String key = file.toAbsolutePath().normalize().toString();
        return memoryCache.computeIfAbsent(key, ignored -> loadOrDownload(file, attachment));
    }

    void prune() {
        if (!Files.isDirectory(root)) {
            return;
        }
        Instant cutoff = Instant.now().minus(Duration.ofDays(Math.max(1, settings.getImageCacheDays())));
        try (var paths = Files.walk(root)) {
            paths.sorted(Comparator.reverseOrder()).forEach(path -> prunePath(path, cutoff));
        } catch (Exception e) {
            logger.debug("Failed to prune image cache", e);
        }
        memoryCache.clear();
    }

    @Override
    public void close() {
        executor.shutdownNow();
    }

    private Optional<BufferedImage> loadOrDownload(Path file, ImageAttachment attachment) {
        try {
            BufferedImage cached = readCached(file).orElse(null);
            if (cached != null) {
                return Optional.of(cached);
            }
            Optional<BufferedImage> downloaded = loadSource(attachment);
            if (downloaded.isEmpty()) {
                return Optional.empty();
            }
            BufferedImage thumbnail = thumbnail(downloaded.get());
            Files.createDirectories(file.getParent());
            writeJpeg(thumbnail, file);
            return Optional.of(thumbnail);
        } catch (Exception e) {
            logger.debug("Failed to load group image attachment, file={}, url={}", attachment.file(), attachment.url(), e);
            return Optional.empty();
        }
    }

    private Optional<BufferedImage> readCached(Path file) throws IOException {
        if (!Files.isRegularFile(file)) {
            return Optional.empty();
        }
        BufferedImage image = ImageIO.read(file.toFile());
        return image == null ? Optional.empty() : Optional.of(image);
    }

    private Optional<BufferedImage> loadSource(ImageAttachment attachment) throws IOException, InterruptedException {
        String url = sourceUrl(attachment);
        if (url.isBlank()) {
            return Optional.empty();
        }
        if (url.startsWith("data:image/")) {
            return readDataUrl(url);
        }
        URI uri = URI.create(url);
        String scheme = SummaryText.nullTo(uri.getScheme(), "").toLowerCase();
        if (!("http".equals(scheme) || "https".equals(scheme)) || uri.getHost() == null) {
            return Optional.empty();
        }
        HttpRequest request = HttpRequest.newBuilder(uri)
                .timeout(Duration.ofSeconds(timeoutSeconds()))
                .header("User-Agent", "ZeroBot-ChatSummary/1.0")
                .GET()
                .build();
        HttpResponse<byte[]> response = client.send(request, HttpResponse.BodyHandlers.ofByteArray());
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            return Optional.empty();
        }
        byte[] body = response.body();
        if (body == null || body.length == 0 || body.length > MAX_DOWNLOAD_BYTES) {
            return Optional.empty();
        }
        try (ByteArrayInputStream input = new ByteArrayInputStream(body)) {
            BufferedImage image = ImageIO.read(input);
            return image == null ? Optional.empty() : Optional.of(image);
        }
    }

    private String sourceUrl(ImageAttachment attachment) {
        String url = attachment.url();
        if (!url.isBlank()) {
            return url;
        }
        String file = attachment.file();
        if (file.startsWith("http://") || file.startsWith("https://") || file.startsWith("data:image/")) {
            return file;
        }
        return "";
    }

    private Optional<BufferedImage> readDataUrl(String value) throws IOException {
        int comma = value.indexOf(',');
        if (comma < 0 || !value.substring(0, comma).contains(";base64")) {
            return Optional.empty();
        }
        byte[] bytes = Base64.getDecoder().decode(value.substring(comma + 1));
        if (bytes.length == 0 || bytes.length > MAX_DOWNLOAD_BYTES) {
            return Optional.empty();
        }
        try (ByteArrayInputStream input = new ByteArrayInputStream(bytes)) {
            BufferedImage image = ImageIO.read(input);
            return image == null ? Optional.empty() : Optional.of(image);
        }
    }

    private BufferedImage thumbnail(BufferedImage source) {
        int width = source.getWidth();
        int height = source.getHeight();
        double scale = Math.min(1.0, MAX_THUMBNAIL_SIZE / (double) Math.max(width, height));
        int targetW = Math.max(1, (int) Math.round(width * scale));
        int targetH = Math.max(1, (int) Math.round(height * scale));
        BufferedImage target = new BufferedImage(targetW, targetH, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = target.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        g.setColor(new Color(42, 39, 41));
        g.fillRect(0, 0, targetW, targetH);
        g.drawImage(source, 0, 0, targetW, targetH, null);
        g.dispose();
        return target;
    }

    private void writeJpeg(BufferedImage image, Path file) throws IOException {
        Iterator<ImageWriter> writers = ImageIO.getImageWritersByFormatName("jpg");
        if (!writers.hasNext()) {
            ImageIO.write(image, "jpg", file.toFile());
            return;
        }
        ImageWriter writer = writers.next();
        ImageWriteParam param = writer.getDefaultWriteParam();
        if (param.canWriteCompressed()) {
            param.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
            param.setCompressionQuality(0.88f);
        }
        try (ImageOutputStream output = ImageIO.createImageOutputStream(file.toFile())) {
            writer.setOutput(output);
            writer.write(null, new IIOImage(image, null, null), param);
        } finally {
            writer.dispose();
        }
    }

    private String toDataUrl(BufferedImage image) throws IOException {
        try (ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            writeJpeg(image, output);
            return "data:image/jpeg;base64," + Base64.getEncoder().encodeToString(output.toByteArray());
        }
    }

    private void writeJpeg(BufferedImage image, ByteArrayOutputStream output) throws IOException {
        Iterator<ImageWriter> writers = ImageIO.getImageWritersByFormatName("jpg");
        if (!writers.hasNext()) {
            ImageIO.write(image, "jpg", output);
            return;
        }
        ImageWriter writer = writers.next();
        ImageWriteParam param = writer.getDefaultWriteParam();
        if (param.canWriteCompressed()) {
            param.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
            param.setCompressionQuality(0.82f);
        }
        try (ImageOutputStream imageOutput = ImageIO.createImageOutputStream(output)) {
            writer.setOutput(imageOutput);
            writer.write(null, new IIOImage(image, null, null), param);
        } finally {
            writer.dispose();
        }
    }

    private List<ImageRef> refs(List<RecordedMessage> messages, int limit) {
        List<ImageRef> refs = new ArrayList<>();
        for (RecordedMessage message : SummaryText.safeList(messages).stream()
                .sorted(Comparator.comparing(RecordedMessage::time))
                .toList()) {
            List<ImageAttachment> images = SummaryText.safeList(message.images());
            for (int i = 0; i < images.size(); i++) {
                ImageAttachment attachment = images.get(i);
                if (attachment.hasSource()) {
                    refs.add(new ImageRef(message, i, attachment));
                }
            }
        }
        if (refs.size() > limit) {
            refs = new ArrayList<>(refs.subList(refs.size() - limit, refs.size()));
        }
        return refs;
    }

    private String caption(RecordedMessage message, ImageAttachment attachment) {
        String description = attachment.description();
        if (!"图片".equals(description)) {
            return SummaryText.trim(description, 18);
        }
        String cleaned = SummaryText.nullTo(message.text(), "")
                .replaceAll("\\[图片[^\\]]*\\]", "")
                .replace("[表情]", "")
                .trim();
        return cleaned.isBlank() ? "图片" : SummaryText.trim(cleaned, 18);
    }

    private void prunePath(Path path, Instant cutoff) {
        if (path.equals(root)) {
            return;
        }
        try {
            if (Files.isRegularFile(path) && Files.getLastModifiedTime(path).toInstant().isBefore(cutoff)) {
                Files.deleteIfExists(path);
                return;
            }
            if (Files.isDirectory(path)) {
                try (var children = Files.list(path)) {
                    if (!children.findAny().isPresent()) {
                        Files.deleteIfExists(path);
                    }
                }
            }
        } catch (Exception e) {
            logger.debug("Failed to prune cached group image {}", path, e);
        }
    }

    private Path fileFor(RecordedMessage message, int index) {
        LocalDate date = LocalDate.ofInstant(message.time(), zoneId);
        return root.resolve(safePart(message.groupId()))
                .resolve(date.toString())
                .resolve(message.messageId() + "-" + index + ".jpg");
    }

    private String safePart(String value) {
        String safe = SAFE_PATH_PART.matcher(SummaryText.nullTo(value, "unknown")).replaceAll("_");
        return safe.isBlank() ? "unknown" : safe;
    }

    private boolean enabled() {
        return settings.isImageCacheEnabled();
    }

    private int timeoutSeconds() {
        return SummaryText.clamp(settings.getImageDownloadTimeoutSeconds(), 1, 60);
    }

    record ReportImage(BufferedImage image, String sender, String userId, Instant time, String caption) {
    }

    record AiImage(String label, String dataUrl) {
    }

    private record ImageRef(RecordedMessage message, int index, ImageAttachment attachment) {
    }
}
