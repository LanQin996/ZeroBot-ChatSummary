package cn.zerobot.chatsummary;

import java.awt.AlphaComposite;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.GraphicsEnvironment;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.Shape;
import java.awt.geom.Ellipse2D;
import java.awt.geom.RoundRectangle2D;
import java.awt.image.BufferedImage;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

final class ReportRenderer {
    private static final Color BACKGROUND = new Color(35, 32, 33);
    private static final Color PANEL = new Color(61, 58, 60);
    private static final Color CARD = new Color(82, 79, 82);
    private static final Color CARD_DARK = new Color(49, 46, 48);
    private static final Color TEXT = new Color(246, 239, 231);
    private static final Color MUTED = new Color(191, 181, 181);
    private static final Color SOFT = new Color(153, 146, 148);
    private static final Color GOLD = new Color(255, 188, 55);
    private static final Color ORANGE = new Color(255, 132, 56);
    private static final Color BLUE = new Color(69, 143, 255);
    private static final Color TEAL = new Color(50, 214, 180);
    private static final Color PINK = new Color(255, 100, 139);
    private static final Color VIOLET = new Color(142, 115, 255);
    private static final Color GREEN = new Color(111, 221, 91);
    private static final Color[] ACCENTS = {GOLD, BLUE, ORANGE, TEAL, PINK, VIOLET, GREEN};
    private static final DateTimeFormatter TIME_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    private final Settings settings;
    private final ZoneId zoneId;
    private final AvatarService avatarService;
    private final ImageCacheService imageCacheService;
    private final String fontFamily;

    ReportRenderer(Settings settings, ZoneId zoneId) {
        this(settings, zoneId, null, null);
    }

    ReportRenderer(Settings settings, ZoneId zoneId, AvatarService avatarService) {
        this(settings, zoneId, avatarService, null);
    }

    ReportRenderer(Settings settings, ZoneId zoneId, AvatarService avatarService, ImageCacheService imageCacheService) {
        this.settings = settings;
        this.zoneId = zoneId;
        this.avatarService = avatarService;
        this.imageCacheService = imageCacheService;
        this.fontFamily = chooseFontFamily();
    }

    BufferedImage render(ReportData data) {
        int scale = SummaryText.clamp(settings.getRenderScale(), 1, 3);
        int width = Math.max(520, settings.getReportWidth());
        int canvasHeight = Math.max(5200, width * 12);
        BufferedImage canvas = new BufferedImage(width * scale, canvasHeight * scale, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = canvas.createGraphics();
        g.scale(scale, scale);
        setup(g);
        g.setColor(BACKGROUND);
        g.fillRect(0, 0, width, canvasHeight);

        int margin = 14;
        int innerWidth = width - margin * 2;
        int y = 14;
        y = drawHeader(g, data, margin, y, innerWidth);
        y = drawLead(g, data, margin, y + 8, innerWidth);
        y = drawMetrics(g, data, margin, y + 8, innerWidth);
        y = drawImagePreview(g, data, margin, y + 8, innerWidth);
        y = drawParticipants(g, data, margin, y + 8, innerWidth);
        y = drawActivity(g, data, margin, y + 8, innerWidth);
        y = drawTopUsers(g, data, margin, y + 8, innerWidth);
        y = drawWordCloud(g, data, margin, y + 8, innerWidth);
        y = drawTags(g, data, margin, y + 8, innerWidth);
        y = drawInteractions(g, data, margin, y + 8, innerWidth);
        y = drawSummary(g, data, margin, y + 8, innerWidth);
        y = drawTopics(g, data, margin, y + 8, innerWidth);
        y = drawProfiles(g, data, margin, y + 8, innerWidth);
        y = drawQuotes(g, data, margin, y + 8, innerWidth);
        drawFooter(g, margin, y + 6, innerWidth);
        y += 42;
        g.dispose();

        int height = Math.min(canvasHeight, y + 20);
        BufferedImage cropped = new BufferedImage(width * scale, height * scale, BufferedImage.TYPE_INT_RGB);
        Graphics2D cg = cropped.createGraphics();
        cg.drawImage(canvas, 0, 0, null);
        cg.dispose();
        return cropped;
    }

    private int drawHeader(Graphics2D g, ReportData data, int x, int y, int w) {
        int h = 72;
        drawRound(g, x, y, w, h, 6, BACKGROUND);
        drawAvatar(g, x + 8, y + 9, 44, AvatarService.Type.GROUP, data.groupId(), data.groupName());
        drawText(g, "群聊总结报告", x + 62, y + 28, 22, Font.BOLD, TEXT);
        drawText(g, data.groupName(), x + 62, y + 48, 12, Font.PLAIN, MUTED);
        String range = formatTime(data.window().from()) + " - " + formatTime(data.window().to());
        drawText(g, data.window().label() + " | " + range, x + 62, y + 63, 10, Font.PLAIN, SOFT);
        drawPill(g, x + w - 88, y + 12, 76, 24, "热度 " + data.activityScore(), GOLD, true);
        return y + h;
    }

    private int drawLead(Graphics2D g, ReportData data, int x, int y, int w) {
        int h = 28;
        drawRound(g, x, y, w, h, 6, PANEL);
        drawText(g, "以下内容由聊天记录自动生成", x + 12, y + 19, 12, Font.BOLD, TEXT);
        return y + h;
    }

    private int drawMetrics(Graphics2D g, ReportData data, int x, int y, int w) {
        List<Metric> metrics = List.of(
                new Metric("聊天条数", String.valueOf(data.totalMessages()), GOLD),
                new Metric("有效消息", String.valueOf(data.messages().size()), TEAL),
                new Metric("人均消息", String.format("%.1f", data.totalMessages() / (double) Math.max(1, data.participantCount())), BLUE),
                new Metric("参与人数", String.valueOf(data.participantCount()), PINK),
                new Metric("文字量", String.valueOf(data.readableChars()), ORANGE),
                new Metric("图片/表情", String.valueOf(data.imageCount() + data.faceCount()), VIOLET),
                new Metric("话题数", String.valueOf(data.topics().size()), GREEN),
                new Metric("@ 次数", String.valueOf(data.atCount()), BLUE),
                new Metric("高峰时段", SummaryText.formatHour(data.peakHour()), GOLD)
        );
        int gap = 6;
        int columns = 3;
        int cellW = (w - gap * (columns - 1)) / columns;
        int cellH = 52;
        int h = cellH * 3 + gap * 2;
        for (int i = 0; i < metrics.size(); i++) {
            int col = i % columns;
            int row = i / columns;
            int cx = x + col * (cellW + gap);
            int cy = y + row * (cellH + gap);
            Metric metric = metrics.get(i);
            drawRound(g, cx, cy, cellW, cellH, 5, PANEL);
            g.setColor(metric.color());
            g.fillRoundRect(cx + 9, cy + 10, 3, cellH - 20, 3, 3);
            drawText(g, metric.label(), cx + 18, cy + 21, 10, Font.PLAIN, MUTED);
            drawText(g, metric.value(), cx + 18, cy + 43, 17, Font.BOLD, TEXT);
        }
        return y + h;
    }

    private int drawImagePreview(Graphics2D g, ReportData data, int x, int y, int w) {
        if (imageCacheService == null) {
            return y - 8;
        }
        List<ImageCacheService.ReportImage> images = imageCacheService.reportImages(data);
        if (images.isEmpty()) {
            return y - 8;
        }
        int gap = 6;
        int columns = 3;
        int tileW = (w - 24 - gap * (columns - 1)) / columns;
        int imageH = 92;
        int tileH = imageH + 36;
        int rows = (images.size() + columns - 1) / columns;
        int h = 36 + rows * tileH + Math.max(0, rows - 1) * gap + 10;
        drawSection(g, x, y, w, h, "图片速览");
        for (int i = 0; i < images.size(); i++) {
            ImageCacheService.ReportImage image = images.get(i);
            int col = i % columns;
            int row = i / columns;
            int cx = x + 12 + col * (tileW + gap);
            int cy = y + 36 + row * (tileH + gap);
            drawRound(g, cx, cy, tileW, tileH, 5, CARD);
            drawThumbnail(g, image.image(), cx + 5, cy + 5, tileW - 10, imageH, 4, false);
            drawText(g, SummaryText.trim(image.caption(), 12), cx + 7, cy + imageH + 20, 9, Font.BOLD, TEXT);
            drawText(g, SummaryText.trim(image.sender(), 10) + " · " + formatShortTime(image.time()),
                    cx + 7, cy + imageH + 33, 8, Font.PLAIN, MUTED);
        }
        return y + h;
    }

    private int drawParticipants(Graphics2D g, ReportData data, int x, int y, int w) {
        List<TopUser> participants = data.topUsers().stream().limit(32).toList();
        Font chipFont = font(10, Font.PLAIN);
        FontMetrics fm = g.getFontMetrics(chipFont);
        int rows = 1;
        int cursor = 8;
        for (TopUser user : participants) {
            int chipW = Math.min(112, fm.stringWidth(user.displayName()) + 32);
            if (cursor + chipW > w - 8) {
                rows++;
                cursor = 8;
            }
            cursor += chipW + 5;
        }
        int h = 38 + rows * 22 + 10;
        drawSection(g, x, y, w, h, "参与人");
        int cx = x + 10;
        int cy = y + 34;
        g.setFont(chipFont);
        for (int i = 0; i < participants.size(); i++) {
            TopUser user = participants.get(i);
            int chipW = Math.min(112, fm.stringWidth(user.displayName()) + 32);
            if (cx + chipW > x + w - 8) {
                cx = x + 10;
                cy += 22;
            }
            drawNameChip(g, cx, cy, chipW, 17, user.userId(), user.displayName(), ACCENTS[i % ACCENTS.length]);
            cx += chipW + 5;
        }
        return y + h;
    }

    private int drawActivity(Graphics2D g, ReportData data, int x, int y, int w) {
        int h = 126;
        drawSection(g, x, y, w, h, "活跃分布");
        int chartX = x + 14;
        int chartY = y + 42;
        int chartW = w - 28;
        int chartH = 60;
        int max = 1;
        for (int value : data.hourly()) {
            max = Math.max(max, value);
        }
        g.setColor(new Color(91, 87, 91));
        g.drawLine(chartX, chartY + chartH, chartX + chartW, chartY + chartH);
        int gap = 3;
        int barW = Math.max(5, (chartW - gap * 23) / 24);
        for (int i = 0; i < 24; i++) {
            int barH = (int) Math.round(data.hourly()[i] * (chartH - 14) / (double) max);
            int bx = chartX + i * (barW + gap);
            int by = chartY + chartH - barH;
            g.setColor(i == data.peakHour() ? GOLD : ACCENTS[i % ACCENTS.length]);
            g.fillRoundRect(bx, by, barW, Math.max(2, barH), 2, 2);
        }
        drawText(g, "0", chartX, chartY + chartH + 14, 9, Font.PLAIN, SOFT);
        drawText(g, "6", chartX + chartW / 4, chartY + chartH + 14, 9, Font.PLAIN, SOFT);
        drawText(g, "12", chartX + chartW / 2, chartY + chartH + 14, 9, Font.PLAIN, SOFT);
        drawText(g, "18", chartX + chartW * 3 / 4, chartY + chartH + 14, 9, Font.PLAIN, SOFT);
        drawText(g, "23", chartX + chartW - 14, chartY + chartH + 14, 9, Font.PLAIN, SOFT);
        drawText(g, "高峰：" + SummaryText.formatHour(data.peakHour()), chartX, y + h - 8, 9, Font.PLAIN, MUTED);
        return y + h;
    }

    private int drawTopUsers(Graphics2D g, ReportData data, int x, int y, int w) {
        int count = Math.min(8, data.topUsers().size());
        int h = 38 + count * 25 + 10;
        drawSection(g, x, y, w, h, "活跃用户");
        int max = data.topUsers().stream().mapToInt(TopUser::count).max().orElse(1);
        for (int i = 0; i < count; i++) {
            TopUser user = data.topUsers().get(i);
            int rowY = y + 36 + i * 25;
            drawAvatar(g, x + 14, rowY + 1, 18, AvatarService.Type.USER, user.userId(), user.displayName());
            drawText(g, "#" + (i + 1), x + 39, rowY + 15, 10, Font.BOLD, ACCENTS[i % ACCENTS.length]);
            drawText(g, user.displayName(), x + 66, rowY + 15, 10, Font.PLAIN, TEXT);
            int barX = x + 230;
            int barW = w - 300;
            drawRound(g, barX, rowY + 6, barW, 8, 4, new Color(72, 68, 72));
            g.setColor(ACCENTS[i % ACCENTS.length]);
            g.fillRoundRect(barX, rowY + 6, Math.max(5, user.count() * barW / max), 8, 4, 4);
            drawTextRight(g, user.count() + "条", x + w - 14, rowY + 15, 10, Font.PLAIN, MUTED);
        }
        return y + h;
    }

    private int drawWordCloud(Graphics2D g, ReportData data, int x, int y, int w) {
        int h = 176;
        drawSection(g, x, y, w, h, "热词词云");
        if (data.topWords().isEmpty()) {
            drawText(g, "暂时没有足够集中的热词。", x + 14, y + 56, 12, Font.PLAIN, MUTED);
            return y + h;
        }
        double max = data.topWords().stream().mapToDouble(WordStat::score).max().orElse(1);
        int cloudX = x + 10;
        int cloudY = y + 36;
        int cloudW = w - 20;
        int cloudH = h - 48;
        List<Rectangle> used = new ArrayList<>();
        for (int i = 0; i < Math.min(70, data.topWords().size()); i++) {
            WordStat word = data.topWords().get(i);
            int size = 10 + (int) Math.round(14 * word.score() / max);
            Font wordFont = font(size, i < 8 ? Font.BOLD : Font.PLAIN);
            FontMetrics fm = g.getFontMetrics(wordFont);
            int wordW = fm.stringWidth(word.word());
            int px = cloudX;
            int py = cloudY;
            Rectangle box = null;
            for (int attempt = 0; attempt < 20; attempt++) {
                px = cloudX + Math.floorMod(i * 73 + attempt * 37 + word.word().hashCode(), Math.max(1, cloudW - wordW));
                py = cloudY + fm.getAscent() + Math.floorMod(i * 31 + attempt * 19 + word.word().hashCode() / 7, Math.max(1, cloudH - fm.getHeight()));
                box = new Rectangle(px - 2, py - fm.getAscent(), wordW + 4, fm.getHeight());
                Rectangle candidate = box;
                if (used.stream().noneMatch(candidate::intersects)) {
                    break;
                }
                box = null;
            }
            if (box == null) {
                continue;
            }
            used.add(box);
            g.setFont(wordFont);
            g.setColor(ACCENTS[i % ACCENTS.length]);
            g.drawString(word.word(), px, py);
        }
        return y + h;
    }

    private int drawTags(Graphics2D g, ReportData data, int x, int y, int w) {
        int h = 150;
        drawSection(g, x, y, w, h, "群奖项");
        int gap = 6;
        int cardW = (w - 24 - gap) / 2;
        int cardH = 48;
        for (int i = 0; i < data.tags().size(); i++) {
            Tag tag = data.tags().get(i);
            int col = i % 2;
            int row = i / 2;
            int cx = x + 12 + col * (cardW + gap);
            int cy = y + 36 + row * (cardH + gap);
            drawRound(g, cx, cy, cardW, cardH, 5, CARD);
            drawText(g, tag.title(), cx + 9, cy + 16, 10, Font.BOLD, ACCENTS[i % ACCENTS.length]);
            drawText(g, tag.value(), cx + 9, cy + 32, 12, Font.BOLD, TEXT);
            drawText(g, SummaryText.trim(tag.description(), 20), cx + 9, cy + 44, 8, Font.PLAIN, MUTED);
        }
        return y + h;
    }

    private int drawInteractions(Graphics2D g, ReportData data, int x, int y, int w) {
        int rows = Math.max(1, data.interactions().size());
        int h = 34 + rows * 22 + 10;
        drawSection(g, x, y, w, h, "互动关系");
        if (data.interactions().isEmpty()) {
            drawText(g, "这段时间互动链路还不够明显。", x + 14, y + 58, 11, Font.PLAIN, MUTED);
            return y + h;
        }
        Map<String, String> userIds = userIdsByName(data);
        for (int i = 0; i < data.interactions().size(); i++) {
            Interaction interaction = data.interactions().get(i);
            int rowY = y + 34 + i * 22;
            drawNameChip(g, x + 12, rowY, 112, 16, userIdForName(userIds, interaction.left()),
                    interaction.left(), ACCENTS[i % ACCENTS.length]);
            int lineX = x + 132;
            g.setColor(ACCENTS[i % ACCENTS.length]);
            g.setStroke(new BasicStroke(1.4f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            g.drawLine(lineX, rowY + 8, x + w - 150, rowY + 8);
            drawTextRight(g, interaction.count() + "次", x + w - 126, rowY + 12, 9, Font.BOLD, ACCENTS[i % ACCENTS.length]);
            drawNameChip(g, x + w - 118, rowY, 106, 16, userIdForName(userIds, interaction.right()),
                    interaction.right(), ACCENTS[(i + 3) % ACCENTS.length]);
        }
        return y + h;
    }

    private int drawSummary(Graphics2D g, ReportData data, int x, int y, int w) {
        Font body = font(10, Font.PLAIN);
        FontMetrics fm = g.getFontMetrics(body);
        List<String> lines = new ArrayList<>();
        for (String paragraph : data.summary()) {
            lines.addAll(wrap(paragraph, fm, w - 24, 5));
            lines.add("");
        }
        int h = 34 + Math.max(2, lines.size()) * 14 + 8;
        drawSection(g, x, y, w, h, "群聊内容总结");
        int cy = y + 36;
        for (String line : lines) {
            if (line.isEmpty()) {
                cy += 4;
                continue;
            }
            drawText(g, line, x + 12, cy, 10, Font.PLAIN, TEXT);
            cy += 14;
        }
        return y + h;
    }

    private int drawTopics(Graphics2D g, ReportData data, int x, int y, int w) {
        Font body = font(9, Font.PLAIN);
        FontMetrics fm = g.getFontMetrics(body);
        List<List<String>> wrapped = data.topics().stream()
                .map(topic -> wrap(topic.summary(), fm, w - 34, 4))
                .toList();
        int h = 34 + wrapped.stream().mapToInt(lines -> 58 + lines.size() * 13).sum() + 8;
        drawSection(g, x, y, w, h, "主要话题");
        Map<String, String> userIds = userIdsByName(data);
        int cy = y + 34;
        for (int i = 0; i < data.topics().size(); i++) {
            Topic topic = data.topics().get(i);
            List<String> lines = wrapped.get(i);
            int cardH = 50 + lines.size() * 13;
            drawRound(g, x + 10, cy, w - 20, cardH, 5, CARD);
            drawText(g, "#" + (i + 1), x + 20, cy + 17, 10, Font.BOLD, ACCENTS[i % ACCENTS.length]);
            drawText(g, topic.title(), x + 50, cy + 17, 12, Font.BOLD, TEXT);
            drawTextRight(g, topic.count() + "条", x + w - 18, cy + 17, 9, Font.PLAIN, MUTED);
            int chipX = x + 20;
            int chipY = cy + 25;
            for (int j = 0; j < Math.min(5, topic.speakers().size()); j++) {
                String speaker = topic.speakers().get(j);
                int chipW = Math.min(88, 26 + g.getFontMetrics(font(8, Font.PLAIN)).stringWidth(speaker));
                drawNameChip(g, chipX, chipY, chipW, 14, userIdForName(userIds, speaker), speaker,
                        ACCENTS[(i + j) % ACCENTS.length]);
                chipX += chipW + 4;
                if (chipX > x + w - 92) {
                    break;
                }
            }
            int textY = cy + 50;
            for (String line : lines) {
                drawText(g, line, x + 20, textY, 9, Font.PLAIN, MUTED);
                textY += 13;
            }
            cy += cardH + 6;
        }
        return y + h;
    }

    private int drawProfiles(Graphics2D g, ReportData data, int x, int y, int w) {
        int count = data.profiles().size();
        int rows = Math.max(1, (count + 1) / 2);
        int gap = 6;
        int cardW = (w - 24 - gap) / 2;
        int cardH = 98;
        int h = 36 + rows * cardH + (rows - 1) * gap + 10;
        drawSection(g, x, y, w, h, "群友画像");
        for (int i = 0; i < count; i++) {
            Profile profile = data.profiles().get(i);
            int col = i % 2;
            int row = i / 2;
            int cx = x + 12 + col * (cardW + gap);
            int cy = y + 36 + row * (cardH + gap);
            drawRound(g, cx, cy, cardW, cardH, 5, CARD);
            drawAvatar(g, cx + 9, cy + 9, 22, AvatarService.Type.USER, profile.userId(), profile.name());
            drawText(g, profile.name(), cx + 38, cy + 23, 10, Font.BOLD, TEXT);
            drawText(g, profile.title(), cx + 9, cy + 48, 11, Font.BOLD, ACCENTS[i % ACCENTS.length]);
            Font descriptionFont = font(9, Font.PLAIN);
            List<String> lines = wrap(profile.description(), g.getFontMetrics(descriptionFont), cardW - 18, 3);
            int ty = cy + 64;
            for (String line : lines) {
                drawText(g, line, cx + 9, ty, 9, Font.PLAIN, MUTED);
                ty += 12;
            }
        }
        return y + h;
    }

    private int drawQuotes(Graphics2D g, ReportData data, int x, int y, int w) {
        if (data.quotes().isEmpty()) {
            int h = 78;
            drawSection(g, x, y, w, h, "群聊金句");
            drawText(g, "暂时没有抓到适合展示的金句。", x + 14, y + 56, 11, Font.PLAIN, MUTED);
            return y + h;
        }
        Font body = font(10, Font.PLAIN);
        FontMetrics fm = g.getFontMetrics(body);
        List<List<String>> wrapped = data.quotes().stream()
                .map(quote -> wrap(quote.text(), fm, w - 36, 3))
                .toList();
        int h = 34 + wrapped.stream().mapToInt(lines -> 30 + lines.size() * 14).sum() + 8;
        drawSection(g, x, y, w, h, "群聊金句");
        int cy = y + 34;
        for (int i = 0; i < data.quotes().size(); i++) {
            Quote quote = data.quotes().get(i);
            List<String> lines = wrapped.get(i);
            int cardH = 24 + lines.size() * 14;
            drawRound(g, x + 10, cy, w - 20, cardH, 5, CARD);
            drawText(g, quote.name(), x + 20, cy + 15, 10, Font.BOLD, ACCENTS[i % ACCENTS.length]);
            int ty = cy + 30;
            for (String line : lines) {
                drawText(g, line, x + 20, ty, 10, Font.PLAIN, TEXT);
                ty += 14;
            }
            cy += cardH + 6;
        }
        return y + h;
    }

    private void drawFooter(Graphics2D g, int x, int y, int w) {
        drawText(g, "Generated by ZeroBot ChatSummary", x, y + 16, 8, Font.PLAIN, SOFT);
        drawTextRight(g, LocalDateTime.now(zoneId).format(TIME_FORMAT), x + w, y + 16, 8, Font.PLAIN, SOFT);
    }

    private void drawSection(Graphics2D g, int x, int y, int w, int h, String title) {
        drawRound(g, x, y, w, h, 6, PANEL);
        drawText(g, title, x + 10, y + 21, 14, Font.BOLD, TEXT);
    }

    private void drawNameChip(Graphics2D g, int x, int y, int w, int h, String id, String name, Color color) {
        drawRound(g, x, y, w, h, h / 2, CARD);
        drawAvatar(g, x + 2, y + 2, h - 4, AvatarService.Type.USER, id, name);
        drawText(g, SummaryText.trim(name, 9), x + h + 3, y + h - 5, Math.max(8, h - 7), Font.PLAIN, TEXT);
        g.setColor(color);
        g.fillOval(x + w - 8, y + h / 2 - 2, 4, 4);
    }

    private Map<String, String> userIdsByName(ReportData data) {
        Map<String, String> result = new LinkedHashMap<>();
        for (UserAggregate user : data.users().values()) {
            if (user.displayName() != null && !user.displayName().isBlank()) {
                result.putIfAbsent(user.displayName(), user.userId);
            }
        }
        for (TopUser user : data.topUsers()) {
            if (user.displayName() != null && !user.displayName().isBlank()) {
                result.putIfAbsent(user.displayName(), user.userId());
            }
        }
        return result;
    }

    private String userIdForName(Map<String, String> userIds, String name) {
        return SummaryText.firstNotBlank(userIds.get(name), name);
    }

    private void drawPill(Graphics2D g, int x, int y, int w, int h, String text, Color color, boolean filled) {
        if (filled) {
            drawRound(g, x, y, w, h, h / 2, color);
            drawTextCentered(g, text, x, y, w, h, 10, Font.BOLD, BACKGROUND);
        } else {
            g.setColor(color);
            g.setStroke(new BasicStroke(1.5f));
            g.drawRoundRect(x, y, w, h, h, h);
            drawTextCentered(g, text, x, y, w, h, 10, Font.PLAIN, color);
        }
    }

    private void drawAvatar(Graphics2D g, int x, int y, int size, AvatarService.Type type, String id, String name) {
        if (avatarService != null) {
            BufferedImage image = avatarService.image(type, id).orElse(null);
            if (image != null) {
                drawImageAvatar(g, image, x, y, size);
                return;
            }
        }
        drawFallbackAvatar(g, x, y, size, id, name);
    }

    private void drawImageAvatar(Graphics2D g, BufferedImage image, int x, int y, int size) {
        Shape oldClip = g.getClip();
        g.setClip(new Ellipse2D.Float(x, y, size, size));
        g.drawImage(image, x, y, size, size, null);
        g.setClip(oldClip);
        g.setColor(new Color(255, 255, 255, 44));
        g.setStroke(new BasicStroke(Math.max(1f, size / 16f)));
        g.drawOval(x, y, size - 1, size - 1);
    }

    private void drawThumbnail(Graphics2D g, BufferedImage image, int x, int y, int w, int h, int radius,
                               boolean cover) {
        Shape oldClip = g.getClip();
        g.setClip(new RoundRectangle2D.Float(x, y, w, h, radius, radius));
        g.setColor(CARD_DARK);
        g.fillRect(x, y, w, h);
        double scale = cover
                ? Math.max(w / (double) image.getWidth(), h / (double) image.getHeight())
                : Math.min(w / (double) image.getWidth(), h / (double) image.getHeight());
        int drawW = Math.max(1, (int) Math.round(image.getWidth() * scale));
        int drawH = Math.max(1, (int) Math.round(image.getHeight() * scale));
        int dx = x + (w - drawW) / 2;
        int dy = y + (h - drawH) / 2;
        g.drawImage(image, dx, dy, drawW, drawH, null);
        g.setClip(oldClip);
        g.setColor(new Color(255, 255, 255, 38));
        g.setStroke(new BasicStroke(1f));
        g.drawRoundRect(x, y, w, h, radius, radius);
    }

    private void drawFallbackAvatar(Graphics2D g, int x, int y, int size, String id, String name) {
        Color color = colorFrom(id);
        g.setColor(color);
        g.fillOval(x, y, size, size);
        g.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, 0.24f));
        g.setColor(Color.WHITE);
        g.fillOval(x + size / 3, y + size / 8, size / 2, size / 2);
        g.setComposite(AlphaComposite.SrcOver);
        String label = avatarLabel(name, id);
        drawTextCentered(g, label, x, y, size, size, Math.max(12, size / 3), Font.BOLD, Color.WHITE);
    }

    private Color colorFrom(String seed) {
        int hash = Math.abs(SummaryText.nullTo(seed, "0").hashCode());
        Color base = ACCENTS[hash % ACCENTS.length];
        return new Color(
                Math.min(255, base.getRed() + 10),
                Math.min(255, base.getGreen() + 8),
                Math.min(255, base.getBlue() + 8)
        );
    }

    private String avatarLabel(String name, String id) {
        String source = SummaryText.firstNotBlank(name, id, "?");
        int offset = source.offsetByCodePoints(0, Math.min(1, source.codePointCount(0, source.length())));
        return source.substring(0, offset);
    }

    private void drawRound(Graphics2D g, int x, int y, int w, int h, int radius, Color color) {
        g.setColor(color);
        g.fill(new RoundRectangle2D.Float(x, y, w, h, radius, radius));
    }

    private void drawText(Graphics2D g, String text, int x, int y, int size, int style, Color color) {
        g.setFont(font(size, style));
        g.setColor(color);
        g.drawString(SummaryText.nullTo(text, ""), x, y);
    }

    private void drawTextRight(Graphics2D g, String text, int rightX, int y, int size, int style, Color color) {
        g.setFont(font(size, style));
        FontMetrics fm = g.getFontMetrics();
        String safeText = SummaryText.nullTo(text, "");
        g.setColor(color);
        g.drawString(safeText, rightX - fm.stringWidth(safeText), y);
    }

    private void drawTextCentered(Graphics2D g, String text, int x, int y, int w, int h, int size, int style, Color color) {
        g.setFont(font(size, style));
        FontMetrics fm = g.getFontMetrics();
        String safeText = SummaryText.nullTo(text, "");
        int tx = x + (w - fm.stringWidth(safeText)) / 2;
        int ty = y + (h - fm.getHeight()) / 2 + fm.getAscent();
        g.setColor(color);
        g.drawString(safeText, tx, ty);
    }

    private List<String> wrap(String text, FontMetrics fm, int maxWidth, int maxLines) {
        List<String> lines = new ArrayList<>();
        String normalized = SummaryText.nullTo(text, "").trim();
        if (normalized.isEmpty()) {
            return lines;
        }
        for (String paragraph : normalized.split("\\R")) {
            StringBuilder line = new StringBuilder();
            for (int offset = 0; offset < paragraph.length(); ) {
                int codePoint = paragraph.codePointAt(offset);
                String piece = new String(Character.toChars(codePoint));
                if (fm.stringWidth(line + piece) > maxWidth && !line.isEmpty()) {
                    lines.add(line.toString());
                    if (lines.size() == maxLines) {
                        return ellipsize(lines, fm, maxWidth);
                    }
                    line.setLength(0);
                }
                line.append(piece);
                offset += Character.charCount(codePoint);
            }
            if (!line.isEmpty()) {
                lines.add(line.toString());
                if (lines.size() == maxLines) {
                    return ellipsize(lines, fm, maxWidth);
                }
            }
        }
        return lines;
    }

    private List<String> ellipsize(List<String> lines, FontMetrics fm, int maxWidth) {
        if (lines.isEmpty()) {
            return lines;
        }
        int last = lines.size() - 1;
        String line = lines.get(last);
        while (!line.isEmpty() && fm.stringWidth(line + "...") > maxWidth) {
            line = line.substring(0, line.length() - 1);
        }
        lines.set(last, line + "...");
        return lines;
    }

    private Font font(int size, int style) {
        return new Font(fontFamily, style, size);
    }

    private void setup(Graphics2D g) {
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_LCD_HRGB);
        g.setRenderingHint(RenderingHints.KEY_FRACTIONALMETRICS, RenderingHints.VALUE_FRACTIONALMETRICS_ON);
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
    }

    private String formatTime(Instant instant) {
        return LocalDateTime.ofInstant(instant, zoneId).format(TIME_FORMAT);
    }

    private String formatShortTime(Instant instant) {
        return LocalDateTime.ofInstant(instant, zoneId).format(DateTimeFormatter.ofPattern("HH:mm"));
    }

    private static String chooseFontFamily() {
        Set<String> families = Set.of(GraphicsEnvironment.getLocalGraphicsEnvironment().getAvailableFontFamilyNames());
        for (String candidate : List.of("Microsoft YaHei UI", "Microsoft YaHei", "PingFang SC", "Noto Sans CJK SC",
                "Source Han Sans SC", "SimHei", "SansSerif")) {
            if (families.contains(candidate)) {
                return candidate;
            }
        }
        return Font.SANS_SERIF;
    }

    private record Metric(String label, String value, Color color) {
    }
}
