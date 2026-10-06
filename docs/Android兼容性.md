# Android 兼容性

Crid Next 2.0.0 的最低运行版本为 **Android 12（API 31）**，`compileSdk` 与 `targetSdk` 均为 37。P3 按显示能力自动启用；HDR 控件已在 1.11 移除。下文将当前兼容条件与 1.9 历史审计分开记录。

## 决定最低版本的调用

原生 `RemoteViews(Map)` 和 `RemoteCollectionItems` 均从 API 31 提供。应用用它们按启动器给出的尺寸选择布局，并直接提供课程集合。保留这套实现，可在小幅修改范围内将运行下限设为 API 31。[RemoteViews 官方参考](https://developer.android.com/reference/android/widget/RemoteViews)

当前仅按硬件加速、配置和显示器广色域能力选择普通窗口或 Display P3。HDR 开关、持久化属性、动态范围检测、监听和窗口请求已经删除。原生小组件仍决定应用的 API 31 运行下限。

| 生产特性与调用 | 引入 API | 当前使用方式 |
| --- | ---: | --- |
| 预测返回开始及进度回调 | 34 | 使用 AndroidX `OnBackPressedCallback` / `BackEventCompat`，由兼容库接入平台 |
| `Intent.getParcelableExtra(String, Class)` | 33 | 分享文件入口改用 AndroidX `IntentCompat` |
| `Bundle.getParcelableArrayList(String, Class)` | 33 | 小组件尺寸读取改用 AndroidX `BundleCompat` |
| `InputStream.readNBytes(int)` / `nullInputStream()` | 33 | 改用有界读取 helper 和空 `ByteArrayInputStream`，保持输入大小限制 |
| 通知运行时权限、启动器 monochrome 图层 | 33 | 仅 API 33+ 检查和请求 POST 权限；较早启动器使用彩色自适应图标 |
| `AlarmManager.canScheduleExactAlarms()` | 31 | 直接检查授权；未授权时回退允许闲置期间执行的非精确提醒 |
| `RemoteViews(Map)`、`RemoteCollectionItems`、`setColorStateList` | **31** | 自适应小组件布局与集合直接调用，构成当前原生实现的版本边界 |
| `Canvas.quickReject(float, float, float, float)` | 30 | 导出绘制裁剪，直接调用 |
| 长色值 `LinearGradient`、导航栏对比控制、`NumberPicker.setTextColor` | 29 | 原生绘制与选择器直接调用 |
| `ActivityManager.isBackgroundRestricted()` | 28 | 读取系统明确的后台限制状态 |
| P3 窗口颜色模式、通知通道、`java.time` | 26 | API 31 已提供这些接口；P3 按硬件能力启用 |
| `setExactAndAllowWhileIdle()`、电池优化状态查询 | 23 | 本地课程提醒与系统状态诊断 |

预测返回回调见 [AndroidX Activity 参考](https://developer.android.com/reference/kotlin/androidx/activity/OnBackPressedCallback)。[类型安全 Bundle 读取](https://developer.android.com/reference/android/os/Bundle#getParcelableArrayList(java.lang.String,%20java.lang.Class%3C?%20extends%20T%3E))和 [InputStream.readNBytes](https://developer.android.com/reference/java/io/InputStream#readNBytes(int))均始于 API 33。[RemoteViews 的按尺寸构造方法](https://developer.android.com/reference/android/widget/RemoteViews)与 [精确提醒授权检查](https://developer.android.com/reference/android/app/AlarmManager#canScheduleExactAlarms())始于 API 31。

`IntentCompat` 和 `BundleCompat` 在旧系统上核对 Parcelable 类型，接口本身来自已有的 AndroidX Core 依赖。[IntentCompat 官方参考](https://developer.android.com/reference/androidx/core/content/IntentCompat)、[BundleCompat 官方参考](https://developer.android.com/reference/androidx/core/os/BundleCompat)。`BoundedStreams.readAtMost` 最多读取调用方指定的字节数，处理短读和零字节返回；调用方继续保留课表文件的超限检测。

API 36/37 的新重载与同名类型按完整签名核对。例如，应用使用 `DisplayManager.registerDisplayListener(DisplayListener, Handler)` 两参重载，始于 API 17；未使用 API 36 的新重载。

## 1.9 历史审计方法

初始审计于 2026 年 9 月 28 日检查生产 Kotlin 源码、Manifest 和资源，并将编译后的平台成员引用与本地 SDK 的 `api-versions.xml` 对照。源码审查同时检查调用路径、能力判断及 SDK 版本保护，区分 API 存在条件与设备是否支持 HDR。

当时 AGP 9.1.1 的生产字节码目录是 `app/build/intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes`；core 使用 `core/build/classes/kotlin/main`。旧目录 `app/build/tmp/kotlin-classes/debug` 有历史构建残留，未用于该次结论。

初始快照扫描这两个生产目录中的 439 个 `.class` 文件，从常量池提取 `Methodref`、`InterfaceMethodref` 和 `Fieldref`。引用按所属类、成员名及 JVM 描述符去重，只统计 `android/`、`java/` 和 `javax/`；查询版本表时沿父类及接口解析继承成员，并取类与成员引入版本的较高值。版本表位于本机 SDK 的 `platforms/android-37.0/data/api-versions.xml`。

该快照得到 **774 个唯一平台或 JDK 成员引用**。其中 772 个在版本表中匹配，最高为两个 API 35 的窗口 headroom 方法，没有 API 36/37 成员。其余两个引用是 `LambdaMetafactory.metafactory` 和 `StringConcatFactory.makeConcatWithConstants`，属于编译生成的 lambda 与字符串连接引导，由 Android 构建工具处理。774 项结果用于定位兼容改动；随后加入的版本保护及 API 替换已经改变调用路径，最终最低版本还需结合 lint 与设备执行结果判断。

成员扫描提供静态引用证据，设备测试负责验证实际执行。测试源码与测试 APK 单独评估，其 API 使用不会直接抬高生产应用的 `minSdk`。兼容库内部的版本分支也按库自身支持范围处理。

## 依赖与构建版本

2.0.0 使用 AGP 9.1.1、Kotlin 2.3.0、Compose BOM 2026.09.00、Activity Compose 1.12.1 与 Lifecycle 2.10.0。当前直接依赖以 [app/build.gradle.kts](../app/build.gradle.kts) 和 [core/build.gradle.kts](../core/build.gradle.kts) 为准。WorkManager 和 Browser 已移除；周期维护由系统 JobScheduler 承担，教务页面使用平台 WebView。

### 1.9 历史依赖快照

该次审计从 debug 应用的 Manifest 合并报告读取 64 个依赖 Manifest，包含生产依赖与 debug 工具依赖，声明的运行时 `minSdk` 最高为 **23**。下表仅记录当时依赖，不代表 2.0.0 仍包含全部组件。

| 依赖 | 版本 | 运行时最低 API | AAR 要求的最低 compileSdk |
| --- | --- | ---: | ---: |
| Compose UI / Foundation | 1.12.1 | 23 | 37 |
| Activity / Activity Compose | 1.12.1 | 23 | 36 |
| WorkManager | 2.11.0 | 23 | 35 |
| Lifecycle Runtime / ViewModel Compose | 2.10.0 | 23 | 35 |
| Material 3 | 1.4.0 | 21 | 35 |
| Browser | 1.9.0 | 21 | 36 |

`minSdk` 决定可安装的最低 Android 版本；`compileSdk` 提供编译时接口，`targetSdk` 决定应用采用的系统行为规则。Compose 的 AAR 元数据要求使用 API 37 编译，同时其运行时 Manifest 支持 API 23 起的设备。项目保持 `compileSdk=37` 与 `targetSdk=37`，当前 `minSdk=31` 对应应用自身原生功能的边界。

API 31 已提供所用的 `java.time` 和通知通道接口。构建保持标准 D8 语言处理，未启用 `coreLibraryDesugaring`；小组件继续使用平台集合 API，未引入 `core-remoteviews` 或旧版 `RemoteViewsService`。Java 17 编译目标与 Android 运行库版本分别核对。

## 1.9 历史验证范围

Android 15 模拟器该次累计 **130 项不同方法通过，无失败或跳过**。初始候选版完成 111 项，最低版本调整及最终修复后补测相关路径，按当时测试方法去重。包含真实通知链、原有 13 项导出检查、HDR F16 绘制和输入法开启时的两种页面切换。本地记录：`artifacts/qa-v1.9/api35/COMPATIBILITY.json`。

API 31 已完成 **113 项设备检查，全部通过，无跳过**。覆盖全部课表样例的解析与持久化、原生小组件，以及 7 项通知调度检查；真实 AlarmManager 到 Manifest receiver 再到通知的投递链已通过。该组还包含 5 项导出检查和 6 项完全本地的 WebView 下载捕获检查。键盘弹出时点击导航和直接横滑均取得同一原失败测试 APK 的修复前后对照，目标页稳定停留、草稿保留和课表表头手势回归通过。

两个版本的最终测试均已恢复设备设置与授权，最终应用崩溃缓冲为零。API 31 首轮同时运行 UI 自动化诊断曾导致测试工具的 UiAutomation 冲突，诊断日志单独保留，未计为应用崩溃或通过项。最低版本详情保存在本地 `artifacts/qa-v1.9/api31/COMPATIBILITY.json`，跨设备去重和测试范围在 `artifacts/qa-v1.9/QA-SUMMARY.json`。

上述 HDR 测试属于 1.9 历史记录，该功能及专属测试已在 1.11 删除，移除验证见 [1.11 迭代记录](迭代验证-1.11.md)。当前发行范围见 [2.0.0 发行说明](releases/v2.0.0.md)。历史 `artifacts/` 路径指开发时的本地记录，不随公开源码提供。P3 物理色域、厂商后台管理策略和不同启动器行为仍需对应设备验证。
