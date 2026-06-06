package cn.zerobot.chatsummary;

import java.time.ZoneId;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class SummaryText {
    static final Pattern CQ_AT_PATTERN = Pattern.compile("\\[CQ:at,[^\\]]*qq=([^,\\]]+)[^\\]]*\\]");
    static final Pattern CQ_IMAGE_PATTERN = Pattern.compile("\\[CQ:image[^\\]]*\\]");
    static final Pattern CQ_FACE_PATTERN = Pattern.compile("\\[CQ:face[^\\]]*\\]");
    static final Pattern CQ_FILE_PATTERN = Pattern.compile("\\[CQ:file[^\\]]*\\]");
    static final Pattern ENGLISH_WORD_PATTERN = Pattern.compile("(?iu)[a-z][a-z0-9_+#.\\-]{1,}");
    static final Pattern CHINESE_RUN_PATTERN = Pattern.compile("[\\p{IsHan}]{2,}");

    private static final Pattern SPACE_PATTERN = Pattern.compile("\\s+");
    private static final Pattern CQ_OTHER_PATTERN = Pattern.compile("\\[CQ:[^\\]]+\\]");

    private SummaryText() {
    }

    static String cleanText(String text) {
        if (text == null) {
            return "";
        }
        String cleaned = CQ_AT_PATTERN.matcher(text).replaceAll(" @$1 ");
        cleaned = CQ_IMAGE_PATTERN.matcher(cleaned).replaceAll(" [图片] ");
        cleaned = CQ_FACE_PATTERN.matcher(cleaned).replaceAll(" [表情] ");
        cleaned = CQ_FILE_PATTERN.matcher(cleaned).replaceAll(" [文件] ");
        cleaned = CQ_OTHER_PATTERN.matcher(cleaned).replaceAll(" ");
        cleaned = cleaned.replace('\u00A0', ' ');
        return SPACE_PATTERN.matcher(cleaned).replaceAll(" ").trim();
    }

    static int countMatches(Pattern pattern, String text) {
        Matcher matcher = pattern.matcher(nullTo(text, ""));
        int count = 0;
        while (matcher.find()) {
            count++;
        }
        return count;
    }

    static ZoneId resolveZone(String value) {
        try {
            return value == null || value.isBlank() ? ZoneId.systemDefault() : ZoneId.of(value.trim());
        } catch (Exception ignored) {
            return ZoneId.systemDefault();
        }
    }

    static String firstNotBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value.trim();
            }
        }
        return "";
    }

    static String nullTo(String value, String fallback) {
        return value == null ? fallback : value;
    }

    static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    static <T> List<T> safeList(List<T> value) {
        return value == null ? List.of() : value;
    }

    static String formatHour(int hour) {
        int normalized = ((hour % 24) + 24) % 24;
        return String.format(Locale.ROOT, "%02d:00-%02d:00", normalized, (normalized + 1) % 24);
    }

    static int peakHour(int[] hourly) {
        int bestHour = 0;
        int bestCount = -1;
        for (int i = 0; i < hourly.length; i++) {
            if (hourly[i] > bestCount) {
                bestCount = hourly[i];
                bestHour = i;
            }
        }
        return bestHour;
    }

    static String trim(String text, int maxLength) {
        if (text == null) {
            return "";
        }
        String normalized = cleanText(text);
        if (normalized.codePointCount(0, normalized.length()) <= maxLength) {
            return normalized;
        }
        int end = normalized.offsetByCodePoints(0, Math.max(0, maxLength - 3));
        return normalized.substring(0, end) + "...";
    }
}
