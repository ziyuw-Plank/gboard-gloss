# GboardGloss — LSPosed 模块：Gboard 候选词译词

在 Gboard 候选词下方叠加显示目标语言译词（灵感来自青简 qingjian）。只作用于 `com.google.android.inputmethod.latin`。

- 仅叠加绘制：在候选 TextView 自身 onDraw 之后画一行小字，不改候选对象、上屏文本、排序或任何输入逻辑；所有异常都会被吞掉。
- 控制中心（Material 3）：启用开关、目标语言选择（Chip）、译词字号滑块、词典状态、模块激活状态。
- 离线词典可插拔：`app/src/main/assets/dict_<lang>.tsv`（每行 `词\t译词`）。内置 zh→en（CC-CEDICT，CC BY-SA 4.0）。
- 适配版本：Gboard 18.4.1.985164140 arm64（只做过静态分析，未在真机测试）。

构建：`./gradlew assembleRelease`（需要 JDK 17，并在 local.properties 里设置 sdk.dir）。
