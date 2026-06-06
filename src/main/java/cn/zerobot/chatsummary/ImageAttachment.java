package cn.zerobot.chatsummary;

record ImageAttachment(
        String file,
        String fileId,
        String url,
        String summary,
        String subType
) {
    ImageAttachment {
        file = SummaryText.nullTo(file, "").trim();
        fileId = SummaryText.nullTo(fileId, "").trim();
        url = SummaryText.nullTo(url, "").trim();
        summary = SummaryText.cleanText(summary);
        subType = SummaryText.nullTo(subType, "").trim();
    }

    boolean hasSource() {
        return !url.isBlank() || !file.isBlank();
    }

    String description() {
        String cleanedSummary = SummaryText.cleanText(summary)
                .replace("[图片]", "")
                .trim();
        if (!cleanedSummary.isBlank()) {
            return cleanedSummary;
        }
        if (!subType.isBlank()) {
            return subType + " 图片";
        }
        return "图片";
    }
}
