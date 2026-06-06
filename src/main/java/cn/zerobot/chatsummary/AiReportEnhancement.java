package cn.zerobot.chatsummary;

import java.util.List;

record AiReportEnhancement(
        List<String> summary,
        List<WordStat> topWords,
        List<Topic> topics,
        List<Profile> profiles,
        List<Tag> tags,
        List<Quote> quotes
) {
    boolean isEmpty() {
        return SummaryText.safeList(summary).isEmpty()
                && SummaryText.safeList(topWords).isEmpty()
                && SummaryText.safeList(topics).isEmpty()
                && SummaryText.safeList(profiles).isEmpty()
                && SummaryText.safeList(tags).isEmpty()
                && SummaryText.safeList(quotes).isEmpty();
    }
}
