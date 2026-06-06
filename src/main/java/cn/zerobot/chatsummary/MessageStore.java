package cn.zerobot.chatsummary;

import java.io.IOException;
import java.time.Instant;
import java.util.List;

interface MessageStore {
    void append(RecordedMessage message) throws IOException;

    List<RecordedMessage> query(String groupId, Instant from, Instant to) throws IOException;

    void prune(Instant cutoff) throws IOException;
}
