package cn.zerobot.chatsummary;

import java.text.DecimalFormat;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneId;
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
        List<Quote> quotes = buildQuotes(sorted);
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
        List<Topic> topics = new ArrayList<>();
        Set<String> usedWords = new HashSet<>();
        for (WordStat word : topWords) {
            if (topics.size() >= settings.getTopicLimit()) {
                break;
            }
            if (usedWords.stream().anyMatch(used -> used.contains(word.word()) || word.word().contains(used))) {
                continue;
            }
            List<RecordedMessage> related = messages.stream()
                    .filter(message -> normalizeForWords(message.text()).contains(word.word()))
                    .toList();
            if (related.size() < Math.max(2, settings.getMinHotWordCount())) {
                continue;
            }
            usedWords.add(word.word());
            List<String> speakers = related.stream()
                    .map(RecordedMessage::displayName)
                    .distinct()
                    .limit(4)
                    .toList();
            List<String> keywords = topWords.stream()
                    .map(WordStat::word)
                    .filter(candidate -> !candidate.equals(word.word()))
                    .filter(candidate -> related.stream().anyMatch(message -> normalizeForWords(message.text()).contains(candidate)))
                    .limit(4)
                    .toList();
            String sample = related.stream()
                    .map(RecordedMessage::text)
                    .filter(text -> text.length() >= 8)
                    .max(Comparator.comparingInt(String::length))
                    .orElse(related.get(0).text());
            String summary = "围绕「" + word.word() + "」产生了 " + related.size()
                    + " 条发言，主要参与者是 " + String.join("、", speakers)
                    + "。代表片段：" + SummaryText.trim(sample, 54);
            topics.add(new Topic(word.word(), related.size(), speakers, keywords, summary));
        }
        if (topics.isEmpty() && !messages.isEmpty()) {
            topics.add(new Topic("日常聊天", messages.size(),
                    messages.stream().map(RecordedMessage::displayName).distinct().limit(4).toList(),
                    List.of(), "这段时间的聊天比较分散，更多是轻量的日常交流和即时回应。"));
        }
        return topics;
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

    private List<Quote> buildQuotes(List<RecordedMessage> messages) {
        Set<String> usedUsers = new HashSet<>();
        return messages.stream()
                .filter(message -> {
                    int length = message.readableLength();
                    return length >= settings.getMinQuoteLength() && length <= settings.getMaxQuoteLength()
                            && !message.text().contains("[图片]");
                })
                .sorted(Comparator.comparingInt(this::quoteScore).reversed())
                .filter(message -> usedUsers.add(message.userId()))
                .limit(settings.getQuoteLimit())
                .map(message -> new Quote(message.displayName(), SummaryText.trim(message.text(), settings.getMaxQuoteLength())))
                .toList();
    }

    private int quoteScore(RecordedMessage message) {
        String text = message.text();
        int score = Math.min(80, message.readableLength());
        if (text.contains("？") || text.contains("?")) {
            score += 12;
        }
        if (text.contains("！") || text.contains("!")) {
            score += 10;
        }
        if (text.contains("哈哈") || text.contains("笑") || text.contains("绝") || text.contains("离谱")) {
            score += 12;
        }
        if (text.contains("我") || text.contains("我们")) {
            score += 4;
        }
        return score;
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
}
