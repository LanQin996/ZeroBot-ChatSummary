package cn.zerobot.chatsummary;

import java.text.DecimalFormat;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

final class ReportAnalyzer {
    private static final DateTimeFormatter TOPIC_TIME_FORMAT = DateTimeFormatter.ofPattern("HH:mm");

    private final Settings settings;
    private final ZoneId zoneId;
    private final Set<String> stopWords;

    ReportAnalyzer(Settings settings, ZoneId zoneId) {
        this.settings = settings;
        this.zoneId = zoneId;
        this.stopWords = SummaryText.safeList(settings.getStopWords()).stream()
                .filter(Objects::nonNull)
                .map(word -> word.trim().toLowerCase(Locale.ROOT))
                .filter(word -> !word.isBlank())
                .collect(Collectors.toSet());
    }

    ReportData analyze(String groupId, String groupName, ReportWindow window, List<RecordedMessage> messages) {
        List<RecordedMessage> sorted = messages.stream()
                .sorted(Comparator.comparing(RecordedMessage::time))
                .toList();
        Map<String, UserAggregate> users = aggregateUsers(sorted);
        List<TopUser> topUsers = users.values().stream()
                .sorted(Comparator.comparingInt(UserAggregate::messageCount).reversed()
                        .thenComparing(UserAggregate::displayName))
                .limit(settings.getTopUserLimit())
                .map(user -> new TopUser(user.userId, user.displayName, user.messageCount(), user.readableChars(),
                        SummaryText.peakHour(user.hourly), user.topWord()))
                .toList();
        List<WordStat> topWords = extractWords(sorted).stream()
                .sorted(Comparator.comparingDouble(WordStat::score).reversed()
                        .thenComparing(WordStat::word))
                .limit(settings.getHotWordLimit())
                .toList();

        int[] hourly = new int[24];
        int imageCount = 0;
        int atCount = 0;
        int faceCount = 0;
        int fileCount = 0;
        int readableChars = 0;
        for (RecordedMessage message : sorted) {
            int hour = LocalDateTime.ofInstant(message.time(), zoneId).getHour();
            hourly[hour]++;
            imageCount += message.imageCount();
            atCount += message.atCount();
            faceCount += message.faceCount();
            fileCount += message.fileCount();
            readableChars += message.readableLength();
        }

        int peakHour = SummaryText.peakHour(hourly);
        List<Topic> topics = buildTopics(sorted, topWords);
        List<Profile> profiles = buildProfiles(users.values(), sorted.size());
        List<Interaction> interactions = buildInteractions(sorted, users);
        List<Quote> quotes = buildQuotes(sorted, users);
        List<Tag> tags = buildTags(sorted, topWords, peakHour, imageCount, users.size());
        List<String> summary = buildSummary(sorted, users, topWords, topics, peakHour, imageCount);

        return new ReportData(
                groupId,
                groupName,
                window,
                sorted,
                users,
                topUsers,
                topWords,
                topics,
                profiles,
                interactions,
                quotes,
                tags,
                summary,
                hourly,
                sorted.size(),
                users.size(),
                readableChars,
                imageCount,
                atCount,
                faceCount,
                fileCount,
                peakHour,
                activityScore(sorted.size(), users.size(), activeHourCount(hourly))
        );
    }

    private Map<String, UserAggregate> aggregateUsers(List<RecordedMessage> messages) {
        Map<String, UserAggregate> users = new LinkedHashMap<>();
        for (RecordedMessage message : messages) {
            UserAggregate user = users.computeIfAbsent(message.userId(),
                    key -> new UserAggregate(message.userId(), message.displayName()));
            user.displayName = SummaryText.firstNotBlank(message.displayName(), user.displayName, message.userId());
            user.messages.add(message);
            int hour = LocalDateTime.ofInstant(message.time(), zoneId).getHour();
            user.hourly[hour]++;
        }
        return users;
    }

    private List<WordStat> extractWords(List<RecordedMessage> messages) {
        Map<String, WordStat> words = new HashMap<>();
        for (RecordedMessage message : messages) {
            String text = normalizeForWords(message.text());
            collectEnglishWords(words, message, text);
            collectChineseWords(words, message, text);
        }
        return words.values().stream()
                .filter(word -> word.count >= Math.max(1, settings.getMinHotWordCount()))
                .filter(word -> word.word.length() >= 2)
                .toList();
    }

    private void collectEnglishWords(Map<String, WordStat> words, RecordedMessage message, String text) {
        Matcher matcher = SummaryText.ENGLISH_WORD_PATTERN.matcher(text);
        while (matcher.find()) {
            String word = matcher.group().toLowerCase(Locale.ROOT);
            if (isUsefulWord(word)) {
                words.computeIfAbsent(word, WordStat::new).add(message.userId(), 1.2);
            }
        }
    }

    private void collectChineseWords(Map<String, WordStat> words, RecordedMessage message, String text) {
        Matcher matcher = SummaryText.CHINESE_RUN_PATTERN.matcher(text);
        while (matcher.find()) {
            String run = matcher.group();
            if (run.length() <= 4) {
                if (isUsefulWord(run)) {
                    words.computeIfAbsent(run, WordStat::new).add(message.userId(), run.length() >= 3 ? 1.2 : 1.0);
                }
                continue;
            }
            int maxN = Math.min(4, run.length());
            for (int n = 2; n <= maxN; n++) {
                for (int i = 0; i + n <= run.length(); i++) {
                    String word = run.substring(i, i + n);
                    if (isUsefulWord(word)) {
                        words.computeIfAbsent(word, WordStat::new).add(message.userId(), n >= 3 ? 1.15 : 1.0);
                    }
                }
            }
        }
    }

    private String normalizeForWords(String text) {
        return SummaryText.nullTo(text, "")
                .replace("[图片]", " ")
                .replace("[表情]", " ")
                .replace("[文件]", " ")
                .replaceAll("@\\d+", " ")
                .toLowerCase(Locale.ROOT);
    }

    private boolean isUsefulWord(String word) {
        if (word == null || word.isBlank()) {
            return false;
        }
        String normalized = word.trim().toLowerCase(Locale.ROOT);
        if (normalized.length() < 2 || stopWords.contains(normalized)) {
            return false;
        }
        if (normalized.chars().allMatch(Character::isDigit)) {
            return false;
        }
        for (String stopWord : stopWords) {
            if (normalized.length() <= 4 && normalized.contains(stopWord)) {
                return false;
            }
        }
        return true;
    }

    private List<Topic> buildTopics(List<RecordedMessage> messages, List<WordStat> topWords) {
        Set<String> usedTitles = new HashSet<>();
        List<Topic> topics = topicCandidates(messages, topWords).stream()
                .sorted(Comparator.comparingInt(TopicCandidate::score).reversed()
                        .thenComparing(candidate -> candidate.from))
                .map(candidate -> candidate.toTopic(topWords, zoneId))
                .filter(topic -> usedTitles.add(normalizeTopicTitle(topic.title())))
                .limit(settings.getTopicLimit())
                .toList();
        if (topics.isEmpty() && !messages.isEmpty()) {
            RecordedMessage sample = messages.stream()
                    .max(Comparator.comparingInt(RecordedMessage::readableLength))
                    .orElse(messages.get(0));
            topics = List.of(new Topic("日常交流与即时回应", messages.size(),
                    messages.stream().map(RecordedMessage::displayName).distinct().limit(5).toList(),
                    topWords.stream().map(WordStat::word).limit(4).toList(),
                    "这段时间的聊天比较分散，主要是成员之间的即时交流。代表片段：" + SummaryText.trim(sample.text(), 72),
                    messages.get(0).time(), messages.get(messages.size() - 1).time(), activityScore(messages.size(),
                    (int) messages.stream().map(RecordedMessage::userId).distinct().count(), 1),
                    SummaryText.trim(sample.text(), 90)));
        }
        return topics;
    }

    private String normalizeTopicTitle(String title) {
        return SummaryText.cleanText(title)
                .replaceAll("[\\p{Punct}\\p{IsPunctuation}\\s]+", "")
                .toLowerCase(Locale.ROOT);
    }

    private List<TopicCandidate> topicCandidates(List<RecordedMessage> messages, List<WordStat> topWords) {
        List<TopicCandidate> candidates = new ArrayList<>();
        List<RecordedMessage> current = new ArrayList<>();
        for (RecordedMessage message : messages) {
            if (!isTopicMessage(message)) {
                continue;
            }
            if (!current.isEmpty()) {
                RecordedMessage previous = current.get(current.size() - 1);
                long gap = Math.abs(Duration.between(previous.time(), message.time()).toMinutes());
                if (gap > 12 || (!sharesImportantWord(previous, message, topWords) && gap > 5)) {
                    addTopicCandidate(candidates, current, topWords);
                    current = new ArrayList<>();
                }
            }
            current.add(message);
        }
        addTopicCandidate(candidates, current, topWords);

        if (candidates.size() < Math.max(2, settings.getTopicLimit() / 2)) {
            List<String> majorWords = topWords.stream().map(WordStat::word).limit(8).toList();
            for (String word : majorWords) {
                List<RecordedMessage> related = messages.stream()
                        .filter(this::isTopicMessage)
                        .filter(message -> normalizeForWords(message.text()).contains(word))
                        .toList();
                addTopicCandidate(candidates, related, topWords);
            }
        }
        return mergeTopicCandidates(candidates);
    }

    private void addTopicCandidate(List<TopicCandidate> candidates, List<RecordedMessage> messages,
                                   List<WordStat> topWords) {
        if (messages == null || messages.size() < Math.max(2, settings.getMinHotWordCount())) {
            return;
        }
        long speakers = messages.stream().map(RecordedMessage::userId).distinct().count();
        int readable = messages.stream().mapToInt(RecordedMessage::readableLength).sum();
        if (readable < 18 && messages.size() < 4) {
            return;
        }
        candidates.add(new TopicCandidate(messages, topicScore(messages, topWords)));
    }

    private List<TopicCandidate> mergeTopicCandidates(List<TopicCandidate> candidates) {
        List<TopicCandidate> result = new ArrayList<>();
        for (TopicCandidate candidate : candidates.stream()
                .sorted(Comparator.comparingInt(TopicCandidate::score).reversed())
                .toList()) {
            Set<Long> ids = candidate.messageIds();
            Set<String> words = candidate.keywordSet();
            boolean overlaps = result.stream().anyMatch(existing -> overlap(ids, existing.messageIds()) >= 0.30
                    || keywordOverlap(words, existing.keywordSet()) >= 0.65
                    || normalizedEvidence(candidate).equals(normalizedEvidence(existing)));
            if (!overlaps) {
                result.add(candidate);
            }
        }
        return result;
    }

    private double keywordOverlap(Set<String> left, Set<String> right) {
        if (left.isEmpty() || right.isEmpty()) {
            return 0;
        }
        long hits = left.stream().filter(right::contains).count();
        return hits / (double) Math.min(left.size(), right.size());
    }

    private String normalizedEvidence(TopicCandidate candidate) {
        return SummaryText.cleanText(candidate.evidenceMessage().text())
                .replaceAll("[\\p{Punct}\\p{IsPunctuation}\\s]+", "")
                .toLowerCase(Locale.ROOT);
    }

    private double overlap(Set<Long> left, Set<Long> right) {
        if (left.isEmpty() || right.isEmpty()) {
            return 0;
        }
        long hits = left.stream().filter(right::contains).count();
        return hits / (double) Math.min(left.size(), right.size());
    }

    private boolean isTopicMessage(RecordedMessage message) {
        if (message == null) {
            return false;
        }
        String text = SummaryText.nullTo(message.text(), "")
                .replace("[图片]", "")
                .replace("[表情]", "")
                .replace("[文件]", "")
                .trim();
        return message.readableLength() >= 4 || text.contains("？") || text.contains("?")
                || text.contains("！") || text.contains("!");
    }

    private boolean sharesImportantWord(RecordedMessage left, RecordedMessage right, List<WordStat> topWords) {
        String leftText = normalizeForWords(left.text());
        String rightText = normalizeForWords(right.text());
        for (String word : topWords.stream().map(WordStat::word).limit(18).toList()) {
            if (leftText.contains(word) && rightText.contains(word)) {
                return true;
            }
        }
        return false;
    }

    private int topicScore(List<RecordedMessage> messages, List<WordStat> topWords) {
        int messageScore = messages.size() * 7;
        int speakerScore = (int) messages.stream().map(RecordedMessage::userId).distinct().count() * 10;
        int readableScore = Math.min(35, messages.stream().mapToInt(RecordedMessage::readableLength).sum() / 10);
        int keywordScore = 0;
        String joined = messages.stream().map(message -> normalizeForWords(message.text())).collect(Collectors.joining(" "));
        for (WordStat word : topWords.stream().limit(12).toList()) {
            if (joined.contains(word.word())) {
                keywordScore += 3;
            }
        }
        return SummaryText.clamp(messageScore + speakerScore + readableScore + keywordScore, 0, 100);
    }

    private List<Profile> buildProfiles(Iterable<UserAggregate> users, int totalMessages) {
        List<UserAggregate> sorted = new ArrayList<>();
        users.forEach(sorted::add);
        sorted.sort(Comparator.comparingInt(UserAggregate::messageCount).reversed());
        return sorted.stream()
                .limit(settings.getProfileLimit())
                .map(user -> {
                    double avgLength = user.messages.stream().mapToInt(RecordedMessage::readableLength).average().orElse(0);
                    int imageCount = user.messages.stream().mapToInt(RecordedMessage::imageCount).sum();
                    long questions = user.messages.stream().filter(message -> message.text().contains("?")
                            || message.text().contains("？")).count();
                    long night = user.messages.stream()
                            .filter(message -> {
                                int hour = LocalDateTime.ofInstant(message.time(), zoneId).getHour();
                                return hour < 7 || hour >= 23;
                            })
                            .count();
                    String title;
                    if (user.messageCount() >= Math.max(5, totalMessages * 0.2)) {
                        title = "话题发动机";
                    } else if (avgLength >= 28) {
                        title = "长文选手";
                    } else if (imageCount >= Math.max(2, user.messageCount() / 3)) {
                        title = "表情包投手";
                    } else if (questions >= Math.max(2, user.messageCount() / 4)) {
                        title = "提问担当";
                    } else if (night >= Math.max(2, user.messageCount() / 3)) {
                        title = "夜间在线";
                    } else {
                        title = "稳定输出";
                    }
                    String topWord = user.topWord();
                    String description = "发言 " + user.messageCount() + " 条，平均 " + new DecimalFormat("0.#").format(avgLength)
                            + " 字。高峰在 " + SummaryText.formatHour(SummaryText.peakHour(user.hourly)) + "，"
                            + (topWord.isBlank() ? "内容覆盖比较均衡。" : "常提到「" + topWord + "」。");
                    return new Profile(user.userId, user.displayName, title, description);
                })
                .toList();
    }

    private List<Interaction> buildInteractions(List<RecordedMessage> messages, Map<String, UserAggregate> users) {
        Map<String, InteractionCounter> counters = new HashMap<>();
        RecordedMessage previous = null;
        for (RecordedMessage message : messages) {
            Matcher mention = Pattern.compile("@(\\d+)").matcher(message.text());
            while (mention.find()) {
                String mentioned = mention.group(1);
                if (!mentioned.equals(message.userId())) {
                    countPair(counters, users, message.userId(), mentioned, 2);
                }
            }
            if (previous != null && !previous.userId().equals(message.userId())
                    && Duration.between(previous.time(), message.time()).abs().toMinutes() <= 5) {
                countPair(counters, users, previous.userId(), message.userId(), 1);
            }
            previous = message;
        }
        return counters.values().stream()
                .sorted(Comparator.comparingInt(InteractionCounter::count).reversed())
                .limit(settings.getInteractionLimit())
                .map(counter -> new Interaction(counter.leftName, counter.rightName, counter.count))
                .toList();
    }

    private void countPair(Map<String, InteractionCounter> counters, Map<String, UserAggregate> users,
                           String leftId, String rightId, int weight) {
        if (leftId == null || rightId == null || leftId.equals(rightId)) {
            return;
        }
        String first = leftId.compareTo(rightId) <= 0 ? leftId : rightId;
        String second = leftId.compareTo(rightId) <= 0 ? rightId : leftId;
        String key = first + "|" + second;
        InteractionCounter counter = counters.computeIfAbsent(key, ignored -> new InteractionCounter(
                displayName(users, first), displayName(users, second)
        ));
        counter.count += weight;
    }

    private String displayName(Map<String, UserAggregate> users, String userId) {
        UserAggregate user = users.get(userId);
        return user == null ? userId : user.displayName;
    }

    private List<Quote> buildQuotes(List<RecordedMessage> messages, Map<String, UserAggregate> users) {
        Set<String> usedUsers = new HashSet<>();
        Set<String> usedTexts = new HashSet<>();
        Map<String, String> namesByUserId = users.values().stream()
                .collect(Collectors.toMap(user -> user.userId, UserAggregate::displayName, (a, b) -> a, LinkedHashMap::new));
        return messages.stream()
                .filter(this::isQuoteCandidate)
                .sorted(Comparator.comparingInt(this::quoteScore).reversed())
                .filter(message -> usedUsers.add(message.userId()))
                .filter(message -> usedTexts.add(normalizeQuoteText(message.text())))
                .limit(settings.getQuoteLimit())
                .map(message -> new Quote(message.displayName(), SummaryText.trim(
                        SummaryText.replaceMentions(message.text(), namesByUserId),
                        settings.getMaxQuoteLength())))
                .toList();
    }

    private boolean isQuoteCandidate(RecordedMessage message) {
        int length = message.readableLength();
        if (length < settings.getMinQuoteLength() || length > settings.getMaxQuoteLength()) {
            return false;
        }
        String text = cleanQuoteText(message.text());
        if (text.isBlank() || text.contains("[图片]") || text.contains("[文件]")) {
            return false;
        }
        String lower = text.toLowerCase(Locale.ROOT);
        if (lower.matches(".*(https?://|build[\\\\/]|\\.java|\\.yml|\\.json|\\.png|\\.jar|compilejava|generated by).*")) {
            return false;
        }
        if (lower.matches(".*(权限节点|生成路径|plugin\\.yml|registercommand|jdk\\s*21|chat-summary\\.report).*")) {
            return false;
        }
        if (text.matches("^[\\d\\s:：,，.。/\\\\\\-_=+]+$")) {
            return false;
        }
        return quoteSignalScore(text) >= 10;
    }

    private int quoteScore(RecordedMessage message) {
        String text = cleanQuoteText(message.text());
        int length = text.codePointCount(0, text.length());
        int score = Math.min(35, length);
        score += quoteSignalScore(text);
        if (length >= 16 && length <= 58) {
            score += 10;
        }
        if (text.contains("@")) {
            score += 4;
        }
        return score;
    }

    private int quoteSignalScore(String text) {
        int score = 0;
        if (text.contains("？") || text.contains("?")) {
            score += 8;
        }
        if (text.contains("！") || text.contains("!")) {
            score += 8;
        }
        if (text.contains("哈哈") || text.contains("笑") || text.contains("绝") || text.contains("离谱")
                || text.contains("绷") || text.contains("草") || text.contains("乐") || text.contains("逆天")) {
            score += 18;
        }
        if (text.contains("我觉得") || text.contains("我感觉") || text.contains("我看") || text.contains("我支持")
                || text.contains("别") || text.contains("不要") || text.contains("应该") || text.contains("可以")) {
            score += 8;
        }
        if (text.contains("像") || text.contains("变成") || text.contains("不是") || text.contains("就是")
                || text.contains("重点") || text.contains("本来") || text.contains("结果")) {
            score += 8;
        }
        if (text.contains("：") || text.contains(":")) {
            score += 4;
        }
        if (text.contains("错误") || text.contains("失败") || text.contains("路径") || text.contains("默认")
                || text.contains("配置") || text.contains("参数")) {
            score -= 8;
        }
        return score;
    }

    private String cleanQuoteText(String text) {
        return SummaryText.cleanText(text)
                .replace("[表情]", "")
                .trim();
    }

    private String normalizeQuoteText(String text) {
        return cleanQuoteText(text)
                .replaceAll("[\\p{Punct}\\p{IsPunctuation}\\s]+", "")
                .toLowerCase(Locale.ROOT);
    }

    private List<Tag> buildTags(List<RecordedMessage> messages, List<WordStat> topWords, int peakHour,
                                int imageCount, int participantCount) {
        List<Tag> tags = new ArrayList<>();
        tags.add(new Tag("活跃高峰", SummaryText.formatHour(peakHour), "这段时间最密集的聊天窗口。"));
        if (!topWords.isEmpty()) {
            tags.add(new Tag("关键词", topWords.get(0).word(), "聊天里反复出现的核心词。"));
        }
        if (imageCount > 0) {
            tags.add(new Tag("图片浓度", imageCount + " 张", "图文和表情让讨论更热闹。"));
        }
        tags.add(new Tag("参与广度", participantCount + " 人", "有发言记录的群成员数量。"));
        if (messages.size() >= 80) {
            tags.add(new Tag("热聊模式", "高频", "群聊节奏持续在线。"));
        }
        return tags.stream().limit(4).toList();
    }

    private List<String> buildSummary(List<RecordedMessage> messages, Map<String, UserAggregate> users,
                                      List<WordStat> topWords, List<Topic> topics, int peakHour, int imageCount) {
        String topUser = users.values().stream()
                .max(Comparator.comparingInt(UserAggregate::messageCount))
                .map(UserAggregate::displayName)
                .orElse("大家");
        String words = topWords.stream().limit(5).map(WordStat::word).collect(Collectors.joining("、"));
        String firstTopic = topics.isEmpty() ? "日常交流" : "「" + topics.get(0).title() + "」";
        List<String> summary = new ArrayList<>();
        summary.add("这段时间群里共记录 " + messages.size() + " 条消息，" + users.size()
                + " 位成员参与。聊天高峰出现在 " + SummaryText.formatHour(peakHour) + "，"
                + topUser + " 的存在感最强，整体节奏偏" + (messages.size() >= 80 ? "热烈" : "轻快") + "。");
        summary.add(words.isBlank()
                ? "热词比较分散，讨论更像连续的即时交流。"
                : "高频词包括 " + words + "，主要话题集中在 " + firstTopic + "。"
                + (imageCount > 0 ? " 期间还穿插了 " + imageCount + " 张图片或表情。" : ""));
        return summary;
    }

    private int activityScore(int messages, int users, int activeHours) {
        return SummaryText.clamp(messages * 2 + users * 5 + activeHours * 4, 0, 100);
    }

    private int activeHourCount(int[] hourly) {
        int count = 0;
        for (int value : hourly) {
            if (value > 0) {
                count++;
            }
        }
        return count;
    }

    private static final class InteractionCounter {
        private final String leftName;
        private final String rightName;
        private int count;

        private InteractionCounter(String leftName, String rightName) {
            this.leftName = leftName;
            this.rightName = rightName;
        }

        int count() {
            return count;
        }
    }

    private static final class TopicCandidate {
        private final List<RecordedMessage> messages;
        private final Instant from;
        private final Instant to;
        private final int score;

        private TopicCandidate(List<RecordedMessage> messages, int score) {
            this.messages = messages.stream()
                    .sorted(Comparator.comparing(RecordedMessage::time))
                    .toList();
            this.from = this.messages.get(0).time();
            this.to = this.messages.get(this.messages.size() - 1).time();
            this.score = score;
        }

        int score() {
            return score;
        }

        Set<Long> messageIds() {
            return messages.stream().map(RecordedMessage::messageId).collect(Collectors.toSet());
        }

        Set<String> keywordSet() {
            return messages.stream()
                    .flatMap(message -> SummaryText.CHINESE_RUN_PATTERN.matcher(message.text()).results()
                            .map(match -> match.group().toLowerCase(Locale.ROOT)))
                    .filter(word -> word.length() >= 2)
                    .collect(Collectors.toSet());
        }

        Topic toTopic(List<WordStat> topWords, ZoneId zoneId) {
            List<String> speakers = messages.stream()
                    .collect(Collectors.groupingBy(RecordedMessage::displayName, LinkedHashMap::new, Collectors.counting()))
                    .entrySet().stream()
                    .sorted(Map.Entry.<String, Long>comparingByValue().reversed())
                    .map(Map.Entry::getKey)
                    .limit(6)
                    .toList();
            List<String> keywords = keywords(topWords);
            RecordedMessage evidenceMessage = evidenceMessage();
            String evidence = SummaryText.trim(cleanTopicText(evidenceMessage.text()), 96);
            String title = titleFrom(evidence, keywords);
            String timeRange = LocalDateTime.ofInstant(from, zoneId).format(TOPIC_TIME_FORMAT)
                    + "-" + LocalDateTime.ofInstant(to, zoneId).format(TOPIC_TIME_FORMAT);
            String participantText = speakers.isEmpty() ? "大家" : String.join("、", speakers.stream().limit(3).toList());
            String summary = participantText + " 在 " + timeRange + " 集中聊到" + summarySubject(title, keywords)
                    + "，共 " + messages.size() + " 条相关发言。"
                    + "代表片段：" + evidence;
            return new Topic(title, messages.size(), speakers, keywords, summary, from, to, score, evidence);
        }

        private List<String> keywords(List<WordStat> topWords) {
            String joined = messages.stream()
                    .map(message -> SummaryText.nullTo(message.text(), "").toLowerCase(Locale.ROOT))
                    .collect(Collectors.joining(" "));
            List<String> result = new ArrayList<>();
            for (WordStat word : topWords) {
                if (result.size() >= 5) {
                    break;
                }
                if (joined.contains(word.word().toLowerCase(Locale.ROOT))) {
                    result.add(word.word());
                }
            }
            return result;
        }

        private RecordedMessage evidenceMessage() {
            return messages.stream()
                    .max(Comparator.comparingInt(message -> evidenceScore(message.text())))
                    .orElse(messages.get(0));
        }

        private int evidenceScore(String text) {
            String cleaned = cleanTopicText(text);
            int score = Math.min(80, cleaned.codePointCount(0, cleaned.length()));
            if (cleaned.contains("因为") || cleaned.contains("所以") || cleaned.contains("但是")
                    || cleaned.contains("建议") || cleaned.contains("问题") || cleaned.contains("失败")) {
                score += 16;
            }
            if (cleaned.contains("？") || cleaned.contains("?") || cleaned.contains("！") || cleaned.contains("!")) {
                score += 8;
            }
            return score;
        }

        private String titleFrom(String evidence, List<String> keywords) {
            String text = cleanTopicText(evidence)
                    .replaceAll("^[,，。！？\\s]+", "");
            String heuristic = heuristicTitle(text, keywords);
            if (!heuristic.isBlank()) {
                return heuristic;
            }
            if (text.isBlank() && !keywords.isEmpty()) {
                return "讨论" + String.join("、", keywords.stream().limit(2).toList());
            }
            text = text.replaceAll("^(我觉得|我感觉|感觉|就是|这个|那个|然后|所以|但是|可以|可能|应该)", "");
            int end = firstSentenceEnd(text);
            if (end > 0) {
                text = text.substring(0, end);
            }
            text = text.replaceAll("[,，。！？!?.；;：:、\\s]+$", "");
            int maxLength = text.codePointCount(0, text.length()) >= 10 ? 18 : 22;
            String title = SummaryText.trim(text, maxLength);
            if (title.codePointCount(0, title.length()) < 4 && !keywords.isEmpty()) {
                title = "讨论" + String.join("、", keywords.stream().limit(2).toList());
            }
            return title;
        }

        private String heuristicTitle(String text, List<String> keywords) {
            String normalized = SummaryText.nullTo(text, "").toLowerCase(Locale.ROOT);
            if (containsAny(normalized, "分身", "主体", "本体")
                    && containsAny(normalized, "攻击", "机制", "时间", "有限", "窗口")) {
                return "分身攻击窗口与本体机制讨论";
            }
            if (containsAny(normalized, "图片", "image", "gpt-image", "生图", "绘图")
                    && containsAny(normalized, "失败", "报错", "不可用", "分组", "接口", "渠道")) {
                return "图片生成报错与分组切换测试";
            }
            if (containsAny(normalized, "服务器", "服务端", "群服")
                    && containsAny(normalized, "挂", "故障", "攻击", "被打", "不可用", "排查")) {
                return "服务器故障与可用性排查";
            }
            if (containsAny(normalized, "gpt", "claude", "中转", "转站")
                    && containsAny(normalized, "价格", "低价", "成本", "引流", "回本")) {
                return "AI 中转价格与成本争议";
            }
            if (containsAny(normalized, "提示词", "prompt", "报告", "总结")
                    && containsAny(normalized, "ai", "生成", "优化", "效果")) {
                return "AI 报告提示词与效果优化";
            }
            if (keywords.size() >= 2) {
                return SummaryText.trim(String.join("与", keywords.stream().limit(2).toList()) + "讨论", 22);
            }
            return "";
        }

        private boolean containsAny(String text, String... values) {
            for (String value : values) {
                if (text.contains(value)) {
                    return true;
                }
            }
            return false;
        }

        private int firstSentenceEnd(String text) {
            int best = -1;
            for (String delimiter : List.of("。", "！", "？", "!", "?", "；", ";")) {
                int index = text.indexOf(delimiter);
                if (index >= 6 && (best < 0 || index < best)) {
                    best = index;
                }
            }
            return best < 0 ? -1 : best + 1;
        }

        private String summarySubject(String title, List<String> keywords) {
            if (title == null || title.isBlank()) {
                return keywords.isEmpty() ? "一个集中话题" : "「" + String.join("、", keywords.stream().limit(2).toList()) + "」";
            }
            return "「" + title + "」";
        }

        private static String cleanTopicText(String text) {
            return SummaryText.cleanText(text)
                    .replace("[图片]", "")
                    .replace("[表情]", "")
                    .replace("[文件]", "")
                    .trim();
        }
    }
}
