package cn.aminplay.discord;

import com.sun.net.httpserver.HttpServer;

import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.logging.Logger;

/** 2026-10-07 message collect：时间规则 + 在本地假 Discord 上跑一次完整收集。 */
public final class CollectorTest {
    private static int passed, failed;
    private static final ZoneId JST = ZoneId.of("Asia/Tokyo");

    public static void main(String[] args) throws Exception {
        rules();
        try {
            collect();
        } catch (Exception e) {
            e.printStackTrace();
            fail("收集抛异常：" + e);
        }
        System.out.println("CollectorTest: " + passed + " passed, " + failed + " failed");
        System.exit(failed > 0 ? 1 : 0);
    }

    private static void rules() {
        ZonedDateTime now = ZonedDateTime.of(2026, 10, 7, 15, 30, 0, 0, JST);
        eq(Instant.parse("2026-10-06T15:00:00Z"), BotRules.parseTime("今天", false, JST, now), "今天开始 = JST 0 点");
        eq(Instant.parse("2026-10-07T14:59:59.999Z"), BotRules.parseTime("今天", true, JST, now), "今天结束");
        eq(Instant.parse("2026-10-05T15:00:00Z"), BotRules.parseTime("昨天", false, JST, now), "昨天");
        eq(Instant.parse("2026-10-04T06:30:00Z"), BotRules.parseTime("3d", false, JST, now), "3d = 现在往前 3 天");
        eq(Instant.parse("2026-10-07T03:30:00Z"), BotRules.parseTime("3小时", false, JST, now), "3小时");
        eq(Instant.parse("2026-10-05T15:00:00Z"), BotRules.parseTime("2026-10-06", false, JST, now), "日期开始");
        eq(Instant.parse("2026-10-06T14:59:59.999Z"), BotRules.parseTime("2026-10-06", true, JST, now), "日期结束");
        eq(Instant.parse("2026-10-06T11:00:00Z"), BotRules.parseTime("10-06 20:00", false, JST, now), "无年份 + 时间");
        eq(Instant.parse("2026-10-06T11:00:00Z"), BotRules.parseTime("2026/10/06_20:00", false, JST, now), "斜杠 + 下划线");
        eq(Instant.parse("2026-10-06T11:00:00Z"), BotRules.parseTime("2026-10-06T20:00", false, JST, now), "T 分隔");
        eq(Instant.parse("2026-10-06T11:00:59.999Z"), BotRules.parseTime("10-06 20:00", true, JST, now), "结束包含这一分钟");
        eq(Instant.parse("2026-10-07T11:00:00Z"), BotRules.parseTime("20:00", false, JST, now), "只有时间 = 今天");
        eq(null, BotRules.parseTime("", false, JST, now), "空");
        bad("明天见");
        bad("2026-13-01");
        bad("25:00");

        Instant[] r = BotRules.collectRange(null, null, JST, now);
        eq(Instant.parse("2026-10-06T15:00:00Z"), r[0], "默认从今天 0 点");
        eq(now.toInstant(), r[1], "默认到现在");
        r = BotRules.collectRange("昨天", "昨天", JST, now);
        eq(Instant.parse("2026-10-05T15:00:00Z"), r[0], "昨天整天 开始");
        eq(Instant.parse("2026-10-06T14:59:59.999Z"), r[1], "昨天整天 结束");
        r = BotRules.collectRange(null, "2026-10-01", JST, now);
        eq(Instant.parse("2026-09-30T15:00:00Z"), r[0], "只给结束 = 那天 0 点起");
        r = BotRules.collectRange("今天", "2026-12-31", JST, now);
        eq(now.toInstant(), r[1], "结束不超过现在");
        try {
            BotRules.collectRange("10-07 20:00", "10-07 10:00", JST, now);
            fail("开始晚于结束应报错");
        } catch (IllegalArgumentException e) {
            passed++;
        }

        eq(true, BotRules.snowflakeAt(Instant.parse("2015-01-01T00:00:01Z")) == 1000L << 22, "雪花 ID");
        eq("a_b_.png", BotRules.safeFileName("a/b\\.png"), "文件名去掉路径符");
        eq("x.png", BotRules.safeFileName("..x.png"), "去掉开头的点");
        eq(true, BotRules.safeFileName("a".repeat(200) + ".png").length() == 80 && BotRules.safeFileName("a".repeat(200) + ".png").endsWith(".png"), "长文件名保留扩展名");
        eq("1.5 MB", BotRules.formatBytes(1572864), "formatBytes");
        eq(List.of(List.of(0, 1), List.of(3)), BotRules.batches(new long[]{4, 5, 20, 3}, 10), "分批：超限的跳过，按大小切");
        long[] many = new long[12];
        eq(2, BotRules.batches(many, 10).size(), "每批最多 10 个");
    }

    /**
     * 假服务器：
     * #general(101) 有 3 条：昨天 1 条（不在范围）、今天 2 条（111 带图片、222 @111）
     * #forum(102) 下有一个活跃贴子 201（111 1 条）、一个早就归档的贴子 202（应被跳过，不去拉消息）
     * #secret(109) 机器人没权限 → 跳过
     */
    private static void collect() throws Exception {
        List<String> calls = Collections.synchronizedList(new ArrayList<>());
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        int port = server.getAddress().getPort();
        String base = "http://127.0.0.1:" + port;
        long today = BotRules.snowflakeAt(Instant.parse("2026-10-07T01:00:00Z"));
        long yesterday = BotRules.snowflakeAt(Instant.parse("2026-10-05T20:00:00Z"));
        String msgsC1 = "[" + msg(today + 2, "101", "222", "小红", "<@111> 这个 bug 在 <#201> 说过", "", "[{\"id\":\"111\",\"username\":\"xiaoming\",\"global_name\":\"小明\"}]")
                + "," + msg(today + 1, "101", "111", "小明", "起床战争床不掉落", "[{\"id\":\"901\",\"filename\":\"截图.png\",\"size\":5,\"url\":\"" + base + "/cdn/901\",\"content_type\":\"image/png\"}]", "[]")
                + "," + msg(yesterday, "101", "111", "小明", "昨天的话", "", "[]") + "]";
        String msgsT1 = "[" + msg(today + 3, "201", "111", "小明", "补充：只在雪岭", "", "[]") + "]";
        server.setExecutor(java.util.concurrent.Executors.newCachedThreadPool());
        server.createContext("/", ex -> {
            try {
            String path = ex.getRequestURI().getPath() + (ex.getRequestURI().getRawQuery() == null ? "" : "?" + ex.getRequestURI().getRawQuery());
            calls.add(ex.getRequestMethod() + " " + path);
            int status = 200;
            String resp;
            if (path.equals("/guilds/g1/channels")) {
                resp = "[{\"id\":\"101\",\"type\":0,\"name\":\"general\",\"last_message_id\":\"" + (today + 2) + "\"},"
                        + "{\"id\":\"102\",\"type\":15,\"name\":\"bug反馈\"},"
                        + "{\"id\":\"109\",\"type\":0,\"name\":\"secret\",\"last_message_id\":\"" + (today + 9) + "\"},"
                        + "{\"id\":\"100\",\"type\":0,\"name\":\"old\",\"last_message_id\":\"" + yesterday + "\"}]";
            } else if (path.equals("/channels/201")) {
                resp = "{\"id\":\"201\",\"type\":11,\"name\":\"床不掉落\",\"parent_id\":\"102\",\"last_message_id\":\"" + (today + 3) + "\"}";
            } else if (path.equals("/guilds/g1/roles")) {
                resp = "[{\"id\":\"r1\",\"name\":\"Min-craft玩家\"}]";
            } else if (path.equals("/guilds/g1/threads/active")) {
                resp = "{\"threads\":[{\"id\":\"201\",\"type\":11,\"name\":\"床不掉落\",\"parent_id\":\"102\",\"last_message_id\":\"" + (today + 3) + "\"}]}";
            } else if (path.startsWith("/channels/102/threads/archived/public")) {
                resp = "{\"threads\":[{\"id\":\"202\",\"type\":11,\"name\":\"老贴\",\"parent_id\":\"102\",\"thread_metadata\":{\"archive_timestamp\":\"2026-09-01T00:00:00+00:00\"}}],\"has_more\":true}";
            } else if (path.startsWith("/channels/") && path.contains("/threads/archived/private")) {
                status = 403;
                resp = "{\"code\":50001,\"message\":\"Missing Access\"}";
            } else if (path.startsWith("/channels/100/threads/archived/public") || path.startsWith("/channels/101/threads/archived/public")) {
                resp = "{\"threads\":[],\"has_more\":false}";
            } else if (path.startsWith("/channels/109/")) {
                status = 403;
                resp = "{\"code\":50001,\"message\":\"Missing Access\"}";
            } else if (path.startsWith("/channels/101/messages")) {
                resp = after(msgsC1, path);
            } else if (path.startsWith("/channels/201/messages")) {
                resp = after(msgsT1, path);
            } else if (path.equals("/cdn/901")) {
                resp = "PNG!!";
            } else {
                status = 404;
                resp = "{\"code\":0,\"message\":\"not found " + path + "\"}";
            }
            byte[] b = resp.getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(status, b.length);
            ex.getResponseBody().write(b);
            } catch (RuntimeException e) {
                e.printStackTrace();
                ex.sendResponseHeaders(500, -1);
            } finally {
                ex.close();
            }
        });

        server.start();
        try {
            runCollect(server, base, calls, today);
        } finally {
            server.stop(0);
        }
        System.out.println("CollectorTest calls: " + calls.size());
    }

    private static void runCollect(HttpServer server, String base, List<String> calls, long today) throws Exception {
        Path out = Files.createTempDirectory("collect-test");
        HttpClient http = HttpClient.newHttpClient();
        DiscordRest rest = new DiscordRest(http, "x", base);
        Collector c = new Collector(rest, http, out, JST, 20000, 1024 * 1024, 10 * 1024 * 1024, Logger.getLogger("t"));
        Instant from = Instant.parse("2026-10-06T15:00:00Z"), to = Instant.parse("2026-10-07T06:00:00Z");
        List<String> progress = new ArrayList<>();
        Collector.Result r = c.collect(new Collector.Request("g1", null, null, from, to, true, true, "测试"), progress::add);
        String md = Files.readString(r.transcript(), StandardCharsets.UTF_8);
        String json = Files.readString(r.json(), StandardCharsets.UTF_8);

        eq(3, r.messages(), "全服今天 3 条（昨天的不算）");
        eq(2, r.authors(), "2 位发言者");
        eq(1, r.files().size(), "1 个附件已下载");
        eq("PNG!!", Files.readString(r.files().get(0).path()), "附件内容正确");
        check(r.skipped().contains("#secret"), "没权限的频道记为跳过：" + r.skipped());
        check(calls.stream().noneMatch(x -> x.startsWith("GET /channels/100/messages")), "很久没人说话的频道不去拉消息");
        check(calls.stream().noneMatch(x -> x.startsWith("GET /channels/202/messages")), "早就归档的贴子不去拉消息");
        check(md.contains("### #general") && md.contains("### #bug反馈 › 床不掉落"), "按位置分组，贴子显示 父频道 › 贴子");
        check(md.indexOf("起床战争床不掉落") < md.indexOf("这个 bug"), "组内按时间排序");
        check(md.contains("@小明 这个 bug 在 #床不掉落 说过"), "提及换成名字：" + md);
        check(md.contains("![📎 截图.png](files/0001-截图.png)"), "图片在记录里能显示");
        check(md.contains("2026-10-07 00:00 ～ 2026-10-07 15:00（Asia/Tokyo）"), "时间段按 JST 显示");
        check(md.contains("| 小明 | 2 |"), "发言统计");
        check(!md.contains("昨天的话"), "范围外的消息不出现");
        check(md.contains("[原消息](https://discord.com/channels/g1/101/" + (today + 1) + ")"), "原消息链接");
        check(json.contains("\"file\": \"files/0001-截图.png\"") && json.contains("\"location\": \"#bug反馈 › 床不掉落\""), "JSON 有附件路径和位置");

        // 只收某个人 + 某个频道（论坛 → 它的贴子）
        calls.clear();
        Collector.Result r2 = c.collect(new Collector.Request("g1", "111", "102", from, to, false, true, "测试"), x -> {});
        eq(1, r2.messages(), "论坛 + 成员 111：1 条");
        eq(0, r2.files().size(), "不收附件");
        check(r2.scope().contains("#bug反馈") && r2.scope().contains("小明"), "范围说明：" + r2.scope());
        check(calls.stream().noneMatch(x -> x.startsWith("GET /channels/101/messages")), "只收论坛时不碰别的频道");

        // 只收一个子区
        Collector.Result r3 = c.collect(new Collector.Request("g1", null, "201", from, to, true, true, "测试"), x -> {});
        eq(1, r3.messages(), "单个子区 1 条");
        check(r3.scope().startsWith("子区"), "范围说明是子区");

        // 上限截断
        Collector small = new Collector(rest, http, out, JST, 100, 1024, 1024, Logger.getLogger("t"));
        Collector.Result r4 = small.collect(new Collector.Request("g1", null, "101", from, to, true, false, "测试"), x -> {});
        eq(2, r4.messages(), "101 今天 2 条");
        server.stop(0);
        System.out.println("CollectorTest calls: " + calls.size());
    }

    /** 像真的 Discord 一样：只返回 id > after 的消息。 */
    private static String after(String array, String path) {
        long after = Long.parseLong(path.replaceAll(".*after=(\\d+).*", "$1"));
        com.google.gson.JsonArray in = com.google.gson.JsonParser.parseString(array).getAsJsonArray(), out = new com.google.gson.JsonArray();
        for (com.google.gson.JsonElement e : in) if (Long.parseLong(e.getAsJsonObject().get("id").getAsString()) > after) out.add(e);
        return out.toString();
    }

    private static String msg(long id, String channel, String uid, String name, String content, String attachments, String mentions) {
        String ts = Instant.ofEpochMilli((id >> 22) + 1420070400000L).toString();
        return "{\"id\":\"" + id + "\",\"channel_id\":\"" + channel + "\",\"type\":0,\"timestamp\":\"" + ts + "\",\"edited_timestamp\":null,"
                + "\"author\":{\"id\":\"" + uid + "\",\"username\":\"" + uid + "\",\"global_name\":\"" + name + "\"},"
                + "\"content\":\"" + content + "\",\"attachments\":" + (attachments.isEmpty() ? "[]" : attachments)
                + ",\"embeds\":[],\"mentions\":" + mentions + "}";
    }

    private static void bad(String s) {
        try {
            BotRules.parseTime(s, false, JST, ZonedDateTime.now(JST));
            fail("应拒绝 " + s);
        } catch (IllegalArgumentException e) {
            passed++;
        }
    }

    private static void eq(Object expected, Object actual, String what) {
        if (expected == null ? actual == null : expected.equals(actual)) passed++;
        else fail(what + ": expected " + expected + " got " + actual);
    }

    private static void check(boolean ok, String what) {
        if (ok) passed++;
        else fail(what);
    }

    private static void fail(String msg) {
        failed++;
        System.out.println("FAIL " + msg);
    }
}
