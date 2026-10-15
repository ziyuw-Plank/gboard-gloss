# GboardGloss：Gboard 候选词翻译 LSPosed 模块 | Inline translation for Gboard candidates (Xposed / LSPosed)

**中文** · [English](#english)

在谷歌输入法（Gboard）的候选词旁叠加显示目标语言译词，打字时顺便学外语。灵感来自 [青简 Qingjian](https://github.com/qingjian-team/qingjian)。

> **状态：** v0.2 已能编译，但**尚未在真机上验证注入效果**。欢迎反馈测试结果（附 `[gloss]` 日志）。

## 功能
- 仅作用于 Gboard（`com.google.android.inputmethod.latin`）的 LSPosed 模块
- 候选词上方叠加一行译词，只做绘制叠加，**不改变** Gboard 的选词、上屏和其他行为
- 自动识别源语言（按文字类型）
- 目标语言：英、中、日、德、法、西、俄；无直接词典时经英语中转
- 离线词典，无网络请求、无延迟
- Material You 控制中心：动态取色、深浅色、开关、语言选择、字号
- 注入成功提示：屏幕底部胶囊条，几秒后淡出
- 调试模式，日志带 `[gloss]` 前缀

## 环境要求
- Android 8.1+（minSdk 27）
- Root（Magisk）+ LSPosed（Zygisk）
- Gboard（对照分析版本 18.4.1）

## 安装与使用
1. 从 [Releases](https://github.com/ziyuw-Plank/gboard-gloss/releases) 下载 `GboardGloss-0.2.apk` 并安装
2. LSPosed 中启用模块，作用域勾选 Gboard（同时勾选模块本身，设置页才能显示状态）
3. 强行停止 Gboard
4. 打开 GboardGloss，选择目标语言
5. 在任意输入框打字，例如输入 `hello`（目标中文）或 `nihao`（目标英文）

## 故障排查
- 看不到译词：打开调试模式，在 LSPosed 日志中筛选 `[gloss]`，提 Issue 时附上日志和 Gboard 版本
- 没有注入提示：确认作用域已勾选 Gboard 并已强停 Gboard
- Gboard 更新后失效：Gboard 内部结构可能变化，请反馈版本号

## 支持语言与词典
| 方向 | 来源 | 词条数 |
|---|---|---|
| zh → en | CC-CEDICT | 189,267 |
| en → zh | CC-CEDICT（反查） | 55,708 |
| ja → en | JMdict | 465,880 |
| en → ja | JMdict（反查） | 145,436 |
| de → en / en → de | FreeDict | 372,021 / 360,338 |
| fr → en / en → fr | FreeDict | 8,249 / 8,782 |
| es → en / en → es | FreeDict | 4,497 / 58,868 |
| ru → en / en → ru | FreeDict | 42,281 / 57,005 |

其他组合（如 zh → ja）经英语中转。暂不支持韩语（缺少可自由使用的词典）。

## 构建
```bash
git clone https://github.com/ziyuw-Plank/gboard-gloss
cd gboard-gloss
python3 tools/build_dicts.py   # 可选：重新生成词典
./gradlew assembleRelease      # 需要 JDK 17 与 Android SDK
```

## 常见问题
**问：会影响 Gboard 正常输入吗？**
答：不会。模块只在候选词视图上额外绘制文字，不修改选词和上屏逻辑。

**问：需要联网吗？**
答：不需要，所有词典都在本地。

**问：不 root 能用吗？**
答：不能，需要 root 和 LSPosed。

**问：支持哪些语言？**
答：中、英、日、德、法、西、俄之间互译（部分经英语中转）。

## 致谢与许可
- 灵感：[青简 Qingjian](https://github.com/qingjian-team/qingjian)
- 词典：CC-CEDICT、JMdict/EDRDG、FreeDict，详见 [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md)
- 许可：[GPL-3.0-or-later](LICENSE)。本项目与 Google 无关。

---

<a id="english"></a>
## English

GboardGloss shows a translation right above each Gboard suggestion, so you pick up vocabulary in another language while you type. Inspired by [Qingjian 青简](https://github.com/qingjian-team/qingjian).

> **Status:** v0.2 builds, but injection has **not yet been verified on a real device**. Test reports with `[gloss]` logs are welcome.

### Features
- LSPosed module scoped to Gboard only (`com.google.android.inputmethod.latin`)
- Translation gloss drawn over candidates; purely additive, **never changes** Gboard's suggestions, commits, or behavior
- Automatic source language detection (by script)
- Target languages: en, zh, ja, de, fr, es, ru, with English as pivot when no direct dictionary exists
- Offline dictionaries, no network, no typing lag
- Material You control center: dynamic color, light/dark, toggle, language picker, font size
- Injection banner at the bottom of the screen that fades out
- Debug mode with `[gloss]`-tagged logs

### Requirements
- Android 8.1+ (minSdk 27)
- Root (Magisk) + LSPosed (Zygisk)
- Gboard (analyzed against 18.4.1)

### Install & use
1. Download `GboardGloss-0.2.apk` from [Releases](https://github.com/ziyuw-Plank/gboard-gloss/releases) and install it
2. Enable the module in LSPosed and tick Gboard in its scope (tick the module itself too so the settings page can show status)
3. Force-stop Gboard
4. Open GboardGloss and choose a target language
5. Type in any text field, e.g. `hello` (target zh) or `nihao` (target en)

### Troubleshooting
- No gloss: enable debug mode, filter LSPosed logs for `[gloss]`, and open an issue with the logs and your Gboard version
- No banner: check that Gboard is in scope and was force-stopped
- Broke after a Gboard update: internals may have changed; report the version

### Languages
See the table above. Other pairs (e.g. zh → ja) go through English. Korean is not supported yet (no freely licensed dictionary found).

### Build
See the build section above: JDK 17 + Android SDK, `./gradlew assembleRelease`.

### FAQ
**Does it affect normal typing?** No. It only draws extra text on candidate views.

**Does it need internet?** No. All dictionaries are bundled.

**Does it work without root?** No. It needs root and LSPosed.

**Which languages?** Chinese, English, Japanese, German, French, Spanish, Russian (some pairs via English).

### Credits & license
Dictionaries: CC-CEDICT, JMdict/EDRDG, FreeDict — see [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md). Code licensed [GPL-3.0-or-later](LICENSE). Not affiliated with Google; Gboard is a trademark of Google LLC.

## Keywords
Gboard, Gboard translation, Gboard 翻译, 谷歌输入法, 谷歌输入法翻译, LSPosed, LSPosed module, Xposed module, Xposed 模块, 输入法翻译, 候选词翻译, bilingual candidates, 双语候选词, 外语学习, language learning keyboard, vocabulary learning, Android, root, Magisk, Zygisk, offline dictionary, 离线词典, CC-CEDICT, JMdict, FreeDict, Qingjian, 青简, Material You
