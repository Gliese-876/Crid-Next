# Crid Next

[简体中文](README.md) · [繁體中文](README.zh-Hant.md) · [English](README.en.md)

**把每一周，安排得刚刚好。**

Crid Next 是一款轻巧的原生 Android 课表应用。它把今天的行程和整周的课程整理得清楚易读，也认真保留单双周、分段授课、不同教师和教室这些真实课表里的细节。从开学时导入课表，到期末整理备份，清晰的视图与连贯的交互陪你用完整个学期。

**Android 12 及以上 · 简体中文 / 繁體中文 / English · 首个公开版本 2.0.0**

[![GitHub 下载](https://img.shields.io/badge/GitHub-%E4%B8%8B%E8%BD%BD-181717?style=for-the-badge&logo=github&logoColor=white)](https://github.com/Gliese-876/Crid-Next/releases/latest)
[![Gitee 下载](https://img.shields.io/badge/Gitee-%E4%B8%8B%E8%BD%BD-C71D23?style=for-the-badge&logo=gitee&logoColor=white)](https://gitee.com/gliese-876/crid-next/releases)
[![GitHub 源码](https://img.shields.io/badge/GitHub-%E6%BA%90%E7%A0%81-181717?style=for-the-badge&logo=github&logoColor=white)](https://github.com/Gliese-876/Crid-Next)
[![Gitee 源码](https://img.shields.io/badge/Gitee-%E6%BA%90%E7%A0%81-C71D23?style=for-the-badge&logo=gitee&logoColor=white)](https://gitee.com/gliese-876/crid-next)

[更新日志](CHANGELOG.md)

## 为真实的校园日常而做

### 看着清爽，用着顺手

每门课都有柔和、稳定的配色，换个视图仍容易认出。清楚的文字层级和适度留白，让课名、时间与地点各有位置。浅色与深色主题保持统一的视觉风格，空闲时的小插画也让课表多一点轻松。

横滑切页时，页面与底栏指示一起跟随手指；菜单和弹窗从点击的位置展开，再沿原路收回。课程详情可以拖动展开或下拉关闭，让查看、切换和返回自然衔接。

### 下一节去哪，整周怎么排，都看得清

今天页把正在进行和接下来的课程放在眼前，周课表帮你把握整周节奏。今日与周课表小组件把时间、课名和地点带到桌面，上课前提醒则让你少一件需要惦记的事。

手机上随手翻看，平板上舒展浏览；小组件也会随大小调整布局和信息量。放大系统字号时，界面与小组件为文字留出更多空间，让常用信息依然好读。

### 课表里的复杂安排，值得认真保留

单双周、分段周次、轮换的教师与教室，都按实际授课安排保留；明确待排的课程也能收进课表，方便后续补全。导入时先展示预览、缺项与时间冲突，再由你确认保存，让课表更新更有把握。

多学期与多方案让你保留不同阶段的安排；自定作息、课程编辑和节假日调休设置，则让课表贴合学校的实际节奏。

### 轻巧装进手机，课表也方便带走

2.0.0 的通用 APK 约 **4.05 MB**。课表保存在设备本地，日常查看和编辑可离线完成，校园里信号不稳时也能随时查课。

想分享时，PNG 与 PDF 延续应用中的日程和课表样式，可按日、周、月或整个学期导出；想留好数据时，JSON 保存课程与原始授课记录，方便备份和重新导入。

## 安装

在 [GitHub 发行页](https://github.com/Gliese-876/Crid-Next/releases/latest)或 [Gitee 发行页](https://gitee.com/gliese-876/crid-next/releases)下载 `.apk` 文件，打开后按系统提示安装。首次通过浏览器或文件管理器安装时，需要允许该应用安装未知来源的应用。APK 支持 ARM64、ARM32、x86 和 x86_64 设备。

## 第一次使用

1. 打开「方案」，新建学期，确认学期起止日期、周数和每日作息。按节次导入的课程会使用这里的上课时间。
2. 点击「导入课表」。可以选择已有文件，也可以进入北京师范大学北京或珠海校区教务系统，登录并下载课表。内置解析支持这些教务系统的列表与网格课表导出，以及 Crid Next JSON 文件。
3. 在预览中核对课程和目标学期，选择「新建方案」并确认导入。已有方案也可以合并或替换；想手动录入时，先新建空白方案，再添加课程。
4. 回到「今天」或「课表」查看安排。点击课程可编辑；在「更多 → 管理课程」中可以找到全部课程，包括待排课程。在「方案」中切换当前课表。

珠海教务系统的学期选项若没有加载，可以反复切换「按列表显示」「按课表显示」，再选择学期导出。下载不能直接完成时，先用系统浏览器保存文件，再从本地导入。

## 让 AI 帮你转换其他课表

内置解析暂不支持你的文件时，可以请能读取该文件的 AI 将它转成 Crid Next JSON。表格、PDF 和图片是否可读，取决于你选择的 AI。

1. 下载仓库中的 [JSON 课表示例](examples/timetable.json)。示例中的课程、教师和地点均为虚构。
2. 将**下面的提示词、你的课表原文件和 JSON 示例**一起发送给 AI。发送前可遮去姓名、学号等与排课无关的信息。
3. 回答 AI 对缺失信息的提问，保存它生成的 `timetable.json`，然后在 Crid Next 中选择「导入课表 → 从本地文件导入」。核对周次、星期、时间、教师和地点后再保存。

<details>
<summary>展开并复制 AI 转换提示词</summary>

```text
请把我提供的课表原文件转换成可导入 Crid Next 的 JSON。另附的 timetable.json 仅用于说明格式，不要把示例课程混入我的课表。

先读取原文件，保留每门课程的全部授课安排、单双周、分段周次，以及每次安排对应的教师、地点和备注。遇到无法辨认、含义不明或缺少的必要排课信息，先向我提问，不要猜测或静默丢弃课程。原文未提供教师、地点、学分时使用空字符串。

严格使用以下结构与规则：
1. 顶层只有 format、version、name、courses：format 为 "crid-next"，version 为数字 1，name 为非空方案名称，courses 为非空课程数组。应用版本 2.0.0 不改变这个文件版本号。
2. 每门课程使用 name、credits、extra、lessons。name 为非空课程名；credits 为字符串；extra 为键和值均是字符串的对象，可保存课程代码等附加信息；lessons 为非空授课安排数组。省略 id 和 color，让应用生成标识并选择颜色。教师和地点属于每条 lesson，不能放在课程顶层。
3. 每条 lesson 只使用 weeks、weekday、startPeriod、endPeriod、date、startTime、endTime、location、teacher、note、unscheduled。location、teacher、note 为字符串，unscheduled 为布尔值。
4. 按周重复时，weeks 是实际授课周次的正整数数组，展开区间和单双周，去重并升序排列；date 为 null。按具体日期上课时，date 使用 "YYYY-MM-DD"，weeks 使用 []。weekday 为星期一=1 至星期日=7，并与具体日期一致，不受界面一周起始日设置影响。必要时先询问学期与周次的对应关系。
5. 已排课的 unscheduled 为 false。时间表达选择一种：按节次时，startPeriod 和 endPeriod 为正整数且结束节次不小于开始节次，startTime/endTime 为 null；按时刻时，startTime/endTime 为 24 小时制 "HH:mm" 且开始早于结束，startPeriod/endPeriod 为 null。不连续的节次或不同的时间、教师、地点分为不同安排，不要填满中间的空档。
6. 只有原文件明确标注待排时间且给出授课周次时，才使用 unscheduled:true、weekday:0，并保留 weeks；date、startPeriod、endPeriod、startTime、endTime 全部为 null。不要用待排标记掩盖识别失败或缺失信息。
7. 不添加学期对象、应用设置、账号、学号、密码、密钥或其他未列出的字段；extra 也不保存个人身份或凭据。每个方案最多 500 门课程、3000 条授课安排，每条安排最多 366 个周次。

输出前逐项核对原文件，确保课程及授课安排没有遗漏、周次没有扩大、教师与地点没有错配。所有必要信息明确后，只输出 UTF-8 编码的 timetable.json 文件内容或提供该文件，不加 Markdown 代码围栏或说明文字。
```

</details>

JSON 文件只保存一个课表方案及其课程。学期日期、作息和个人设置需要在应用中配置；若要试用示例，请创建至少 16 周、包含第 1–4 节作息的学期。

## 提醒与数据

在「设置」中开启上课前提醒，并在「提醒设置」中检查通知、准时提醒和后台权限；需要时开启「闹钟级提醒」。部分国产手机还需要允许自启动、后台运行并解除电池限制。提醒送达受系统和厂商后台策略影响，强行停止应用也会影响提醒；换机或更改系统设置后，建议用一节测试课程确认。[提醒设置与送达说明](docs/提醒送达设计.md)

课表保存在设备本地，文件导入在本地完成。使用上述 AI 转换时，文件会交给你选择的 AI 服务。卸载前请导出 JSON 备份；PNG / PDF 用于查看和分享，恢复课程请使用 JSON。

## 从 Crid，到 Crid Next

Crid Next 延续了 [Crid](https://github.com/Gliese-876/Crid) 的课表应用方向，由同一作者使用 Kotlin、Jetpack Compose 和 Material 3 从零重写为原生 Android 应用。原 Crid 基于 Flutter，保留 Apache-2.0 许可并已归档，供查阅历史实现；后续开发在 Crid Next 继续。

两个项目使用独立的数据格式。从原 Crid 转用 Crid Next 时，请重新导入教务课表，或按上述方法转换为 Crid Next JSON，并重新设置学期和作息。

这也是一次使用前沿模型进行 **vibe coding** 的实践：从课表里的真实需求出发，通过自然语言协作推进实现、界面打磨和测试。项目将这些尝试落实为可以下载使用、阅读源码和继续改进的应用，也欢迎更多人把自己的校园使用经验带进来。

## 反馈与贡献

欢迎在 [GitHub Issues](https://github.com/Gliese-876/Crid-Next/issues) 反馈问题或提出建议。导入问题请附匿名课表、预期结果和应用版本；界面问题可附设备型号及截图。提交文件前请移除真实姓名、学号和隐藏元数据。[公开测试样例说明](tests/README.md)介绍了现有样例和检查方式。

作者：[![Gliese-876](https://img.shields.io/badge/Gliese--876-181717?style=flat-square&logo=github&logoColor=white)](https://github.com/Gliese-876)

特别鸣谢：

- [![方缘（Fangyuanz06）](https://img.shields.io/badge/%E6%96%B9%E7%BC%98%EF%BC%88Fangyuanz06%EF%BC%89-181717?style=flat-square&logo=github&logoColor=white)](https://github.com/Fangyuanz06)：协助测试和改进。
- [![ChiHuchen](https://img.shields.io/badge/ChiHuchen-181717?style=flat-square&logo=github&logoColor=white)](https://github.com/ChiHuchen)：提供北京校区课表数据。

也感谢参与反馈与贡献的每个人。

## 开发者：构建与测试

准备 JDK 21、Android SDK 37 和 Build Tools 37，用 Android Studio 打开仓库根目录。首次构建需要下载 Gradle 和依赖。Windows PowerShell 可运行：

```powershell
./tools/build.ps1 -Task ':core:test', ':app:testDebugUnitTest', ':app:assembleDebug'
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

其他系统可使用 Gradle Wrapper：

```sh
sh ./gradlew :core:test :app:testDebugUnitTest :app:assembleDebug
```

- `core/`：课表模型、导入解析、数据校验与日程计算。
- `app/`：Android 界面、存储、提醒、小组件与导出。
- `examples/timetable.json`：可导入的虚构课表示例。
- `tests/`：公开匿名样例及逐字段解析预期；`tests/private/` 被 Git 忽略。
- `docs/`：设计与验证记录。

正式构建与签名见[发布流程](docs/发布流程.md)，实现说明见[技术方案](docs/技术方案.md)和[解析验证](docs/解析验证.md)。JSON 格式以 [PlanCodec](core/src/main/kotlin/cn/crid/next/core/PlanCodec.kt)、[数据模型](core/src/main/kotlin/cn/crid/next/core/Models.kt)及[校验规则](core/src/main/kotlin/cn/crid/next/core/DataValidator.kt)为准。

## 许可

除非另有声明，Crid Next 有权许可的原创源代码、文档与原创资源以 [MIT License](LICENSE) 开源，可按其条款使用、修改及分发。第三方组件保留各自许可，品牌图标和头像另有权利说明；这些内容不因随应用发布而改为 MIT。具体适用范围见[许可说明](LICENSING.md)，第三方许可全文也可在应用的「设置 → 关于」中离线阅读。
