package cn.zerobot.chatsummary;

import java.util.List;

record MessageContent(String text, List<ImageAttachment> images, int imageCount, int atCount, int faceCount,
                      int fileCount) {
    MessageContent {
        images = images == null ? List.of() : List.copyOf(images);
        imageCount = Math.max(imageCount, images.size());
    }
}
