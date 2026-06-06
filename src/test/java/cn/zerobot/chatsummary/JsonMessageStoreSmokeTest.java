package cn.zerobot.chatsummary;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;

public final class JsonMessageStoreSmokeTest {
    private JsonMessageStoreSmokeTest() {
    }

    public static void main(String[] args) throws Exception {
        Path root = Path.of("build", "tmp", "json-message-store");
        if (Files.exists(root)) {
            try (var files = Files.walk(root)) {
                files.sorted((a, b) -> b.compareTo(a)).forEach(path -> {
                    try {
                        Files.deleteIfExists(path);
                    } catch (Exception e) {
                        throw new IllegalStateException(e);
                    }
                });
            }
        }

        JsonMessageStore store = new JsonMessageStore(root, ZoneId.of("Asia/Shanghai"));
        Instant base = Instant.parse("2026-06-06T08:00:00Z");
        store.append(message("10086", 1, base, "第一条"));
        store.append(imageMessage("10086", 2, base.plusSeconds(60), "第二条 [图片]"));
        store.append(message("10086", 3, base.plusSeconds(120), "第三条"));

        List<RecordedMessage> messages = store.query("10086", base.plusSeconds(30), base.plusSeconds(90));
        if (messages.size() != 1 || !"第二条 [图片]".equals(messages.get(0).text())) {
            throw new IllegalStateException("Unexpected JSON message store query result: " + messages);
        }
        if (messages.get(0).images().size() != 1 || !"https://example.com/a.jpg".equals(messages.get(0).images().get(0).url())) {
            throw new IllegalStateException("Image attachments were not persisted: " + messages.get(0).images());
        }

        System.out.println(root.toAbsolutePath().normalize());
    }

    private static RecordedMessage message(String groupId, long messageId, Instant time, String text) {
        return new RecordedMessage(groupId, "1001", "测试用户", "member", text, time, messageId,
                0, 0, 0, 0);
    }

    private static RecordedMessage imageMessage(String groupId, long messageId, Instant time, String text) {
        return new RecordedMessage(groupId, "1001", "测试用户", "member", text, time, messageId,
                1, 0, 0, 0, List.of(new ImageAttachment(
                "a.jpg",
                "file-a",
                "https://example.com/a.jpg",
                "测试图片",
                "normal"
        )));
    }
}
