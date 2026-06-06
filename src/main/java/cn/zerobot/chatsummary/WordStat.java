package cn.zerobot.chatsummary;

import java.util.LinkedHashSet;
import java.util.Set;

final class WordStat {
    final String word;
    int count;
    private double weight;
    private final Set<String> speakers = new LinkedHashSet<>();

    WordStat(String word) {
        this.word = word;
    }

    void add(String speaker, double value) {
        count++;
        weight += value;
        speakers.add(speaker);
    }

    String word() {
        return word;
    }

    double score() {
        return weight + speakers.size() * 0.8;
    }
}
