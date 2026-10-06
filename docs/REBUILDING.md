# Rebuilding Crid Next

You can build Crid Next from a release's matching source archive, replace its
JExcelAPI library and install your own APK. The application source is under MIT;
JExcelAPI is under LGPL-2.1-or-later. Modification and reverse engineering to debug
changes to that library are permitted. Its source and license are supplied in
[`third_party/`](../third_party/README.md).

## Build the application

Use the source archive attached to the same release as the APK, or check out that
release's Git tag. For 2.0.0, use tag `v2.0.0`. Install:

- JDK 21, with `JAVA_HOME` pointing to it;
- Android SDK Platform 37, SDK Build Tools and Platform Tools, with `ANDROID_HOME`
  pointing to the SDK;
- PowerShell for the Windows helper commands below.

The repository's Gradle wrapper selects Gradle 9.3.1. The build selects Android
Gradle Plugin 9.1.1 and Kotlin 2.3.0. The first build downloads these tools and the
dependencies from their configured repositories.

From the repository root, run:

```powershell
./tools/build.ps1 -Task ':core:test', ':app:testDebugUnitTest', ':app:assembleDebug'
```

This produces `app/build/outputs/apk/debug/app-debug.apk`, signed with your local
Android debug key. For an optimized unsigned release APK, run:

```powershell
./tools/build.ps1 -Task ':core:test', ':app:testReleaseUnitTest', ':app:assembleRelease'
```

Without release signing environment variables, the release output is
`app/build/outputs/apk/release/app-release-unsigned.apk`. You can sign it with your
own Android signing key. The project's `tools/release.ps1` is for the maintainer's
registered release key; the commands above work independently of that key.

On Linux or macOS, use `sh ./gradlew` with the same Gradle task names. Android Studio
can also open the root directory and build the project with JDK 21.

## Rebuild JExcelAPI

These PowerShell commands build JExcelAPI from the supplied source ZIP. They use
the generated lexer already in the upstream source and the default SimpleLogger,
so they need no lexer generator or Log4j dependency. Run them from the repository
root in a fresh checkout:

```powershell
$repo = (Get-Location).Path
$jxlWork = Join-Path $repo 'tools/local/jxl-rebuild'
Expand-Archive -LiteralPath 'third_party/jxl-2.6.12-complete-sources.zip' -DestinationPath $jxlWork
$jxlRoot = Join-Path $jxlWork 'jexcelapi'
$jxlClasses = Join-Path $jxlRoot 'classes'
New-Item -ItemType Directory -Path $jxlClasses | Out-Null

# Edit the library's Java files in $jxlRoot/src before compiling, if desired.
$jxlSources = @(Get-ChildItem -LiteralPath (Join-Path $jxlRoot 'src') -Filter '*.java' -Recurse -File |
    Where-Object { $_.Name -notin @('Log4JLogger.java', 'Log4jLoggerName.java', 'SimpleLoggerName.java') } |
    ForEach-Object { '"' + $_.FullName.Replace('\', '/') + '"' })
$jxlSourceList = Join-Path $jxlRoot 'sources.txt'
[IO.File]::WriteAllLines($jxlSourceList, $jxlSources, [Text.UTF8Encoding]::new($false))
& "$env:JAVA_HOME/bin/javac" --release 8 -encoding ISO-8859-1 -d $jxlClasses "@$jxlSourceList"
if ($LASTEXITCODE -ne 0) { throw 'JExcelAPI compilation failed.' }
& "$env:JAVA_HOME/bin/jar" --create --file (Join-Path $jxlWork 'jxl-custom.jar') `
    -C $jxlClasses . -C (Join-Path $jxlRoot 'resources') .
if ($LASTEXITCODE -ne 0) { throw 'JExcelAPI packaging failed.' }
```

The three excluded files are the optional Log4j backend and alternate templates
for `LoggerName`; the selected `LoggerName.java` and `SimpleLogger.java` are built.
The original Ant scripts and lexer inputs remain in `jexcelapi/build/` for users
who want to work with the upstream build process.

## Relink and install

In `core/build.gradle.kts`, replace the entire JExcelAPI Maven dependency block
(including its `exclude` block) with:

```kotlin
implementation(files(rootProject.file("tools/local/jxl-rebuild/jxl-custom.jar")))
```

Run the application build commands above again. Gradle now compiles and packages
your replacement library into the new APK, including when R8 optimizes a release
build. Keep JExcelAPI's API compatible with the methods used by the app, or update
the app's source to match your changes.

To install your build alongside the official app, change `applicationId` in
`app/build.gradle.kts` to a unique value such as `cn.crid.next.relinked`, then build
and install the debug APK:

```powershell
adb install app/build/outputs/apk/debug/app-debug.apk
```

Android requires an update to use the same signing certificate as the installed
app. A separate application ID lets your independently signed build coexist with
the official version. Use JSON export/import to copy a timetable between them.

## Release source and notices

The release's application source archive includes the Gradle wrapper, build
configuration, app and core sources, resources, tests, third-party source and
license texts. The accompanying complete JExcelAPI ZIP contains its original
sources, build scripts and resources. Release checksums identify the exact files
offered with the APK.

Keep the application source archive, `jxl-2.6.12-complete-sources.zip`, `LICENSE`,
the full `THIRD_PARTY_NOTICES.txt` copied from `app/src/main/assets/licenses/`, and
this document available on the same release page as the binaries. This supplies
the materials for modifying the library and rebuilding the combined application
under [LGPL 2.1 §6(a) and §6(d)](https://www.gnu.org/licenses/old-licenses/lgpl-2.1.html#section6).
See also the GNU project's explanation of [LGPL and Java](https://www.gnu.org/licenses/lgpl-java.html).
