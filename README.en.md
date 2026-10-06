[简体中文](README.md) · [繁體中文](README.zh-Hant.md) · [English](README.en.md)

# Crid Next

Crid Next is an Android timetable app for checking today's classes, managing semesters and timetable plans, and getting reminders before class.

**Android 12 or later · 简体中文 / 繁體中文 / English · First public release: 2.0.0**

[<img src="docs/assets/github.svg" width="18" height="18" alt=""> Download from GitHub](https://github.com/Gliese-876/Crid-Next/releases/latest) · [<img src="docs/assets/gitee.svg" width="18" height="18" alt=""> Download from Gitee](https://gitee.com/gliese-876/crid-next/releases) · [<img src="docs/assets/github.svg" width="18" height="18" alt=""> GitHub source](https://github.com/Gliese-876/Crid-Next) · [<img src="docs/assets/gitee.svg" width="18" height="18" alt=""> Gitee source](https://gitee.com/gliese-876/crid-next) · [Changelog](CHANGELOG.md)

## Install

Download the `.apk` file from the [<img src="docs/assets/github.svg" width="18" height="18" alt=""> GitHub releases](https://github.com/Gliese-876/Crid-Next/releases/latest) or [<img src="docs/assets/gitee.svg" width="18" height="18" alt=""> Gitee releases](https://gitee.com/gliese-876/crid-next/releases), open it, and follow the installation prompts. When installing through a browser or file manager for the first time, allow that app to install apps from unknown sources. The APK supports ARM64, ARM32, x86, and x86_64 devices.

## What you can do

- **See today and the whole week.** Today shows your daily schedule and next class; the weekly timetable gives you a broader view.
- **Keep different timetables.** Save multiple plans for each semester, add courses manually, and edit teachers, locations, and class times.
- **Review before importing.** Import from a campus portal or a local file, check missing details and time conflicts, then create, merge, or replace a plan.
- **Check classes from your home screen.** Add a today or weekly widget, and turn on reminders before class.
- **Share and back up.** Export a day, week, month, or semester as PNG / PDF, or save a JSON timetable you can import again.
- **Make it your own.** Choose light or dark themes, three interface languages, phone or tablet layouts, and holiday and makeup-class settings.

## Get started

1. Open **Plans**, create a term, and check its dates, week count, and daily class periods. Courses imported by period number use the times you set here.
2. Tap **Import**. Choose a saved file, or open the Beijing Normal University Beijing or Zhuhai campus portal, sign in, and download your timetable. The built-in parser supports list and grid timetable exports from these portals, as well as Crid Next JSON files.
3. Review the courses and target semester, select **New plan**, and confirm the import. You can also merge with or replace an existing plan. To enter courses manually, create an empty plan first.
4. Open **Today** or **Timetable** to see your classes. Tap a course to edit it. **More → Manage courses** includes all courses, including those awaiting a class time. Switch the active timetable in **Plans**.

If the Zhuhai portal does not load its semester options, switch repeatedly between **按列表显示** (list view) and **按课表显示** (timetable view), then select the semester and export. If a download does not complete in the app, save the file with your system browser and import it from local storage.

## Use AI to convert another timetable format

If the built-in parser does not support your file, an AI that can read it can help convert it to Crid Next JSON. Support for spreadsheets, PDFs, and images depends on the AI you choose.

1. Download the [example JSON timetable](examples/timetable.json). Its courses, teachers, and locations are fictional.
2. Send **the prompt below, your original timetable, and the JSON example** to the AI together. You can remove names, student IDs, and other details unrelated to scheduling before sending the file.
3. Answer any questions about missing information, save the generated `timetable.json`, and choose **Import → From a local file** in Crid Next. Check weeks, weekdays, times, teachers, and locations before saving.

<details>
<summary>Expand and copy the AI conversion prompt</summary>

```text
Convert my original timetable file into JSON that Crid Next can import. The attached timetable.json is a format example only. Do not include its sample courses in my timetable.

Read the original file and preserve every course and teaching arrangement, including odd/even weeks, separate week ranges, and the teacher, location, and notes associated with each arrangement. Ask me about unreadable, ambiguous, or missing essential scheduling information before proceeding. Do not guess or silently omit courses. Use empty strings for teachers, locations, and credits that the source does not provide.

Follow this structure and these rules exactly:
1. The root contains only format, version, name, and courses. format is "crid-next"; version is the number 1; name is a nonblank plan name; courses is a nonempty array. App version 2.0.0 does not change this file format version.
2. Each course uses name, credits, extra, and lessons. name is a nonblank course name; credits is a string; extra is an object with string keys and string values for details such as a course code; lessons is a nonempty array of teaching arrangements. Omit id and color so the app generates identifiers and chooses colors. Teachers and locations belong to individual lessons, not to the course object.
3. Each lesson uses only weeks, weekday, startPeriod, endPeriod, date, startTime, endTime, location, teacher, note, and unscheduled. location, teacher, and note are strings; unscheduled is a boolean.
4. For recurring classes, weeks is an ascending array of unique positive integers listing the actual teaching weeks. Expand ranges and odd/even weeks; set date to null. For a class on a specific date, use "YYYY-MM-DD" for date and [] for weeks. weekday is Monday=1 through Sunday=7 and must match any specific date, regardless of the app's first-day-of-week setting. Ask how weeks map to the semester if needed.
5. For scheduled classes, unscheduled is false. Use one time representation: for numbered periods, startPeriod and endPeriod are positive integers, endPeriod is at least startPeriod, and startTime/endTime are null; for clock times, startTime/endTime use 24-hour "HH:mm", the start is before the end, and startPeriod/endPeriod are null. Split nonconsecutive periods and arrangements with different times, teachers, or locations into separate lessons. Do not fill gaps between classes.
6. Use unscheduled:true and weekday:0 only when the source explicitly leaves the class time unassigned and provides teaching weeks. Keep weeks, and set date, startPeriod, endPeriod, startTime, and endTime to null. Do not use this flag to hide recognition failures or missing information.
7. Do not add a semester object, app settings, account details, student IDs, passwords, keys, or unlisted fields. Do not store personal identifiers or credentials in extra. A plan can contain at most 500 courses and 3000 lessons, with at most 366 week entries per lesson.

Check the result against the original file before output: no courses or lessons omitted, no extra teaching weeks, and no mismatched teachers or locations. Once all essential information is clear, output only the UTF-8 contents of timetable.json or provide that file, without Markdown fences or explanatory text.
```

</details>

A JSON file stores one timetable plan and its courses. Set semester dates, daily periods, and personal preferences in the app. To try the example, create a semester with at least 16 weeks and daily periods 1–4.

## Reminders and your data

Turn on reminders in **Settings**, then check notification, exact-alarm, and background permissions in **Reminder settings**. Enable **Alarm-clock reminders** if needed. Some phones, particularly models with strict manufacturer background controls, also require permission to auto-start and run in the background, plus an exemption from battery restrictions. Delivery depends on these system policies; force-stopping the app can also affect reminders. Test a reminder after changing phones or system settings. [Reminder delivery details](docs/提醒送达设计.md)

Timetables are stored on your device, and file imports are processed locally. When using the AI conversion workflow above, you send the file to the AI service you choose. Export a JSON backup before uninstalling. PNG / PDF files are for viewing and sharing; use JSON to restore courses.

## How Crid Next relates to Crid

Crid Next continues the timetable app idea behind [Crid](https://github.com/Gliese-876/Crid). The same author rewrote it from scratch as a native Android app using Kotlin, Jetpack Compose, and Material 3. The original Flutter-based Crid retains its Apache-2.0 license and is archived for reference. Development continues in Crid Next.

The projects use separate data formats. When moving from the original Crid, import your campus timetable again or convert it to Crid Next JSON using the workflow above, then set up the semester and daily periods.

This project is also an experiment in **vibe coding with frontier models**: using natural-language collaboration to develop requirements, implement features, refine the interface, and test the app. The code is public for others to use, inspect, and improve.

## Feedback and contributions

Report problems or suggest improvements through [<img src="docs/assets/github.svg" width="18" height="18" alt=""> GitHub Issues](https://github.com/Gliese-876/Crid-Next/issues). For import problems, include an anonymized timetable, the expected result, and the app version. For interface problems, include your device model and a screenshot when useful. Remove real names, student IDs, and hidden metadata before submitting files. The [public fixture guide](tests/README.md) describes the existing examples and checks.

Author: [<img src="docs/assets/avatar-gliese-876.png" width="40" height="40" alt=""> Gliese-876](https://github.com/Gliese-876)

Special thanks:

- [<img src="docs/assets/avatar-fangyuanz06.png" width="40" height="40" alt=""> Fangyuanz06 (方缘)](https://github.com/Fangyuanz06) for testing and improvements.
- [<img src="docs/assets/avatar-chihuchen.png" width="40" height="40" alt=""> ChiHuchen](https://github.com/ChiHuchen) for Beijing campus timetable data.

Thanks to everyone who shares feedback or contributes.

## For developers: build and test

Install JDK 21, Android SDK 37, and Build Tools 37, then open the repository root in Android Studio. The first build downloads Gradle and its dependencies. On Windows, run these commands in PowerShell:

```powershell
./tools/build.ps1 -Task ':core:test', ':app:testDebugUnitTest', ':app:assembleDebug'
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

On other systems, use the Gradle Wrapper:

```sh
sh ./gradlew :core:test :app:testDebugUnitTest :app:assembleDebug
```

- `core/`: timetable models, import parsers, validation, and schedule calculations.
- `app/`: Android interface, storage, reminders, widgets, and exports.
- `examples/timetable.json`: an importable fictional timetable.
- `tests/`: public anonymized fixtures and field-by-field expectations; `tests/private/` is ignored by Git.
- `docs/`: design and validation records.

See the [release workflow](docs/发布流程.md) for release builds and signing, and the [technical design](docs/技术方案.md) and [parser validation](docs/解析验证.md) for implementation details. The JSON format is defined by [PlanCodec](core/src/main/kotlin/cn/crid/next/core/PlanCodec.kt), the [data models](core/src/main/kotlin/cn/crid/next/core/Models.kt), and the [validation rules](core/src/main/kotlin/cn/crid/next/core/DataValidator.kt). These supporting documents are currently in Simplified Chinese.

## License

Unless otherwise stated, Crid Next's original source code, documentation and original assets are available under the [MIT License](LICENSE), to the extent the project has the right to license them, for use, modification and redistribution on its terms. Third-party components retain their own licenses; brand icons and avatars have separate rights notices. Bundling them with the app does not relicense them under MIT. See [Licensing scope](LICENSING.md#english-summary); third-party license texts are also available offline in **Settings → About**.
