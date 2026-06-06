package cn.zerobot.chatsummary;

import java.util.List;
import java.util.Map;

record ReportData(
        String groupId,
        String groupName,
        ReportWindow window,
        List<RecordedMessage> messages,
        Map<String, UserAggregate> users,
        List<TopUser> topUsers,
        List<WordStat> topWords,
        List<Topic> topics,
        List<Profile> profiles,
        List<Interaction> interactions,
        List<Quote> quotes,
        List<Tag> tags,
        List<String> summary,
        int[] hourly,
        int totalMessages,
        int participantCount,
        int readableChars,
        int imageCount,
        int atCount,
        int faceCount,
        int fileCount,
        int peakHour,
        int activityScore
) {
    ReportData withEnhancement(AiReportEnhancement enhancement) {
        if (enhancement == null || enhancement.isEmpty()) {
            return this;
        }
        List<String> enhancedSummary = SummaryText.safeList(enhancement.summary()).isEmpty()
                ? summary
                : enhancement.summary();
        List<WordStat> enhancedTopWords = SummaryText.safeList(enhancement.topWords()).isEmpty()
                ? topWords
                : enhancement.topWords();
        List<Topic> enhancedTopics = SummaryText.safeList(enhancement.topics()).isEmpty()
                ? topics
                : enhancement.topics();
        List<Profile> enhancedProfiles = SummaryText.safeList(enhancement.profiles()).isEmpty()
                ? profiles
                : enhancement.profiles();
        List<Tag> enhancedTags = SummaryText.safeList(enhancement.tags()).isEmpty()
                ? tags
                : enhancement.tags();
        List<Quote> enhancedQuotes = SummaryText.safeList(enhancement.quotes()).isEmpty()
                ? quotes
                : enhancement.quotes();
        return new ReportData(
                groupId,
                groupName,
                window,
                messages,
                users,
                topUsers,
                enhancedTopWords,
                enhancedTopics,
                enhancedProfiles,
                interactions,
                enhancedQuotes,
                enhancedTags,
                enhancedSummary,
                hourly,
                totalMessages,
                participantCount,
                readableChars,
                imageCount,
                atCount,
                faceCount,
                fileCount,
                peakHour,
                activityScore
        );
    }
}
