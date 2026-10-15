# GboardGloss — LSPosed 模块：Gboard 候选词译词

在 Gboard 候选词下方叠加显示目标语言译词（灵感来自青简 qingjian）。只作用于 `com.google.android.inputmethod.latin`。

## 原理（v0.2）
- 在 Gboard 进程里 hook `ViewGroup.dispatchDraw`，只处理 `com.google.android.libraries.inputmethod.widgets.SoftKeyView`（这个类名没有被混淆），而且要求它的某个父 View 类名里含有 `Candidate`/`Suggestion`（比如 `FixedSizeCandidatesHolderView`、`LatinFixedCountCandidatesHolderView`）。候选词文字从子 TextView（或 contentDescription）读取，译词画在按键底部。
- 只叠加绘制：不改候选数据、上屏文本、排序、布局或输入逻辑。每个回调都包在 try/catch 里。
- 词典直接从模块 APK 读取（ZipFile + modulePath），不依赖 `createPackageContext`，后者在 Android 11+ 会受软件包可见性限制。
- 注入提示：Application.onCreate 后弹一次 Toast，键盘首次显示时在底部叠加约 6 秒的“已注入”提示（ViewOverlay）。LSPosed 日志里所有记录都以 `[gloss]` 开头。

## 控制中心
启用开关、注入提示开关、调试模式（在 LSPosed 日志里记录看到的候选词）、目标语言（源语言自动识别）、字号、词典覆盖情况。

## 词典（assets/dicts/<src>_<tgt>.tsv.gz，按 UTF-8 排序，二分查找）
zh↔en：CC-CEDICT（CC BY-SA 4.0）；ja↔en：JMdict（EDRDG，CC BY-SA 4.0）；en↔de/fr/es/ru：FreeDict（GPL）。en→zh、en→ja 是反查生成的。没有直接词典的方向经英语中转。暂不支持韩语（没有找到可用的免费数据）。
数据构建脚本：`tools/build_dicts.py`。

## 构建
`./gradlew assembleRelease`（需要 JDK 17，在 local.properties 里设置 sdk.dir）。
