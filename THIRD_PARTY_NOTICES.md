# Third-party notices / 第三方数据声明

GboardGloss bundles offline dictionaries derived from the projects below (`app/src/main/assets/dicts/`, built by `tools/build_dicts.py`).
GboardGloss 内置的离线词典来自以下项目，生成脚本见 `tools/build_dicts.py`。

| Data / 数据 | Used for / 用途 | License / 许可 | Source / 来源 |
|---|---|---|---|
| CC-CEDICT (MDBG) | zh ↔ en | CC BY-SA 4.0 | https://www.mdbg.net/chinese/dictionary?page=cc-cedict |
| jieba dict.txt (fxsjy/jieba) | zh word frequencies (segmentation cost only) | MIT | https://github.com/fxsjy/jieba |
| JMdict (Electronic Dictionary Research and Development Group, EDRDG) | ja ↔ en | CC BY-SA 4.0 | https://www.edrdg.org/jmdict/j_jmdict.html — license: https://www.edrdg.org/edrdg/licence.html |
| FreeDict | de/fr/es/ru ↔ en | GPL (per dictionary; see each FreeDict TEI header) | https://freedict.org/ |

The converted TSV files are derivative works and are distributed under the same terms as their sources (CC BY-SA 4.0 for CC-CEDICT/JMdict data, GPL for FreeDict data). The program code is licensed GPL-3.0-or-later (see `LICENSE`).

转换后的词典文件属于衍生作品，按原许可分发：CC-CEDICT 与 JMdict 数据为 CC BY-SA 4.0，FreeDict 数据为 GPL。程序代码采用 GPL-3.0-or-later，见 `LICENSE`。

This project is not affiliated with Google. Gboard is a trademark of Google LLC.
本项目与 Google 无关，Gboard 是 Google LLC 的商标。

## Optional online fill-in / 可选在线补全

When the user enables “online fill-in” in Settings, GboardGloss may query the [MyMemory Translation API](https://mymemory.translated.net/) for offline misses. That feature is **off by default**. No API key is embedded; usage is subject to MyMemory’s own terms and rate limits. Results are cached in memory only for the process lifetime.

用户在设置中开启「在线补全」后，离线未命中时可能请求 MyMemory 翻译接口。该功能**默认关闭**。未内置 API 密钥，须遵守 MyMemory 服务条款与频率限制。结果仅缓存在进程内存中。

## Google ML Kit Translation (on-device NMT) / 端侧翻译
GboardGloss links the Google ML Kit on-device Translation library (`com.google.mlkit:translate`) and downloads its language models on request. ML Kit is provided by Google under the [ML Kit Terms of Service](https://developers.google.com/ml-kit/terms); it is not covered by this project's GPL. Translation runs on the device; input text is not sent to servers by this feature.

本项目链接 Google ML Kit 端侧翻译库，并在用户请求时下载语言模型。ML Kit 受 Google [ML Kit 服务条款](https://developers.google.com/ml-kit/terms) 约束，不属于本项目 GPL 授权范围。翻译在本机完成，此功能不会将输入内容发送到服务器。
