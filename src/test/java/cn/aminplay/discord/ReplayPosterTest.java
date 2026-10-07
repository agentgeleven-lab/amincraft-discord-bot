package cn.aminplay.discord;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpServer;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import java.util.logging.Logger;

/**
 * 2026-10-07 daily replay bundle：用本地假 Discord（不连真的 Discord）测回放贴：开贴（要求标签）、复用贴子、附件上传、
 * 分卷、失败重试只补没发的部分、markPosted 失败后不重发、贴子被删重开、归档贴取消归档、429 限速、无对局的一天、文字。
 * 运行：java -cp classes;test-classes;<paper deps> cn.aminplay.discord.ReplayPosterTest
 */
public final class ReplayPosterTest {
    private static int passed, failed;

    /** 一次请求：方法 + 路径 + 正文（multipart 原样） */
    record Call(String method, String path, byte[] body, String contentType) {
        String text() {
            return new String(body, StandardCharsets.UTF_8);
        }

        JsonObject payload() {
            String t = text();
            if (contentType != null && contentType.startsWith("multipart/")) {
                int a = t.indexOf("\r\n\r\n") + 4;
                int b = t.indexOf("\r\n--", a);
                return JsonParser.parseString(t.substring(a, b)).getAsJsonObject();
            }
            return JsonParser.parseString(t).getAsJsonObject();
        }
    }

    static final List<Call> calls = Collections.synchronizedList(new ArrayList<>());
    /** 按调用决定回应：返回 null = 默认回应。 */
    static volatile Function<Call, int[]> fault = c -> null;
    static final AtomicInteger ids = new AtomicInteger(100);
    static volatile String forumJson = "{\"id\":\"1\",\"type\":15,\"flags\":16,\"available_tags\":[{\"id\":\"tg1\",\"name\":\"公告\"},{\"id\":\"tg2\",\"name\":\"回放\"}]}";

    static class FakeSource implements ReplayPoster.Source {
        final List<Map<String, Object>> pending = new ArrayList<>();
        final List<String> posted = new ArrayList<>(), failedIds = new ArrayList<>();
        int failMarkPosted;
        String nowId;

        @Override
        public List<Map<String, Object>> pending() {
            return new ArrayList<>(pending);
        }

        @Override
        public void markPosted(String id, String where) throws Exception {
            if (failMarkPosted > 0) {
                failMarkPosted--;
                throw new Exception("MiniGameHub 暂时不可用");
            }
            posted.add(id);
            pending.removeIf(b -> b.get("id").equals(id));
        }

        @Override
        public void markFailed(String id, String error) {
            failedIds.add(id);
        }

        @Override
        public String bundleNow() {
            return nowId;
        }

        @Override
        public Map<String, Object> status() {
            return Map.of("lastCutoffText", "10-07 01:00", "nextCutoffText", "10-08 01:00", "zone", "Asia/Tokyo", "posted", posted.size());
        }
    }

    public static void main(String[] args) throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", ex -> {
            byte[] body = ex.getRequestBody().readAllBytes();
            Call c = new Call(ex.getRequestMethod(), ex.getRequestURI().getPath(), body, ex.getRequestHeaders().getFirst("Content-Type"));
            calls.add(c);
            int[] f = fault.apply(c);
            int status = 200;
            String resp;
            if (f != null) {
                status = f[0];
                resp = f[0] == 429 ? "{\"message\":\"You are being rate limited.\",\"retry_after\":0.05,\"global\":false}" : "{\"code\":" + f[1] + ",\"message\":\"x\"}";
            } else if (c.method.equals("GET") && c.path.equals("/channels/1")) resp = forumJson;
            else if (c.method.equals("POST") && c.path.equals("/channels/1/threads")) resp = "{\"id\":\"" + ids.incrementAndGet() + "\",\"type\":11}";
            else if (c.method.equals("POST") && c.path.endsWith("/messages")) resp = "{\"id\":\"m" + ids.incrementAndGet() + "\"}";
            else resp = "{}";
            byte[] b = resp.getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(status, b.length);
            ex.getResponseBody().write(b);
            ex.close();
        });
        server.start();
        try {
            run("http://127.0.0.1:" + server.getAddress().getPort());
        } finally {
            server.stop(0);
        }
        System.out.println("ReplayPosterTest: " + passed + " passed, " + failed + " failed");
        if (failed > 0) System.exit(1);
    }

    static void run(String api) throws Exception {
        Path dir = Files.createTempDirectory("replay-poster-test");
        YamlConfiguration yc = new YamlConfiguration();
        yc.loadFromString("discord:\n  guild-id: '100000000000000001'\nreplays:\n  forum-channel: '1'\n  thread-id: ''\n");   // 假论坛 id = 1
        final BotConfig[] cfg = {BotConfig.load(yc)};
        List<String> saved = new ArrayList<>();
        ReplayPoster.Settings settings = new ReplayPoster.Settings() {
            @Override
            public BotConfig cfg() {
                return cfg[0];
            }

            @Override
            public void saveThreadId(String id) {
                saved.add(id);
                yc.set("replays.thread-id", id);
                cfg[0] = BotConfig.load(yc);
            }
        };
        Logger log = Logger.getLogger("test");
        File ledger = dir.resolve("replays-posted.yml").toFile();
        DiscordRest rest = new DiscordRest(HttpClient.newHttpClient(), "fake-token", api);
        ReplayPoster poster = new ReplayPoster(rest, settings, ledger, log, 0);
        FakeSource src = new FakeSource();

        check(cfg[0].replaysForum.equals("1") && cfg[0].replaysThread.isEmpty() && cfg[0].replaysTitle.equals("📼 Amincraft 对局回放"), "配置默认值（标题 Amincraft）");
        check(BotConfig.load(new YamlConfiguration()).replaysForum.isEmpty(), "默认论坛为空（用指令设置）");
        check(poster.tick(src, false).equals("没有待发的回放包") && calls.isEmpty(), "没有包时不发任何请求");
        check(poster.tick(null, false).contains("不可用"), "MiniGameHub 不在时只回一句话");

        // ---------- 1. 第一天：开贴（论坛要求标签）+ 一个 zip ----------
        Path zip1 = dir.resolve("Amincraft-回放-2026-10-06.zip");
        Files.write(zip1, "PK-fake-zip-1".getBytes(StandardCharsets.UTF_8));
        src.pending.add(bundle("w-1-2", "2026-10-06", List.of(zip1), 7));
        String r = poster.tick(src, false);
        check(r.equals("已发 1 个回放包"), "第一天发送成功：" + r);
        check(saved.size() == 1 && cfg[0].replaysThread.startsWith("10"), "贴子 id 写回配置：" + saved);
        String thread = cfg[0].replaysThread;
        List<Call> c1 = new ArrayList<>(calls);
        check(c1.size() == 3, "GET 论坛 + 开贴 + 发附件 = 3 次，实际 " + c1.size());
        Call create = c1.get(1);
        check(create.method.equals("POST") && create.path.equals("/channels/1/threads"), "在论坛开贴");
        JsonObject cb = create.payload();
        check(cb.get("name").getAsString().equals("📼 Amincraft 对局回放"), "贴子标题");
        check(cb.getAsJsonArray("applied_tags").get(0).getAsString().equals("tg1"), "论坛要求标签时自动选第一个");
        check(cb.getAsJsonObject("message").get("content").getAsString().contains("日本时间凌晨 1 点"), "开贴说明");
        Call up = c1.get(2);
        check(up.path.equals("/channels/" + thread + "/messages") && up.contentType.startsWith("multipart/form-data; boundary="), "在贴子里回复（multipart）");
        check(up.text().contains("filename=\"Amincraft-回放-2026-10-06.zip\"") && up.text().contains("PK-fake-zip-1"), "附件文件名和内容");
        JsonObject p1 = up.payload();
        String content = p1.get("content").getAsString();
        check(content.contains("2026-10-06") && content.contains("共 **7** 局") && content.contains("方块CS 4 · 空岛战争 3"), "摘要：日期、局数、各游戏");
        check(content.contains("最长对局：方块CS · 炙热沙城 **12:44**"), "摘要：最长对局");
        check(content.contains("AminTV 当日最佳（正式）：**Amin\\_Doll** 1.35") && content.contains("AminTV 当日最佳（人机）：**rp1606** 0.85"), "摘要：正式 / 人机分开，名字转义：" + content);
        check(content.contains("下载解压后双击 index.html 打开。") && content.contains("日本时间"), "打开方法一行");
        check(p1.getAsJsonArray("attachments").get(0).getAsJsonObject().get("filename").getAsString().equals("Amincraft-回放-2026-10-06.zip"), "payload 里的附件名");
        check(p1.get("nonce").getAsString().equals(ReplayPoster.nonce("w-1-2", 0)) && p1.get("enforce_nonce").getAsBoolean(), "固定 nonce 防重复");
        check(p1.getAsJsonObject("allowed_mentions").getAsJsonArray("parse").isEmpty(), "不 @ 任何人");
        check(src.posted.equals(List.of("w-1-2")), "MiniGameHub 标记已发");

        // ---------- 2. 第二天：复用贴子、分 3 卷，第 2 卷第一次失败 → 只补发第 2、3 卷 ----------
        calls.clear();
        List<Path> parts = new ArrayList<>();
        for (int i = 1; i <= 3; i++) {
            Path z = dir.resolve("Amincraft-回放-2026-10-07-part" + i + ".zip");
            Files.write(z, ("PK-part-" + i).getBytes(StandardCharsets.UTF_8));
            parts.add(z);
        }
        src.pending.add(bundle("w-2-3", "2026-10-07", parts, 30));
        AtomicInteger part2 = new AtomicInteger();
        fault = c -> c.text().contains("PK-part-2") && part2.incrementAndGet() == 1 ? new int[]{500, 0} : null;
        r = poster.tick(src, false);
        check(r.startsWith("失败") && src.failedIds.equals(List.of("w-2-3")) && src.posted.size() == 1, "第 2 卷失败：报告失败、markFailed、还没标记已发：" + r);
        check(poster.tick(src, false).contains("秒后重试"), "失败后按退避时间等待，不会马上狂发");
        r = poster.tick(src, true);
        check(r.equals("已发 1 个回放包"), "重试成功：" + r);
        long first = calls.stream().filter(c -> c.text().contains("PK-part-1")).count();
        long second = calls.stream().filter(c -> c.text().contains("PK-part-2")).count();
        long third = calls.stream().filter(c -> c.text().contains("PK-part-3")).count();
        check(first == 1 && second == 2 && third == 1, "第 1 卷只发一次、第 2 卷重试一次、第 3 卷一次：" + first + "/" + second + "/" + third);
        check(calls.stream().noneMatch(c -> c.path.endsWith("/threads") || c.method.equals("GET")), "复用已有贴子，不重新开贴");
        check(calls.stream().allMatch(c -> c.path.equals("/channels/" + thread + "/messages")), "都发在同一个贴子里");
        List<Call> okParts = calls.stream().filter(c -> c.text().contains("PK-part-")).toList();
        check(okParts.get(0).payload().get("content").getAsString().contains("共 3 个压缩包"), "第一条说明一共几卷");
        check(okParts.get(okParts.size() - 1).payload().get("content").getAsString().contains("第 3/3 部分"), "后面的写第几部分");
        check(!okParts.get(1).payload().get("nonce").getAsString().equals(okParts.get(0).payload().get("nonce").getAsString()), "每一卷 nonce 不同");
        check(okParts.get(1).payload().get("nonce").getAsString().equals(okParts.get(2).payload().get("nonce").getAsString()), "重试同一卷用同一个 nonce");

        // ---------- 3. 发完但 MiniGameHub 没收到「已发」→ 下次只补标记，不重发 ----------
        calls.clear();
        Path z4 = dir.resolve("Amincraft-回放-2026-10-08.zip");
        Files.write(z4, "PK-day-8".getBytes(StandardCharsets.UTF_8));
        src.pending.add(bundle("w-3-4", "2026-10-08", List.of(z4), 2));
        src.failMarkPosted = 1;
        r = poster.tick(src, true);
        check(r.startsWith("失败") && calls.size() == 1, "消息发出去了，但标记失败");
        r = poster.tick(src, true);
        check(r.equals("已发 1 个回放包") && calls.size() == 1 && src.posted.contains("w-3-4"), "第二次只补标记，消息没有发第二遍");
        // 重启（新的 poster 读同一个记录文件）后同一个包又出现在待发里，也不会再发
        src.pending.add(bundle("w-3-4", "2026-10-08", List.of(z4), 2));
        ReplayPoster restarted = new ReplayPoster(rest, settings, ledger, log, 0);
        restarted.tick(src, true);
        check(calls.size() == 1, "重启后也不会重复发");

        // ---------- 4. 贴子被删 → 重新开贴；归档的贴子 → 先取消归档 ----------
        calls.clear();
        Path z5 = dir.resolve("Amincraft-回放-2026-10-09.zip");
        Files.write(z5, "PK-day-9".getBytes(StandardCharsets.UTF_8));
        src.pending.add(bundle("w-4-5", "2026-10-09", List.of(z5), 1));
        String old = cfg[0].replaysThread;
        fault = c -> c.path.equals("/channels/" + old + "/messages") ? new int[]{404, 10003} : null;
        r = poster.tick(src, true);
        check(r.equals("已发 1 个回放包") && !cfg[0].replaysThread.equals(old), "贴子被删后重新开贴并发送：" + r);
        String t2 = cfg[0].replaysThread;
        check(calls.stream().anyMatch(c -> c.path.equals("/channels/" + t2 + "/messages") && c.text().contains("PK-day-9")), "发到新贴子");
        calls.clear();
        Path z6 = dir.resolve("Amincraft-回放-2026-10-10.zip");
        Files.write(z6, "PK-day-10".getBytes(StandardCharsets.UTF_8));
        src.pending.add(bundle("w-5-6", "2026-10-10", List.of(z6), 1));
        AtomicInteger arch = new AtomicInteger();
        fault = c -> c.path.equals("/channels/" + t2 + "/messages") && arch.incrementAndGet() == 1 ? new int[]{400, 50083} : null;
        r = poster.tick(src, true);
        check(r.equals("已发 1 个回放包") && calls.stream().anyMatch(c -> c.method.equals("PATCH") && c.path.equals("/channels/" + t2) && c.text().contains("\"archived\":false")), "归档贴先取消归档再发");
        check(cfg[0].replaysThread.equals(t2), "归档不换贴子");

        // ---------- 5. 429 限速：等 retry_after 再发，只发出一条 ----------
        calls.clear();
        Path z7 = dir.resolve("Amincraft-回放-2026-10-11.zip");
        Files.write(z7, "PK-day-11".getBytes(StandardCharsets.UTF_8));
        src.pending.add(bundle("w-6-7", "2026-10-11", List.of(z7), 1));
        AtomicInteger rl = new AtomicInteger();
        fault = c -> c.text().contains("PK-day-11") && rl.incrementAndGet() <= 2 ? new int[]{429, 0} : null;
        r = poster.tick(src, false);
        check(r.equals("已发 1 个回放包") && rl.get() == 3 && src.failedIds.size() == 2, "429 两次后成功，不算失败：" + r + " rl=" + rl.get());
        fault = c -> null;

        // ---------- 6. 没有对局的一天（post-empty 打开时 MiniGameHub 才会给）：只发文字 ----------
        calls.clear();
        Map<String, Object> empty = bundle("w-7-8", "2026-10-12", List.of(), 0);
        empty.put("empty", true);
        src.pending.add(empty);
        r = poster.tick(src, false);
        check(r.equals("已发 1 个回放包") && calls.size() == 1 && calls.get(0).payload().get("content").getAsString().contains("昨日无对局") && !calls.get(0).contentType.startsWith("multipart"), "无对局：一条文字");

        // ---------- 7. post-now：没有待发时先让 MiniGameHub 打包 ----------
        src.nowId = null;
        check(poster.postNow(src).contains("没有可打包"), "post-now：没有新的时间段");
        src.nowId = "w-8-9";
        check(poster.postNow(src).contains("没有对局"), "post-now：打包了但是空的（不发）");
        calls.clear();
        Path z9 = dir.resolve("Amincraft-回放-2026-10-13.zip");
        Files.write(z9, "PK-now".getBytes(StandardCharsets.UTF_8));
        FakeSource lazy = new FakeSource() {
            @Override
            public String bundleNow() {
                pending.add(bundle("w-9-10", "2026-10-13", List.of(z9), 3));
                return "w-9-10";
            }
        };
        check(poster.postNow(lazy).equals("已发 1 个回放包") && lazy.posted.equals(List.of("w-9-10")) && calls.size() == 1, "post-now：打包后马上发");
        check(String.join("\n", poster.status(lazy)).contains("贴子 " + t2), "状态里显示贴子");

        // ---------- 8. 纯文字规则 ----------
        check(ReplayPoster.nonce("w-1-2", 12).length() <= 25 && ReplayPoster.nonce("w-1-2", 0).equals(ReplayPoster.nonce("w-1-2", 0)), "nonce ≤ 25 字符且稳定");
        check(ReplayPoster.md("a_b*c`d|e").equals("a\\_b\\*c\\`d\\|e"), "markdown 转义");
        JsonObject forum = JsonParser.parseString(forumJson).getAsJsonObject();
        check("tg2".equals(ReplayPoster.pickTag(forum, "回放")) && "tg2".equals(ReplayPoster.pickTag(forum, "tg2")) && "tg1".equals(ReplayPoster.pickTag(forum, "")), "按名字 / id 选标签");
        check(ReplayPoster.pickTag(new JsonObject(), "") == null, "没有标签");
        Map<String, Object> multi = bundle("w-x", "2026-10-04至2026-10-06", List.of(zip1), 9);
        check(ReplayPoster.dailyText(multi).contains("2026-10-04至2026-10-06"), "补打包的多天标题");
        Map<String, Object> e2 = bundle("w-y", "2026-10-04至2026-10-06", List.of(), 0);
        check(ReplayPoster.dailyText(e2).contains("这段时间没有对局"), "多天没对局的说法");
        check(ReplayPoster.dailyText(multi).length() <= 2000, "消息不超过 2000 字");
        check(!ReplayPoster.dailyText(multi).contains("AminPlay"), "文字里没有旧名 AminPlay");
    }

    /** 和 MiniGameHub ReplayBundleApi 给的格式一样的包。 */
    static Map<String, Object> bundle(String id, String label, List<Path> zips, int count) {
        Map<String, Object> b = new LinkedHashMap<>();
        b.put("id", id);
        b.put("label", label);
        b.put("zone", "Asia/Tokyo");
        List<Object> files = new ArrayList<>();
        for (Path z : zips) {
            Map<String, Object> f = new LinkedHashMap<>();
            f.put("path", z.toAbsolutePath().toString());
            f.put("name", z.getFileName().toString());
            f.put("bytes", 3_400_000L);
            f.put("matches", 4L);
            files.add(f);
        }
        b.put("files", files);
        b.put("empty", count == 0);
        Map<String, Object> s = new LinkedHashMap<>();
        s.put("label", label);
        s.put("zone", "Asia/Tokyo");
        s.put("fromText", "2026-10-06 01:00");
        s.put("toText", "2026-10-07 01:00");
        s.put("count", (long) count);
        s.put("games", List.of(Map.of("game", "cs", "name", "方块CS", "count", 4L), Map.of("game", "skywars", "name", "空岛战争", "count", 3L)));
        s.put("longest", Map.of("game", "方块CS", "map", "炙热沙城", "durationText", "12:44", "start", "10-07 00:16"));
        s.put("bestOfficial", Map.of("name", "Amin_Doll", "ratingText", "1.35", "map", "炙热沙城"));
        s.put("bestBot", Map.of("name", "rp1606", "ratingText", "0.85", "map", "炙热沙城"));
        s.put("botMatches", 2L);
        s.put("oversize", 0L);
        b.put("summary", s);
        b.put("attempts", 0L);
        return b;
    }

    private static void check(boolean ok, String what) {
        if (ok) passed++;
        else {
            failed++;
            System.out.println("FAIL " + what);
        }
    }
}
