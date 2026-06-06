package cn.zerobot.chatsummary;

import java.util.ArrayList;
import java.util.List;

public class Settings {
    private List<String> commands = new ArrayList<>(List.of("/群总结", "/群聊总结", "/summary"));
    private String reportPermission = "chat-summary.report";
    private boolean reportDefaultAllowed = true;
    private String noPermissionReply = "你没有权限生成群聊总结";
    private boolean sendGeneratingReply = false;
    private String timeZone = "Asia/Shanghai";
    private int defaultHours = 24;
    private int maxHours = 168;
    private int retentionHours = 168;
    private int maxMessagesPerGroup = 6000;
    private boolean storageEnabled = true;
    private int storageRetentionDays = 14;
    private int cleanupIntervalMinutes = 60;
    private boolean avatarEnabled = true;
    private int avatarCacheDays = 7;
    private int avatarDownloadTimeoutSeconds = 5;
    private boolean imageCacheEnabled = true;
    private int imageCacheDays = 7;
    private int imageDownloadTimeoutSeconds = 10;
    private int imagePreviewLimit = 6;
    private boolean aiImageInputEnabled = false;
    private int aiImageInputLimit = 4;
    private boolean ignoreCommandLikeMessages = true;
    private int reportWidth = 560;
    private int renderScale = 2;
    private int topUserLimit = 12;
    private int hotWordLimit = 60;
    private int minHotWordCount = 2;
    private int topicLimit = 5;
    private int profileLimit = 6;
    private int interactionLimit = 7;
    private int quoteLimit = 5;
    private int minQuoteLength = 10;
    private int maxQuoteLength = 90;
    private boolean aiEnabled = false;
    private String aiBaseUrl = "https://api.openai.com/v1";
    private String aiApiKey = "";
    private String aiApiKeyEnv = "OPENAI_API_KEY";
    private String aiModel = "gpt-5.4-nano";
    private int aiTimeoutSeconds = 20;
    private int aiMaxMessages = 360;
    private int aiMaxChars = 30000;
    private int aiMaxOutputTokens = 2200;
    private double aiTemperature = -1;
    private List<String> stopWords = new ArrayList<>(List.of(
            "这个", "那个", "我们", "你们", "他们", "什么", "不是", "就是", "没有", "可以",
            "然后", "现在", "因为", "所以", "一个", "一下", "感觉", "真的", "还是", "但是",
            "如果", "怎么", "这么", "自己", "大家", "今天", "明天", "昨天", "时候", "已经",
            "可能", "应该", "不会", "不要", "知道", "哈哈", "哈哈哈", "啊啊", "图片", "表情",
            "文件", "回复", "the", "and", "you", "that", "this", "with", "for", "are", "not"
    ));

    public List<String> getCommands() {
        return commands;
    }

    public void setCommands(List<String> commands) {
        this.commands = commands;
    }

    public String getReportPermission() {
        return reportPermission;
    }

    public void setReportPermission(String reportPermission) {
        this.reportPermission = reportPermission;
    }

    public boolean isReportDefaultAllowed() {
        return reportDefaultAllowed;
    }

    public void setReportDefaultAllowed(boolean reportDefaultAllowed) {
        this.reportDefaultAllowed = reportDefaultAllowed;
    }

    public String getNoPermissionReply() {
        return noPermissionReply;
    }

    public void setNoPermissionReply(String noPermissionReply) {
        this.noPermissionReply = noPermissionReply;
    }

    public boolean isSendGeneratingReply() {
        return sendGeneratingReply;
    }

    public void setSendGeneratingReply(boolean sendGeneratingReply) {
        this.sendGeneratingReply = sendGeneratingReply;
    }

    public String getTimeZone() {
        return timeZone;
    }

    public void setTimeZone(String timeZone) {
        this.timeZone = timeZone;
    }

    public int getDefaultHours() {
        return defaultHours;
    }

    public void setDefaultHours(int defaultHours) {
        this.defaultHours = defaultHours;
    }

    public int getMaxHours() {
        return maxHours;
    }

    public void setMaxHours(int maxHours) {
        this.maxHours = maxHours;
    }

    public int getRetentionHours() {
        return retentionHours;
    }

    public void setRetentionHours(int retentionHours) {
        this.retentionHours = retentionHours;
    }

    public int getMaxMessagesPerGroup() {
        return maxMessagesPerGroup;
    }

    public void setMaxMessagesPerGroup(int maxMessagesPerGroup) {
        this.maxMessagesPerGroup = maxMessagesPerGroup;
    }

    public boolean isStorageEnabled() {
        return storageEnabled;
    }

    public void setStorageEnabled(boolean storageEnabled) {
        this.storageEnabled = storageEnabled;
    }

    public int getStorageRetentionDays() {
        return storageRetentionDays;
    }

    public void setStorageRetentionDays(int storageRetentionDays) {
        this.storageRetentionDays = storageRetentionDays;
    }

    public int getCleanupIntervalMinutes() {
        return cleanupIntervalMinutes;
    }

    public void setCleanupIntervalMinutes(int cleanupIntervalMinutes) {
        this.cleanupIntervalMinutes = cleanupIntervalMinutes;
    }

    public boolean isAvatarEnabled() {
        return avatarEnabled;
    }

    public void setAvatarEnabled(boolean avatarEnabled) {
        this.avatarEnabled = avatarEnabled;
    }

    public int getAvatarCacheDays() {
        return avatarCacheDays;
    }

    public void setAvatarCacheDays(int avatarCacheDays) {
        this.avatarCacheDays = avatarCacheDays;
    }

    public int getAvatarDownloadTimeoutSeconds() {
        return avatarDownloadTimeoutSeconds;
    }

    public void setAvatarDownloadTimeoutSeconds(int avatarDownloadTimeoutSeconds) {
        this.avatarDownloadTimeoutSeconds = avatarDownloadTimeoutSeconds;
    }

    public boolean isImageCacheEnabled() {
        return imageCacheEnabled;
    }

    public void setImageCacheEnabled(boolean imageCacheEnabled) {
        this.imageCacheEnabled = imageCacheEnabled;
    }

    public int getImageCacheDays() {
        return imageCacheDays;
    }

    public void setImageCacheDays(int imageCacheDays) {
        this.imageCacheDays = imageCacheDays;
    }

    public int getImageDownloadTimeoutSeconds() {
        return imageDownloadTimeoutSeconds;
    }

    public void setImageDownloadTimeoutSeconds(int imageDownloadTimeoutSeconds) {
        this.imageDownloadTimeoutSeconds = imageDownloadTimeoutSeconds;
    }

    public int getImagePreviewLimit() {
        return imagePreviewLimit;
    }

    public void setImagePreviewLimit(int imagePreviewLimit) {
        this.imagePreviewLimit = imagePreviewLimit;
    }

    public boolean isAiImageInputEnabled() {
        return aiImageInputEnabled;
    }

    public void setAiImageInputEnabled(boolean aiImageInputEnabled) {
        this.aiImageInputEnabled = aiImageInputEnabled;
    }

    public int getAiImageInputLimit() {
        return aiImageInputLimit;
    }

    public void setAiImageInputLimit(int aiImageInputLimit) {
        this.aiImageInputLimit = aiImageInputLimit;
    }

    public boolean isIgnoreCommandLikeMessages() {
        return ignoreCommandLikeMessages;
    }

    public void setIgnoreCommandLikeMessages(boolean ignoreCommandLikeMessages) {
        this.ignoreCommandLikeMessages = ignoreCommandLikeMessages;
    }

    public int getReportWidth() {
        return reportWidth;
    }

    public void setReportWidth(int reportWidth) {
        this.reportWidth = reportWidth;
    }

    public int getRenderScale() {
        return renderScale;
    }

    public void setRenderScale(int renderScale) {
        this.renderScale = renderScale;
    }

    public int getTopUserLimit() {
        return topUserLimit;
    }

    public void setTopUserLimit(int topUserLimit) {
        this.topUserLimit = topUserLimit;
    }

    public int getHotWordLimit() {
        return hotWordLimit;
    }

    public void setHotWordLimit(int hotWordLimit) {
        this.hotWordLimit = hotWordLimit;
    }

    public int getMinHotWordCount() {
        return minHotWordCount;
    }

    public void setMinHotWordCount(int minHotWordCount) {
        this.minHotWordCount = minHotWordCount;
    }

    public int getTopicLimit() {
        return topicLimit;
    }

    public void setTopicLimit(int topicLimit) {
        this.topicLimit = topicLimit;
    }

    public int getProfileLimit() {
        return profileLimit;
    }

    public void setProfileLimit(int profileLimit) {
        this.profileLimit = profileLimit;
    }

    public int getInteractionLimit() {
        return interactionLimit;
    }

    public void setInteractionLimit(int interactionLimit) {
        this.interactionLimit = interactionLimit;
    }

    public int getQuoteLimit() {
        return quoteLimit;
    }

    public void setQuoteLimit(int quoteLimit) {
        this.quoteLimit = quoteLimit;
    }

    public int getMinQuoteLength() {
        return minQuoteLength;
    }

    public void setMinQuoteLength(int minQuoteLength) {
        this.minQuoteLength = minQuoteLength;
    }

    public int getMaxQuoteLength() {
        return maxQuoteLength;
    }

    public void setMaxQuoteLength(int maxQuoteLength) {
        this.maxQuoteLength = maxQuoteLength;
    }

    public boolean isAiEnabled() {
        return aiEnabled;
    }

    public void setAiEnabled(boolean aiEnabled) {
        this.aiEnabled = aiEnabled;
    }

    public String getAiBaseUrl() {
        return aiBaseUrl;
    }

    public void setAiBaseUrl(String aiBaseUrl) {
        this.aiBaseUrl = aiBaseUrl;
    }

    public String getAiApiKey() {
        return aiApiKey;
    }

    public void setAiApiKey(String aiApiKey) {
        this.aiApiKey = aiApiKey;
    }

    public String getAiApiKeyEnv() {
        return aiApiKeyEnv;
    }

    public void setAiApiKeyEnv(String aiApiKeyEnv) {
        this.aiApiKeyEnv = aiApiKeyEnv;
    }

    public String getAiModel() {
        return aiModel;
    }

    public void setAiModel(String aiModel) {
        this.aiModel = aiModel;
    }

    public int getAiTimeoutSeconds() {
        return aiTimeoutSeconds;
    }

    public void setAiTimeoutSeconds(int aiTimeoutSeconds) {
        this.aiTimeoutSeconds = aiTimeoutSeconds;
    }

    public int getAiMaxMessages() {
        return aiMaxMessages;
    }

    public void setAiMaxMessages(int aiMaxMessages) {
        this.aiMaxMessages = aiMaxMessages;
    }

    public int getAiMaxChars() {
        return aiMaxChars;
    }

    public void setAiMaxChars(int aiMaxChars) {
        this.aiMaxChars = aiMaxChars;
    }

    public int getAiMaxOutputTokens() {
        return aiMaxOutputTokens;
    }

    public void setAiMaxOutputTokens(int aiMaxOutputTokens) {
        this.aiMaxOutputTokens = aiMaxOutputTokens;
    }

    public double getAiTemperature() {
        return aiTemperature;
    }

    public void setAiTemperature(double aiTemperature) {
        this.aiTemperature = aiTemperature;
    }

    public List<String> getStopWords() {
        return stopWords;
    }

    public void setStopWords(List<String> stopWords) {
        this.stopWords = stopWords;
    }
}
