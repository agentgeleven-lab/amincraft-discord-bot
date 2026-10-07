package cn.aminplay.discord;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

/**
 * 2026-10-07 auth-gate：名字 → 正版 UUID（api.mojang.com）。离线模式的服务器上 Bukkit.getOfflinePlayer(名字) 只会算出离线 UUID，
 * 封不到没来过的正版玩家，所以 /minmin 封禁 先查这里。测试时 -Daminplay.mojang.api 指向本地假服务器。
 */
final class MojangLookup {
    private MojangLookup() {}

    static final String API = System.getProperty("aminplay.mojang.api", "https://api.mojang.com");
    private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

    /** {带横线的 UUID, 正确大小写的名字}；不是正版名字或查不到时返回 null。worker 线程调用。 */
    static String[] uuidOf(String name) {
        if (!BotRules.validPlayerName(name)) return null;
        try {
            HttpRequest r = HttpRequest.newBuilder(URI.create(API + "/users/profiles/minecraft/" + name)).timeout(Duration.ofSeconds(6)).GET().build();
            HttpResponse<String> resp = HTTP.send(r, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (resp.statusCode() != 200) return null;
            return parse(resp.body());
        } catch (Exception e) {
            return null;
        }
    }

    static String[] parse(String body) {
        try {
            JsonObject o = JsonParser.parseString(body).getAsJsonObject();
            String id = o.get("id").getAsString().replace("-", "");
            if (!id.matches("[0-9a-fA-F]{32}")) return null;
            String dashed = id.replaceFirst("(.{8})(.{4})(.{4})(.{4})(.{12})", "$1-$2-$3-$4-$5").toLowerCase();
            return new String[]{dashed, o.get("name").getAsString()};
        } catch (RuntimeException e) {
            return null;
        }
    }
}
