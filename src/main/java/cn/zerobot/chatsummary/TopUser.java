package cn.zerobot.chatsummary;

record TopUser(String userId, String displayName, int count, int readableChars, int peakHour, String topWord) {
}
