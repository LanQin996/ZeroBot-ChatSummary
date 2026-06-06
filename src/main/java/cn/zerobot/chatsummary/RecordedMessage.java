package cn.zerobot.chatsummary;

import java.time.Instant;

record RecordedMessage(
        String groupId,
        String userId,
        String displayName,
        String role,
        String text,
        Instant time,
        long messageId,
        int imageCount,
        int atCount,
        int faceCount,
        int fileCount
) {
    int readableLength() {
        String normalized = SummaryText.nullTo(text, "")
                .replace("[图片]", "")
                .replace("[表情]", "")
                .replace("[文件]", "")
                .trim();
        return normalized.codePointCount(0, normalized.length());
    }
}
