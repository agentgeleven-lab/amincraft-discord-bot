package cn.aminplay.discord;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpServer;

import java.io.File;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.logging.Logger;

/**
 * 用本地假 Discord 跑一遍「订阅 → 临时身份组 → @ → 改成文字 → 删身份组」。
 * 需要在 JVM 启动前设置 -Daminplay.discord.api，所以这里自己起子进程跑 inner()。
 */
public final class NotifierTest {
    private static int passed, failed;

    public static void main(String[] args) throws Exception {
        if (args.length == 1 && args[0].equals("inner")) {
            inner(Integer.getInteger("port"));
            return;
        }
        List<String> calls = Collections.synchronizedList(new ArrayList<>());
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", ex -> {
            String body = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            String path = ex.getRequestURI().getPath();
            String call = ex.getRequestMethod() + " " + path;
            calls.add(call + (body.isEmpty() ? "" : " " + body));
            int status = 200;
            String resp = "";
            if (call.equals("POST /guilds/g1/roles")) resp = "{\"id\":\"r9\"}";
            else if (call.equals("PUT /guilds/g1/members/u2/roles/r9")) {
                status = 404;
                resp = "{\"code\":10007,\"message\":\"Unknown Member\"}";
            } else if (call.startsWith("PUT ") || call.startsWith("DELETE ")) status = 204;
            else if (call.equals("POST /channels/c1/messages")) resp = "{\"id\":\"m5\"}";
            byte[] b = resp.getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(status, b.length == 0 ? -1 : b.length);
            if (b.length > 0) ex.getResponseBody().write(b);
            ex.close();
        });
        server.start();
        int port = server.getAddress().getPort();
        String java = System.getProperty("java.home") + File.separator + "bin" + File.separator + "java";
        Process p = new ProcessBuilder(java, "-Daminplay.discord.api=http://127.0.0.1:" + port, "-Daminplay.discord.settle-ms=10",
                "-Dport=" + port, "-Dfile.encoding=UTF-8", "-Dstdout.encoding=UTF-8",
                "-cp", System.getProperty("java.class.path"), NotifierTest.class.getName(), "inner").inheritIO().start();
        int code = p.waitFor();
        server.stop(0);
        check(code == 0, "子进程正常结束");

        List<String> c = new ArrayList<>(calls);
        check(c.size() == 7, "调用次数 7，实际 " + c.size() + " " + c);
        if (c.size() != 7) {
            System.out.println("NotifierTest: " + passed + " passed, " + failed + " failed");
            System.exit(1);
        }
        check(c.get(0).startsWith("POST /guilds/g1/roles ") && c.get(0).contains("\"name\":\"版本更新\""), "建同名临时身份组");
        check(c.get(1).startsWith("PUT /guilds/g1/members/u1/roles/r9"), "加 u1");
        check(c.get(2).startsWith("PUT /guilds/g1/members/u2/roles/r9"), "加 u2（已退群）");
        check(c.get(3).startsWith("PUT /guilds/g1/members/u3/roles/r9"), "加 u3");
        check(c.get(4).startsWith("POST /channels/c1/messages") && c.get(4).contains("<@&r9>") && c.get(4).contains("\"roles\":[\"r9\"]"), "消息 @ 临时身份组");
        check(c.get(5).startsWith("PATCH /channels/c1/messages/m5") && c.get(5).contains("@版本更新") && !c.get(5).contains("<@&"), "@ 改成普通文字");
        check(c.get(6).startsWith("DELETE /guilds/g1/roles/r9"), "删除临时身份组");
        System.out.println("NotifierTest: " + passed + " passed, " + failed + " failed");
        if (failed > 0) System.exit(1);
    }

    private static void inner(int port) throws Exception {
        // port 只是为了让假服务器和子进程对上号
        File dir = Files.createTempDirectory("notifier-test").toFile();
        File f = new File(dir, "subscriptions.yml");
        Logger log = Logger.getLogger("test");
        Subscriptions subs = new Subscriptions(f, log);
        check(subs.toggle("update", "u1"), "u1 订阅");
        check(subs.toggle("update", "u2"), "u2 订阅");
        check(subs.toggle("update", "u3"), "u3 订阅");
        check(subs.toggle("update", "u4"), "u4 订阅");
        check(!subs.toggle("update", "u4"), "u4 再点 = 取消");
        check(subs.count("update") == 3, "名单 3 人");
        check(new Subscriptions(f, log).count("update") == 3, "重启后名单还在");

        HttpClient http = HttpClient.newHttpClient();
        DiscordRest rest = new DiscordRest(http, "x");
        Notifier n = new Notifier(rest, subs, log);
        BotConfig.PanelRole topic = new BotConfig.PanelRole("update", "版本更新", "subscribe", "", "📦", "");
        List<String> progress = new ArrayList<>();
        Notifier.Mention m = n.prepareSubscription("g1", topic, progress::add);
        check(m.count() == 2 && m.gone() == 1, "2 人送达、1 人已退群");
        check(subs.count("update") == 2, "退群的人从名单移除");
        check(subs.pending().equals(List.of("r9")), "临时身份组记为待清理");
        JsonObject msg = Interactions.message(m.content(), null);
        msg.add("allowed_mentions", m.allowed());
        String id = rest.post("/channels/c1/messages", msg).getAsJsonObject().get("id").getAsString();
        n.finish("g1", m, "/channels/c1/messages/" + id, m.plainText());
        check(subs.pending().isEmpty(), "删除后不再待清理");
        check(new Subscriptions(f, log).pending().isEmpty(), "待清理列表已保存");
        check(Notifier.Mention.none().content() == null, "没人订阅时不 @");
        check(JsonParser.parseString(Notifier.everyone().allowed().toString()).getAsJsonObject().has("parse"), "everyone 允许");
        org.bukkit.configuration.file.YamlConfiguration yc = new org.bukkit.configuration.file.YamlConfiguration();
        yc.loadFromString("roles:\n  panel:\n  - {key: n1, name: 版本更新, type: subscribe, emoji: '📦'}\n  - {key: mc, name: Min-craft玩家, type: role, id: '555'}\n");
        BotConfig cfg = BotConfig.load(yc);
        JsonObject panel = Interactions.panelMessage(cfg, cfg.panelRoles);
        com.google.gson.JsonArray rows = panel.getAsJsonArray("components");
        check(rows.size() == 3, "身份一行 + 通知一行 + 密钥按钮一行");   // 2026-10-07 auth-gate: keys.panel-button default true
        check(rows.get(2).getAsJsonObject().getAsJsonArray("components").get(0).getAsJsonObject().get("custom_id").getAsString().equals("key|claim"), "密钥按钮");
        yc.set("keys.panel-button", false);
        check(Interactions.panelMessage(BotConfig.load(yc), cfg.panelRoles).getAsJsonArray("components").size() == 2, "关掉密钥按钮 = 原来的两行");
        JsonObject first = rows.get(0).getAsJsonObject().getAsJsonArray("components").get(0).getAsJsonObject();
        JsonObject second = rows.get(1).getAsJsonObject().getAsJsonArray("components").get(0).getAsJsonObject();
        check(first.get("custom_id").getAsString().equals("role|555") && first.get("style").getAsInt() == 1, "常驻身份组在上，蓝色按钮");
        check(second.get("custom_id").getAsString().equals("sub|n1") && second.get("style").getAsInt() == 2, "通知组在下，灰色按钮");
        String desc = panel.getAsJsonArray("embeds").get(0).getAsJsonObject().get("description").getAsString();
        check(desc.indexOf("**身份**") < desc.indexOf("**通知**") && desc.contains("<@&555>") && desc.contains("**版本更新**"), "面板文字分两段");
        System.out.println("NotifierTest(inner): " + passed + " passed, " + failed + " failed");
        System.exit(failed > 0 ? 1 : 0);
    }

    private static void check(boolean ok, String what) {
        if (ok) passed++;
        else {
            failed++;
            System.out.println("FAIL " + what);
        }
    }
}
