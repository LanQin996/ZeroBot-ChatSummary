package cn.zerobot.chatsummary;

import java.time.Instant;
import java.util.List;

record Topic(String title, int count, List<String> speakers, List<String> keywords, String summary,
             Instant from, Instant to, int score, String evidence) {
    Topic {
        speakers = speakers == null ? List.of() : List.copyOf(speakers);
        keywords = keywords == null ? List.of() : List.copyOf(keywords);
        summary = SummaryText.cleanText(summary);
        evidence = SummaryText.cleanText(evidence);
        score = SummaryText.clamp(score, 0, 100);
    }

    Topic(String title, int count, List<String> speakers, List<String> keywords, String summary) {
        this(title, count, speakers, keywords, summary, null, null, 0, "");
    }
}
