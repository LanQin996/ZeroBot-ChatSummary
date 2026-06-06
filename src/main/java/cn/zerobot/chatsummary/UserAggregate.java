package cn.zerobot.chatsummary;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;

final class UserAggregate {
    final String userId;
    String displayName;
    final List<RecordedMessage> messages = new ArrayList<>();
    final int[] hourly = new int[24];

    UserAggregate(String userId, String displayName) {
        this.userId = userId;
        this.displayName = displayName;
    }

    int messageCount() {
        return messages.size();
    }

    int readableChars() {
        return messages.stream().mapToInt(RecordedMessage::readableLength).sum();
    }

    String topWord() {
        Map<String, Integer> counts = new HashMap<>();
        for (RecordedMessage message : messages) {
            Matcher matcher = SummaryText.CHINESE_RUN_PATTERN.matcher(message.text());
            while (matcher.find()) {
                String run = matcher.group();
                if (run.length() >= 2 && run.length() <= 5) {
                    counts.merge(run, 1, Integer::sum);
                }
            }
        }
        return counts.entrySet().stream()
                .max(Map.Entry.comparingByValue())
                .map(Map.Entry::getKey)
                .orElse("");
    }

    String displayName() {
        return displayName;
    }
}
