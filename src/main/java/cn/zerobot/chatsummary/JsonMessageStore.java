package cn.zerobot.chatsummary;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Pattern;

final class JsonMessageStore implements MessageStore {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Pattern SAFE_PATH_PART = Pattern.compile("[^a-zA-Z0-9._-]");

    private final Path root;
    private final ZoneId zoneId;

    JsonMessageStore(Path root, ZoneId zoneId) throws IOException {
        this.root = root;
        this.zoneId = zoneId;
        Files.createDirectories(root);
    }

    @Override
    public void append(RecordedMessage message) throws IOException {
        Path file = fileFor(message.groupId(), LocalDate.ofInstant(message.time(), zoneId));
        Files.createDirectories(file.getParent());
        synchronized (lockFor(message.groupId())) {
            try (BufferedWriter writer = Files.newBufferedWriter(file, StandardCharsets.UTF_8,
                    java.nio.file.StandardOpenOption.CREATE,
                    java.nio.file.StandardOpenOption.WRITE,
                    java.nio.file.StandardOpenOption.APPEND)) {
                writer.write(MAPPER.writeValueAsString(toJson(message)));
                writer.newLine();
            }
        }
    }

    @Override
    public List<RecordedMessage> query(String groupId, Instant from, Instant to) throws IOException {
        List<RecordedMessage> result = new ArrayList<>();
        LocalDate start = LocalDate.ofInstant(from, zoneId);
        LocalDate end = LocalDate.ofInstant(to, zoneId);
        for (LocalDate date = start; !date.isAfter(end); date = date.plusDays(1)) {
            Path file = fileFor(groupId, date);
            if (!Files.isRegularFile(file)) {
                continue;
            }
            readFile(file, from, to, result);
        }
        return result.stream()
                .sorted(Comparator.comparing(RecordedMessage::time).thenComparingLong(RecordedMessage::messageId))
                .toList();
    }

    @Override
    public void prune(Instant cutoff) throws IOException {
        if (!Files.isDirectory(root)) {
            return;
        }
        LocalDate cutoffDate = LocalDate.ofInstant(cutoff, zoneId);
        try (DirectoryStream<Path> groups = Files.newDirectoryStream(root)) {
            for (Path groupDir : groups) {
                if (!Files.isDirectory(groupDir)) {
                    continue;
                }
                pruneGroup(groupDir, cutoffDate);
            }
        }
    }

    private void pruneGroup(Path groupDir, LocalDate cutoffDate) throws IOException {
        try (DirectoryStream<Path> files = Files.newDirectoryStream(groupDir, "*.jsonl")) {
            for (Path file : files) {
                LocalDate date = parseDate(file.getFileName().toString());
                if (date != null && date.isBefore(cutoffDate)) {
                    Files.deleteIfExists(file);
                }
            }
        }
        try (DirectoryStream<Path> remaining = Files.newDirectoryStream(groupDir)) {
            if (!remaining.iterator().hasNext()) {
                Files.deleteIfExists(groupDir);
            }
        }
    }

    private void readFile(Path file, Instant from, Instant to, List<RecordedMessage> output) throws IOException {
        try (BufferedReader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isBlank()) {
                    continue;
                }
                RecordedMessage message = fromJson(MAPPER.readTree(line));
                if (!message.time().isBefore(from) && !message.time().isAfter(to)) {
                    output.add(message);
                }
            }
        }
    }

    private ObjectNode toJson(RecordedMessage message) {
        ObjectNode node = MAPPER.createObjectNode();
        node.put("groupId", message.groupId());
        node.put("userId", message.userId());
        node.put("displayName", message.displayName());
        node.put("role", message.role());
        node.put("text", message.text());
        node.put("time", message.time().toEpochMilli());
        node.put("messageId", message.messageId());
        node.put("imageCount", message.imageCount());
        node.put("atCount", message.atCount());
        node.put("faceCount", message.faceCount());
        node.put("fileCount", message.fileCount());
        return node;
    }

    private RecordedMessage fromJson(JsonNode node) {
        return new RecordedMessage(
                node.path("groupId").asText(""),
                node.path("userId").asText("unknown"),
                node.path("displayName").asText(node.path("userId").asText("unknown")),
                node.path("role").asText(""),
                node.path("text").asText(""),
                Instant.ofEpochMilli(node.path("time").asLong()),
                node.path("messageId").asLong(),
                node.path("imageCount").asInt(),
                node.path("atCount").asInt(),
                node.path("faceCount").asInt(),
                node.path("fileCount").asInt()
        );
    }

    private Path fileFor(String groupId, LocalDate date) {
        return root.resolve(safePart(groupId)).resolve(date + ".jsonl");
    }

    private String safePart(String value) {
        String safe = SAFE_PATH_PART.matcher(SummaryText.nullTo(value, "unknown")).replaceAll("_");
        return safe.isBlank() ? "unknown" : safe;
    }

    private String lockFor(String groupId) {
        return safePart(groupId).intern();
    }

    private LocalDate parseDate(String fileName) {
        if (!fileName.endsWith(".jsonl")) {
            return null;
        }
        try {
            return LocalDate.parse(fileName.substring(0, fileName.length() - 6));
        } catch (Exception ignored) {
            return null;
        }
    }
}
