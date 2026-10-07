package cn.aminplay.discord;

import java.time.Duration;
import java.util.Set;

/** 不依赖测试框架的自检：java -cp classes;test-classes cn.aminplay.discord.BotRulesTest */
public final class BotRulesTest {
    private static int passed, failed;

    public static void main(String[] args) {
        eq(null, BotRules.parseDuration(""), "空=永久");
        eq(null, BotRules.parseDuration(null), "null=永久");
        eq(null, BotRules.parseDuration("永久"), "永久");
        eq(Duration.ofMinutes(30), BotRules.parseDuration("30m"), "30m");
        eq(Duration.ofHours(12), BotRules.parseDuration("12H"), "12H");
        eq(Duration.ofDays(7), BotRules.parseDuration("7d"), "7d");
        eq(Duration.ofDays(14), BotRules.parseDuration("2w"), "2w");
        eq(Duration.ofHours(36), BotRules.parseDuration("1d12h"), "1d12h");
        eq(Duration.ofHours(36), BotRules.parseDuration("1d 12h"), "带空格");
        eq(Duration.ofDays(3), BotRules.parseDuration("3天"), "3天");
        eq(Duration.ofMinutes(90), BotRules.parseDuration("1小时30分钟"), "中文组合");
        bad("abc");
        bad("7x");
        bad("d7");
        bad("0m");
        bad("7d abc");

        eq("永久", BotRules.formatDuration(null), "fmt 永久");
        eq("1天12小时", BotRules.formatDuration(Duration.ofHours(36)), "fmt 1d12h");
        eq("30分钟", BotRules.formatDuration(Duration.ofMinutes(30)), "fmt 30m");

        eq("op", BotRules.commandRoot("/minecraft:OP Steve"), "root 命名空间");
        eq("say", BotRules.commandRoot("say hi"), "root 普通");
        eq("stop", BotRules.commandRoot("//stop"), "root 多斜杠");
        Set<String> blocked = Set.of("op", "deop");
        eq(true, BotRules.isBlocked("minecraft:op x", blocked), "blocked op");
        eq(false, BotRules.isBlocked("ban x", blocked), "not blocked");
        eq("say hi", BotRules.stripSlash(" /say hi"), "stripSlash");

        eq(true, BotRules.validPlayerName("AminDoll"), "name ok");
        eq(true, BotRules.validPlayerName("a_1"), "name underscore");
        eq(false, BotRules.validPlayerName("bad name"), "name space");
        eq(false, BotRules.validPlayerName("12345678901234567"), "name too long");
        eq(false, BotRules.validPlayerName(""), "name empty");

        eq("abcd…", BotRules.truncate("abcdefgh", 5), "truncate");
        eq("abc", BotRules.truncate("abc", 5), "truncate short");
        eq("aˋˋˋb", BotRules.escapeCodeBlock("a```b"), "escape");

        eq(true, BotRules.hasAdminBits("8"), "admin bit");
        eq(true, BotRules.hasAdminBits("32"), "manage guild bit");
        eq(false, BotRules.hasAdminBits("2048"), "send messages only");
        eq(true, BotRules.hasAdminBits("18446744073709551615"), "all bits (unsigned)");
        eq(false, BotRules.hasAdminBits("garbage"), "garbage");

        System.out.println("BotRulesTest: " + passed + " passed, " + failed + " failed");
        if (failed > 0) System.exit(1);
    }

    private static void bad(String s) {
        try {
            BotRules.parseDuration(s);
            fail("应拒绝 " + s);
        } catch (IllegalArgumentException e) {
            passed++;
        }
    }

    private static void eq(Object expected, Object actual, String what) {
        if (expected == null ? actual == null : expected.equals(actual)) passed++;
        else fail(what + ": expected " + expected + " got " + actual);
    }

    private static void fail(String msg) {
        failed++;
        System.out.println("FAIL " + msg);
    }
}
