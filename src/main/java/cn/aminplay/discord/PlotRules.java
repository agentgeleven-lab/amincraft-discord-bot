package cn.aminplay.discord;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 2026-10-08 bot-plots：类脑市地块申请在 Discord 上的纯逻辑（嵌入、按钮、回复文字、custom_id），由 PlotDeskTest 覆盖。
 * 申请视图 / 事件的字段见 workstreams/city-v2/接口约定.md「bot-plots」段。
 */
final class PlotRules {
    private PlotRules() {}

    static final int COLOR_OPEN = 0xF2B33D, COLOR_OK = 0x57C84D, COLOR_NO = 0xE5534B, COLOR_GONE = 0x99AAB5;
    /** custom_id 前缀：管理频道的 [同意]/[驳回]、驳回原因弹窗、私信里的共同开发者 [同意]/[拒绝]。 */
    static final String BTN = "plot|", MODAL = "plotrej|", CODEV = "codev|";

    static String str(Object o) {
        return o == null ? "" : String.valueOf(o);
    }

    static long num(Object o) {
        if (o instanceof Number n) return n.longValue();
        try {
            return Long.parseLong(str(o));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /** 游戏名里的 Discord markdown（_ * ~ ` |）转义。 */
    static String esc(String s) {
        return str(s).replaceAll("([_*~`|\\\\>])", "\\\\$1");
    }

    @SuppressWarnings("unchecked")
    static List<Map<String, Object>> applicants(Map<String, Object> app) {
        List<Map<String, Object>> out = new ArrayList<>();
        if (app != null && app.get("applicants") instanceof List<?> l) for (Object o : l) if (o instanceof Map<?, ?> m) out.add((Map<String, Object>) m);
        return out;
    }

    static List<String> applicantNames(Map<String, Object> app) {
        List<String> out = new ArrayList<>();
        for (Map<String, Object> a : applicants(app)) out.add(str(a.get("name")));
        return out;
    }

    static String plotLabel(Map<String, Object> app) {
        String l = str(app.get("plotLabel"));
        return l.isBlank() ? str(app.get("plot")) : l;
    }

    static String status(Map<String, Object> app) {
        String s = str(app.get("status"));
        return s.isEmpty() ? "open" : s;
    }

    static boolean open(Map<String, Object> app) {
        return status(app).equals("open");
    }

    static String where(Object source) {
        return switch (str(source)) {
            case "discord" -> "Discord";
            case "game" -> "游戏内";
            case "menu" -> "游戏内菜单";
            case "admin" -> "管理员指令";
            default -> str(source).isEmpty() ? "" : str(source);
        };
    }

    /** 状态一行（嵌入的「状态」栏和「我的申请」都用）。 */
    static String statusLine(Map<String, Object> app, boolean withWho) {
        String by = str(app.get("decidedBy")), src = where(app.get("decidedSource"));
        long at = num(app.get("decidedAt"));
        String who = !withWho || by.isEmpty() ? "" : " · 由 **" + esc(by) + "**" + (src.isEmpty() ? "" : "（" + src + "）") + (at > 0 ? " <t:" + at / 1000 + ":f>" : "");
        String reason = str(app.get("decidedReason")).trim();
        return switch (status(app)) {
            case "open" -> "⏳ 待审批";
            case "approved" -> "✅ 已同意" + who;
            case "rejected" -> "❌ 已驳回" + who + (withWho && !reason.isEmpty() ? "\n原因：" + BotRules.truncate(reason, 300) : "");
            case "cancelled" -> "↩️ 已撤回" + who;
            case "expired" -> "⌛ 已过期";
            default -> status(app);
        };
    }

    /**
     * 管理频道里的一条申请：嵌入（地块、申请人 + Discord 提及、理由、竞争申请、状态）+ 待审时的 [同意]/[驳回]。
     * mentions：uuid → Discord 用户 id（绑定了才有）。allowed_mentions 一律为空：显示提及但不 @ 人。
     */
    static JsonObject applicationMessage(Map<String, Object> app, List<Map<String, Object>> competing, Map<String, String> mentions) {
        String id = str(app.get("id"));
        StringBuilder who = new StringBuilder();
        int i = 0;
        for (Map<String, Object> a : applicants(app)) {
            String d = mentions == null ? null : mentions.get(str(a.get("uuid")));
            who.append(++i).append(". **").append(esc(str(a.get("name")))).append("**").append(d == null ? "" : " <@" + d + ">").append(i == 1 ? "（发起人）" : "").append("\n");
        }
        if (i == 0) who.append("（无）");
        StringBuilder comp = new StringBuilder();
        if (competing != null) for (Map<String, Object> c : competing) {
            if (!open(c) || str(c.get("id")).equals(id)) continue;
            String line = "• `" + str(c.get("id")) + "` " + esc(String.join("、", applicantNames(c))) + "\n";
            if (comp.length() + line.length() > 900) {
                comp.append("…");
                break;
            }
            comp.append(line);
        }
        String reason = str(app.get("reason")).trim();
        JsonObject embed = new JsonObject();
        embed.addProperty("title", BotRules.truncate("🏙️ 地块申请 · " + plotLabel(app), 256));
        embed.addProperty("color", switch (status(app)) {
            case "open" -> COLOR_OPEN;
            case "approved" -> COLOR_OK;
            case "rejected" -> COLOR_NO;
            default -> COLOR_GONE;
        });
        JsonArray fields = new JsonArray();
        fields.add(field("地块", "`" + str(app.get("plot")) + "`", true));
        fields.add(field("人数", applicants(app).size() + " 人", true));
        fields.add(field("申请人", BotRules.truncate(who.toString().trim(), 1024), false));
        fields.add(field("理由", reason.isEmpty() ? "（没写）" : BotRules.truncate(reason, 1024), false));
        fields.add(field("同一地块的其它申请", comp.length() == 0 ? "无" : comp.toString().trim(), false));
        fields.add(field("状态", BotRules.truncate(statusLine(app, true), 1024), false));
        embed.add("fields", fields);
        JsonObject footer = new JsonObject();
        footer.addProperty("text", "申请编号 " + id + (where(app.get("source")).isEmpty() ? "" : " · 在" + where(app.get("source")) + "发起"));
        embed.add("footer", footer);
        long t = num(app.get("time"));
        if (t > 0) embed.addProperty("timestamp", Instant.ofEpochMilli(t).toString());
        JsonObject msg = Interactions.message(null, embed);
        JsonArray rows = new JsonArray();
        if (open(app)) rows.add(row(button(3, "同意", BTN + "ok|" + id), button(4, "驳回", BTN + "no|" + id)));
        msg.add("components", rows);   // empty array = buttons removed once decided
        msg.add("allowed_mentions", Interactions.noMentions());
        return msg;
    }

    /** 驳回原因弹窗（可以不填）。 */
    static JsonObject rejectModal(String id) {
        JsonObject input = new JsonObject();
        input.addProperty("type", 4);
        input.addProperty("custom_id", "reason");
        input.addProperty("style", 2);
        input.addProperty("required", false);
        input.addProperty("max_length", 300);
        input.addProperty("placeholder", "可以不填；会告诉申请人");
        JsonObject wrap = new JsonObject();
        wrap.addProperty("type", 18);
        wrap.addProperty("label", "驳回原因（可选）");
        wrap.add("component", input);
        JsonArray comps = new JsonArray();
        comps.add(wrap);
        JsonObject data = new JsonObject();
        data.addProperty("custom_id", MODAL + id);
        data.addProperty("title", "驳回地块申请");
        data.add("components", comps);
        return data;
    }

    /** /minmin 申请地块 的结果。r 里有 message 时用游戏服务器给的原因。 */
    static String submitReply(Map<String, Object> r, String plot) {
        String st = str(r.get("status")), why = str(r.get("message")).trim();
        String p = "`" + plot + "`";
        String base = switch (st) {
            case "ok" -> "✅ 已提交地块 " + p + " 的申请" + (r.get("id") == null ? "" : "（编号 `" + r.get("id") + "`）") + "，等管理员审批。结果会同步到游戏里。";
            case "not-found" -> "找不到地块 " + p + "，请从下拉列表里选，或者在游戏里打开地块地图看编号。";
            case "taken" -> "地块 " + p + " 已经有主人了。";
            case "already" -> "你已经申请过 " + p + "（或者已经是这块地的开发者）。";
            case "limit" -> "你的地块 / 申请数量已经到上限了。";
            case "not-eligible" -> "你现在还不能申请地块。";
            case "closed" -> "现在不接受地块申请。";
            case "locked" -> "这片区域还没开放，不能申请。";
            default -> "❌ 申请失败，请稍后再试或联系管理员。";
        };
        return why.isEmpty() || st.equals("ok") ? base : base + "\n" + BotRules.truncate(why, 300);
    }

    /** /minmin 加入申请 的结果。 */
    static String joinReply(Map<String, Object> r) {
        String st = str(r.get("status")), why = str(r.get("message")).trim();
        @SuppressWarnings("unchecked")
        Map<String, Object> app = r.get("application") instanceof Map<?, ?> m ? (Map<String, Object>) m : null;
        String base = switch (st) {
            case "ok" -> "✅ 已加入" + (app == null ? "" : "地块 `" + str(app.get("plot")) + "` 的") + "申请"
                    + (app == null ? "" : "（现在 " + applicants(app).size() + " 人：" + esc(String.join("、", applicantNames(app))) + "）") + "，等管理员审批。";
            case "not-found" -> "找不到这个申请，可能已经被处理了。请从下拉列表里选。";
            case "already" -> "你已经在这个申请里了。";
            case "closed" -> "这个申请已经结束，不能再加入。";
            case "limit" -> "这个申请人数满了，或者你的地块 / 申请数量到上限了。";
            case "not-eligible" -> "你现在还不能加入地块申请。";
            case "ambiguous" -> "这块地有好几个待审申请，请从下拉列表里选要加入哪一个" + (r.get("ids") instanceof List<?> l && !l.isEmpty() ? "（" + String.join("、", l.stream().map(String::valueOf).toList()) + "）" : "") + "。";
            default -> "❌ 加入失败，请稍后再试或联系管理员。";
        };
        return why.isEmpty() || st.equals("ok") ? base : base + "\n" + BotRules.truncate(why, 300);
    }

    /** 管理员点了按钮但没成功（已经被别人处理了等），仅自己可见。 */
    static String decisionError(Map<String, Object> r) {
        String why = str(r.get("message")).trim();
        String base = switch (str(r.get("status"))) {
            case "decided" -> "这个申请已经被处理过了（嵌入已更新成最新状态）。";
            case "not-found" -> "找不到这个申请，可能已经被删除了。";
            case "taken" -> "这块地已经有主人了，不能再批给这个申请。";
            default -> "❌ 操作失败，请稍后再试或在游戏里用 /mgcity applications 处理。";
        };
        return why.isEmpty() ? base : base + "\n" + BotRules.truncate(why, 300);
    }

    /** 「我的申请」（最近 10 条）。 */
    static String mine(List<Map<String, Object>> apps) {
        StringBuilder b = new StringBuilder("**我的申请**");
        if (apps == null || apps.isEmpty()) return b.append("\n你还没有地块申请。").toString();
        int n = 0;
        for (Map<String, Object> a : apps) {
            if (++n > 10) {
                b.append("\n…还有 ").append(apps.size() - 10).append(" 条");
                break;
            }
            long t = num(a.get("time"));
            b.append("\n• `").append(str(a.get("plot"))).append("` ").append(statusLine(a, false))
                    .append("（").append(applicants(a).size()).append(" 人").append(t > 0 ? " · <t:" + t / 1000 + ":d>" : "").append("）");
            String reason = str(a.get("decidedReason")).trim();
            if (status(a).equals("rejected") && !reason.isEmpty()) b.append(" 原因：").append(BotRules.truncate(reason, 100));
        }
        return b.toString();
    }

    /** 自动补全里一条待审申请的显示名。 */
    static String choiceLabel(Map<String, Object> app) {
        List<String> names = applicantNames(app);
        String who = names.isEmpty() ? "" : names.get(0) + (names.size() > 1 ? " 等 " + names.size() + " 人" : "");
        return BotRules.truncate(plotLabel(app) + " · " + who + " · #" + str(app.get("id")), 100);
    }

    // ===== 共同开发者私信 =====

    @SuppressWarnings("unchecked")
    static List<Map<String, Object>> members(Map<String, Object> e) {
        List<Map<String, Object>> out = new ArrayList<>();
        if (e.get("members") instanceof List<?> l) for (Object o : l) if (o instanceof Map<?, ?> m) out.add((Map<String, Object>) m);
        return out;
    }

    static JsonObject codevDm(Map<String, Object> e, String serverName) {
        String id = str(e.get("id"));
        String plot = str(e.get("plotLabel")).isBlank() ? str(e.get("plot")) : str(e.get("plotLabel"));
        StringBuilder d = new StringBuilder();
        String by = str(e.get("by"));
        d.append(by.isEmpty() ? "有人" : "**" + esc(by) + "**").append(" 想要：").append(BotRules.truncate(str(e.get("action")), 500)).append("\n\n");
        d.append("这块地的权限变动要**所有共同开发者都同意**才会生效。你可以在这里表态，也可以上线后在游戏里表态。");
        long exp = num(e.get("expiresAt"));
        if (exp > 0) d.append("\n截止：<t:").append(exp / 1000).append(":f>（<t:").append(exp / 1000).append(":R>）");
        JsonObject embed = new JsonObject();
        embed.addProperty("title", BotRules.truncate("🤝 " + serverName + " 类脑市 · 共同开发者确认 · " + plot, 256));
        embed.addProperty("description", BotRules.truncate(d.toString(), 4000));
        embed.addProperty("color", COLOR_OPEN);
        JsonObject msg = Interactions.message(null, embed);
        JsonArray rows = new JsonArray();
        rows.add(row(button(3, "同意", CODEV + "y|" + id), button(4, "拒绝", CODEV + "n|" + id)));
        msg.add("components", rows);
        return msg;
    }

    static String codevAnswer(Map<String, Object> r, boolean yes) {
        String why = str(r.get("message")).trim();
        String base = switch (str(r.get("status"))) {
            case "ok" -> (yes ? "✅ 你已同意。" : "❌ 你已拒绝。") + switch (str(r.get("result"))) {
                case "approved" -> "所有人都同意了，变动已生效。";
                case "rejected" -> "这次变动不会生效。";
                default -> "还在等其他共同开发者表态。";
            };
            case "decided" -> "这次确认已经结束了。";
            case "wrong-user" -> "这个确认不是给你的（或者你已经不是这块地的共同开发者）。";
            case "already" -> "你已经表过态了。";
            case "not-found" -> "这次确认已经过期或被撤回了。";
            default -> "❌ 出错了，请上线后在游戏里表态。";
        };
        return why.isEmpty() || str(r.get("status")).equals("ok") ? base : base + "\n" + BotRules.truncate(why, 300);
    }

    static String codevClosed(String result) {
        return switch (str(result)) {
            case "approved" -> "✅ 所有共同开发者都同意了，变动已生效。";
            case "rejected" -> "❌ 有共同开发者拒绝了，这次变动不生效。";
            case "expired" -> "⌛ 确认已过期，变动没有生效。";
            case "cancelled" -> "↩️ 发起人撤回了这次变动。";
            case "failed" -> "⚠️ 大家都同意了，但变动没能执行（可能地块状态已经变了），请在游戏里看看。";
            default -> "这次确认已经结束。";
        };
    }

    // ===== 小工具 =====

    static JsonObject button(int style, String label, String id) {
        JsonObject b = new JsonObject();
        b.addProperty("type", 2);
        b.addProperty("style", style);
        b.addProperty("label", label);
        b.addProperty("custom_id", BotRules.truncate(id, 100));
        return b;
    }

    static JsonObject row(JsonObject... buttons) {
        JsonArray a = new JsonArray();
        for (JsonObject b : buttons) a.add(b);
        JsonObject r = new JsonObject();
        r.addProperty("type", 1);
        r.add("components", a);
        return r;
    }

    private static JsonObject field(String name, String value, boolean inline) {
        JsonObject f = new JsonObject();
        f.addProperty("name", name);
        f.addProperty("value", value.isEmpty() ? "-" : value);
        f.addProperty("inline", inline);
        return f;
    }
}
