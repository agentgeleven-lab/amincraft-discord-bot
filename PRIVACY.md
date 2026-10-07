# Amincraft 机器人隐私政策 / Privacy Policy

更新日期 / Last updated: 2026-10-08

本机器人（Amincraft Discord 机器人，源码即本仓库）只服务于 Amincraft Minecraft 服务器和它所在的 Discord 社区。机器人作为 Minecraft 服务器插件运行。**消息内容不会保存在 Discord 之外**；下面列出的账号绑定、订阅名单等非消息数据保存在运行该服务器的主机上。

## 我们收集哪些数据
- **Discord 账号信息**：用户 ID、用户名，以及你在本服务器里的身份组。用途：领取身份组、使用指令时判断权限。
- **订阅名单**：你在面板上订阅了哪些通知（只记录用户 ID）。用途：发公告时提醒订阅的人。
- **服务器密钥和账号绑定**：你的 Discord 用户 ID、你填写的 Minecraft 游戏名、发给你的服务器密钥，以及领取时间。用途：让你登录游戏服务器，并防止一人领取多个密钥。领取时会检查你的 Discord 账号注册时间和加入本服务器的时间是否达到门槛，这两个时间只用于判断，不单独保存。
- **反馈频道的消息**：只在管理员指定的反馈频道（例如 bug 汇报、建议）里，管理员使用收集功能时，收集这些频道和贴子里的消息文字、作者名字和附件（附件会下载保存）。用途：整理成汇总，发到只有发起收集的管理员能看到的 Discord 私密子区，用来修复问题、改进服务器。整理时的临时文件在发送完成后**立即删除**，服务器上不保留副本。其它频道的消息不会被收集。
- **聊天互通**：目前**关闭**。如果以后开启（把一个指定频道的消息转发到游戏聊天），会先更新本政策。
- **游戏数据**：你在游戏服务器里的对局记录、排行榜和回放由游戏服务器产生，机器人会把其中一部分发到 Discord（例如每日回放、排行榜）。以后可能会增加个人数据汇报。

我们**不**收集密码、支付信息，也**不**读取和上面这些用途无关的频道或私信。

## 怎么退出
在 Discord 里使用 `/minmin 隐私 退出`。退出后，机器人不再收集你的消息，聊天互通也不再把你的消息转发到游戏里。随时可以用 `/minmin 隐私 恢复` 改回来，用 `/minmin 隐私 查看` 查看现在的选择。

## 数据怎么用、给谁
- 只用于运营 Amincraft 服务器和社区。
- 不出售，也不提供给其他人。以下两种情况例外：
  - 领取密钥时，为了判断游戏名是不是正版账号，会把你填写的**游戏名**发给 Mojang 的公开查询接口。
  - 管理员可能会把反馈汇总交给 AI 工具（例如 Claude）帮忙整理问题和修复 bug。这些内容**不会**用于训练机器学习或 AI 模型。

## 保存多久，怎么删除
- 反馈汇总只存在 Discord 的私密子区里，服务器上不保留副本（临时文件发送后立即删除）。
- 订阅名单会保存到你取消订阅为止；账号绑定和密钥会一直保存，直到你申请删除（管理员会帮你解绑并删除）。
- 回放按服务器的规则保存，会定期清理。
- 想查看或删除自己的数据，请在本仓库提交 Issue：<https://github.com/agentgeleven-lab/amincraft-discord-bot/issues>（不要在 Issue 里写密钥等私密信息，管理员会再联系你），或者私信服务器管理员。

## 变更
本政策有改动时，会更新这个文件，修改记录可以在本仓库的提交历史里看到。

---

## English summary
- **What we collect:**
  - Discord user ID, username and roles in our server, used for role buttons and permission checks.
  - Notification subscriptions (user ID only).
  - Server-key binding: Discord user ID, the Minecraft name you enter, the issued key and when it was issued. Account age and server join date are checked when you claim a key, but are not stored.
  - Feedback messages, **only** from the feedback channels an admin has configured (bug reports, suggestions), when an admin runs the collect command: message text, author names and attachments. They are compiled into a summary posted to a private Discord thread visible only to that admin. Temporary files are deleted immediately after posting. **Message content is not stored outside Discord.** No other channels are collected.
  - The chat bridge is currently **disabled**. If it is ever enabled, this policy will be updated first.
  - Game data such as match records, leaderboards and replays is produced by the game server, and some of it is posted to Discord.
- **What we don't collect:** passwords and payment data. We don't read unrelated channels or DMs.
- **Opt out:** run `/minmin 隐私 退出` (privacy → opt out). After that, your messages are no longer collected or relayed to the game. Use `/minmin 隐私 恢复` to opt back in.
- **Use:** only to run the Amincraft server and community. Never sold or shared, with two exceptions:
  - when you claim a key, the Minecraft name you enter is sent to Mojang's public lookup API;
  - admins may use AI tools (e.g. Claude) to help triage feedback summaries. This content is **not** used to train machine-learning or AI models.
- **Retention:** feedback summaries exist only in the private Discord thread; no copy is kept on our server. Subscriptions are kept until you unsubscribe. Key bindings are kept until you ask for deletion.
- **Contact:** open an issue at <https://github.com/agentgeleven-lab/amincraft-discord-bot/issues> (don't post keys) or DM a server admin.
