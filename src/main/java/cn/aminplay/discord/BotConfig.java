package cn.aminplay.discord;

import org.bukkit.configuration.file.FileConfiguration;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** config.yml 的只读快照。 */
final class BotConfig {
    /** type: "subscribe" = 只记名单、@ 时临时建身份组（不占名额）；"role" = 真身份组。 */
    record PanelRole(String key, String name, String type, String id, String emoji, String description) {
        boolean subscribe() {
            return !"role".equals(type);
        }
    }

    final String token;
    final String guildId;
    final String announceChannel;
    final String statusChannel;
    final String bridgeChannel;
    final String logChannel;
    final String plotsChannel;   // 2026-10-08 bot-plots: 类脑市地块申请的管理频道
    final List<String> adminRoles;
    final List<PanelRole> panelRoles;
    final String panelTitle;
    final String panelText;
    final boolean bridgeChat;
    final boolean bridgeJoinLeave;
    final boolean pingOnStart;
    final boolean presence;
    final Set<String> blockedCommands;
    final String serverName;
    final String serverAddress;
    // 2026-10-07 daily replay bundle: 论坛回放贴
    final boolean replaysEnabled;
    // 2026-10-07 message collect
    final String collectChannel;
    final java.time.ZoneId collectZone;
    final int collectMaxMessages;
    final long collectMaxFileBytes;
    final long collectMaxTotalBytes;
    final List<String> collectSources;   // 2026-10-08 privacy: only these feedback channels/forums (and their threads)
    final int collectKeepDays;
    final String replaysForum;
    final String replaysThread;
    final String replaysTitle;
    final String replaysTag;
    final int replaysPollSeconds;
    // 2026-10-07 auth-gate：学习版玩家 Discord 密钥
    final boolean keysEnabled;
    final int keysMinAccountDays;
    final int keysMinMemberDays;
    final boolean keysPanelButton;
    final boolean keysDmCopy;

    private BotConfig(FileConfiguration c) {
        token = c.getString("discord.token", "").trim();
        guildId = id(c.getString("discord.guild-id", ""));
        announceChannel = id(c.getString("channels.announce", ""));
        statusChannel = id(c.getString("channels.status", ""));
        bridgeChannel = id(c.getString("channels.bridge", ""));
        logChannel = id(c.getString("channels.log", ""));
        plotsChannel = id(c.getString("channels.plots", ""));   // 2026-10-08 bot-plots
        adminRoles = new ArrayList<>();
        for (String s : c.getStringList("roles.admin")) if (!id(s).isEmpty()) adminRoles.add(id(s));
        panelRoles = new ArrayList<>();
        for (Map<?, ?> m : c.getMapList("roles.panel")) {
            String name = str(m.get("name"));
            if (name.isEmpty()) continue;
            String key = str(m.get("key"));
            if (key.isEmpty()) key = name;
            key = BotRules.truncate(key.replace("|", "_"), 30);
            String type = str(m.get("type")).toLowerCase(Locale.ROOT).equals("role") ? "role" : "subscribe";
            panelRoles.add(new PanelRole(key, name, type, id(str(m.get("id"))), str(m.get("emoji")), str(m.get("description"))));
        }
        panelTitle = c.getString("panel.title", "🔔 订阅通知");
        panelText = c.getString("panel.text", "点下面的按钮订阅，再点一次取消。");
        bridgeChat = c.getBoolean("bridge.chat", false);
        bridgeJoinLeave = c.getBoolean("bridge.join-leave", true);
        pingOnStart = c.getBoolean("notify.ping-on-start", false);
        presence = c.getBoolean("notify.presence", true);
        blockedCommands = new LinkedHashSet<>();
        for (String s : c.getStringList("commands.blocked")) blockedCommands.add(BotRules.commandRoot(s));
        serverName = c.getString("server.name", "Amincraft");   // 2026-10-06 rename Amincraft
        serverAddress = c.getString("server.address", "");
        replaysEnabled = c.getBoolean("replays.enabled", true);   // 2026-10-07 daily replay bundle
        collectChannel = id(c.getString("channels.collect", ""));   // 2026-10-07 message collect
        java.time.ZoneId z;
        try {
            z = java.time.ZoneId.of(c.getString("collect.timezone", "Asia/Tokyo"));
        } catch (java.time.DateTimeException e) {
            z = java.time.ZoneId.of("Asia/Tokyo");
        }
        collectZone = z;
        collectMaxMessages = Math.max(100, c.getInt("collect.max-messages", 20000));
        collectMaxFileBytes = Math.max(1, c.getLong("collect.max-file-mb", 25)) * 1024 * 1024;
        collectMaxTotalBytes = Math.max(1, c.getLong("collect.max-total-mb", 500)) * 1024 * 1024;
        collectSources = new ArrayList<>();   // 2026-10-08 privacy
        for (String s : c.getStringList("collect.sources")) if (!id(s).isEmpty()) collectSources.add(id(s));
        collectKeepDays = Math.max(1, c.getInt("collect.keep-days", 1));   // 2026-10-08 privacy: results are deleted right after posting; this only cleans leftovers
        replaysForum = id(c.getString("replays.forum-channel", ""));   // 2026-10-08 github: no built-in id, set with /discordbot set replays.forum-channel <id>
        replaysThread = id(c.getString("replays.thread-id", ""));
        replaysTitle = c.getString("replays.thread-title", "📼 Amincraft 对局回放");
        replaysTag = c.getString("replays.forum-tag", "").trim();
        replaysPollSeconds = Math.max(15, c.getInt("replays.poll-seconds", 60));
        keysEnabled = c.getBoolean("keys.enabled", true);   // 2026-10-07 auth-gate
        keysMinAccountDays = Math.max(0, c.getInt("keys.min-account-days", 7));
        keysMinMemberDays = Math.max(0, c.getInt("keys.min-member-days", 1));
        keysPanelButton = c.getBoolean("keys.panel-button", true);
        keysDmCopy = c.getBoolean("keys.dm-copy", true);
    }

    static BotConfig load(FileConfiguration c) {
        return new BotConfig(c);
    }

    boolean hasPanelRole(String id) {
        for (PanelRole r : panelRoles) if (!r.subscribe() && r.id().equals(id)) return true;
        return false;
    }

    PanelRole entry(String key) {
        for (PanelRole r : panelRoles) if (r.key().equals(key)) return r;
        return null;
    }

    int intents() {
        int i = DiscordGateway.INTENT_GUILDS;
        if (bridgeChat && !bridgeChannel.isEmpty()) i |= DiscordGateway.INTENT_GUILD_MESSAGES | DiscordGateway.INTENT_MESSAGE_CONTENT;
        return i;
    }

    /** 把解析/创建出来的身份组 id 写回 config.yml。 */
    static void writePanelIds(FileConfiguration c, List<PanelRole> roles) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (PanelRole r : roles) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("key", r.key());
            m.put("name", r.name());
            m.put("type", r.type());
            if (!r.subscribe()) m.put("id", r.id());
            m.put("emoji", r.emoji());
            m.put("description", r.description());
            out.add(m);
        }
        c.set("roles.panel", out);
    }

    private static String str(Object o) {
        return o == null ? "" : String.valueOf(o).trim();
    }

    /** 允许直接粘贴 <#123> / <@&123> 这类格式。 */
    private static String id(String s) {
        if (s == null) return "";
        String digits = s.replaceAll("[^0-9]", "");
        return digits.toLowerCase(Locale.ROOT);
    }
}
