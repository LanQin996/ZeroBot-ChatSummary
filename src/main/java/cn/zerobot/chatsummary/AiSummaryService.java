package cn.zerobot.chatsummary;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

final class AiSummaryService {
    private static final DateTimeFormatter TIME_FORMAT = DateTimeFormatter.ofPattern("HH:mm");
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final Settings settings;
    private final ZoneId zoneId;
    private final Logger logger;
    private final HttpClient client;
    private final ImageCacheService imageCacheService;

    AiSummaryService(Settings settings, ZoneId zoneId, Logger logger) {
        this(settings, zoneId, logger, null);
    }

    AiSummaryService(Settings settings, ZoneId zoneId, Logger logger, ImageCacheService imageCacheService) {
        this.settings = settings;
        this.zoneId = zoneId;
        this.logger = logger;
        this.imageCacheService = imageCacheService;
        this.client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(timeoutSeconds()))
                .build();
    }

    Optional<AiReportEnhancement> enhance(ReportData data) {
        if (!settings.isAiEnabled()) {
            return Optional.empty();
        }

        String apiKey = resolveApiKey();
        if (apiKey.isBlank()) {
            logger.warn("AI chat summary is enabled but no API key was configured");
            return Optional.empty();
        }

        try {
            boolean includeImages = imageCacheService != null && settings.isAiImageInputEnabled();
            Optional<AiReportEnhancement> result = requestEnhancement(data, apiKey, includeImages);
            if (result.isPresent() || !includeImages) {
                return result;
            }
            logger.debug("AI image input was not accepted or returned no report, retrying with text-only context");
            return requestEnhancement(data, apiKey, false);
        } catch (Exception e) {
            logger.warn("AI chat summary request failed", e);
            return Optional.empty();
        }
    }

    private Optional<AiReportEnhancement> requestEnhancement(ReportData data, String apiKey, boolean includeImages) {
        try {
            HttpRequest request = HttpRequest.newBuilder(endpoint())
                    .timeout(Duration.ofSeconds(timeoutSeconds()))
                    .header("Authorization", "Bearer " + apiKey)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(MAPPER.writeValueAsString(requestBody(data, includeImages))))
                    .build();
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                logger.warn("AI chat summary request failed: status={}, body={}",
                        response.statusCode(), SummaryText.trim(response.body(), 240));
                return Optional.empty();
            }
            return parseResponse(response.body(), data);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            logger.warn("AI chat summary request was interrupted", e);
            return Optional.empty();
        } catch (Exception e) {
            logger.warn("AI chat summary request failed", e);
            return Optional.empty();
        }
    }

    private ObjectNode requestBody(ReportData data, boolean includeImages) {
        ObjectNode root = MAPPER.createObjectNode();
        root.put("model", model());
        root.put("max_completion_tokens", SummaryText.clamp(settings.getAiMaxOutputTokens(), 500, 8000));
        if (settings.getAiTemperature() >= 0) {
            root.put("temperature", Math.min(2, settings.getAiTemperature()));
        }
        root.set("response_format", MAPPER.createObjectNode().put("type", "json_object"));

        ArrayNode messages = MAPPER.createArrayNode();
        messages.add(message("system", systemPrompt()));
        messages.add(userMessage(data, includeImages));
        root.set("messages", messages);
        return root;
    }

    private ObjectNode message(String role, String content) {
        ObjectNode node = MAPPER.createObjectNode();
        node.put("role", role);
        node.put("content", content);
        return node;
    }

    private ObjectNode userMessage(ReportData data, boolean includeImages) {
        ObjectNode node = MAPPER.createObjectNode();
        node.put("role", "user");
        List<ImageCacheService.AiImage> images = includeImages ? imageCacheService.aiImages(data) : List.of();
        if (images.isEmpty()) {
            node.put("content", userPrompt(data));
            return node;
        }

        ArrayNode content = MAPPER.createArrayNode();
        ObjectNode text = MAPPER.createObjectNode();
        text.put("type", "text");
        text.put("text", userPrompt(data) + "\n图片输入：以下图片编号与聊天记录中的图片上下文对应，请只根据可见内容谨慎补充图片相关重点。");
        content.add(text);
        for (ImageCacheService.AiImage image : images) {
            ObjectNode label = MAPPER.createObjectNode();
            label.put("type", "text");
            label.put("text", "图片 " + image.label());
            content.add(label);
            ObjectNode imageUrl = MAPPER.createObjectNode();
            imageUrl.put("url", image.dataUrl());
            imageUrl.put("detail", "low");
            ObjectNode imageNode = MAPPER.createObjectNode();
            imageNode.put("type", "image_url");
            imageNode.set("image_url", imageUrl);
            content.add(imageNode);
        }
        node.set("content", content);
        return node;
    }

    private String systemPrompt() {
        return """
                你是一个中文群聊复盘主编。请根据真实聊天记录，策划一张群聊总结长图的核心内容。
                只输出 JSON，不要 Markdown，不要代码块，不要解释。
                必须保留事实边界：不能编造未出现的人、事件、数量、身份或结论。图片只作为上下文补充，无法确认的内容不要写成事实。
                统计数字由系统提供，不能改写；你只负责决定哪些内容最值得展示。
                风格要像群内复盘，清晰、有一点轻松感，抓重点、抓梗、抓转折，但不要攻击、羞辱或泄露敏感信息。
                JSON 字段：
                {
                  "summary": ["2 到 3 段，每段 45 到 95 个中文字符"],
                  "keywords": ["12 到 24 个能代表这段聊天上下文的热词，优先保留专有名词、需求点、共识和梗"],
                  "tags": [{"title":"4 到 8 字奖项名","value":"8 字内结果","description":"16 到 28 字解释"}],
                  "topics": [{"title":"12 到 22 字事件式标题，必须说明发生了什么，不要只写抽象关键词","keywords":["相关热词"],"summary":"70 到 130 个中文字符，说明谁围绕什么问题/事件讨论、有什么观点或结论"}],
                  "profiles": [{"userId":"必须来自输入 candidates","title":"6 字内标签","description":"28 到 55 个中文字符"}],
                  "quotes": [{"name":"原发言人名称","text":"必须来自候选金句，尽量使用原话或轻微清理后的原话"}]
                }
                tags 最多 4，topics 最多 5，profiles 最多 6，quotes 最多 5。
                topics.title 禁止只输出“时间、攻击、机制、分身、主体、图片、AI”这类单词；应该像“分身攻击窗口与机制讨论”“图片生成接口报错与分组切换测试”这样能直接看懂。
                quotes 只选有梗、有观点、有反差、吐槽、总结感或群内语气的句子；不要选配置说明、路径、命令、报错流水账、纯教程句。
                keywords 不要输出“这个、那个、我们、可以、哈哈”等泛词。
                如果聊天内容很少，也要输出精简但真实的报告方案。""";
    }

    private String userPrompt(ReportData data) {
        StringBuilder prompt = new StringBuilder();
        prompt.append("群名：").append(data.groupName()).append('\n');
        prompt.append("时间范围：").append(data.window().label()).append('\n');
        prompt.append("统计：消息 ").append(data.totalMessages())
                .append("，参与 ").append(data.participantCount())
                .append("，文字量 ").append(data.readableChars())
                .append("，图片 ").append(data.imageCount())
                .append("，表情 ").append(data.faceCount())
                .append("，@ ").append(data.atCount())
                .append("，高峰 ").append(SummaryText.formatHour(data.peakHour()))
                .append("，活跃度 ").append(data.activityScore()).append('\n');
        prompt.append("热词：").append(data.topWords().stream()
                .limit(18)
                .map(WordStat::word)
                .collect(Collectors.joining("、"))).append('\n');

        prompt.append("成员候选：\n");
        for (TopUser user : data.topUsers().stream().limit(settings.getProfileLimit()).toList()) {
            prompt.append("- userId=").append(user.userId())
                    .append(", name=").append(user.displayName())
                    .append(", messages=").append(user.count())
                    .append(", topWord=").append(user.topWord())
                    .append('\n');
        }

        prompt.append("本地初稿摘要：\n");
        for (String line : data.summary()) {
            prompt.append("- ").append(line).append('\n');
        }

        prompt.append("本地话题初稿：\n");
        for (Topic topic : data.topics()) {
            prompt.append("- title=").append(topic.title())
                    .append(", count=").append(topic.count())
                    .append(", speakers=").append(String.join("、", topic.speakers()))
                    .append(", keywords=").append(String.join("、", topic.keywords()))
                    .append(", summary=").append(topic.summary())
                    .append('\n');
        }

        prompt.append("可选金句候选：\n");
        for (Quote quote : data.quotes()) {
            prompt.append("- ").append(quote.name()).append("：")
                    .append(SummaryText.trim(quote.text(), settings.getMaxQuoteLength()))
                    .append('\n');
        }

        prompt.append("图片上下文：\n");
        appendImageContext(prompt, data.messages());

        prompt.append("聊天记录，按时间排序，最多 ").append(maxMessages()).append(" 条：\n");
        appendMessages(prompt, data.messages());
        return prompt.toString();
    }

    private void appendImageContext(StringBuilder prompt, List<RecordedMessage> messages) {
        int count = 0;
        for (RecordedMessage message : SummaryText.safeList(messages)) {
            if (SummaryText.safeList(message.images()).isEmpty()) {
                continue;
            }
            String descriptions = message.images().stream()
                    .limit(3)
                    .map(ImageAttachment::description)
                    .collect(Collectors.joining("、"));
            prompt.append("- [").append(LocalDateTime.ofInstant(message.time(), zoneId).format(TIME_FORMAT)).append("] ")
                    .append(message.displayName()).append("(").append(message.userId()).append(")")
                    .append(" 发了 ").append(message.images().size()).append(" 张图片");
            if (!descriptions.isBlank()) {
                prompt.append("：").append(SummaryText.trim(descriptions, 80));
            }
            String nearbyText = SummaryText.nullTo(message.text(), "")
                    .replace("[图片]", "")
                    .trim();
            if (!nearbyText.isBlank()) {
                prompt.append("，配文：").append(SummaryText.trim(nearbyText, 80));
            }
            prompt.append('\n');
            count++;
            if (count >= 40) {
                break;
            }
        }
        if (count == 0) {
            prompt.append("- 无可用图片上下文\n");
        }
    }

    private void appendMessages(StringBuilder prompt, List<RecordedMessage> messages) {
        int maxChars = Math.max(1000, settings.getAiMaxChars());
        int maxMessages = maxMessages();
        int start = Math.max(0, messages.size() - maxMessages);
        int usedChars = prompt.length();
        for (int i = start; i < messages.size(); i++) {
            RecordedMessage message = messages.get(i);
            String text = SummaryText.trim(message.text(), 160);
            if (text.isBlank()) {
                continue;
            }
            String line = "- [" + LocalDateTime.ofInstant(message.time(), zoneId).format(TIME_FORMAT) + "] "
                    + message.displayName() + "(" + message.userId() + "): " + text + '\n';
            if (usedChars + line.length() > maxChars) {
                break;
            }
            prompt.append(line);
            usedChars += line.length();
        }
    }

    private Optional<AiReportEnhancement> parseResponse(String body, ReportData data) throws IOException {
        JsonNode root = MAPPER.readTree(body);
        String content = root.path("choices").path(0).path("message").path("content").asText("");
        if (content.isBlank()) {
            logger.warn("AI chat summary response did not contain message content");
            return Optional.empty();
        }

        JsonNode json = parseJsonContent(content);
        AiReportEnhancement enhancement = new AiReportEnhancement(
                parseSummary(json.path("summary")),
                parseKeywords(json.path("keywords"), data),
                parseTopics(json.path("topics"), data),
                parseProfiles(json.path("profiles"), data),
                parseTags(json.path("tags")),
                parseQuotes(json.path("quotes"), data)
        );
        return enhancement.isEmpty() ? Optional.empty() : Optional.of(enhancement);
    }

    private JsonNode parseJsonContent(String content) throws IOException {
        String trimmed = content.trim();
        if (trimmed.startsWith("```")) {
            int firstNewline = trimmed.indexOf('\n');
            int lastFence = trimmed.lastIndexOf("```");
            if (firstNewline >= 0 && lastFence > firstNewline) {
                trimmed = trimmed.substring(firstNewline + 1, lastFence).trim();
            }
        }
        return MAPPER.readTree(trimmed);
    }

    private List<String> parseSummary(JsonNode node) {
        if (!node.isArray()) {
            return List.of();
        }
        List<String> result = new ArrayList<>();
        for (JsonNode item : node) {
            addText(result, item.asText(""), 120);
            if (result.size() >= 3) {
                break;
            }
        }
        return result;
    }

    private List<WordStat> parseKeywords(JsonNode node, ReportData data) {
        if (!node.isArray()) {
            return List.of();
        }

        Map<String, WordStat> existing = data.topWords().stream()
                .collect(Collectors.toMap(WordStat::word, word -> word, (a, b) -> a, LinkedHashMap::new));
        List<WordStat> result = new ArrayList<>();
        for (JsonNode item : node) {
            if (result.size() >= Math.min(settings.getHotWordLimit(), 24)) {
                break;
            }
            String keyword = clean(item.asText(""), 12);
            if (!isUsefulKeyword(keyword) || result.stream().anyMatch(word -> word.word().equalsIgnoreCase(keyword))) {
                continue;
            }
            WordStat stat = existing.get(keyword);
            result.add(stat == null ? aiWord(keyword, 24 - result.size()) : stat);
        }
        return result;
    }

    private List<Topic> parseTopics(JsonNode node, ReportData data) {
        if (!node.isArray()) {
            return List.of();
        }

        List<Topic> fallbackTopics = data.topics();
        List<Topic> result = new ArrayList<>();
        for (int i = 0; i < node.size() && result.size() < settings.getTopicLimit(); i++) {
            JsonNode item = node.get(i);
            Topic fallback = fallbackTopics.isEmpty()
                    ? new Topic("群聊热点", data.totalMessages(), List.of(), List.of(), "",
                    data.window().from(), data.window().to(), 0, "")
                    : fallbackTopics.get(Math.min(i, fallbackTopics.size() - 1));
            String title = clean(item.path("title").asText(fallback.title()), 24);
            String summary = clean(item.path("summary").asText(fallback.summary()), 140);
            if (title.isBlank() || summary.isBlank()) {
                continue;
            }
            List<String> keywords = parseTopicKeywords(item.path("keywords"), fallback.keywords());
            result.add(new Topic(title, fallback.count(), fallback.speakers(), keywords, summary,
                    fallback.from(), fallback.to(), fallback.score(), fallback.evidence()));
        }
        return result;
    }

    private List<String> parseTopicKeywords(JsonNode node, List<String> fallback) {
        if (!node.isArray()) {
            return fallback;
        }
        List<String> result = new ArrayList<>();
        for (JsonNode item : node) {
            if (result.size() >= 5) {
                break;
            }
            String keyword = clean(item.asText(""), 12);
            if (isUsefulKeyword(keyword) && !result.contains(keyword)) {
                result.add(keyword);
            }
        }
        return result.isEmpty() ? fallback : result;
    }

    private List<Tag> parseTags(JsonNode node) {
        if (!node.isArray()) {
            return List.of();
        }
        List<Tag> result = new ArrayList<>();
        for (JsonNode item : node) {
            if (result.size() >= 4) {
                break;
            }
            String title = clean(item.path("title").asText(""), 10);
            String value = clean(item.path("value").asText(""), 12);
            String description = clean(item.path("description").asText(""), 34);
            if (!title.isBlank() && !value.isBlank() && !description.isBlank()) {
                result.add(new Tag(title, value, description));
            }
        }
        return result;
    }

    private List<Profile> parseProfiles(JsonNode node, ReportData data) {
        if (!node.isArray()) {
            return List.of();
        }

        Map<String, Profile> fallbackByUser = data.profiles().stream()
                .collect(Collectors.toMap(Profile::userId, profile -> profile, (a, b) -> a, LinkedHashMap::new));
        Map<String, TopUser> usersById = data.topUsers().stream()
                .collect(Collectors.toMap(TopUser::userId, user -> user, (a, b) -> a, LinkedHashMap::new));
        List<Profile> result = new ArrayList<>();
        for (JsonNode item : node) {
            if (result.size() >= settings.getProfileLimit()) {
                break;
            }
            String userId = item.path("userId").asText("");
            TopUser user = usersById.get(userId);
            if (user == null) {
                continue;
            }
            Profile fallback = fallbackByUser.get(userId);
            String title = clean(item.path("title").asText(fallback == null ? "" : fallback.title()), 10);
            String description = clean(item.path("description").asText(fallback == null ? "" : fallback.description()), 70);
            if (title.isBlank() || description.isBlank()) {
                continue;
            }
            result.add(new Profile(userId, user.displayName(), title, description));
        }
        return result;
    }

    private List<Quote> parseQuotes(JsonNode node, ReportData data) {
        if (!node.isArray()) {
            return List.of();
        }

        Map<String, List<String>> quoteCandidates = quoteCandidates(data);
        Map<String, String> namesByUserId = namesByUserId(data);
        List<Quote> result = new ArrayList<>();
        for (JsonNode item : node) {
            if (result.size() >= settings.getQuoteLimit()) {
                break;
            }
            String name = clean(item.path("name").asText(""), 24);
            String text = clean(item.path("text").asText(""), settings.getMaxQuoteLength());
            if (name.isBlank() || text.isBlank() || text.length() < settings.getMinQuoteLength()) {
                continue;
            }
            if (isSupportedQuote(name, text, quoteCandidates)) {
                result.add(new Quote(name, SummaryText.replaceMentions(text, namesByUserId)));
            }
        }
        return result;
    }

    private Map<String, List<String>> quoteCandidates(ReportData data) {
        Map<String, List<String>> candidates = new HashMap<>();
        Map<String, String> namesByUserId = namesByUserId(data);
        for (RecordedMessage message : data.messages().stream()
                .filter(message -> message.readableLength() >= settings.getMinQuoteLength())
                .sorted(Comparator.comparingInt(RecordedMessage::readableLength).reversed())
                .limit(80)
                .toList()) {
            candidates.computeIfAbsent(message.displayName(), ignored -> new ArrayList<>())
                    .add(normalizeQuote(message.text()));
            candidates.computeIfAbsent(message.displayName(), ignored -> new ArrayList<>())
                    .add(normalizeQuote(SummaryText.replaceMentions(message.text(), namesByUserId)));
        }
        for (Quote quote : data.quotes()) {
            candidates.computeIfAbsent(quote.name(), ignored -> new ArrayList<>())
                    .add(normalizeQuote(quote.text()));
        }
        return candidates;
    }

    private Map<String, String> namesByUserId(ReportData data) {
        return data.users().values().stream()
                .collect(Collectors.toMap(user -> user.userId, UserAggregate::displayName, (a, b) -> a, LinkedHashMap::new));
    }

    private boolean isSupportedQuote(String name, String text, Map<String, List<String>> candidates) {
        String normalized = normalizeQuote(text);
        List<String> userQuotes = candidates.get(name);
        if (userQuotes == null || userQuotes.isEmpty()) {
            return false;
        }
        for (String candidate : userQuotes) {
            if (candidate.contains(normalized) || normalized.contains(candidate)
                    || overlapRatio(normalized, candidate) >= 0.65) {
                return true;
            }
        }
        return false;
    }

    private double overlapRatio(String left, String right) {
        if (left.isBlank() || right.isBlank()) {
            return 0;
        }
        String shorter = left.length() <= right.length() ? left : right;
        String longer = left.length() <= right.length() ? right : left;
        int hits = 0;
        for (int i = 0; i < shorter.length(); i++) {
            if (longer.indexOf(shorter.charAt(i)) >= 0) {
                hits++;
            }
        }
        return hits / (double) shorter.length();
    }

    private String normalizeQuote(String value) {
        return SummaryText.cleanText(value)
                .replaceAll("[\\p{Punct}\\p{IsPunctuation}\\s]+", "")
                .toLowerCase(Locale.ROOT);
    }

    private void addText(List<String> result, String value, int maxLength) {
        String cleaned = clean(value, maxLength);
        if (!cleaned.isBlank()) {
            result.add(cleaned);
        }
    }

    private String clean(String value, int maxLength) {
        return SummaryText.trim(SummaryText.cleanText(value), maxLength);
    }

    private boolean isUsefulKeyword(String keyword) {
        if (keyword == null || keyword.isBlank()) {
            return false;
        }
        String normalized = keyword.trim().toLowerCase(Locale.ROOT);
        return normalized.codePointCount(0, normalized.length()) >= 2
                && !List.of("这个", "那个", "我们", "你们", "他们", "可以", "哈哈", "哈哈哈",
                "图片", "表情", "文件", "the", "and", "you", "that", "this").contains(normalized);
    }

    private WordStat aiWord(String keyword, int weight) {
        WordStat stat = new WordStat(keyword);
        for (int i = 0; i < Math.max(1, weight); i++) {
            stat.add("ai", 1.1);
        }
        return stat;
    }

    private URI endpoint() {
        String baseUrl = SummaryText.firstNotBlank(settings.getAiBaseUrl(), "https://api.openai.com/v1").trim();
        if (baseUrl.endsWith("/")) {
            baseUrl = baseUrl.substring(0, baseUrl.length() - 1);
        }
        String url = baseUrl.endsWith("/chat/completions") ? baseUrl : baseUrl + "/chat/completions";
        return URI.create(url);
    }

    private String resolveApiKey() {
        String configured = SummaryText.nullTo(settings.getAiApiKey(), "").trim();
        if (!configured.isBlank()) {
            return configured;
        }
        String envName = SummaryText.nullTo(settings.getAiApiKeyEnv(), "").trim();
        if (envName.isBlank()) {
            return "";
        }
        return SummaryText.nullTo(System.getenv(envName), "").trim();
    }

    private String model() {
        return SummaryText.firstNotBlank(settings.getAiModel(), "gpt-5.4-nano");
    }

    private int maxMessages() {
        return SummaryText.clamp(settings.getAiMaxMessages(), 20, 1200);
    }

    private int timeoutSeconds() {
        return SummaryText.clamp(settings.getAiTimeoutSeconds(), 5, 90);
    }
}
