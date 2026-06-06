package cn.zerobot.chatsummary;

import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Comparator;
import java.util.Deque;
import java.util.List;

final class GroupBuffer {
    private final Deque<RecordedMessage> messages = new ArrayDeque<>();

    synchronized void add(RecordedMessage message, Instant cutoff, int maxSize) {
        messages.addLast(message);
        prune(cutoff, maxSize);
    }

    synchronized List<RecordedMessage> snapshot(Instant from, Instant to) {
        return messages.stream()
                .filter(message -> !message.time().isBefore(from) && message.time().isBefore(to.plusMillis(1)))
                .sorted(Comparator.comparing(RecordedMessage::time))
                .toList();
    }

    synchronized boolean pruneAndIsEmpty(Instant cutoff, int maxSize) {
        prune(cutoff, maxSize);
        return messages.isEmpty();
    }

    private void prune(Instant cutoff, int maxSize) {
        while (!messages.isEmpty() && messages.peekFirst().time().isBefore(cutoff)) {
            messages.removeFirst();
        }
        while (messages.size() > maxSize) {
            messages.removeFirst();
        }
    }
}
