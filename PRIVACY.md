# Amincraft 机器人隐私政策 / Privacy Policy

更新日期 / Last updated: 2026-10-08

本机器人（Amincraft Discord 机器人，源码即本仓库）只服务于 Amincraft Minecraft 服务器和它所在的 Discord 社区。机器人作为 Minecraft 服务器插件运行，数据只保存在运行该服务器的主机上。

## 我们收集哪些数据
- **Discord 账号信息**：用户 ID、用户名，以及你在本服务器里的身份组。用途：领取身份组、使用指令时判断权限。
- **订阅名单**：你在面板上订阅了哪些通知（只记录用户 ID）。用途：发公告时提醒订阅的人。
- **服务器密钥和账号绑定**：你的 Discord 用户 ID、你填写的 Minecraft 游戏名、发给你的服务器密钥，以及领取时间。用途：让你登录游戏服务器，并防止一人领取多个密钥。领取时会检查你的 Discord 账号注册时间和加入本服务器的时间是否达到门槛，这两个时间只用于判断，不单独保存。
- **反馈帖内容**：管理员使用收集功能时，指定的 bug 汇报帖或建议帖里的消息文字、作者名字和附件链接。用途：整理成汇总交给管理员，用来修复问题、改进服务器。
- **聊天互通（只在开启时）**：指定互通频道里的消息文字和发送者名字，会转发到游戏服务器的聊天里；游戏里的聊天也会转发到这个频道。
- **游戏数据**：你在游戏服务器里的对局记录、排行榜和回放由游戏服务器产生，机器人会把其中一部分发到 Discord（例如每日回放、排行榜）。以后可能会增加个人数据汇报。

我们**不**收集密码、支付信息，也**不**读取和上面这些用途无关的频道或私信。

## 数据怎么用、给谁
- 只用于运营 Amincraft 服务器和社区。
- 不出售，也不提供给其他人。
- 唯一的例外：领取密钥时，为了判断游戏名是不是正版账号，会把你填写的**游戏名**发给 Mojang 的公开查询接口。

## 保存多久，怎么删除
- 订阅名单会保存到你取消订阅为止；账号绑定和密钥会一直保存，直到你申请删除（管理员会帮你解绑并删除）。
- 反馈汇总和回放按服务器的规则保存，回放会定期清理。
- 想查看或删除自己的数据，请在本仓库提交 Issue：<https://github.com/agentgeleven-lab/amincraft-discord-bot/issues>（不要在 Issue 里写密钥等私密信息，管理员会再联系你）。

## 变更
本政策有改动时，会更新这个文件，修改记录可以在本仓库的提交历史里看到。

---

## English summary
- **What we collect:** Discord user ID, username and roles in our server (for role buttons and permission checks); notification subscriptions (user ID only); server-key binding (Discord user ID, the Minecraft name you enter, the issued key and when it was issued). Account age and server join date are checked when you claim a key, but are not stored. We also collect the text, author names and attachment links of messages in the bug-report and suggestion threads an admin chooses to summarise. If the chat bridge is enabled, messages in the bridge channel are relayed to the game server. Game data is produced by the game server: match records, leaderboards and replays, some of which the bot posts to Discord.
- **What we don't collect:** passwords and payment data. We don't read unrelated channels or DMs.
- **Use:** only to run the Amincraft server and community. Never sold or shared. The one exception: when you claim a key, the Minecraft name you enter is sent to Mojang's public lookup API to check whether it is a premium account.
- **Storage and deletion:** data is stored only on the host running the game server. Subscriptions are kept until you unsubscribe; key bindings are kept until you ask for deletion (an admin will unbind and delete them). To see or delete your data, open an issue at <https://github.com/agentgeleven-lab/amincraft-discord-bot/issues>, and don't post keys in it.
