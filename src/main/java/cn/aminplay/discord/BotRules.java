package cn.aminplay.discord;

import java.time.Duration;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** 纯逻辑（无 Bukkit/网络依赖），由 BotRulesTest 覆盖。 */
final class BotRules {
    private BotRules() {}

    private static final Pattern PART = Pattern.compile("(\\d+)\\s*(w|d|h|m|s|周|天|小时|时|分钟|分|秒)");
    private static final Pattern NAME = Pattern.compile("[A-Za-z0-9_]{1,16}");
    private static final Set<String> PERMANENT = Set.of("", "永久", "perm", "permanent", "forever", "0");

    /** 解析封禁时长，例如 30m、12h、7d、1d12h、3天。返回 null 表示永久；格式不对抛 IllegalArgumentException。 */
    static Duration parseDuration(String raw) {
        String s = raw == null ? "" : raw.trim().toLowerCase(Locale.ROOT).replace(" ", "");
        if (PERMANENT.contains(s)) return null;
        Matcher m = PART.matcher(s);
        Duration total = Duration.ZERO;
        int end = 0;
        while (m.find()) {
            if (m.start() != end) throw new IllegalArgumentException(raw);
            long n = Long.parseLong(m.group(1));
            total = total.plus(switch (m.group(2)) {
                case "w", "周" -> Duration.ofDays(7 * n);
                case "d", "天" -> Duration.ofDays(n);
                case "h", "小时", "时" -> Duration.ofHours(n);
                case "m", "分钟", "分" -> Duration.ofMinutes(n);
                default -> Duration.ofSeconds(n);
            });
            end = m.end();
        }
        if (end != s.length() || total.isZero()) throw new IllegalArgumentException(raw);
        return total;
    }

    static String formatDuration(Duration d) {
        if (d == null) return "永久";
        long sec = d.getSeconds();
        StringBuilder b = new StringBuilder();
        long days = sec / 86400, hours = sec % 86400 / 3600, mins = sec % 3600 / 60, s = sec % 60;
        if (days > 0) b.append(days).append("天");
        if (hours > 0) b.append(hours).append("小时");
        if (mins > 0) b.append(mins).append("分钟");
        if (s > 0 || b.isEmpty()) b.append(s).append("秒");
        return b.toString();
    }

    /** 指令的根名：去掉开头的 /、命名空间前缀，转小写。"/minecraft:OP x" → "op" */
    static String commandRoot(String command) {
        String s = command == null ? "" : command.trim();
        while (s.startsWith("/")) s = s.substring(1);
        int sp = s.indexOf(' ');
        String root = (sp < 0 ? s : s.substring(0, sp)).toLowerCase(Locale.ROOT);
        int colon = root.indexOf(':');
        return colon < 0 ? root : root.substring(colon + 1);
    }

    static boolean isBlocked(String command, Set<String> blocked) {
        return blocked.contains(commandRoot(command));
    }

    static String stripSlash(String command) {
        String s = command == null ? "" : command.trim();
        while (s.startsWith("/")) s = s.substring(1);
        return s;
    }

    static boolean validPlayerName(String name) {
        return name != null && NAME.matcher(name).matches();
    }

    static String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, Math.max(0, max - 1)) + "…";
    }

    /** 放进 ``` 代码块前，防止内容提前结束代码块。 */
    static String escapeCodeBlock(String s) {
        return s == null ? "" : s.replace("```", "ˋˋˋ");
    }

    /** 权限位：ADMINISTRATOR 或 MANAGE_GUILD。 */
    static boolean hasAdminBits(String permissions) {
        if (permissions == null || permissions.isEmpty()) return false;
        try {
            long p = Long.parseUnsignedLong(permissions);
            return (p & (1L << 3)) != 0 || (p & (1L << 5)) != 0;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    // ===================== 2026-10-07 message collect =====================

    private static final long DISCORD_EPOCH = 1420070400000L;
    private static final Pattern DATE = Pattern.compile("(?:(\\d{4})[-/.])?(\\d{1,2})[-/.](\\d{1,2})");
    private static final Pattern CLOCK = Pattern.compile("(\\d{1,2}):(\\d{2})(?::(\\d{2}))?");
    private static final Pattern RELATIVE = Pattern.compile("(\\d+)\\s*(w|d|h|m|周|天|小时|时|分钟|分)");

    /** 某个时刻对应的 Discord 雪花 ID（用于 ?after= 翻页）。 */
    static long snowflakeAt(java.time.Instant t) {
        return Math.max(0, t.toEpochMilli() - DISCORD_EPOCH) << 22;
    }

    /**
     * 解析收集用的时间（按 zone）：今天 / 昨天 / 前天 / 3d、12h、30m（= 现在往前推）/ 2026-10-06 / 10-06 / 20:00 /
     * 2026-10-06 20:00（空格、T、_ 都行）。只有日期时：开始 = 当天 0 点，结束（end=true）= 当天最后一刻。
     * 空 = null；看不懂抛 IllegalArgumentException。
     */
    static java.time.Instant parseTime(String raw, boolean end, java.time.ZoneId zone, java.time.ZonedDateTime now) {
        if (raw == null || raw.isBlank()) return null;
        String s = raw.trim().toLowerCase(Locale.ROOT);
        java.time.LocalDate today = now.withZoneSameInstant(zone).toLocalDate();
        java.time.LocalDate day = switch (s) {
            case "今天", "today", "当天" -> today;
            case "昨天", "yesterday" -> today.minusDays(1);
            case "前天" -> today.minusDays(2);
            default -> null;
        };
        if (day != null) return dayEdge(day, end, zone);
        if (s.equals("现在") || s.equals("now")) return now.toInstant();
        Matcher rel = RELATIVE.matcher(s.replace(" ", ""));
        if (rel.matches()) {
            long n = Long.parseLong(rel.group(1));
            Duration d = switch (rel.group(2)) {
                case "w", "周" -> Duration.ofDays(7 * n);
                case "d", "天" -> Duration.ofDays(n);
                case "h", "小时", "时" -> Duration.ofHours(n);
                default -> Duration.ofMinutes(n);
            };
            return now.toInstant().minus(d);
        }
        String[] parts = s.split("[ t_]+", 2);
        String datePart = null, clockPart = null;
        if (parts.length == 2) {
            datePart = parts[0];
            clockPart = parts[1];
        } else if (CLOCK.matcher(parts[0]).matches()) {
            clockPart = parts[0];
        } else {
            datePart = parts[0];
        }
        java.time.LocalDate date = today;
        if (datePart != null) {
            Matcher dm = DATE.matcher(datePart);
            if (!dm.matches()) throw new IllegalArgumentException(raw);
            int year = dm.group(1) == null ? today.getYear() : Integer.parseInt(dm.group(1));
            try {
                date = java.time.LocalDate.of(year, Integer.parseInt(dm.group(2)), Integer.parseInt(dm.group(3)));
            } catch (java.time.DateTimeException e) {
                throw new IllegalArgumentException(raw);
            }
        }
        if (clockPart == null) return dayEdge(date, end, zone);
        Matcher cm = CLOCK.matcher(clockPart);
        if (!cm.matches()) throw new IllegalArgumentException(raw);
        int h = Integer.parseInt(cm.group(1)), mi = Integer.parseInt(cm.group(2)), se = cm.group(3) == null ? 0 : Integer.parseInt(cm.group(3));
        if (h > 23 || mi > 59 || se > 59) throw new IllegalArgumentException(raw);
        java.time.ZonedDateTime z = date.atTime(h, mi, se).atZone(zone);
        if (end && cm.group(3) == null) z = z.plusSeconds(59).plusNanos(999_000_000); // 结束「20:00」包含这一分钟
        return z.toInstant();
    }

    private static java.time.Instant dayEdge(java.time.LocalDate d, boolean end, java.time.ZoneId zone) {
        java.time.ZonedDateTime start = d.atStartOfDay(zone);
        return end ? start.plusDays(1).toInstant().minusMillis(1) : start.toInstant();
    }

    /**
     * 收集的时间段：都不填 = 今天 0 点到现在；只填开始 = 到现在；只填结束 = 结束那天 0 点起。
     * 返回 {from, to}；开始晚于结束抛 IllegalArgumentException。
     */
    static java.time.Instant[] collectRange(String fromRaw, String toRaw, java.time.ZoneId zone, java.time.ZonedDateTime now) {
        java.time.Instant from = parseTime(fromRaw, false, zone, now);
        java.time.Instant to = parseTime(toRaw, true, zone, now);
        if (to == null || to.isAfter(now.toInstant())) to = now.toInstant();
        if (from == null) from = to.atZone(zone).toLocalDate().atStartOfDay(zone).toInstant();
        if (!from.isBefore(to)) throw new IllegalArgumentException("开始时间要早于结束时间");
        return new java.time.Instant[]{from, to};
    }

    /** 附件存盘用的文件名：去掉路径和奇怪字符，最长 80。 */
    static String safeFileName(String name) {
        String s = name == null ? "file" : name.replaceAll("[\\\\/:*?\"<>|\\p{Cntrl}]", "_").trim();
        while (s.startsWith(".")) s = s.substring(1);
        if (s.isEmpty()) s = "file";
        if (s.length() > 80) {
            int dot = s.lastIndexOf('.');
            String ext = dot > 0 && s.length() - dot <= 10 ? s.substring(dot) : "";
            s = s.substring(0, 80 - ext.length()) + ext;
        }
        return s;
    }

    static String formatBytes(long b) {
        if (b < 1024) return b + " B";
        if (b < 1024 * 1024) return String.format(Locale.ROOT, "%.1f KB", b / 1024.0);
        return String.format(Locale.ROOT, "%.1f MB", b / 1024.0 / 1024.0);
    }

    /** 把文件分批：每批最多 10 个、总大小不超过 limit（Discord 单条消息上限）。单个超过 limit 的不放进任何批。 */
    static java.util.List<java.util.List<Integer>> batches(long[] sizes, long limit) {
        java.util.List<java.util.List<Integer>> out = new java.util.ArrayList<>();
        java.util.List<Integer> cur = new java.util.ArrayList<>();
        long total = 0;
        for (int i = 0; i < sizes.length; i++) {
            if (sizes[i] > limit) continue;
            if (cur.size() == 10 || total + sizes[i] > limit) {
                out.add(cur);
                cur = new java.util.ArrayList<>();
                total = 0;
            }
            cur.add(i);
            total += sizes[i];
        }
        if (!cur.isEmpty()) out.add(cur);
        return out;
    }
}
