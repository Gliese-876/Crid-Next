# Third-party source and licenses

Crid Next's original code, documentation and original assets are MIT-licensed to
the extent its contributors can grant those rights; see [Licensing scope](../LICENSING.md).
Its dependencies keep their own licenses. JExcelAPI 2.6.12, used to read BIFF spreadsheets, is licensed under
LGPL-2.1-or-later. You may modify or replace it and rebuild Crid Next; see
[Rebuilding Crid Next](../docs/REBUILDING.md).

## JExcelAPI source

- `jxl-2.6.12-sources.jar` is the unmodified [Maven Central source artifact](https://repo.maven.apache.org/maven2/net/sourceforge/jexcelapi/jxl/2.6.12/jxl-2.6.12-sources.jar).
- `jxl-2.6.12-complete-sources.zip` adds the upstream build files and resources
  needed to rebuild the library. It contains all 458 Java source files, seven
  resource files, seven build files and `COPYING.LESSER`.

The complete source archive was assembled from the official
[JExcelAPI 2.6.12 distribution](https://sourceforge.net/projects/jexcelapi/files/jexcelapi/2.6.12/jexcelapi_2_6_12.zip/download).
Every included upstream file is unchanged, and all 458 Java files match the
Maven source artifact byte for byte. The archive preserves the source copyright
notices and includes the LGPL 2.1 text. It omits precompiled JXL, JFlex and JLex
binaries, sample workbooks, generated documentation and editor backup files.
The generated Java lexer and its `.flex`/`.lex` inputs are included; the documented
build uses the generated lexer and needs only a JDK.

| Artifact | SHA-256 |
| --- | --- |
| Maven source JAR | `c541035d7ea51a5fc4834793c64c7d818f03051dc0ff81842f5a46fc2ef43b90` |
| Complete source ZIP | `821c09fd3853f57c39b4a9e4681c201a601d3c3253a227dcf398a7ccfa5f8522` |
| Original upstream distribution ZIP | `f1bc5fd1bcc085c2e83b64d2c906cc12adf9a1290b030088cacbc9ab9f1366a1` |

## Notices and release downloads

`licenses/` contains the Apache 2.0, LGPL 2.1, jsoup 1.18.3 MIT and Simple Icons CC0 texts.
The root [`THIRD_PARTY_NOTICES.txt`](../THIRD_PARTY_NOTICES.txt) is an attribution
index. The [application's copy](../app/src/main/assets/licenses/THIRD_PARTY_NOTICES.txt)
also includes the full license texts and is available offline in the app.

The published `v2.0.0` release on [GitHub](https://github.com/Gliese-876/Crid-Next/releases/tag/v2.0.0)
and [Gitee](https://gitee.com/gliese-876/crid-next/releases/tag/v2.0.0) includes the
matching Crid Next source archive, this complete JExcelAPI source ZIP, the app's
full third-party notices, the MIT license, [its scope](../LICENSING.md) and the
[rebuilding instructions](../docs/REBUILDING.md) alongside the APK. Keep these
materials available with binary downloads on each release host. This uses the
source-and-relinking route in [LGPL 2.1 §6(a) and §6(d)](https://www.gnu.org/licenses/old-licenses/lgpl-2.1.html#section6).
Crid Next's original portions retain their MIT license; JExcelAPI retains LGPL-2.1-or-later.
