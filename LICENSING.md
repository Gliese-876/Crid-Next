# 许可适用范围 / Licensing scope

Crid Next 有权许可的原创源代码、文档与原创资源采用 MIT 许可。仓库和安装包还包含第三方代码、平台标志及头像；这些内容分别适用其许可或其他权利规则。本文件说明各项授权的适用对象，具体权利义务以对应许可正文为准。

## 原创源代码、文档与资源

除具体文件、目录或本说明另有声明外，Crid Next 贡献者有权授权的原创部分适用根目录的 [MIT License](LICENSE)，包括原创源代码、文档、构建脚本、测试代码、配套 JSON 示例，以及自行创作的图标、插画等原创资源。下文列出的第三方品牌图标和头像适用各自的权利说明。

MIT 允许使用、复制、修改、合并、发布、分发、再许可及销售相应软件及配套文档，包括商业使用。分发软件的副本或实质部分时，应保留其版权声明与许可声明；无担保及责任限制适用许可正文。这里的授权范围不包括下列另有权利说明的第三方内容。[MIT 原文（Open Source Initiative）](https://opensource.org/license/mit)

根目录 [LICENSE](LICENSE) 保持标准 MIT 文本。本文件不改写该许可，也不变更任何第三方已经授予的权利。

## 第三方代码

| 内容 | 适用许可 | 对应材料 |
| --- | --- | --- |
| Kotlin、kotlinx 库、AndroidX / Jetpack Compose / Material、Guava ListenableFuture、JSpecify、JetBrains annotations | Apache-2.0 | [许可全文](third_party/licenses/Apache-2.0.txt)与[第三方归属索引](THIRD_PARTY_NOTICES.txt) |
| jsoup 1.18.3 | MIT，由其原权利人授权 | [jsoup MIT 全文与版权声明](third_party/licenses/jsoup-MIT.txt) |
| JExcelAPI 2.6.12（JXL） | LGPL-2.1-or-later | [许可全文](third_party/licenses/LGPL-2.1-or-later.txt)、[对应源码](third_party/README.md)与[重建说明](docs/REBUILDING.md) |

这些开源代码可按各自许可使用、修改和分发。再次分发包含它们的源代码或安装包时，须同时履行相应义务：例如保留所需版权与许可声明，Apache-2.0 要求的修改标记及适用 NOTICE，以及 LGPL 要求的对应源码与修改后重新链接条件。MIT 不替代或取消这些义务。[Apache-2.0 第 4 条](https://www.apache.org/licenses/LICENSE-2.0#redistribution)；[LGPL 2.1 第 4、6 条](https://www.gnu.org/licenses/old-licenses/lgpl-2.1.html)

JXL 对应源码随本仓库和 Release 提供。使用者可以修改或替换 JXL，重新构建应用，并为调试这些修改进行逆向工程。分发包含 JXL 的 APK / AAB 时，本项目采用 LGPL 2.1 第 6(a)、6(d) 条的源码与重新链接材料提供方式，提供匹配的应用源码、JXL 源码、构建说明和许可文本。详见[重建说明](docs/REBUILDING.md)。

APK / AAB 是由上述不同来源内容组成的分发物，不能将其中全部内容统称为仅适用 MIT。原创应用代码的 MIT 授权和第三方组件的许可同时保留；图片等素材的再分发范围还应按下文核对。

## 平台图标、名称与商标

应用内 GitHub、Gitee 图标的路径数据来自指定版本的 Simple Icons，按其 [CC0 1.0 Universal](third_party/licenses/CC0-1.0.txt) 声明提供；Android VectorDrawable 是这些路径的格式转换。来源、版本和 README 托管徽章的说明见[素材归属说明](docs/assets/ATTRIBUTION.md)。

CC0 处理的是声明者有权放弃或许可的版权及相关权利。其第 4(a) 条明确排除商标权和专利权，声明者也不负责为其他权利人清理权利。因此，图标路径的 CC0 声明不等于 GitHub、Gitee 的名称或商标可被任意使用，也不表示这些平台认可或赞助本项目；具体使用仍须有相应权利依据。[CC0 原文第 2–4 条](https://creativecommons.org/publicdomain/zero/1.0/legalcode.en)

## 头像及人物相关权利

仓库和应用中的 GitHub 头像用于识别作者及致谢对象，具体文件与获取来源列于[素材归属说明](docs/assets/ATTRIBUTION.md)。头像的著作权及可能涉及的肖像、隐私等权利归相应权利人；账户使用某张头像，并不能证明账户持有人拥有底层作品的著作权。

头像可以公开访问，也不构成 MIT、CC0 或其他通用再许可授权。本项目未将这些头像纳入自己的 MIT 授权，现有来源记录亦未提供头像权利人的额外开放许可。复制、修改或再次分发头像，应依据权利人的有效授权或适用法律允许的使用范围，不能仅依据本仓库的 MIT 许可。GitHub 的服务条款也区分用户提供的内容与他人创作的底层内容。[GitHub 服务条款 A、D 部分](https://docs.github.com/en/site-policy/github-terms/github-terms-of-service)

## 原 Crid 项目

原 Flutter 版 [Crid](https://github.com/Gliese-876/Crid) 与 Crid Next 是独立代码库。原项目已有的 Apache-2.0 授权及其第三方许可保持不变；原项目归档和 Crid Next 采用 MIT 均不改变这些授权。需要复用原项目代码时，应依原项目的许可处理。

## English summary

Except where a file, directory or this document states otherwise, Crid Next's
original source code, documentation and original assets, to the extent its
contributors have the right to license them, are provided under the standard
[MIT License](LICENSE). This includes original build scripts, tests, the
accompanying JSON example, and original icons and illustrations. Third-party
brand icons and avatars remain subject to the separate terms described here.
MIT permits commercial use and redistribution subject to preserving the required
copyright and permission notices.

Third-party code retains its own license: Apache-2.0 for the components identified
above, MIT for jsoup, and LGPL-2.1-or-later for JExcelAPI. Redistributing an APK or
AAB must satisfy the applicable terms for its included components. The package
as a whole must not be represented as exclusively MIT-licensed. Corresponding
JExcelAPI source and instructions for replacing it and rebuilding the app are
provided in [third_party](third_party/README.md) and [REBUILDING](docs/REBUILDING.md).

Simple Icons' CC0 dedication covers the icon paths to the extent the affirmer can
grant those rights; it does not grant trademark rights. GitHub profile avatars
are excluded from this project's MIT grant. Public accessibility and account use
do not establish copyright ownership or an open license for the underlying
artwork. Avatar reuse needs an appropriate authorization or other legal basis.
The original Flutter Crid keeps its existing Apache-2.0 license.

The applicable license texts govern their respective materials; this document
does not alter those texts.
