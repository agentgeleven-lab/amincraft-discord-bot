package cn.aminplay.discord;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpServer;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import java.util.logging.Logger;

/**
 * 2026-10-08 bot-plots：用本地假 Discord（不连真的 Discord）+ 假 City 申请 API 测类脑市地块申请：
 * 新申请发管理频道（提及、竞争申请、按钮、不 @ 人）、没设频道不发、管理员同意 / 驳回（原因弹窗）/ 非管理员被拒、已处理的再点、游戏内审批同步嵌入、
 * 消息被删重发、Discord 申请（未绑定 / 已绑定 / 封禁 / 接口不可用）、加入申请（按地块编号）、我的申请、自动补全、
 * 共同开发者私信（发到 / 没绑定 / 关私信 → notified）、私信按钮、确认结束改私信、绑定表适配、记录文件、指令定义和注册日志、隐私（不加 intent）。
 * 运行：java -cp classes;test-classes;<paper deps> cn.aminplay.discord.PlotDeskTest
 */
public final class PlotDeskTest {
    private static int passed, failed;

    record Call(String method, String path, String body) {
        JsonObject json() {
            return body == null || body.isEmpty() ? new JsonObject() : JsonParser.parseString(body).getAsJsonObject();
        }
    }

    static final List<Call> calls = Collections.synchronizedList(new ArrayList<>());
    static volatile Function<Call, int[]> fault = c -> null;
    static final AtomicInteger ids = new AtomicInteger(100);

    // ===== 假 City 申请 API =====
    static final class FakeCity implements PlotDesk.City {
        final Map<String, Map<String, Object>> apps = new LinkedHashMap<>();
        final Map<String, String> names = new HashMap<>();
        final Set<String> plots = new HashSet<>(Set.of("C1-0063", "C2-0010"));
        final List<String> notified = new ArrayList<>(), answers = new ArrayList<>(), log = new ArrayList<>();
        final Map<String, Set<String>> codevMembers = new HashMap<>();
        int n;

        Map<String, Object> app(String plot, String uuid, String reason, String source) {
            Map<String, Object> a = new LinkedHashMap<>();
            String id = "A" + (++n);
            a.put("id", id);
            a.put("plot", plot);
            a.put("plotLabel", plot + " · Opus 5.5环路");
            List<Map<String, Object>> who = new ArrayList<>();
            who.add(new LinkedHashMap<>(Map.of("uuid", uuid, "name", names.getOrDefault(uuid, "?"))));
            a.put("applicants", who);
            a.put("reason", reason);
            a.put("time", 1_760_000_000_000L + n);
            a.put("status", "open");
            a.put("source", source);
            apps.put(id, a);
            return a;
        }

        @Override public Map<String, Object> submit(String plot, String uuid, String reason, String source) {
            log.add("submit " + plot + " " + uuid + " " + reason + " " + source);
            if (!plots.contains(plot)) return Map.of("status", "not-found");
            for (Map<String, Object> a : apps.values()) if (a.get("plot").equals(plot) && PlotRules.open(a) && PlotRules.applicants(a).stream().anyMatch(x -> x.get("uuid").equals(uuid)))
                return Map.of("status", "already", "message", "你已经在 " + plot + " 的申请里了");
            Map<String, Object> a = app(plot, uuid, reason, source);
            return Map.of("status", "ok", "id", a.get("id"), "application", a);
        }

        @Override public Map<String, Object> join(String id, String uuid, String source) {
            log.add("join " + id + " " + uuid + " " + source);
            Map<String, Object> a = apps.get(id);
            if (a == null) return Map.of("status", "not-found");
            if (!PlotRules.open(a)) return Map.of("status", "closed");
            @SuppressWarnings("unchecked") List<Map<String, Object>> who = (List<Map<String, Object>>) a.get("applicants");
            if (who.stream().anyMatch(x -> x.get("uuid").equals(uuid))) return Map.of("status", "already");
            who.add(new LinkedHashMap<>(Map.of("uuid", uuid, "name", names.getOrDefault(uuid, "?"))));
            return Map.of("status", "ok", "application", a);
        }

        @Override public List<Map<String, Object>> list(String filter) {
            List<Map<String, Object>> out = new ArrayList<>();
            for (Map<String, Object> a : apps.values()) {
                boolean ok = filter.equals("open") ? PlotRules.open(a)
                        : filter.startsWith("plot:") ? a.get("plot").equals(filter.substring(5))
                        : filter.startsWith("player:") && PlotRules.applicants(a).stream().anyMatch(x -> x.get("uuid").equals(filter.substring(7)));
                if (ok) out.add(a);
            }
            Collections.reverse(out);
            return out;
        }

        @Override public Map<String, Object> get(String id) {
            Map<String, Object> a = apps.get(id);
            return a == null ? Map.of("status", "not-found") : a;
        }

        private Map<String, Object> decide(String id, String st, String by, String reason, String source) {
            Map<String, Object> a = apps.get(id);
            if (a == null) return Map.of("status", "not-found");
            if (!PlotRules.open(a)) return Map.of("status", "decided", "application", a);
            a.put("status", st);
            a.put("decidedBy", by);
            a.put("decidedSource", source);
            a.put("decidedAt", 1_760_000_100_000L);
            if (reason != null && !reason.isEmpty()) a.put("decidedReason", reason);
            return Map.of("status", "ok", "application", a);
        }

        @Override public Map<String, Object> approve(String id, String by, String source) {
            log.add("approve " + id + " " + by + " " + source);
            return decide(id, "approved", by, null, source);
        }

        @Override public Map<String, Object> reject(String id, String by, String reason, String source) {
            log.add("reject " + id + " " + by + " [" + reason + "] " + source);
            return decide(id, "rejected", by, reason, source);
        }

        @Override public Map<String, Object> answerCoDev(String req, String uuid, boolean yes, String source) {
            answers.add(req + " " + uuid + " " + yes + " " + source);
            Set<String> m = codevMembers.get(req);
            if (m == null) return Map.of("status", "not-found");
            if (!m.contains(uuid)) return Map.of("status", "wrong-user");
            return Map.of("status", "ok", "result", yes ? "pending" : "rejected");
        }

        @Override public void notified(String req, String uuid, boolean dm) {
            notified.add(req + " " + uuid + " " + dm);
        }

        @Override public List<Map<String, Object>> searchPlots(String q, int limit) {
            List<Map<String, Object>> out = new ArrayList<>();
            for (String p : plots) if (p.toLowerCase().contains(q.toLowerCase())) out.add(Map.of("id", p, "label", p + " · 区域"));
            return out;
        }
    }

    static final FakeCity city = new FakeCity();
    static volatile boolean cityUp = true;
    static final Map<String, PlotDesk.Account> bound = new HashMap<>();
    static final Map<String, String> discordOf = new HashMap<>();
    static final List<String> adminLogs = Collections.synchronizedList(new ArrayList<>());

    public static void main(String[] args) throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", ex -> {
            String body = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            Call c = new Call(ex.getRequestMethod(), ex.getRequestURI().getPath(), body);
            calls.add(c);
            int[] f = fault.apply(c);
            int status = 200;
            String resp;
            if (f != null) {
                status = f[0];
                resp = "{\"code\":" + f[1] + ",\"message\":\"x\"}";
            } else if (c.method.equals("POST") && c.path.equals("/users/@me/channels")) resp = "{\"id\":\"dm" + ids.incrementAndGet() + "\"}";
            else if (c.method.equals("POST") && c.path.endsWith("/messages")) resp = "{\"id\":\"m" + ids.incrementAndGet() + "\"}";
            else if (c.path.endsWith("/callback")) {
                status = 204;
                resp = "";
            } else resp = "{}";
            byte[] b = resp.getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(status, status == 204 ? -1 : b.length);
            if (status != 204) ex.getResponseBody().write(b);
            ex.close();
        });
        server.start();
        try {
            run("http://127.0.0.1:" + server.getAddress().getPort());
        } finally {
            server.stop(0);
        }
        System.out.println("PlotDeskTest: " + passed + " passed, " + failed + " failed");
        if (failed > 0) System.exit(1);
    }

    static BotConfig cfg(String plotsChannel) {
        YamlConfiguration y = new YamlConfiguration();
        y.set("discord.guild-id", "g1");
        y.set("channels.plots", plotsChannel);
        y.set("roles.admin", List.of("777"));
        return BotConfig.load(y);
    }

    static void run(String base) throws Exception {
        DiscordRest rest = new DiscordRest(HttpClient.newHttpClient(), "test-token", base);
        BotConfig[] conf = {cfg("900")};
        File tmp = Files.createTempDirectory("plotdesk").toFile();
        File storeFile = new File(tmp, "plots-posted.yml");
        PlotDesk.Host host = new PlotDesk.Host() {
            @Override public DiscordRest rest() { return rest; }
            @Override public BotConfig cfg() { return conf[0]; }
            @Override public String applicationId() { return "app1"; }
            @Override public PlotDesk.City city() { return cityUp ? city : null; }
            @Override public PlotDesk.Accounts accounts() {
                return new PlotDesk.Accounts() {
                    @Override public PlotDesk.Account bound(String discordId) { return bound.get(discordId); }
                    @Override public String discordOf(String uuid, String name) { return discordOf.get(uuid); }
                };
            }
            @Override public void adminLog(String text) { adminLogs.add(text); }
        };
        Logger log = Logger.getLogger("PlotDeskTest");
        PlotDesk desk = new PlotDesk(host, new PlotStore(storeFile, log), log);

        String uA = "00000000-0000-0000-0000-00000000000a", uB = "00000000-0000-0000-0000-00000000000b", uC = "00000000-0000-0000-0000-00000000000c";
        String uD = "00000000-0000-0000-0000-00000000000d";
        discordOf.put(uD, "1009");
        city.names.put(uA, "Gardenia_Rain");
        city.names.put(uB, "Bob");
        city.names.put(uC, "Carol");
        bound.put("1001", new PlotDesk.Account(uA, "Gardenia_Rain", false));
        bound.put("1003", new PlotDesk.Account(uC, "Carol", false));
        discordOf.put(uA, "1001");
        discordOf.put(uC, "1003");

        // ---------- 1. 游戏内新申请 → 管理频道 ----------
        Map<String, Object> a1 = city.app("C1-0063", uA, "想建一个**图书馆**", "game");
        calls.clear();
        desk.onEvent(Map.of("type", "application-new", "id", a1.get("id")));
        Call post = find("POST", "/channels/900/messages");
        check(post != null, "新申请发到 channels.plots");
        JsonObject msg = post == null ? new JsonObject() : post.json();
        String emb = msg.toString();
        check(emb.contains("C1-0063 · Opus 5.5环路") && emb.contains("Gardenia\\\\_Rain") && emb.contains("<@1001>"), "嵌入：地块、游戏名（转义）、Discord 提及");
        check(emb.contains("想建一个**图书馆**") && emb.contains("待审批") && emb.contains("同一地块的其它申请"), "嵌入：理由、状态、竞争栏");
        check(msg.getAsJsonObject("allowed_mentions").getAsJsonArray("parse").isEmpty() && !msg.getAsJsonObject("allowed_mentions").has("users"), "提及不 @ 人");
        JsonArray btns = msg.getAsJsonArray("components").get(0).getAsJsonObject().getAsJsonArray("components");
        check(btns.size() == 2 && btns.get(0).getAsJsonObject().get("custom_id").getAsString().equals("plot|ok|A1")
                && btns.get(1).getAsJsonObject().get("custom_id").getAsString().equals("plot|no|A1"), "[同意]/[驳回] 按钮");
        check(desk.store().post("A1") != null && desk.store().post("A1").startsWith("/channels/900/messages/m"), "记下消息位置");

        // 同一个事件再来一次：改，不重复发
        calls.clear();
        desk.onEvent(Map.of("type", "application-updated", "id", "A1"));
        check(find("POST", "/channels/900/messages") == null && find("PATCH", desk.store().post("A1")) != null, "重复事件只改不重发");

        // ---------- 2. 竞争申请：新的一条发出，旧的竞争栏更新 ----------
        Map<String, Object> a2 = city.app("C1-0063", uB, "开咖啡店", "game");
        calls.clear();
        desk.onEvent(Map.of("type", "application-new", "id", a2.get("id")));
        Call p2 = find("POST", "/channels/900/messages");
        check(p2 != null && p2.body.contains("`A1`") && p2.body.contains("Gardenia"), "新申请显示竞争申请 A1");
        Call patch1 = find("PATCH", desk.store().post("A1"));
        check(patch1 != null && patch1.body.contains("`A2`") && patch1.body.contains("Bob"), "旧申请的竞争栏刷新出 A2");
        check(!p2.body.contains("<@"), "没绑定的申请人不显示提及");

        // ---------- 3. 没设管理频道：不发 ----------
        conf[0] = cfg("");
        Map<String, Object> a3 = city.app("C2-0010", uC, "", "game");
        calls.clear();
        desk.onEvent(Map.of("type", "application-new", "id", a3.get("id")));
        check(calls.isEmpty() && desk.store().post("A3") == null, "channels.plots 为空时不发");
        conf[0] = cfg("900");
        calls.clear();
        int caught = desk.catchUp(20);
        check(caught == 1 && desk.store().post("A3") != null && find("POST", "/channels/900/messages").body.contains("（没写）"), "设了频道后补发（空理由显示「没写」）");
        check(desk.catchUp(20) == 0, "补发不重复");

        // ---------- 4. 非管理员点 [同意] ----------
        calls.clear();
        city.log.clear();
        desk.handle(button("plot|ok|A1", "2002", "0", List.of()));
        Call cb = find("POST", "/interactions/i1/tok1/callback");
        check(cb != null && cb.json().get("type").getAsInt() == 4 && cb.json().getAsJsonObject("data").get("flags").getAsInt() == 64
                && cb.body.contains("只有管理员"), "非管理员：仅自己可见的拒绝");
        check(city.log.isEmpty(), "非管理员不会调用 approve");

        // ---------- 5. 管理员（管理身份组）同意 ----------
        calls.clear();
        adminLogs.clear();
        desk.handle(button("plot|ok|A1", "3003", "0", List.of("777")));
        cb = find("POST", "/interactions/i1/tok1/callback");
        check(cb != null && cb.json().get("type").getAsInt() == 6, "按钮先 deferred update");
        check(city.log.contains("approve A1 AdminNick discord"), "调用 approve(id, 谁, discord)");
        Call edit = find("PATCH", "/webhooks/app1/tok1/messages/@original");
        check(edit != null && edit.body.contains("已同意") && edit.body.contains("AdminNick") && edit.body.contains("Discord"), "嵌入改成「已同意 · 由谁（Discord）」");
        check(edit != null && edit.json().getAsJsonArray("components").isEmpty(), "处理后按钮去掉");
        check(adminLogs.stream().anyMatch(s -> s.contains("同意了地块 C1-0063")), "管理记录");
        check(find("POST", "/webhooks/app1/tok1") == null, "成功时没有额外提示");

        // 再点一次 → 已处理
        calls.clear();
        desk.handle(button("plot|ok|A1", "3003", String.valueOf(1L << 5), List.of()));
        Call fu = find("POST", "/webhooks/app1/tok1");
        check(fu != null && fu.body.contains("已经被处理过") && fu.json().get("flags").getAsInt() == 64, "已处理：仅自己可见说明（管理服务器权限也算管理员）");

        // ---------- 6. 驳回：弹窗 → 原因 ----------
        calls.clear();
        desk.handle(button("plot|no|A2", "3003", String.valueOf(1L << 3), List.of()));
        cb = find("POST", "/interactions/i1/tok1/callback");
        check(cb != null && cb.json().get("type").getAsInt() == 9 && cb.body.contains("plotrej|A2") && cb.body.contains("驳回原因"), "[驳回] 打开原因弹窗");
        check(city.log.stream().noneMatch(s -> s.startsWith("reject")), "弹窗前不驳回");
        calls.clear();
        desk.handle(modal("plotrej|A2", "3003", String.valueOf(1L << 3), "离主路太近"));
        check(city.log.contains("reject A2 AdminNick [离主路太近] discord"), "带原因驳回");
        edit = find("PATCH", "/webhooks/app1/tok1/messages/@original");
        check(edit != null && edit.body.contains("已驳回") && edit.body.contains("离主路太近"), "嵌入显示驳回和原因");
        calls.clear();
        desk.handle(modal("plotrej|A3", "2002", "0", ""));
        check(find("POST", "/interactions/i1/tok1/callback").body.contains("只有管理员"), "弹窗也检查管理员");

        // ---------- 7. 游戏内审批 → 嵌入同步 ----------
        city.decide("A3", "approved", "AminDoll", null, "game");
        calls.clear();
        desk.onEvent(Map.of("type", "application-updated", "id", "A3"));
        Call sync = find("PATCH", desk.store().post("A3"));
        check(sync != null && sync.body.contains("AminDoll") && sync.body.contains("游戏内") && sync.json().getAsJsonArray("components").isEmpty(), "游戏内审批同步到嵌入");

        // ---------- 8. 消息被删了：待审的重新发，已结束的不发 ----------
        Map<String, Object> a4 = city.app("C2-0010", uA, "第二个", "game");
        desk.onEvent(Map.of("type", "application-new", "id", a4.get("id")));
        String oldPath = desk.store().post("A4");
        fault = c -> c.method.equals("PATCH") && c.path.equals(oldPath) ? new int[]{404, 10008} : null;
        calls.clear();
        desk.onEvent(Map.of("type", "application-updated", "id", "A4"));
        fault = c -> null;
        check(desk.store().post("A4") != null && !desk.store().post("A4").equals(oldPath) && find("POST", "/channels/900/messages") != null, "嵌入被删 → 重新发");

        // ---------- 8b. 2026-10-08 release-136: channels.plots 是子区且已归档（50083）→ 取消归档后重发 ----------
        Map<String, Object> a4b = city.app("C2-0011", uA, "子区归档", "game");
        final int[] once = {1};
        fault = c -> c.method.equals("POST") && c.path.equals("/channels/900/messages") && once[0]-- > 0 ? new int[]{400, 50083} : null;
        calls.clear();
        desk.onEvent(Map.of("type", "application-new", "id", a4b.get("id")));
        fault = c -> null;
        Call unarch = find("PATCH", "/channels/900");
        check(unarch != null && unarch.body.contains("\"archived\":false") && desk.store().post(String.valueOf(a4b.get("id"))) != null, "子区归档（50083）→ 取消归档 + 重发成功");

        // ---------- 9. Discord 申请 ----------
        calls.clear();
        city.log.clear();
        desk.handle(slash("plotapply", "2002", "0", Map.of("plot", "C1-0063", "reason", "想建房子")));
        cb = find("POST", "/interactions/i1/tok1/callback");
        check(cb != null && cb.json().get("type").getAsInt() == 5 && cb.json().getAsJsonObject("data").get("flags").getAsInt() == 64, "指令先 deferred（仅自己可见）");
        edit = find("PATCH", "/webhooks/app1/tok1/messages/@original");
        check(edit != null && edit.body.contains("绑定了游戏号") && city.log.isEmpty(), "没绑定：不能申请");

        calls.clear();
        desk.handle(slash("plotapply", "1003", "0", Map.of("plot", "C1-0063", "reason", "想建房子")));
        check(city.log.contains("submit C1-0063 " + uC + " 想建房子 discord"), "绑定用户：submit(地块, 游戏号 uuid, 理由, discord)");
        edit = find("PATCH", "/webhooks/app1/tok1/messages/@original");
        check(edit != null && edit.body.contains("已提交") && edit.body.contains("我的申请") && edit.body.contains("Carol"), "回复：结果 + 我的申请 + 游戏号");
        check(adminLogs.stream().anyMatch(s -> s.contains("在 Discord 申请了地块 C1-0063")), "Discord 申请写管理记录");

        calls.clear();
        desk.handle(slash("plotapply", "1003", "0", Map.of("plot", "C1-0063", "reason", "再来")));
        edit = find("PATCH", "/webhooks/app1/tok1/messages/@original");
        check(edit != null && edit.body.contains("已经申请过") && edit.body.contains("你已经在 C1-0063 的申请里了"), "重复申请：显示游戏服务器给的原因");
        calls.clear();
        desk.handle(slash("plotapply", "1003", "0", Map.of("plot", "Z9-9999", "reason", "x")));
        check(find("PATCH", "/webhooks/app1/tok1/messages/@original").body.contains("找不到地块"), "地块不存在");

        bound.put("1004", new PlotDesk.Account(uB, "Bob", true));
        calls.clear();
        desk.handle(slash("plotapply", "1004", "0", Map.of("plot", "C1-0063", "reason", "x")));
        check(find("PATCH", "/webhooks/app1/tok1/messages/@original").body.contains("已被封禁"), "封禁的账号不能申请");

        cityUp = false;
        calls.clear();
        desk.handle(slash("plotmine", "1003", "0", Map.of()));
        check(find("PATCH", "/webhooks/app1/tok1/messages/@original").body.contains("不可用"), "MiniGameHub 不在 → 不可用");
        cityUp = true;

        // ---------- 10. 加入申请（填地块编号，那块地只有一个待审）+ 我的申请 ----------
        city.log.clear();
        calls.clear();
        desk.handle(slash("plotjoin", "1001", "0", Map.of("application", "C2-0010")));
        check(city.log.contains("join A4 " + uA + " discord") || city.log.stream().anyMatch(s -> s.startsWith("join A4")), "按地块编号找到唯一待审申请");
        edit = find("PATCH", "/webhooks/app1/tok1/messages/@original");
        check(edit != null && (edit.body.contains("已经在这个申请里") || edit.body.contains("已加入")), "加入结果");
        city.log.clear();
        calls.clear();
        desk.handle(slash("plotjoin", "1003", "0", Map.of("application", "A4")));
        edit = find("PATCH", "/webhooks/app1/tok1/messages/@original");
        check(city.log.contains("join A4 " + uC + " discord") && edit.body.contains("已加入") && edit.body.contains("2 人"), "按申请编号加入");
        calls.clear();
        desk.handle(slash("plotmine", "1001", "0", Map.of()));
        edit = find("PATCH", "/webhooks/app1/tok1/messages/@original");
        check(edit != null && edit.body.contains("我的申请") && edit.body.contains("C1-0063") && edit.body.contains("已同意") && edit.body.contains("C2-0010"), "我的申请列出结果");

        // ---------- 11. 自动补全 ----------
        calls.clear();
        desk.handle(autocomplete("plotjoin", "application", "c2"));
        cb = find("POST", "/interactions/i1/tok1/callback");
        check(cb != null && cb.json().get("type").getAsInt() == 8 && cb.body.contains("\"value\":\"A4\"") && !cb.body.contains("\"value\":\"A1\""), "加入申请：只列待审、按关键字过滤");
        calls.clear();
        desk.handle(autocomplete("plotapply", "plot", "0063"));
        cb = find("POST", "/interactions/i1/tok1/callback");
        check(cb != null && cb.body.contains("C1-0063 · 区域") && !cb.body.contains("C2-0010"), "地块补全走 searchPlots");

        // ---------- 12. 共同开发者私信 ----------
        city.codevMembers.put("R1", Set.of(uA, uB, uC));
        fault = c -> c.method.equals("POST") && c.path.equals("/users/@me/channels") && c.body.contains("1003") ? new int[]{403, 50007} : null;
        calls.clear();
        desk.onEvent(Map.of("type", "codev-request", "id", "R1", "plot", "C1-0063", "action", "把 Dave 加为共同开发者", "by", "Gardenia_Rain",
                "members", List.of(Map.of("uuid", uA, "name", "Gardenia_Rain", "vote", "pending"), Map.of("uuid", uB, "name", "Bob"), Map.of("uuid", uC, "name", "Carol", "vote", "pending"),
                        Map.of("uuid", uD, "name", "Dave", "vote", "yes")),
                "expiresAt", 1_760_100_000_000L));
        fault = c -> null;
        Call dmMsg = null;
        for (Call c : snapshot()) if (c.method.equals("POST") && c.path.startsWith("/channels/dm") && c.path.endsWith("/messages")) dmMsg = c;
        check(dmMsg != null && dmMsg.body.contains("codev|y|R1") && dmMsg.body.contains("codev|n|R1") && dmMsg.body.contains("把 Dave 加为共同开发者"), "私信 [同意]/[拒绝] + 变动内容");
        check(city.notified.contains("R1 " + uA + " true"), "私信发到 → notified true");
        check(city.notified.contains("R1 " + uB + " false"), "没绑定 Discord → notified false（游戏内提示）");
        check(city.notified.contains("R1 " + uC + " false"), "关了私信 → notified false（游戏内提示）");
        check(desk.store().dmCount() == 1, "记下私信位置");
        check(city.notified.stream().noneMatch(s -> s.contains(uD)) && snapshot().stream().noneMatch(c -> c.body.contains("1009")), "已经表过态的（发起人）不私信");

        calls.clear();
        desk.handle(dmButton("codev|y|R1", "1001"));
        check(city.answers.contains("R1 " + uA + " true discord"), "私信按钮 → answerCoDev(请求, 自己的 uuid, 同意)");
        edit = find("PATCH", "/webhooks/app1/tok1/messages/@original");
        check(edit != null && edit.body.contains("你已同意") && edit.json().getAsJsonArray("components").isEmpty(), "私信改成结果、按钮去掉");
        calls.clear();
        desk.handle(dmButton("codev|n|R1", "9999"));
        edit = find("PATCH", "/webhooks/app1/tok1/messages/@original");
        check(edit != null && edit.body.contains("不是给你的"), "别人点：不是给你的");

        calls.clear();
        desk.onEvent(Map.of("type", "codev-closed", "id", "R1", "result", "approved"));
        Call closed = null;
        for (Call c : snapshot()) if (c.method.equals("PATCH") && c.path.startsWith("/channels/dm")) closed = c;
        check(closed != null && closed.body.contains("变动已生效") && closed.json().getAsJsonArray("components").isEmpty() && desk.store().dmCount() == 0, "确认结束 → 改私信");

        // ---------- 13. 绑定表适配（CrackedKeyApi.lookup） ----------
        HubAuthKeys keys = new HubAuthKeys() {
            @Override List<Map<String, Object>> lookup(String q) {
                Map<String, Object> rec = new HashMap<>(Map.of("discordId", "1001", "bound", true, "name", "Gardenia_Rain", "uuid", uA, "banned", false));
                if (q.equals("1001") || q.equalsIgnoreCase("Gardenia_Rain")) return List.of(rec);
                if (q.equals("1005")) return List.of(Map.of("discordId", "1005", "bound", false, "name", "", "uuid", ""));
                return List.of();
            }
        };
        PlotDesk.Accounts acc = PlotDesk.accountsOf(keys);
        check(acc.bound("1001") != null && acc.bound("1001").uuid().equals(uA) && acc.bound("1005") == null && acc.bound("1006") == null, "Discord → 绑定的游戏号");
        check("1001".equals(acc.discordOf(uA, "Gardenia_Rain")) && acc.discordOf(uB, "Gardenia_Rain") == null && acc.discordOf(uA, "") == null, "游戏号 → Discord（uuid 必须一致）");
        check(PlotDesk.accountsOf(null) == null, "没有密钥接口 → 不可用");

        // ---------- 14. 记录文件 ----------
        PlotStore reloaded = new PlotStore(storeFile, log);
        check(reloaded.post("A1") != null && reloaded.post("A1").equals(desk.store().post("A1")) && reloaded.post("A4").equals(desk.store().post("A4")), "plots-posted.yml 重启后还在");
        String yml = Files.readString(storeFile.toPath(), StandardCharsets.UTF_8);
        check(!yml.contains("图书馆") && !yml.contains("Gardenia") && !yml.contains(uA), "记录文件只有编号和消息位置（不存申请内容）");

        // ---------- 15. 纯文字 ----------
        check(PlotRules.esc("a_b*c").equals("a\\_b\\*c"), "游戏名转义");
        check(PlotRules.mine(List.of()).contains("还没有地块申请"), "我的申请：空");
        check(PlotRules.submitReply(Map.of("status", "limit", "message", "第二块起要审批"), "X").contains("第二块起要审批"), "显示服务器原因");
        check(PlotRules.codevClosed("expired").contains("过期"), "过期文字");
        check(PlotRules.rejectModal("A9").toString().contains("\"required\":false"), "驳回原因可选");
        // city-rules-v2 定稿的额外 status
        check(PlotRules.submitReply(Map.of("status", "locked", "message", "这片区域还没开放"), "X").contains("还没开放"), "locked");
        check(PlotRules.joinReply(Map.of("status", "ambiguous", "ids", List.of("A7", "A9"))).contains("A7、A9"), "ambiguous 列出申请号");
        check(PlotRules.decisionError(Map.of("status", "taken")).contains("已经有主人"), "taken");
        check(PlotRules.codevAnswer(Map.of("status", "already", "message", "你已经投过票了"), true).contains("表过态"), "already");
        check(PlotRules.codevClosed("failed").contains("没能执行"), "failed");
        check(PlotRules.statusLine(Map.of("status", "approved", "decidedBy", "Op", "decidedSource", "menu"), true).contains("游戏内菜单"), "来源 menu");
        check(PlotRules.statusLine(Map.of("status", "rejected", "decidedBy", "Op", "decidedSource", "game", "decidedReason", "管理员已把这块地批给了别的申请"), true).contains("批给了别的申请"), "自动驳回显示原因");

        // ---------- 16. 指令定义 / 注册日志 / 隐私 ----------
        JsonArray subs = Interactions.definitions().get(0).getAsJsonObject().getAsJsonArray("options");
        Set<String> names = new HashSet<>();
        for (JsonElement e : subs) names.add(e.getAsJsonObject().get("name").getAsString());
        check(subs.size() <= 25 && names.containsAll(List.of("plotapply", "plotjoin", "plotmine")), "有 申请地块 / 加入申请 / 我的申请（≤25）");
        JsonObject apply = null;
        for (JsonElement e : subs) if (e.getAsJsonObject().get("name").getAsString().equals("plotapply")) apply = e.getAsJsonObject();
        check(apply != null && !apply.has("default_member_permissions") && apply.toString().contains("\"max_length\":300"), "申请地块 对所有人可见、理由 ≤300");
        String summary = Interactions.commandSummary();
        boolean all = true;
        for (JsonElement e : subs) all &= summary.contains(e.getAsJsonObject().getAsJsonObject("name_localizations").get("zh-CN").getAsString());
        check(all && summary.contains("隐私") && summary.contains("申请地块") && summary.contains("加入申请") && summary.contains("我的申请") && summary.startsWith("/minmin "), "注册日志列出真实指令（含 隐私）：" + summary);
        check(cfg("900").intents() == DiscordGateway.INTENT_GUILDS, "地块功能不需要额外的 Gateway intent（不读消息内容）");

        System.out.println("（注册日志示例）Discord 斜杠指令已注册：" + summary);
    }

    // ===== 交互 JSON =====

    static JsonObject member(String uid, String perms, List<String> roles) {
        JsonObject u = new JsonObject();
        u.addProperty("id", uid);
        u.addProperty("username", "user" + uid);
        JsonObject m = new JsonObject();
        m.add("user", u);
        m.addProperty("nick", "AdminNick");
        m.addProperty("permissions", perms);
        JsonArray r = new JsonArray();
        roles.forEach(r::add);
        m.add("roles", r);
        return m;
    }

    static JsonObject base(int type) {
        JsonObject d = new JsonObject();
        d.addProperty("id", "i1");
        d.addProperty("token", "tok1");
        d.addProperty("type", type);
        d.addProperty("guild_id", "g1");
        d.addProperty("channel_id", "900");
        return d;
    }

    static JsonObject button(String customId, String uid, String perms, List<String> roles) {
        JsonObject d = base(3);
        d.add("member", member(uid, perms, roles));
        JsonObject data = new JsonObject();
        data.addProperty("custom_id", customId);
        data.addProperty("component_type", 2);
        d.add("data", data);
        JsonObject m = new JsonObject();
        m.addProperty("id", "m1");
        d.add("message", m);
        return d;
    }

    static JsonObject dmButton(String customId, String uid) {
        JsonObject d = button(customId, uid, "0", List.of());
        d.remove("guild_id");
        d.remove("member");
        JsonObject u = new JsonObject();
        u.addProperty("id", uid);
        u.addProperty("username", "user" + uid);
        d.add("user", u);
        return d;
    }

    static JsonObject modal(String customId, String uid, String perms, String reason) {
        JsonObject d = base(5);
        d.add("member", member(uid, perms, List.of()));
        JsonObject input = new JsonObject();
        input.addProperty("type", 4);
        input.addProperty("custom_id", "reason");
        input.addProperty("value", reason);
        JsonObject label = new JsonObject();
        label.addProperty("type", 18);
        label.add("component", input);
        JsonArray comps = new JsonArray();
        comps.add(label);
        JsonObject data = new JsonObject();
        data.addProperty("custom_id", customId);
        data.add("components", comps);
        d.add("data", data);
        JsonObject m = new JsonObject();
        m.addProperty("id", "m1");
        d.add("message", m);
        return d;
    }

    static JsonObject slash(String sub, String uid, String perms, Map<String, String> opts) {
        JsonObject d = base(2);
        d.add("member", member(uid, perms, List.of()));
        JsonArray o = new JsonArray();
        opts.forEach((k, v) -> {
            JsonObject x = new JsonObject();
            x.addProperty("type", 3);
            x.addProperty("name", k);
            x.addProperty("value", v);
            o.add(x);
        });
        JsonObject s = new JsonObject();
        s.addProperty("type", 1);
        s.addProperty("name", sub);
        s.add("options", o);
        JsonArray top = new JsonArray();
        top.add(s);
        JsonObject data = new JsonObject();
        data.addProperty("name", "minmin");
        data.add("options", top);
        d.add("data", data);
        return d;
    }

    static JsonObject autocomplete(String sub, String focused, String typed) {
        JsonObject d = slash(sub, "1001", "0", Map.of(focused, typed));
        d.addProperty("type", 4);
        d.getAsJsonObject("data").getAsJsonArray("options").get(0).getAsJsonObject().getAsJsonArray("options").get(0).getAsJsonObject().addProperty("focused", true);
        return d;
    }

    static List<Call> snapshot() {
        synchronized (calls) {
            return new ArrayList<>(calls);
        }
    }

    static Call find(String method, String path) {
        for (Call c : snapshot()) if (c.method.equals(method) && c.path.equals(path)) return c;
        return null;
    }

    private static void check(boolean ok, String name) {
        if (ok) passed++;
        else {
            failed++;
            System.out.println("FAIL " + name);
        }
    }
}
