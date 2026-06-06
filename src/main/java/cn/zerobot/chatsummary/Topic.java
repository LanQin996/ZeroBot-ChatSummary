package cn.zerobot.chatsummary;

import java.util.List;

record Topic(String title, int count, List<String> speakers, List<String> keywords, String summary) {
}
