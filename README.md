# Amincraft Discord 机器人

Amincraft Minecraft 服务器的 Discord 机器人。它作为 Paper 1.21.11 插件（Java 21）运行在游戏服务器里，直接连接 Discord，不需要另外的机器人主机。

只在 Amincraft 自己的 Discord 服务器里使用。

## 功能
- 远程执行服务器指令（管理员），查看在线玩家。
- 发布公告：可以发到文字频道或论坛，可以提醒订阅的人。
- 身份组和订阅面板：玩家点按钮领取游戏相关的身份组、订阅通知。
- 收集反馈：只能在设置好的反馈频道（bug 汇报、建议）里收集消息，整理成汇总发到管理员的私密子区；服务器上不保留副本。
- 隐私：成员可以用 `/minmin 隐私 退出` 选择不被收集、不被转发到游戏里。
- 服务器密钥：玩家在 Discord 领取和账号绑定的服务器登录密钥。
- 每日对局回放：把前一天的回放包发到论坛的回放贴。
- 开服/关服通知、在线人数状态；聊天互通（可选，默认关闭）。

## 需要的 Discord 权限
- **Message Content Intent**：读取反馈帖的文字来做汇总；聊天互通也要用。
- 不需要 Server Members Intent 和 Presence Intent。

## 隐私政策
见 [PRIVACY.md](PRIVACY.md)。

## 安装和设置
1. 把 `AminPlayDiscord.jar` 放进服务器的 `plugins/`，启动一次，生成 `plugins/AminPlayDiscord/config.yml`。
2. 在 `config.yml` 里填 `discord.token`。Token 只能手动填在配置文件里，不要发给任何人。
3. 其它设置都可以在服务器控制台用指令完成，不用改文件：
   - `/discordbot set <项目> <值>`：设置服务器 id、各个频道 id、回放论坛等，例如 `/discordbot set replays.forum-channel 123456789012345678`。不带值就是清空。
   - `/discordbot admins list | add <身份组id> | remove <身份组id>`：额外允许使用管理指令的身份组。
   - `/discordbot sources list | add <频道id> | remove <频道id>`：可以收集的反馈频道 / 论坛（不设置就不能收集）。
   - `/discordbot panel …`：身份组和订阅面板。
   - `/discordbot status`、`/discordbot reload`、`/discordbot replays status`、`/discordbot keys`。

仓库里不带任何服务器或频道 id，全部由使用者自己设置。

## 构建
需要 JDK 21，以及 Paper API 1.21.11 和它带的 Adventure、Gson、Guava、SnakeYAML 等 jar。把它们放在一个工具链目录里（`jdk/` 和 `deps/`），然后：

```bash
TC=/path/to/toolchain bash build.sh 1
```

构建产物是 `build-1/AminPlayDiscord.jar`，构建时会同时跑单元测试。

服务器密钥功能需要配套的 MiniGameHub 插件；没有它时，这个功能会显示「不可用」，其它功能不受影响。

## 许可证
GPL-3.0，见 [LICENSE](LICENSE)。
