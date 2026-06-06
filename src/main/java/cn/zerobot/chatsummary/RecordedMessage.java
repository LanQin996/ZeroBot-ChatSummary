package cn.zerobot.chatsummary;

import java.time.Instant;
import java.util.List;

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
        int fileCount,
        List<ImageAttachment> images
) {
    RecordedMessage {
        images = images == null ? List.of() : List.copyOf(images);
        imageCount = Math.max(imageCount, images.size());
    }

    RecordedMessage(String groupId, String userId, String displayName, String role, String text, Instant time,
                    long messageId, int imageCount, int atCount, int faceCount, int fileCount) {
        this(groupId, userId, displayName, role, text, time, messageId, imageCount, atCount, faceCount, fileCount,
                List.of());
    }

    int readableLength() {
        String normalized = SummaryText.nullTo(text, "")
                .replace("[图片]", "")
                .replace("[表情]", "")
                .replace("[文件]", "")
                .trim();
        return normalized.codePointCount(0, normalized.length());
    }
}
