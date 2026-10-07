package cn.aminplay.discord;

import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.logging.Logger;

/**
 * 「订阅」名单：不占 Discord 身份组，只记用户 ID（subscriptions.yml）。
 * 另外记下还没删掉的临时身份组（万一中途关服，下次启动时清理）。
 */
final class Subscriptions {
    private static final String PENDING = "_temp-roles";
    private static final String PANELS = "_panels";

    private final File file;
    private final Logger log;
    private final Map<String, Set<String>> topics = new LinkedHashMap<>();
    private final Set<String> pendingRoles = new LinkedHashSet<>();
    private final Set<String> panels = new LinkedHashSet<>();

    Subscriptions(File file, Logger log) {
        this.file = file;
        this.log = log;
        YamlConfiguration y = YamlConfiguration.loadConfiguration(file);
        for (String key : y.getKeys(false)) {
            if (key.equals(PENDING)) pendingRoles.addAll(y.getStringList(key));
            else if (key.equals(PANELS)) panels.addAll(y.getStringList(key));
            else topics.put(key, new LinkedHashSet<>(y.getStringList(key)));
        }
    }

    /** 切换订阅，返回切换后是否已订阅。 */
    synchronized boolean toggle(String topic, String userId) {
        Set<String> s = topics.computeIfAbsent(topic, k -> new LinkedHashSet<>());
        boolean now = !s.remove(userId);
        if (now) s.add(userId);
        save();
        return now;
    }

    synchronized List<String> members(String topic) {
        return new ArrayList<>(topics.getOrDefault(topic, Set.of()));
    }

    synchronized int count(String topic) {
        return topics.getOrDefault(topic, Set.of()).size();
    }

    /** 已经退出 Discord 服务器的人从名单里移除。 */
    synchronized void removeAll(String topic, Collection<String> userIds) {
        if (userIds.isEmpty()) return;
        Set<String> s = topics.get(topic);
        if (s != null && s.removeAll(userIds)) save();
    }

    /** 删除通知组时一起删掉名单。 */
    synchronized void drop(String topic) {
        if (topics.remove(topic) != null) save();
    }

    /** 已发出的面板消息（/channels/x/messages/y），通知组变动时自动更新。 */
    synchronized void addPanel(String messagePath) {
        if (panels.add(messagePath)) save();
    }

    synchronized void removePanel(String messagePath) {
        if (panels.remove(messagePath)) save();
    }

    synchronized List<String> panels() {
        return new ArrayList<>(panels);
    }

    synchronized void addPending(String roleId) {
        pendingRoles.add(roleId);
        save();
    }

    synchronized void removePending(String roleId) {
        if (pendingRoles.remove(roleId)) save();
    }

    synchronized List<String> pending() {
        return new ArrayList<>(pendingRoles);
    }

    private void save() {
        YamlConfiguration y = new YamlConfiguration();
        for (Map.Entry<String, Set<String>> e : topics.entrySet()) y.set(e.getKey(), new ArrayList<>(e.getValue()));
        if (!pendingRoles.isEmpty()) y.set(PENDING, new ArrayList<>(pendingRoles));
        if (!panels.isEmpty()) y.set(PANELS, new ArrayList<>(panels));
        try {
            y.save(file);
        } catch (IOException e) {
            log.warning("保存订阅名单失败：" + e.getMessage());
        }
    }
}
