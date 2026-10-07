package cn.aminplay.discord;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.logging.Logger;

/**
 * 发公告时的 @：
 * - 真身份组（type: role）：直接 @。
 * - 订阅（type: subscribe）：临时创建同名身份组 → 把名单上的人加进去 → 发消息 @ 它 →
 *   通知送达后把消息里的 @ 改成普通文字 → 删除临时身份组。平时不占身份组名额。
 */
final class Notifier {
    /** 发出后等多久再把 @ 改成文字并删身份组（给 Discord 送通知的时间）。 */
    private static final long SETTLE_MS = Long.getLong("aminplay.discord.settle-ms", 15_000);

    record Mention(String content, JsonObject allowed, String tempRoleId, String plainText, int count, int gone) {
        static Mention none() {
            return new Mention(null, null, null, null, 0, 0);
        }
    }

    private final DiscordRest rest;
    private final Subscriptions subs;
    private final Logger log;

    Notifier(DiscordRest rest, Subscriptions subs, Logger log) {
        this.rest = rest;
        this.subs = subs;
        this.log = log;
    }

    /** 启动时清理上次没删掉的临时身份组。 */
    void cleanupPending(String guildId) {
        for (String id : subs.pending()) {
            try {
                rest.delete("/guilds/" + guildId + "/roles/" + id);
            } catch (DiscordRest.DiscordException e) {
                if (e.code != 10011) {
                    log.warning("清理临时身份组 " + id + " 失败：" + e.friendly());
                    continue;
                }
            } catch (IOException e) {
                continue;
            }
            subs.removePending(id);
        }
    }

    static Mention everyone() {
        JsonObject allowed = new JsonObject();
        JsonArray parse = new JsonArray();
        parse.add("everyone");
        allowed.add("parse", parse);
        return new Mention("@everyone", allowed, null, null, -1, 0);
    }

    static Mention role(String roleId) {
        JsonObject allowed = new JsonObject();
        allowed.add("parse", new JsonArray());
        JsonArray roles = new JsonArray();
        roles.add(roleId);
        allowed.add("roles", roles);
        return new Mention("<@&" + roleId + ">", allowed, null, null, -1, 0);
    }

    /** 准备订阅的 @：建临时身份组并加人。progress 收到「已加 x / 共 n」。 */
    Mention prepareSubscription(String guildId, BotConfig.PanelRole topic, Consumer<String> progress) throws IOException {
        List<String> members = subs.members(topic.key());
        if (members.isEmpty()) return Mention.none();

        JsonObject body = new JsonObject();
        body.addProperty("name", topic.name());
        body.addProperty("permissions", "0");
        body.addProperty("mentionable", true);
        String roleId = rest.post("/guilds/" + guildId + "/roles", body).getAsJsonObject().get("id").getAsString();
        subs.addPending(roleId);
        boolean ok = false;
        try {
            List<String> gone = new ArrayList<>();
            int done = 0;
            for (String uid : members) {
                try {
                    rest.put("/guilds/" + guildId + "/members/" + uid + "/roles/" + roleId, null);
                } catch (DiscordRest.DiscordException e) {
                    if (e.code == 10007 || e.code == 10013) gone.add(uid); // 已经退出服务器
                    else throw e;
                }
                done++;
                if (done % 10 == 0 && done < members.size()) progress.accept("⏳ 正在准备通知：" + done + " / " + members.size());
            }
            subs.removeAll(topic.key(), gone);
            ok = true;
            Mention m = role(roleId);
            return new Mention(m.content(), m.allowed(), roleId, "📣 **@" + topic.name() + "**",
                    members.size() - gone.size(), gone.size());
        } finally {
            if (!ok) deleteRole(guildId, roleId);
        }
    }

    /**
     * 消息已经发出：等通知送达，把 @ 改成普通文字，删掉临时身份组。
     * messagePath = /channels/{频道或贴子}/messages/{消息}；null 表示消息没发出去，只删身份组。
     */
    void finish(String guildId, Mention m, String messagePath, String newContent) {
        if (m.tempRoleId() == null) return;
        if (messagePath != null) {
            try {
                Thread.sleep(SETTLE_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            try {
                JsonObject edit = new JsonObject();
                edit.addProperty("content", newContent);
                edit.add("allowed_mentions", Interactions.noMentions());
                rest.patch(messagePath, edit);
            } catch (IOException e) {
                log.warning("把公告里的 @ 改成文字失败：" + e.getMessage());
            }
        }
        deleteRole(guildId, m.tempRoleId());
    }

    private void deleteRole(String guildId, String roleId) {
        try {
            rest.delete("/guilds/" + guildId + "/roles/" + roleId);
            subs.removePending(roleId);
        } catch (DiscordRest.DiscordException e) {
            if (e.code == 10011) subs.removePending(roleId);
            else log.warning("删除临时身份组失败（下次启动会再试）：" + e.friendly());
        } catch (IOException e) {
            log.warning("删除临时身份组失败（下次启动会再试）：" + e.getMessage());
        }
    }
}
