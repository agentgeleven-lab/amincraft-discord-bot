package cn.aminplay.discord;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.logging.Logger;

/**
 * 2026-10-07 daily replay bundle：把 MiniGameHub 打好的每日回放包发到论坛回放贴。
 * 用户：「每天日本时间凌晨1点将所有小游戏的统一打包发在特定频道（子区）」「发到论坛里的帖子」「人机局也发」。
 *
 * <ul>
 * <li>回放贴：replays.thread-id 为空时在 replays.forum-channel 论坛开贴「📼 Amincraft 对局回放」，把 id 写回配置，以后每天在里面回复。
 *     贴子被删了就重新开；被归档了先取消归档。</li>
 * <li>每个 zip 一条消息（第一条带当天摘要）。每发完一条就记进 replays-posted.yml，失败后从没发的那一部分接着发；
 *     每条消息带固定的 nonce（enforce_nonce），网络超时后重发也不会出现两条。</li>
 * <li>全部发完才调用 MiniGameHub 的 markPosted；如果那一步失败，下次只补 markPosted，不会再发一遍。</li>
 * <li>失败按 1、2、5、10、30、60 分钟退避重试；限速（429）由 DiscordRest 按 retry_after 等待。</li>
 * </ul>
 */
final class ReplayPoster {
    /** MiniGameHub 的回放包接口（实机 = HubReplaySource 反射调用 Bukkit 服务；测试 = 假的）。 */
    interface Source {
        List<Map<String, Object>> pending() throws Exception;

        void markPosted(String id, String where) throws Exception;

        void markFailed(String id, String error) throws Exception;

        String bundleNow() throws Exception;

        Map<String, Object> status() throws Exception;
    }

    /** 读配置、写回贴子 id（实机在主线程 saveConfig）。 */
    interface Settings {
        BotConfig cfg();

        void saveThreadId(String id) throws Exception;
    }

    static final long[] BACKOFF = {60_000, 120_000, 300_000, 600_000, 1_800_000, 3_600_000};

    private final DiscordRest rest;
    private final Settings settings;
    private final File ledgerFile;
    private final Logger log;
    private final long spacingMs;
    private final YamlConfiguration ledger = new YamlConfiguration();
    private long nextTry;
    private int failures;
    private volatile String lastError;
    private volatile String lastPosted;
    private volatile long lastPostedAt;

    ReplayPoster(DiscordRest rest, Settings settings, File ledgerFile, Logger log, long spacingMs) {
        this.rest = rest;
        this.settings = settings;
        this.ledgerFile = ledgerFile;
        this.log = log;
        this.spacingMs = spacingMs;
        if (ledgerFile.isFile()) {
            try {
                ledger.load(ledgerFile);
            } catch (Exception e) {
                log.warning("replays-posted.yml 读不出来（" + e.getMessage() + "），按空的处理");
            }
        }
    }

    // ================= 定时 / 手动 =================

    /** 发所有待发的包。force = 不管退避时间（管理员 post-now）。返回给管理员看的一行结果。 */
    synchronized String tick(Source src, boolean force) {
        if (src == null) return "MiniGameHub 的回放包接口不可用（MiniGameHub 没装、没开或版本太旧），稍后再试";
        if (!settings.cfg().replaysEnabled) return "replays.enabled = false，不发";
        long now = System.currentTimeMillis();
        if (!force && now < nextTry) return "上次失败，" + ((nextTry - now) / 1000) + " 秒后重试";
        List<Map<String, Object>> pending;
        try {
            pending = src.pending();
        } catch (Exception e) {
            return "读取待发列表失败：" + e;
        }
        if (pending.isEmpty()) return "没有待发的回放包";
        int ok = 0;
        for (Map<String, Object> b : pending) {
            String id = String.valueOf(b.get("id"));
            String label = String.valueOf(b.get("label"));
            try {
                String where = ledger.getString(key(id) + ".where");
                if (!ledger.getBoolean(key(id) + ".done")) where = post(b);
                markPosted(src, id, where);
                failures = 0;
                nextTry = 0;
                lastError = null;
                lastPosted = label;
                lastPostedAt = System.currentTimeMillis();
                ok++;
            } catch (Exception e) {
                String msg = e instanceof DiscordRest.DiscordException d ? d.friendly() : String.valueOf(e.getMessage());
                lastError = label + "：" + msg;
                nextTry = System.currentTimeMillis() + BACKOFF[Math.min(failures, BACKOFF.length - 1)];
                failures++;
                log.warning("发送回放包 " + label + " 失败（第 " + failures + " 次，" + (BACKOFF[Math.min(failures - 1, BACKOFF.length - 1)] / 60000) + " 分钟后重试）：" + msg);
                try {
                    src.markFailed(id, msg);
                } catch (Exception ignored) {
                }
                return "失败：" + lastError + (ok > 0 ? "（之前已发 " + ok + " 个）" : "");
            }
        }
        return "已发 " + ok + " 个回放包";
    }

    /** 管理员测试：有待发的就发；没有就先让 MiniGameHub 把上次截止到现在的对局打包，再发。 */
    String postNow(Source src) {
        if (src == null) return tick(null, true);
        try {
            if (src.pending().isEmpty()) {
                String id = src.bundleNow();
                if (id == null) return "没有待发的回放包，现在也没有可打包的时间段";
                List<Map<String, Object>> p = src.pending();
                if (p.stream().noneMatch(b -> id.equals(b.get("id")))) return "已打包 " + id + "，但这段时间没有对局（post-empty 关着，不发）";
            }
        } catch (Exception e) {
            return "打包失败：" + e;
        }
        return tick(src, true);
    }

    private void markPosted(Source src, String id, String where) throws Exception {
        src.markPosted(id, where);
        ledger.set(key(id) + ".marked", true);
        save();
    }

    // ================= 发送 =================

    /** 发一个包的所有部分（从上次断掉的地方接着发）。返回贴子链接。 */
    private String post(Map<String, Object> b) throws Exception {
        String id = String.valueOf(b.get("id"));
        String k = key(id);
        List<Map<String, Object>> files = files(b);
        int n = Math.max(1, files.size());
        int done = ledger.getInt(k + ".parts", 0);
        ledger.set(k + ".label", String.valueOf(b.get("label")));
        String thread = ensureThread();
        List<String> ids = new ArrayList<>(ledger.getStringList(k + ".messages"));
        for (int i = done; i < n; i++) {
            if (i > done || done > 0) sleep(spacingMs);
            JsonObject payload = Interactions.message(i == 0 ? dailyText(b) : partText(b, i), null);
            payload.add("allowed_mentions", Interactions.noMentions());
            payload.addProperty("nonce", nonce(id, i));
            payload.addProperty("enforce_nonce", true);
            Path file = files.isEmpty() ? null : Path.of(String.valueOf(files.get(i).get("path")));
            String[] where = send(thread, payload, file);
            thread = where[0];
            ids.add(where[1]);
            ledger.set(k + ".parts", i + 1);
            ledger.set(k + ".messages", ids);
            ledger.set(k + ".thread", thread);
            save();
        }
        String guild = settings.cfg().guildId;
        String link = "https://discord.com/channels/" + (guild.isEmpty() ? "@me" : guild) + "/" + thread + (ids.isEmpty() ? "" : "/" + ids.get(0));
        ledger.set(k + ".done", true);
        ledger.set(k + ".where", link);
        ledger.set(k + ".at", System.currentTimeMillis());
        save();
        log.info("回放包 " + b.get("label") + " 已发到回放贴（" + n + " 条消息）：" + link);
        return link;
    }

    /** 发到贴子；贴子被删就重开，被归档就取消归档，各重试一次。返回 {贴子 id, 消息 id}。 */
    private String[] send(String thread, JsonObject payload, Path file) throws Exception {
        for (int attempt = 0; ; attempt++) {
            try {
                JsonElement r = file == null ? rest.post("/channels/" + thread + "/messages", payload)
                        : rest.postFiles("/channels/" + thread + "/messages", payload, List.of(file));
                return new String[]{thread, r.getAsJsonObject().get("id").getAsString()};
            } catch (DiscordRest.DiscordException e) {
                if (attempt > 0) throw e;
                if (e.code == 10003 || e.status == 404) {
                    log.warning("回放贴 " + thread + " 不存在了（可能被删除），重新开贴");
                    settings.saveThreadId("");
                    thread = ensureThread();
                } else if (e.code == 50083) {
                    JsonObject un = new JsonObject();
                    un.addProperty("archived", false);
                    rest.patch("/channels/" + thread, un);
                } else throw e;
            }
        }
    }

    /** 回放贴的 id：配置里有就用；没有就在论坛开贴并写回配置。 */
    String ensureThread() throws Exception {
        BotConfig c = settings.cfg();
        if (!c.replaysThread.isEmpty()) return c.replaysThread;
        if (c.replaysForum.isEmpty()) throw new IOException("没有设置 replays.forum-channel");
        JsonObject ch = rest.get("/channels/" + c.replaysForum).getAsJsonObject();
        int type = ch.has("type") ? ch.get("type").getAsInt() : 15;
        String id;
        if (type == 15 || type == 16) {
            JsonObject body = new JsonObject();
            body.addProperty("name", BotRules.truncate(c.replaysTitle, 100));
            body.addProperty("auto_archive_duration", 10080);
            JsonObject first = Interactions.message(introText(c), null);
            first.add("allowed_mentions", Interactions.noMentions());
            body.add("message", first);
            String tag = pickTag(ch, c.replaysTag);
            boolean requireTag = ch.has("flags") && (ch.get("flags").getAsInt() & 16) != 0;
            if (tag != null && (requireTag || !c.replaysTag.isEmpty())) body.add("applied_tags", tags(tag));
            try {
                id = rest.post("/channels/" + c.replaysForum + "/threads", body).getAsJsonObject().get("id").getAsString();
            } catch (DiscordRest.DiscordException e) {
                if (e.code != 40067 || tag == null) throw e;
                body.add("applied_tags", tags(tag));
                id = rest.post("/channels/" + c.replaysForum + "/threads", body).getAsJsonObject().get("id").getAsString();
            }
            log.info("已在论坛 " + c.replaysForum + " 开回放贴「" + c.replaysTitle + "」：" + id);
        } else if (type == 11 || type == 12) {
            id = c.replaysForum;   // 填的本身就是一个贴子
        } else {
            id = c.replaysForum;   // 普通文字频道：直接发在频道里
        }
        settings.saveThreadId(id);
        return id;
    }

    private static JsonArray tags(String tag) {
        JsonArray a = new JsonArray();
        a.add(tag);
        return a;
    }

    /** 论坛标签：按名字或 id 找 wanted，找不到用第一个；没有标签返回 null。 */
    static String pickTag(JsonObject forum, String wanted) {
        if (!forum.has("available_tags")) return null;
        String first = null;
        for (JsonElement t : forum.getAsJsonArray("available_tags")) {
            JsonObject o = t.getAsJsonObject();
            String id = o.get("id").getAsString();
            if (first == null) first = id;
            if (!wanted.isEmpty() && (wanted.equals(id) || wanted.equals(o.get("name").getAsString()))) return id;
        }
        return first;
    }

    // ================= 文字 =================

    static String introText(BotConfig c) {
        return "这里每天**日本时间凌晨 1 点**自动发前一天（01:00 → 01:00）所有小游戏的对局回放，人机局也在里面。\n"
                + "每条回复带一个 zip：下载解压后双击 index.html 打开，用浏览器离线观看（不需要装任何东西）。\n"
                + "回放在服务器上保留大约一周。";
    }

    /** 当天第一条：日期范围、各游戏局数、最长对局、AminTV 当日最佳（正式 / 人机分开）、附件、打开方法。 */
    @SuppressWarnings("unchecked")
    static String dailyText(Map<String, Object> b) {
        Map<String, Object> s = b.get("summary") instanceof Map<?, ?> m ? (Map<String, Object>) m : Map.of();
        String label = String.valueOf(b.get("label"));
        StringBuilder t = new StringBuilder();
        t.append("📼 **Amincraft 对局回放 · ").append(label).append("**\n");
        String zone = String.valueOf(s.getOrDefault("zone", b.getOrDefault("zone", "Asia/Tokyo")));
        t.append("🕐 ").append(s.getOrDefault("fromText", "?")).append(" → ").append(s.getOrDefault("toText", "?"))
                .append("（").append(zone.equals("Asia/Tokyo") ? "日本时间" : zone).append("）\n");
        int count = num(s.get("count"));
        if (count == 0 || Boolean.TRUE.equals(b.get("empty"))) {
            t.append(label.contains("至") ? "这段时间没有对局。" : "昨日无对局。");
            return t.toString();
        }
        t.append("🎮 共 **").append(count).append("** 局");
        int bots = num(s.get("botMatches"));
        if (bots > 0) t.append("（含人机局 ").append(bots).append("）");
        List<String> per = new ArrayList<>();
        if (s.get("games") instanceof List<?> gl) for (Object o : gl) if (o instanceof Map<?, ?> g) per.add(g.get("name") + " " + num(g.get("count")));
        if (!per.isEmpty()) t.append("：").append(String.join(" · ", per));
        t.append('\n');
        if (s.get("longest") instanceof Map<?, ?> l)
            t.append("⏱ 最长对局：").append(md(String.valueOf(l.get("game")))).append(" · ").append(md(String.valueOf(l.get("map")))).append(" **").append(l.get("durationText")).append("**（").append(l.get("start")).append("）\n");
        if (s.get("bestOfficial") instanceof Map<?, ?> r)
            t.append("🏆 AminTV 当日最佳（正式）：**").append(md(String.valueOf(r.get("name")))).append("** ").append(r.get("ratingText")).append(" · ").append(md(String.valueOf(r.get("map")))).append('\n');
        if (s.get("bestBot") instanceof Map<?, ?> r)
            t.append("🤖 AminTV 当日最佳（人机）：**").append(md(String.valueOf(r.get("name")))).append("** ").append(r.get("ratingText")).append(" · ").append(md(String.valueOf(r.get("map")))).append('\n');
        List<Map<String, Object>> files = files(b);
        if (!files.isEmpty()) {
            Map<String, Object> f = files.get(0);
            t.append("📦 ").append(f.get("name")).append("（").append(mb(f.get("bytes"))).append("，").append(num(f.get("matches"))).append(" 局）");
            if (files.size() > 1) t.append(" · 共 ").append(files.size()).append(" 个压缩包，后面几条接着发");
            t.append('\n');
        }
        int over = num(s.get("oversize"));
        if (over > 0) t.append("⚠ 有 ").append(over).append(" 局太大，没放进压缩包（留在服务器上，index.html 里有说明）\n");
        t.append("下载解压后双击 index.html 打开。");
        return BotRules.truncate(t.toString(), 1900);
    }

    /** 第 2 个以后的压缩包。i = 0 起的序号。 */
    static String partText(Map<String, Object> b, int i) {
        List<Map<String, Object>> files = files(b);
        Map<String, Object> f = files.get(i);
        return "📦 **Amincraft 对局回放 · " + b.get("label") + "** · 第 " + (i + 1) + "/" + files.size() + " 部分：" + f.get("name")
                + "（" + mb(f.get("bytes")) + "，" + num(f.get("matches")) + " 局）\n下载解压后双击 index.html 打开。";
    }

    @SuppressWarnings("unchecked")
    static List<Map<String, Object>> files(Map<String, Object> b) {
        List<Map<String, Object>> out = new ArrayList<>();
        if (b.get("files") instanceof List<?> l) for (Object o : l) if (o instanceof Map<?, ?> m) out.add((Map<String, Object>) m);
        return out;
    }

    private static int num(Object o) {
        return o instanceof Number n ? n.intValue() : 0;
    }

    private static String mb(Object bytes) {
        double b = bytes instanceof Number n ? n.doubleValue() : 0;
        return b >= 1024 * 1024 ? String.format(Locale.ROOT, "%.1f MB", b / 1048576) : String.format(Locale.ROOT, "%d KB", Math.max(1, Math.round(b / 1024)));
    }

    /** Discord markdown 转义（名字里的 _ * ~ ` | > 不会变成格式）。 */
    static String md(String s) {
        return s == null ? "" : s.replaceAll("([\\\\_*~`|>])", "\\\\$1");
    }

    /** 同一个包同一部分永远是同一个 nonce（≤ 25 字符），Discord 收到重复的会直接返回已发的那条。 */
    static String nonce(String bundleId, int part) {
        try {
            byte[] h = MessageDigest.getInstance("SHA-1").digest(bundleId.getBytes(StandardCharsets.UTF_8));
            StringBuilder b = new StringBuilder();
            for (int i = 0; i < 10; i++) b.append(String.format("%02x", h[i]));
            return b.append('p').append(part).toString();
        } catch (Exception e) {
            return Integer.toHexString(bundleId.hashCode()) + "p" + part;
        }
    }

    private static String key(String bundleId) {
        return "posted." + bundleId.replaceAll("[^A-Za-z0-9_-]", "_");
    }

    private void save() {
        try {
            ledger.save(ledgerFile);
        } catch (IOException e) {
            log.warning("保存 replays-posted.yml 失败：" + e.getMessage());
        }
    }

    private static void sleep(long ms) {
        if (ms <= 0) return;
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    // ================= 状态 =================

    /** 管理员看的状态行。 */
    synchronized List<String> status(Source src) {
        BotConfig c = settings.cfg();
        List<String> out = new ArrayList<>();
        out.add("回放贴：" + (c.replaysEnabled ? "开" : "关") + "，论坛 " + (c.replaysForum.isEmpty() ? "未设置" : c.replaysForum)
                + "，贴子 " + (c.replaysThread.isEmpty() ? "（还没开，第一次发送时自动开）" : c.replaysThread));
        if (src == null) out.add("MiniGameHub 回放包接口：不可用（MiniGameHub 没开或版本不含每日回放包）");
        else {
            try {
                Map<String, Object> st = src.status();
                List<Map<String, Object>> p = src.pending();
                out.add("MiniGameHub：上次截止 " + st.get("lastCutoffText") + "，下次打包 " + st.get("nextCutoffText") + "（" + st.get("zone") + "），已发 " + st.get("posted"));
                StringBuilder q = new StringBuilder("待发 " + p.size() + " 个");
                for (Map<String, Object> b : p) q.append("：").append(b.get("label")).append("（").append(files(b).size()).append(" 个 zip，尝试 ").append(num(b.get("attempts"))).append(" 次）");
                out.add(q.toString());
            } catch (Exception e) {
                out.add("MiniGameHub 状态读取失败：" + e);
            }
        }
        if (lastPosted != null) out.add("最近一次发送：" + lastPosted + "（" + new java.util.Date(lastPostedAt) + "）");
        if (lastError != null) out.add("最近一次失败：" + lastError + (nextTry > System.currentTimeMillis() ? "，" + (nextTry - System.currentTimeMillis()) / 1000 + " 秒后重试" : ""));
        return out;
    }
}
