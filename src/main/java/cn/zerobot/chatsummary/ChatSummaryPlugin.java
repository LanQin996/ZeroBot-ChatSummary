package cn.zerobot.chatsummary;

import cn.zerobot.api.BotContext;
import cn.zerobot.api.BotPlugin;
import cn.zerobot.api.command.CommandContext;
import cn.zerobot.api.event.GroupMessageEvent;
import cn.zerobot.api.message.MessageSegment;
import com.fasterxml.jackson.databind.JsonNode;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * ZeroBot plugin entrypoint. Message analysis and image rendering live in
 * dedicated classes so this class stays focused on bot integration.
 */
public class ChatSummaryPlugin implements BotPlugin {
    private static final DateTimeFormatter FILE_TIME = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");

    private final Map<String, GroupBuffer> buffers = new ConcurrentHashMap<>();
    private BotContext context;
    private Settings settings;
    private ZoneId zoneId;
    private MessageStore messageStore;
    private AvatarService avatarService;
    private ImageCacheService imageCacheService;
    private ScheduledExecutorService cleanupExecutor;
    private boolean legacyCommandFallback;

    @Override
    public void onLoad(BotContext context) throws Exception {
        this.context = context;
        saveDefaultConfigIfSupported(context);
        this.settings = context.loadConfig("config.yml", Settings.class);
        this.zoneId = SummaryText.resolveZone(settings.getTimeZone());
        Files.createDirectories(reportsDir());
        this.messageStore = createMessageStore(context);
        this.avatarService = createAvatarService(context);
        this.imageCacheService = createImageCacheService(context);
        startCleanupTask();

        this.legacyCommandFallback = !registerSummaryCommand(context);
        context.onGroupMessage(this::handleGroupMessage);
        context.logger().info("ChatSummaryPlugin loaded, commandFallback={}", legacyCommandFallback);
    }

    private void saveDefaultConfigIfSupported(BotContext context) throws Exception {
        try {
            context.saveDefaultConfig();
        } catch (UnsupportedOperationException e) {
            context.logger().debug("Current ZeroBot runtime does not support default config saving");
        }
    }

    @Override
    public void onUnload() {
        if (cleanupExecutor != null) {
            cleanupExecutor.shutdownNow();
            cleanupExecutor = null;
        }
        if (imageCacheService != null) {
            imageCacheService.close();
            imageCacheService = null;
        }
        buffers.clear();
        if (context != null) {
            context.logger().info("ChatSummaryPlugin unloaded");
        }
    }

    private void handleGroupMessage(GroupMessageEvent event) {
        String rawText = SummaryText.cleanText(event.rawMessage());
        if (legacyCommandFallback) {
            CommandRequest request = parseCommand(rawText);
            if (request != null) {
                handleSummaryCommand(event, request);
                return;
            }
        }

        if (settings.isIgnoreCommandLikeMessages() && looksLikeCommand(rawText)) {
            return;
        }

        RecordedMessage message = toRecordedMessage(event);
        if (message.text().isBlank() && message.imageCount() == 0 && message.fileCount() == 0) {
            return;
        }

        buffers.computeIfAbsent(message.groupId(), key -> new GroupBuffer())
                .add(message, retentionCutoff(), maxMessagesPerGroup());
        appendStoredMessage(message);
        if (imageCacheService != null) {
            imageCacheService.cacheAsync(message);
        }
    }

    private boolean registerSummaryCommand(BotContext context) {
        try {
            context.registerCommand("群总结", this::handleRegisteredCommand);
            return true;
        } catch (UnsupportedOperationException e) {
            context.logger().warn("Current ZeroBot runtime does not support command registration, using legacy message parsing");
            return false;
        }
    }

    private boolean handleRegisteredCommand(CommandContext command) {
        if (!(command.event() instanceof GroupMessageEvent event)) {
            context.reply(command.event(), List.of(MessageSegment.text("群聊总结只能在群聊中使用。")));
            return true;
        }
        handleSummaryCommand(event, new CommandRequest(command.label(), command.joinedArgs(0)));
        return true;
    }

    private void handleSummaryCommand(GroupMessageEvent event, CommandRequest request) {
        if (!context.hasPermission(event, settings.getReportPermission(), settings.isReportDefaultAllowed())) {
            replyText(event, settings.getNoPermissionReply());
            return;
        }

        ReportWindow window = resolveWindow(request);
        if (window == null) {
            replyText(event, usageText());
            return;
        }

        String groupId = event.groupId();
        List<RecordedMessage> messages = loadMessages(groupId, window);
        if (messages.isEmpty()) {
            replyText(event, "这段时间还没有可用于生成总结的群聊记录。");
            return;
        }

        if (settings.isSendGeneratingReply()) {
            replyText(event, "正在生成群聊总结报告...");
        }

        try {
            String groupName = resolveGroupName(groupId);
            ReportData data = new ReportAnalyzer(settings, zoneId).analyze(groupId, groupName, window, messages);
            if (imageCacheService != null) {
                imageCacheService.warmup(data.messages());
            }
            data = new AiSummaryService(settings, zoneId, context.logger(), imageCacheService)
                    .enhance(data)
                    .map(data::withEnhancement)
                    .orElse(data);
            if (avatarService != null) {
                avatarService.warmup(data);
            }
            BufferedImage image = new ReportRenderer(settings, zoneId, avatarService, imageCacheService).render(data);
            Path output = reportsDir().resolve("chat-summary-" + groupId + "-"
                    + LocalDateTime.now(zoneId).format(FILE_TIME) + ".png");
            ImageIO.write(image, "png", output.toFile());
            context.replyImage(event, output).exceptionally(error -> {
                context.logger().warn("Failed to send chat summary image", error);
                return null;
            });
        } catch (Exception e) {
            context.logger().warn("Failed to generate chat summary report", e);
            replyText(event, "生成群聊总结失败：" + e.getMessage());
        }
    }

    private RecordedMessage toRecordedMessage(GroupMessageEvent event) {
        JsonNode raw = event.raw();
        JsonNode sender = raw.path("sender");
        String userId = SummaryText.nullTo(event.userId(), "unknown");
        String name = SummaryText.firstNotBlank(
                sender.path("card").asText(null),
                sender.path("nickname").asText(null),
                sender.path("title").asText(null),
                userId
        );
        String role = sender.path("role").asText("");
        Instant time = raw.has("time") ? Instant.ofEpochSecond(raw.path("time").asLong()) : Instant.now();
        MessageContent content = extractContent(event);
        return new RecordedMessage(
                event.groupId(),
                userId,
                name,
                role,
                content.text(),
                time,
                event.messageId(),
                content.imageCount(),
                content.atCount(),
                content.faceCount(),
                content.fileCount(),
                content.images()
        );
    }

    private MessageContent extractContent(GroupMessageEvent event) {
        JsonNode message = event.message();
        StringBuilder text = new StringBuilder();
        List<ImageAttachment> images = new ArrayList<>();
        int imageCount = 0;
        int atCount = 0;
        int faceCount = 0;
        int fileCount = 0;

        if (message != null && message.isArray()) {
            for (JsonNode segment : message) {
                String type = segment.path("type").asText("");
                JsonNode data = segment.path("data");
                switch (type) {
                    case "text" -> text.append(' ').append(data.path("text").asText(""));
                    case "at" -> {
                        atCount++;
                        text.append(" @").append(SummaryText.firstNotBlank(
                                data.path("qq").asText(null),
                                data.path("user_id").asText(null),
                                ""
                        ));
                    }
                    case "image" -> {
                        imageCount++;
                        ImageAttachment image = new ImageAttachment(
                                data.path("file").asText(""),
                                SummaryText.firstNotBlank(data.path("file_id").asText(null), data.path("fileId").asText(null)),
                                data.path("url").asText(""),
                                data.path("summary").asText(""),
                                SummaryText.firstNotBlank(data.path("sub_type").asText(null), data.path("subType").asText(null))
                        );
                        images.add(image);
                        text.append(" [图片]");
                        if (!image.summary().isBlank()) {
                            text.append(' ').append(image.summary());
                        }
                    }
                    case "face", "mface", "emoji" -> {
                        faceCount++;
                        text.append(" [表情]");
                    }
                    case "file" -> {
                        fileCount++;
                        text.append(" [文件]");
                    }
                    default -> {
                        if (data.has("text")) {
                            text.append(' ').append(data.path("text").asText(""));
                        }
                    }
                }
            }
        } else {
            text.append(SummaryText.nullTo(event.rawMessage(), ""));
        }

        String rawMessage = SummaryText.nullTo(event.rawMessage(), "");
        String cleaned = SummaryText.cleanText(text.toString());
        if (imageCount == 0) {
            imageCount = SummaryText.countMatches(SummaryText.CQ_IMAGE_PATTERN, rawMessage);
        }
        if (atCount == 0) {
            atCount = SummaryText.countMatches(SummaryText.CQ_AT_PATTERN, rawMessage);
        }
        if (faceCount == 0) {
            faceCount = SummaryText.countMatches(SummaryText.CQ_FACE_PATTERN, rawMessage);
        }
        if (fileCount == 0) {
            fileCount = SummaryText.countMatches(SummaryText.CQ_FILE_PATTERN, rawMessage);
        }
        return new MessageContent(cleaned, images, imageCount, atCount, faceCount, fileCount);
    }

    private CommandRequest parseCommand(String rawText) {
        if (rawText == null || rawText.isBlank()) {
            return null;
        }
        String text = rawText.trim();
        for (String command : SummaryText.safeList(settings.getCommands())) {
            if (command == null || command.isBlank()) {
                continue;
            }
            String normalizedCommand = command.trim();
            if (text.equals(normalizedCommand)) {
                return new CommandRequest(normalizedCommand, "");
            }
            if (text.startsWith(normalizedCommand + " ")) {
                return new CommandRequest(normalizedCommand, text.substring(normalizedCommand.length()).trim());
            }
        }
        return null;
    }

    private ReportWindow resolveWindow(CommandRequest request) {
        Instant now = Instant.now();
        String args = request.args();
        if (args == null || args.isBlank()) {
            int hours = SummaryText.clamp(settings.getDefaultHours(), 1, maxWindowHours());
            return new ReportWindow(now.minus(Duration.ofHours(hours)), now, "最近 " + hours + " 小时");
        }

        String token = args.trim().split("\\s+")[0].toLowerCase();
        if ("today".equals(token) || "day".equals(token) || "今日".equals(token) || "今天".equals(token)) {
            LocalDate today = LocalDate.now(zoneId);
            return new ReportWindow(today.atStartOfDay(zoneId).toInstant(), now, "今日");
        }
        if ("yesterday".equals(token) || "昨天".equals(token)) {
            LocalDate today = LocalDate.now(zoneId);
            return new ReportWindow(
                    today.minusDays(1).atStartOfDay(zoneId).toInstant(),
                    today.atStartOfDay(zoneId).toInstant(),
                    "昨天"
            );
        }

        Integer hours = parseHours(token);
        if (hours == null || hours < 1) {
            return null;
        }
        hours = SummaryText.clamp(hours, 1, maxWindowHours());
        return new ReportWindow(now.minus(Duration.ofHours(hours)), now, "最近 " + hours + " 小时");
    }

    private Integer parseHours(String value) {
        try {
            if (value.endsWith("小时")) {
                return Integer.parseInt(value.substring(0, value.length() - 2));
            }
            if (value.endsWith("h")) {
                return Integer.parseInt(value.substring(0, value.length() - 1));
            }
            if (value.endsWith("天")) {
                return Integer.parseInt(value.substring(0, value.length() - 1)) * 24;
            }
            if (value.endsWith("d")) {
                return Integer.parseInt(value.substring(0, value.length() - 1)) * 24;
            }
            return Integer.parseInt(value);
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private String resolveGroupName(String groupId) {
        try {
            JsonNode data = context.callAction("get_group_info", Map.of("group_id", groupId))
                    .get(2, TimeUnit.SECONDS)
                    .data();
            String name = data == null ? null : data.path("group_name").asText(null);
            return SummaryText.firstNotBlank(name, "群 " + groupId);
        } catch (Exception e) {
            context.logger().debug("Failed to query group name for {}", groupId, e);
            return "群 " + groupId;
        }
    }

    private void replyText(GroupMessageEvent event, String text) {
        context.reply(event, List.of(MessageSegment.text(text)));
    }

    private String usageText() {
        String firstCommand = SummaryText.safeList(settings.getCommands()).isEmpty()
                ? "/群总结"
                : settings.getCommands().get(0);
        return "用法：" + firstCommand + " [小时数|今日|昨天]，例如：" + firstCommand + " 24";
    }

    private Path reportsDir() {
        return context.dataDir().resolve("reports");
    }

    private Path messagesDir() {
        return context.dataDir().resolve("messages");
    }

    private Path avatarsDir() {
        return context.dataDir().resolve("avatars");
    }

    private Path imagesDir() {
        return context.dataDir().resolve("images");
    }

    private MessageStore createMessageStore(BotContext context) {
        if (!settings.isStorageEnabled()) {
            return null;
        }
        try {
            MessageStore store = new JsonMessageStore(messagesDir(), zoneId);
            store.prune(storageCutoff());
            return store;
        } catch (Exception e) {
            context.logger().warn("Failed to initialize chat message storage, using memory buffer only", e);
            return null;
        }
    }

    private AvatarService createAvatarService(BotContext context) {
        if (!settings.isAvatarEnabled()) {
            return null;
        }
        try {
            AvatarService service = new AvatarService(settings, avatarsDir(), context.logger());
            service.prune();
            return service;
        } catch (Exception e) {
            context.logger().warn("Failed to initialize avatar cache, using fallback avatars", e);
            return null;
        }
    }

    private ImageCacheService createImageCacheService(BotContext context) {
        if (!settings.isImageCacheEnabled()) {
            return null;
        }
        try {
            ImageCacheService service = new ImageCacheService(settings, imagesDir(), zoneId, context.logger());
            service.prune();
            return service;
        } catch (Exception e) {
            context.logger().warn("Failed to initialize group image cache, image preview will be disabled", e);
            return null;
        }
    }

    private void startCleanupTask() {
        int intervalMinutes = Math.max(1, settings.getCleanupIntervalMinutes());
        cleanupExecutor = Executors.newSingleThreadScheduledExecutor(task -> {
            Thread thread = new Thread(task, "chat-summary-cleanup");
            thread.setDaemon(true);
            return thread;
        });
        cleanupExecutor.scheduleWithFixedDelay(this::cleanupCaches, intervalMinutes, intervalMinutes, TimeUnit.MINUTES);
    }

    private void cleanupCaches() {
        try {
            cleanupMemoryBuffers();
            if (messageStore != null) {
                messageStore.prune(storageCutoff());
            }
            if (avatarService != null) {
                avatarService.prune();
            }
            if (imageCacheService != null) {
                imageCacheService.prune();
            }
        } catch (Exception e) {
            if (context != null) {
                context.logger().warn("Failed to clean chat summary caches", e);
            }
        }
    }

    private void cleanupMemoryBuffers() {
        Instant cutoff = retentionCutoff();
        int maxSize = maxMessagesPerGroup();
        buffers.entrySet().removeIf(entry -> entry.getValue().pruneAndIsEmpty(cutoff, maxSize));
    }

    private void appendStoredMessage(RecordedMessage message) {
        if (messageStore == null) {
            return;
        }
        try {
            messageStore.append(message);
        } catch (Exception e) {
            context.logger().warn("Failed to persist chat message, groupId={}, messageId={}",
                    message.groupId(), message.messageId(), e);
        }
    }

    private List<RecordedMessage> loadMessages(String groupId, ReportWindow window) {
        List<RecordedMessage> hotMessages = hotMessages(groupId, window);
        if (messageStore == null) {
            return hotMessages;
        }
        try {
            List<RecordedMessage> stored = messageStore.query(groupId, window.from(), window.to());
            return mergeMessages(stored, hotMessages);
        } catch (Exception e) {
            context.logger().warn("Failed to query persisted chat messages, groupId={}", groupId, e);
            return hotMessages;
        }
    }

    private List<RecordedMessage> hotMessages(String groupId, ReportWindow window) {
        GroupBuffer buffer = buffers.get(groupId);
        return buffer == null ? List.of() : buffer.snapshot(window.from(), window.to());
    }

    private List<RecordedMessage> mergeMessages(List<RecordedMessage> stored, List<RecordedMessage> hotMessages) {
        Map<String, RecordedMessage> merged = new LinkedHashMap<>();
        for (RecordedMessage message : SummaryText.safeList(stored)) {
            merged.put(messageKey(message), message);
        }
        for (RecordedMessage message : SummaryText.safeList(hotMessages)) {
            merged.putIfAbsent(messageKey(message), message);
        }
        return merged.values().stream()
                .sorted(Comparator.comparing(RecordedMessage::time).thenComparingLong(RecordedMessage::messageId))
                .toList();
    }

    private String messageKey(RecordedMessage message) {
        return message.groupId() + "|" + message.messageId() + "|" + message.time().toEpochMilli();
    }

    private Instant retentionCutoff() {
        int hours = Math.max(settings.getRetentionHours(), maxWindowHours());
        return Instant.now().minus(Duration.ofHours(hours));
    }

    private Instant storageCutoff() {
        int days = Math.max(1, settings.getStorageRetentionDays());
        return Instant.now().minus(Duration.ofDays(days));
    }

    private int maxWindowHours() {
        return Math.max(1, settings.getMaxHours());
    }

    private int maxMessagesPerGroup() {
        return Math.max(100, settings.getMaxMessagesPerGroup());
    }

    private boolean looksLikeCommand(String text) {
        if (text == null || text.isBlank()) {
            return false;
        }
        String trimmed = text.trim();
        return trimmed.startsWith("/") || trimmed.startsWith("!") || trimmed.startsWith("！")
                || trimmed.startsWith(".");
    }
}
