# Use cases: feedback collection (`/minmin 收集`)

Screenshots from our Discord server. Member names and avatars are blurred.

1. **Feedback discussion** ([1-feedback-discussion.png](1-feedback-discussion.png)): players discuss a gameplay problem in the feedback channel. An admin runs `/minmin 收集` with that channel and a 20-minute window, so the discussion is gathered into one summary.
2. **Bug list** ([2-bug-list.png](2-bug-list.png)): a player posts a numbered bug list in the same channel, and the admin replies that the fixes will ship in the next update. Collecting keeps reports like this from getting lost in chat.

The bot reads messages only when an admin runs the command, and only from allow-listed feedback channels. The summary goes to a private thread that only that admin can see. No copy is kept outside Discord, and members can opt out with `/minmin 隐私 退出`. See [PRIVACY.md](../../PRIVACY.md).
