# Ask and Autonomous

[简体中文](modes.md) | English

When Mika works for you, the mode decides which steps it checks with you first. There is one global switch: "Developer options" (开发者选项) → "Autonomous mode" (自主模式) in Settings. Off means Ask, on means Autonomous.

| Mode | Behavior |
|---|---|
| **Ask** (default) | Only the step that spends money is confirmed with you first: placing or submitting an order, or a confirm/submit while the page shows an amount due. The card says where, what and how much, and you can approve just this once or decline. The payment itself is done by you. Sending messages, posting, submitting forms, deleting and changing settings are not asked about; they are just done. |
| **Autonomous** | Does not ask even before paying. The spacing between items in batch jobs still applies. |

- The switch applies to all tasks from then on; if a running task switches from Ask to Autonomous, payments are no longer asked about from the next step.
- In both modes Mika asks you to take over for things only you should do, like logging in, verification codes and payment passwords. It also asks when information is missing or only you can decide (e.g. which of two contacts with the same name).
- For tasks that run while you are away, such as scheduled tasks, Ask mode does not wait at a money-spending step: it skips it and tells you plainly which step was not done. Everything else is completed as usual.
- **Reduce risk** (the settings switch, on by default) applies in both Ask and Autonomous mode. Mika waits a random few seconds between steps (about 3–15 seconds unless you change the minimum and maximum; the wait is not the same every time). Each app has an hourly and a daily cap; hitting one stops the task and tells you. If the screen shows “too many actions”, a slider, a puzzle or face verification, Mika stops, tells you, and pauses that app for 24 hours. It does not try to pass the check. On WeChat, Xiaohongshu, Taobao, Xianyu and JD, sending, posting, commenting, liking, following, ordering, paying and changing a price all wait for you; Xianyu also asks before refreshing a listing. If nobody can confirm, that step is not done. Taobao, Xianyu and JD also cap searches, page turns and item opens, and Xiaohongshu allows less browsing. Turning the switch off removes these extra limits.

You are responsible for actions you approve, and for actions Mika takes on your behalf in Autonomous mode. For anything important, use Ask mode and check the results; see the [disclaimer](disclaimer.en.md).
