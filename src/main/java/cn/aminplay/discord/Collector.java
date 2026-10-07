package cn.aminplay.discord;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 2026-10-07 message collect：按 成员 / 频道 / 子区 / 全服 × 时间段 收集发言（含附件），
 * 写成 transcript.md + messages.json + files/ 存在服务器上（plugins/AminPlayDiscord/exports/…）。
 * 发到 Discord 私密子区由 Interactions 负责。
 */
final class Collector {
    static final Set<Integer> THREAD_TYPES = Set.of(10, 11, 12);
    static final Set<Integer> FORUM_TYPES = Set.of(15, 16);
    /** 有自己消息的频道：文字、语音频道的文字区、公告、舞台。 */
    static final Set<Integer> MESSAGE_TYPES = Set.of(0, 2, 5, 13);

    record Request(String guildId, String userId, String channelId, Instant from, Instant to,
                   boolean files, boolean threads, String requestedBy) {}

    record SavedFile(Path path, String name, long size, String messageLink, String author, Instant time) {}

    record BigFile(String name, long size, String messageLink) {}

    record Result(Path dir, Path transcript, Path json, int messages, int authors, List<SavedFile> files,
                  List<BigFile> bigFiles, List<String> skipped, Map<String, Integer> byLocation,
                  Map<String, Integer> byAuthor, boolean truncated, int emptyContent, String scope) {}

    interface Progress {
        void update(String text);
    }

    private static final Pattern USER_MENTION = Pattern.compile("<@!?(\\d+)>");
    private static final Pattern ROLE_MENTION = Pattern.compile("<@&(\\d+)>");
    private static final Pattern CHANNEL_MENTION = Pattern.compile("<#(\\d+)>");
    private static final Pattern CUSTOM_EMOJI = Pattern.compile("<a?:(\\w+):\\d+>");
    private static final Pattern TIMESTAMP = Pattern.compile("<t:(-?\\d+)(?::[tTdDfFR])?>");

    private final DiscordRest rest;
    private final HttpClient http;
    private final Path exportRoot;
    private final ZoneId zone;
    private final int maxMessages;
    private final long maxFileBytes;
    private final long maxTotalBytes;
    private final Logger log;

    // 2026-10-08 privacy：只允许收集 sources 里的反馈频道（和它们的贴子）；退出的用户的消息不记录。null = 不限制（测试用）
    private Set<String> sources;
    private java.util.function.Predicate<String> optedOut = id -> false;

    Collector policy(Set<String> sources, java.util.function.Predicate<String> optedOut) {
        this.sources = sources;
        this.optedOut = optedOut == null ? id -> false : optedOut;
        return this;
    }

    /** 2026-10-08 privacy：结果发到 Discord 之后马上删除本地文件（本地不保存）。 */
    static void deleteDir(Path dir, Logger log) {
        if (dir == null || !Files.exists(dir)) return;
        try (var walk = Files.walk(dir)) {
            for (Path p : walk.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(p);
        } catch (IOException e) {
            if (log != null) log.warning("删除收集临时文件失败（下次启动或收集时会再清理）：" + e.getMessage());
        }
    }

    /** 删除 exportRoot 下超过 keepDays 天的收集结果（正常情况下发完就删了，这里清理残留），返回删掉的个数。 */
    static int cleanup(Path exportRoot, int keepDays, Logger log) {
        if (exportRoot == null || !Files.isDirectory(exportRoot)) return 0;
        Instant limit = Instant.now().minus(Duration.ofDays(Math.max(1, keepDays)));
        int n = 0;
        try (var dirs = Files.list(exportRoot)) {
            for (Path d : dirs.toList()) {
                if (!Files.isDirectory(d) || !Files.getLastModifiedTime(d).toInstant().isBefore(limit)) continue;
                try (var walk = Files.walk(d)) {
                    for (Path p : walk.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(p);
                }
                n++;
            }
        } catch (IOException e) {
            if (log != null) log.warning("清理过期收集结果失败：" + e.getMessage());
        }
        if (n > 0 && log != null) log.info("已删除 " + n + " 个超过 " + keepDays + " 天的收集结果");
        return n;
    }

    /** null = 可以收；否则是给管理员看的原因。ch = 要收的频道/子区（null = 全服务器）。 */
    static String notAllowed(Set<String> sources, JsonObject ch) {
        if (sources == null) return null;
        if (ch == null) return "只能收集指定的反馈频道：请选一个反馈频道或贴子。";
        String id = ch.has("id") ? ch.get("id").getAsString() : "";
        String parent = ch.has("parent_id") && !ch.get("parent_id").isJsonNull() ? ch.get("parent_id").getAsString() : "";
        if (sources.contains(id) || THREAD_TYPES.contains(ch.get("type").getAsInt()) && sources.contains(parent)) return null;
        return sources.isEmpty() ? "还没有设置反馈频道，请管理员在控制台执行 discordbot sources add <频道ID>。"
                : "这个频道不在反馈频道列表里，不能收集。";
    }

    Collector(DiscordRest rest, HttpClient http, Path exportRoot, ZoneId zone, int maxMessages,
              long maxFileBytes, long maxTotalBytes, Logger log) {
        this.rest = rest;
        this.http = http;
        this.exportRoot = exportRoot;
        this.zone = zone;
        this.maxMessages = maxMessages;
        this.maxFileBytes = maxFileBytes;
        this.maxTotalBytes = maxTotalBytes;
        this.log = log;
    }

    // ===================== 收集 =====================

    Result collect(Request req, Progress progress) throws IOException {
        Map<String, JsonObject> channels = new LinkedHashMap<>();
        for (JsonElement e : rest.get("/guilds/" + req.guildId() + "/channels").getAsJsonArray()) {
            JsonObject c = e.getAsJsonObject();
            channels.put(c.get("id").getAsString(), c);
        }
        Map<String, String> roles = new HashMap<>();
        for (JsonElement e : rest.get("/guilds/" + req.guildId() + "/roles").getAsJsonArray()) {
            roles.put(e.getAsJsonObject().get("id").getAsString(), e.getAsJsonObject().get("name").getAsString());
        }
        List<JsonObject> active = new ArrayList<>();
        JsonElement act = rest.get("/guilds/" + req.guildId() + "/threads/active");
        if (act.isJsonObject() && act.getAsJsonObject().has("threads")) {
            for (JsonElement e : act.getAsJsonObject().getAsJsonArray("threads")) active.add(e.getAsJsonObject());
        }

        List<String> skipped = new ArrayList<>();
        List<JsonObject> targets = new ArrayList<>();
        String scope;
        if (req.channelId() != null) {
            JsonObject ch = channels.get(req.channelId());
            if (ch == null) ch = rest.get("/channels/" + req.channelId()).getAsJsonObject();
            String why = notAllowed(sources, ch);   // 2026-10-08 privacy
            if (why != null) throw new IllegalArgumentException(why);
            int type = ch.get("type").getAsInt();
            if (THREAD_TYPES.contains(type)) {
                targets.add(ch);
                scope = "子区 " + label(ch, channels);
            } else {
                if (!FORUM_TYPES.contains(type)) targets.add(ch);
                if (req.threads()) targets.addAll(threadsOf(ch, active, req.from(), skipped, channels));
                scope = label(ch, channels) + (req.threads() ? "（含子区 / 贴子）" : "");
            }
        } else {
            String why = notAllowed(sources, null);   // 2026-10-08 privacy: no whole-server collect
            if (why != null) throw new IllegalArgumentException(why);
            scope = "全服务器（机器人能看到的地方）";
            for (JsonObject ch : channels.values()) {
                int type = ch.get("type").getAsInt();
                if (MESSAGE_TYPES.contains(type)) targets.add(ch);
                if (req.threads() && (type == 0 || type == 5 || FORUM_TYPES.contains(type))) {
                    targets.addAll(threadsOf(ch, active, req.from(), skipped, channels));
                }
            }
        }
        for (JsonObject t : targets) channels.putIfAbsent(t.get("id").getAsString(), t);

        // ---- 逐个位置拉消息
        List<JsonObject> all = new ArrayList<>();
        long after = BotRules.snowflakeAt(req.from()) - 1;
        long until = BotRules.snowflakeAt(req.to().plusMillis(1));
        boolean truncated = false;
        int done = 0;
        for (JsonObject ch : targets) {
            done++;
            if (done % 5 == 0 || done == targets.size()) {
                progress.update("⏳ 正在收集：已扫描 " + done + " / " + targets.size() + " 个位置，找到 " + all.size() + " 条");
            }
            String last = str(ch, "last_message_id");
            if (!last.isEmpty() && Long.parseUnsignedLong(last) <= after) continue; // 这段时间没人说话
            String cid = ch.get("id").getAsString();
            long cursor = after;
            try {
                while (true) {
                    JsonArray batch = rest.get("/channels/" + cid + "/messages?limit=100&after=" + Long.toUnsignedString(cursor)).getAsJsonArray();
                    if (batch.isEmpty()) break;
                    List<JsonObject> msgs = new ArrayList<>();
                    for (JsonElement e : batch) msgs.add(e.getAsJsonObject());
                    msgs.sort(Comparator.comparing(m -> Long.parseUnsignedLong(m.get("id").getAsString())));
                    boolean past = false;
                    for (JsonObject m : msgs) {
                        long id = Long.parseUnsignedLong(m.get("id").getAsString());
                        cursor = Math.max(cursor, id);
                        if (Long.compareUnsigned(id, until) >= 0) {
                            past = true;
                            continue;
                        }
                        if (req.userId() != null && !req.userId().equals(m.getAsJsonObject("author").get("id").getAsString())) continue;
                        if (optedOut.test(m.getAsJsonObject("author").get("id").getAsString())) continue;   // 2026-10-08 privacy
                        m.addProperty("_channel", cid);
                        all.add(m);
                    }
                    if (all.size() >= maxMessages) {
                        truncated = true;
                        break;
                    }
                    if (past || batch.size() < 100) break;
                }
            } catch (DiscordRest.DiscordException e) {
                if (e.status == 403 || e.code == 50001 || e.code == 50013) skipped.add(label(ch, channels));
                else throw e;
            }
            if (truncated) break;
        }
        all.sort(Comparator.comparing(m -> Long.parseUnsignedLong(m.get("id").getAsString())));
        if (all.size() > maxMessages) all = new ArrayList<>(all.subList(0, maxMessages));

        // ---- 写文件
        String stamp = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").withZone(zone).format(Instant.now());
        Path dir = exportRoot.resolve(stamp + "-" + Long.toString(System.nanoTime() % 46656, 36));
        Files.createDirectories(dir);
        List<SavedFile> saved = new ArrayList<>();
        List<BigFile> big = new ArrayList<>();
        Map<String, String> fileOf = new HashMap<>(); // 附件 id → 相对路径
        if (req.files()) {
            progress.update("⏳ 找到 " + all.size() + " 条消息，正在下载附件…");
            long total = 0;
            int n = 0;
            for (JsonObject m : all) {
                if (!m.has("attachments")) continue;
                for (JsonElement ae : m.getAsJsonArray("attachments")) {
                    JsonObject a = ae.getAsJsonObject();
                    long size = a.has("size") ? a.get("size").getAsLong() : 0;
                    String name = a.get("filename").getAsString();
                    String link = link(req.guildId(), m);
                    if (size > maxFileBytes || total + size > maxTotalBytes) {
                        big.add(new BigFile(name, size, link));
                        continue;
                    }
                    n++;
                    String rel = "files/" + String.format("%04d-", n) + BotRules.safeFileName(name);
                    Path p = dir.resolve(rel);
                    try {
                        download(a.get("url").getAsString(), p);
                        total += Files.size(p);
                        fileOf.put(a.get("id").getAsString(), rel);
                        saved.add(new SavedFile(p, name, Files.size(p), link, authorName(m), time(m)));
                    } catch (IOException e) {
                        log.warning("下载附件失败 " + name + "：" + e.getMessage());
                        big.add(new BigFile(name, size, link));
                    }
                }
            }
        }

        Map<String, Integer> byLocation = new LinkedHashMap<>();
        Map<String, Integer> byAuthor = new LinkedHashMap<>();
        int empty = 0;
        for (JsonObject m : all) {
            byLocation.merge(label(channels.get(m.get("_channel").getAsString()), channels), 1, Integer::sum);
            byAuthor.merge(authorName(m), 1, Integer::sum);
            boolean bot = m.getAsJsonObject("author").has("bot") && m.getAsJsonObject("author").get("bot").getAsBoolean();
            if (!bot && str(m, "content").isEmpty() && m.getAsJsonArray("attachments").isEmpty()
                    && (!m.has("embeds") || m.getAsJsonArray("embeds").isEmpty()) && !m.has("sticker_items")
                    && m.get("type").getAsInt() == 0) empty++;
        }

        String who = req.userId() == null ? null : findAuthor(all, req.userId());
        Path md = dir.resolve("transcript.md");
        Files.writeString(md, transcript(req, scope, who, all, channels, roles, fileOf, big, skipped, byLocation, byAuthor, truncated, empty), StandardCharsets.UTF_8);
        Path json = dir.resolve("messages.json");
        Files.writeString(json, json(req, all, channels, roles, fileOf), StandardCharsets.UTF_8);
        return new Result(dir, md, json, all.size(), byAuthor.size(), saved, big, skipped, byLocation, byAuthor, truncated, empty,
                scope + (who == null ? "" : " · 成员 " + who));
    }

    /** 一个频道 / 论坛下的子区：正在活跃的 + 时间段内归档的（公开；私密的有权限才拿得到）。 */
    private List<JsonObject> threadsOf(JsonObject parent, List<JsonObject> active, Instant from,
                                       List<String> skipped, Map<String, JsonObject> channels) throws IOException {
        String pid = parent.get("id").getAsString();
        List<JsonObject> out = new ArrayList<>();
        for (JsonObject t : active) if (pid.equals(str(t, "parent_id"))) out.add(t);
        for (String kind : new String[]{"public", "private"}) {
            String before = null;
            try {
                while (true) {
                    JsonObject r = rest.get("/channels/" + pid + "/threads/archived/" + kind + "?limit=100"
                            + (before == null ? "" : "&before=" + before)).getAsJsonObject();
                    JsonArray ts = r.getAsJsonArray("threads");
                    boolean old = false;
                    for (JsonElement e : ts) {
                        JsonObject t = e.getAsJsonObject();
                        String at = t.getAsJsonObject("thread_metadata").get("archive_timestamp").getAsString();
                        before = at;
                        if (Instant.parse(at).isBefore(from)) {
                            old = true;
                            break;
                        }
                        out.add(t);
                    }
                    if (old || ts.isEmpty() || !r.has("has_more") || !r.get("has_more").getAsBoolean()) break;
                }
            } catch (DiscordRest.DiscordException e) {
                if (kind.equals("public") && (e.status == 403 || e.code == 50001 || e.code == 50013)) {
                    skipped.add(label(parent, channels) + " 的归档子区");
                }
                // 私密归档子区需要「管理子区」权限，没有就跳过，不算错误
            }
        }
        return out;
    }

    private void download(String url, Path to) throws IOException {
        Files.createDirectories(to.getParent());
        HttpRequest r = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofMinutes(2))
                .header("User-Agent", "DiscordBot (https://github.com/aminplay, 1.0)").GET().build();
        try {
            HttpResponse<InputStream> resp = http.send(r, HttpResponse.BodyHandlers.ofInputStream());
            if (resp.statusCode() >= 400) throw new IOException("HTTP " + resp.statusCode());
            try (InputStream in = resp.body()) {
                Files.copy(in, to, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("interrupted", e);
        }
    }

    // ===================== 输出 =====================

    private String transcript(Request req, String scope, String who, List<JsonObject> all, Map<String, JsonObject> channels,
                              Map<String, String> roles, Map<String, String> fileOf, List<BigFile> big, List<String> skipped,
                              Map<String, Integer> byLocation, Map<String, Integer> byAuthor, boolean truncated, int empty) {
        DateTimeFormatter full = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(zone);
        StringBuilder b = new StringBuilder();
        b.append("# 消息收集 · ").append(scope).append("\n\n");
        b.append("- 时间：").append(full.format(req.from())).append(" ～ ").append(full.format(req.to()))
                .append("（").append(zone.getId()).append("）\n");
        b.append("- 范围：").append(scope).append("\n");
        if (req.userId() != null) b.append("- 成员：").append(who == null ? req.userId() : who).append("（只收这个人）\n");
        b.append("- 共 ").append(all.size()).append(" 条消息，").append(byAuthor.size()).append(" 位发言者，")
                .append(fileOf.size()).append(" 个附件已保存").append(big.isEmpty() ? "" : "，" + big.size() + " 个附件太大没下载").append("\n");
        b.append("- 收集时间：").append(full.format(Instant.now())).append("，收集人：").append(req.requestedBy()).append("\n");
        if (truncated) b.append("- ⚠️ 超过上限 ").append(maxMessages).append(" 条，只收了最早的这些。请缩小时间段再收一次。\n");
        if (empty > 0) b.append("- ⚠️ 有 ").append(empty).append(" 条消息内容是空的：可能没打开 Message Content Intent。\n");
        if (!skipped.isEmpty()) b.append("- 机器人看不到、跳过了：").append(String.join("、", skipped)).append("\n");

        b.append("\n## 统计\n\n| 位置 | 条数 |\n| --- | --- |\n");
        byLocation.forEach((k, v) -> b.append("| ").append(cell(k)).append(" | ").append(v).append(" |\n"));
        b.append("\n| 发言者 | 条数 |\n| --- | --- |\n");
        byAuthor.entrySet().stream().sorted(Map.Entry.<String, Integer>comparingByValue().reversed())
                .forEach(e -> b.append("| ").append(cell(e.getKey())).append(" | ").append(e.getValue()).append(" |\n"));

        b.append("\n## 记录\n");
        // 按位置分组，组内按时间；组的顺序 = 该位置第一条消息的时间
        Map<String, List<JsonObject>> groups = new LinkedHashMap<>();
        for (JsonObject m : all) groups.computeIfAbsent(m.get("_channel").getAsString(), k -> new ArrayList<>()).add(m);
        Map<String, JsonObject> byId = new HashMap<>();
        for (JsonObject m : all) byId.put(m.get("id").getAsString(), m);
        for (Map.Entry<String, List<JsonObject>> g : groups.entrySet()) {
            b.append("\n### ").append(label(channels.get(g.getKey()), channels)).append("\n");
            String lastDay = "";
            for (JsonObject m : g.getValue()) {
                String day = DateTimeFormatter.ofPattern("yyyy-MM-dd").withZone(zone).format(time(m));
                if (!day.equals(lastDay)) {
                    b.append("\n#### ").append(day).append("\n");
                    lastDay = day;
                }
                b.append("\n**[").append(DateTimeFormatter.ofPattern("HH:mm").withZone(zone).format(time(m))).append("] ")
                        .append(md(authorName(m))).append("**");
                if (m.has("edited_timestamp") && !m.get("edited_timestamp").isJsonNull()) b.append(" _(已编辑)_");
                b.append(" — [原消息](").append(link(req.guildId(), m)).append(")\n");
                JsonObject ref = m.has("referenced_message") && m.get("referenced_message").isJsonObject() ? m.getAsJsonObject("referenced_message") : null;
                if (ref != null) {
                    b.append("> ↪ 回复 ").append(md(authorName(ref))).append("：")
                            .append(BotRules.truncate(render(str(ref, "content"), ref, channels, roles).replace('\n', ' '), 80)).append("\n");
                }
                String text = render(str(m, "content"), m, channels, roles);
                if (!text.isEmpty()) b.append("\n").append(text).append("\n");
                if (m.has("embeds")) {
                    for (JsonElement ee : m.getAsJsonArray("embeds")) {
                        JsonObject e = ee.getAsJsonObject();
                        String t = str(e, "title"), d = str(e, "description");
                        if (!t.isEmpty() || !d.isEmpty()) {
                            b.append("\n> 📰 ").append(t.isEmpty() ? "" : "**" + t + "** ")
                                    .append(BotRules.truncate(d, 500).replace("\n", "\n> ")).append("\n");
                        }
                    }
                }
                if (m.has("sticker_items")) {
                    for (JsonElement s : m.getAsJsonArray("sticker_items")) b.append("\n🏷️ 贴纸：").append(str(s.getAsJsonObject(), "name")).append("\n");
                }
                for (JsonElement ae : m.getAsJsonArray("attachments")) {
                    JsonObject a = ae.getAsJsonObject();
                    String rel = fileOf.get(a.get("id").getAsString());
                    String name = a.get("filename").getAsString();
                    if (rel != null) {
                        boolean image = str(a, "content_type").startsWith("image/");
                        b.append("\n").append(image ? "!" : "").append("[📎 ").append(name).append("](").append(rel.replace(" ", "%20")).append(")\n");
                    } else {
                        b.append("\n📎 ").append(name).append("（").append(BotRules.formatBytes(a.has("size") ? a.get("size").getAsLong() : 0))
                                .append("，").append(req.files() ? "太大，没下载，请看原消息" : "没下载附件").append("）\n");
                    }
                }
                if (m.has("thread") && m.get("thread").isJsonObject()) {
                    b.append("\n🧵 由这条消息开了子区：").append(str(m.getAsJsonObject("thread"), "name")).append("\n");
                }
            }
        }
        if (all.isEmpty()) b.append("\n（这段时间没有符合条件的消息）\n");
        return b.toString();
    }

    private String json(Request req, List<JsonObject> all, Map<String, JsonObject> channels, Map<String, String> roles, Map<String, String> fileOf) {
        JsonArray arr = new JsonArray();
        for (JsonObject m : all) {
            JsonObject o = new JsonObject();
            o.addProperty("id", m.get("id").getAsString());
            o.addProperty("time", DateTimeFormatter.ISO_OFFSET_DATE_TIME.withZone(zone).format(time(m)));
            String cid = m.get("_channel").getAsString();
            o.addProperty("channelId", cid);
            o.addProperty("location", label(channels.get(cid), channels));
            o.addProperty("author", authorName(m));
            o.addProperty("authorId", m.getAsJsonObject("author").get("id").getAsString());
            o.addProperty("content", render(str(m, "content"), m, channels, roles));
            if (m.has("referenced_message") && m.get("referenced_message").isJsonObject()) {
                o.addProperty("replyTo", m.getAsJsonObject("referenced_message").get("id").getAsString());
            }
            JsonArray atts = new JsonArray();
            for (JsonElement ae : m.getAsJsonArray("attachments")) {
                JsonObject a = ae.getAsJsonObject();
                JsonObject x = new JsonObject();
                x.addProperty("name", a.get("filename").getAsString());
                x.addProperty("size", a.has("size") ? a.get("size").getAsLong() : 0);
                String rel = fileOf.get(a.get("id").getAsString());
                if (rel != null) x.addProperty("file", rel);
                atts.add(x);
            }
            o.add("attachments", atts);
            o.addProperty("link", link(req.guildId(), m));
            arr.add(o);
        }
        return new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create().toJson(arr);
    }

    /** 把 <@id> <#id> <@&id> <:emoji:id> <t:..> 换成看得懂的文字。 */
    private String render(String content, JsonObject m, Map<String, JsonObject> channels, Map<String, String> roles) {
        if (content.isEmpty()) return content;
        Map<String, String> users = new HashMap<>();
        if (m.has("mentions")) {
            for (JsonElement e : m.getAsJsonArray("mentions")) {
                JsonObject u = e.getAsJsonObject();
                users.put(u.get("id").getAsString(), displayName(u));
            }
        }
        String s = replace(USER_MENTION, content, id -> "@" + users.getOrDefault(id, id));
        s = replace(ROLE_MENTION, s, id -> "@" + roles.getOrDefault(id, id));
        s = replace(CHANNEL_MENTION, s, id -> channels.containsKey(id) ? "#" + str(channels.get(id), "name") : "#" + id);
        s = replace(CUSTOM_EMOJI, s, name -> ":" + name + ":");
        s = replace(TIMESTAMP, s, sec -> DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(zone).format(Instant.ofEpochSecond(Long.parseLong(sec))));
        return s;
    }

    private static String replace(Pattern p, String s, java.util.function.Function<String, String> f) {
        Matcher m = p.matcher(s);
        StringBuilder out = new StringBuilder();
        while (m.find()) m.appendReplacement(out, Matcher.quoteReplacement(f.apply(m.group(1))));
        m.appendTail(out);
        return out.toString();
    }

    // ===================== 小工具 =====================

    static String label(JsonObject ch, Map<String, JsonObject> channels) {
        if (ch == null) return "#?";
        String name = str(ch, "name");
        int type = ch.has("type") ? ch.get("type").getAsInt() : 0;
        if (THREAD_TYPES.contains(type)) {
            JsonObject parent = channels.get(str(ch, "parent_id"));
            return (parent == null ? "#?" : "#" + str(parent, "name")) + " › " + name;
        }
        return "#" + name;
    }

    private static String link(String guild, JsonObject m) {
        return "https://discord.com/channels/" + guild + "/" + m.get("channel_id").getAsString() + "/" + m.get("id").getAsString();
    }

    private static Instant time(JsonObject m) {
        return java.time.OffsetDateTime.parse(m.get("timestamp").getAsString()).toInstant();
    }

    static String authorName(JsonObject m) {
        JsonObject a = m.getAsJsonObject("author");
        if (m.has("member") && m.get("member").isJsonObject() && !str(m.getAsJsonObject("member"), "nick").isEmpty()) {
            return str(m.getAsJsonObject("member"), "nick");
        }
        return a == null ? "?" : displayName(a);
    }

    private static String displayName(JsonObject u) {
        String g = str(u, "global_name");
        return g.isEmpty() ? str(u, "username") : g;
    }

    private static String findAuthor(List<JsonObject> all, String userId) {
        for (JsonObject m : all) if (userId.equals(m.getAsJsonObject("author").get("id").getAsString())) return authorName(m);
        return null;
    }

    private static String md(String s) {
        return s.replace("*", "\\*").replace("_", "\\_");
    }

    private static String cell(String s) {
        return s.replace("|", "\\|");
    }

    static String str(JsonObject o, String key) {
        if (o == null) return "";
        JsonElement e = o.get(key);
        return e == null || e.isJsonNull() ? "" : e.getAsString();
    }
}
