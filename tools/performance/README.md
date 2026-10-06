# Core computation benchmark

Run from the repository root with PowerShell 7 after a normal project build has populated the Gradle dependency cache:

```powershell
./tools/performance.ps1
```

The script uses the bundled JDK at `tools/local/jdk-21` and cached Kotlin 2.3.0 compiler. It exports the baseline from Git (`d2b0fe3` by default), copies the current sources, and compiles both in separate artifact directories without running Gradle. Nothing is read from `.env`.

For a quick behavioral comparison without timing:

```powershell
./tools/performance.ps1 -VerifyOnly
```

An additional probe compares the single-day public entry point on the dense fixture and its out-of-semester early return:

```powershell
./tools/performance.ps1 -SingleDayProbe -OutputDirectory artifacts/performance-2026-10-02/single-day
```

The single-day probe uses at least 100 warm-up operations and batches of up to 256 operations. The shorter early-return probe uses at least 10,000 warm-up operations and permits batches of up to 1,000,000 operations. `-VerifyOnly` can also be combined with `-SingleDayProbe`.

Four timed workloads use deterministic, validator-approved fixtures:

| Workload | One operation |
| --- | --- |
| Schedule | Resolve all 112 semester dates for 500 courses / 3,000 lessons; current prepares one index per batch, and index construction is included. |
| Display | Project 112 days of occurrences for 120 courses / 720 lessons into display cards; occurrence calculation is excluded. |
| Import | Preview a merge of 180 courses plus duplicate/new arrangements, including conflict detection and warnings. |
| Reminders | Build the complete reminder snapshot with 120 courses and the existing 64-alarm cap. |

Each JVM first checks full output SHA-256 digests (including ordering, representatives, source lessons, warnings, and reminder content). An additional fixed-seed comparison covers 128 randomized card-projection cases. A mismatch aborts the run.

Default timing uses three independent JVM forks per version, eight warm-up operations per workload, and nine measured samples per fork. Each sample batches operations toward 100 ms (1–64 operations), while reported times are per operation. Version ordering alternates across forks. Both versions use `-Xms512m -Xmx1536m -XX:+UseSerialGC`; a volatile result sink prevents dead-code elimination. Close builds and other CPU-heavy tasks during measurement.

Each sample also records the current thread's allocated bytes through HotSpot `ThreadMXBean`. This captures temporary allocation volume per operation, not retained heap or peak memory. Unsupported runtimes report `-1`.

Artifacts include all raw samples, per-fork logs, source hashes, exported source snapshots, compiler arguments, environment details, verified output digests, and median summaries. `-OutputDirectory`, `-BaselineRef`, `-Forks`, `-Warmups`, and `-Samples` can customize a run. Artifact files are ignored by Git; the benchmark and fixtures remain version controlled.

These measurements describe desktop JVM computation on deterministic dense inputs. They do not measure Android frame times, animations, device startup, battery consumption, or end-user latency. Compare release-device traces separately when investigating rendering or device-specific performance.
