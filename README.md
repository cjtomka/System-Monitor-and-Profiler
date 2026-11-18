# System Monitor & Profiler

A real-time system monitor for Windows. A small native **C daemon** (`monitord`) samples CPU, memory, disk, and per-process metrics through the Windows performance-counter API (PDH) and streams them over a **local AF_UNIX socket** to a **JavaFX dashboard** that draws live graphs and tables.

The project is built to be read and demonstrated: every layer is small enough to explain, the protocol is human-readable, and each piece has its own tests.

```
 Windows performance counters (PDH)  +  a few Win32 calls
                         |
                         v
   monitord (C)  ----  AF_UNIX socket, one JSON object per line  ---->  dashboard (Java 17 + JavaFX)
   samples once a second                                                graphs, tables, interval control
   keeps a history ring buffer                                          pre-fills graphs from history
```

## Contents

- [What it does](#what-it-does)
- [Requirements](#requirements)
- [Quick start](#quick-start)
- [The launch script](#the-launch-script)
- [Manual build and run](#manual-build-and-run)
- [Command-line reference](#command-line-reference)
- [Architecture](#architecture)
- [Where the numbers come from](#where-the-numbers-come-from)
- [Wire protocol](#wire-protocol)
- [Testing](#testing)
- [Demo walkthrough](#demo-walkthrough)
- [Design decisions and trade-offs](#design-decisions-and-trade-offs)
- [Limits and known gaps](#limits-and-known-gaps)
- [Troubleshooting](#troubleshooting)
- [Repository layout](#repository-layout)
- [Security notes](#security-notes)
- [Roadmap](#roadmap)

## What it does

**Metrics**

- **CPU**: total load and one value per logical core.
- **Memory**: physical memory in use, and commit charge against the commit limit.
- **Disks**: per fixed drive, capacity and free space, read and write throughput, and queue depth.
- **Processes**: 16 entries chosen by the daemon: the 8 busiest by CPU, then the 8 largest by memory that are not already listed.

**Dashboard**

- Four tabs (CPU, Memory, Disks, Processes), each with live graphs or tables.
- A selectable time window (1 minute, 5 minutes, or the whole buffer), with peak-preserving decimation so short spikes never disappear from a long window.
- The process table updates rows in place by PID, so your selection, sort order, and scroll position survive updates.
- A sample-interval control (100 ms to 5 s) that changes the daemon's sampling rate live.
- A status bar that shows the connection state, the daemon's version and core count, how much history is buffered, and how many snapshots the daemon had to drop for this client.

**Robustness**

- The dashboard reconnects with exponential backoff and shows stale data under a banner instead of going blank.
- On connect, the dashboard asks for the daemon's history, so graphs are full immediately instead of starting empty.
- A slow or stalled client can never slow sampling or other clients: each client has a bounded queue that drops the oldest snapshots first.
- The daemon removes stale socket files after a crash, refuses to start if another instance owns the socket, and shuts down cleanly on Ctrl+C.

## Requirements

| Needed for | Requirement |
|---|---|
| Running anything | Windows 10 version 1803 or later, or Windows Server 2019 or later (AF_UNIX sockets) |
| Building the daemon | CMake 3.20 or newer; a C11 compiler, such as MSVC from Visual Studio 2019 or later with the "Desktop development with C++" workload; Windows SDK 10.0.17134 or newer (for `afunix.h`) |
| Building the dashboard | JDK 17 or newer (Unix-domain sockets in Java need 16 or later); Apache Maven 3.9 or newer. JavaFX comes from Maven, so nothing else is installed separately |
| Running the launch script | Windows PowerShell 5.1 or newer (already part of Windows) |

No network port is opened and no firewall prompt appears. The collectors are designed to work without administrator rights; protected system processes cannot be opened and fall back to a less detailed name.

## Quick start

From a terminal in the repository root:

```powershell
.\run-demo.ps1
```

This builds the daemon and the dashboard, starts the daemon, waits until it reports that it is listening, and opens the dashboard. The daemon's log lines appear in the same terminal. **Close the dashboard window to stop everything**; the daemon shuts itself down.

If PowerShell refuses to run the script (execution policy), use the wrapper instead:

```
run-demo.cmd
```

From Git Bash:

```bash
powershell.exe -ExecutionPolicy Bypass -File ./run-demo.ps1
```

To check that everything works on a new machine, run the whole test suite, including an end-to-end smoke test:

```powershell
.\run-demo.ps1 -Test
```

## The launch script

`run-demo.ps1` is the single entry point. It finds the daemon project (a folder with `CMakeLists.txt`, either at the repository root or in `monitord\`) and the dashboard project (`dashboard\`) automatically.

| Switch | Meaning |
|---|---|
| *(none)* | Build both parts, start the daemon, open the dashboard. Closing the dashboard stops the daemon. |
| `-Build` | Build both parts and stop. |
| `-Test` | Build, then run every test (see [Testing](#testing)). Exits with code 1 if any check fails. |
| `-NoBuild` | Skip the build and use what is already built. |
| `-DaemonOnly` | Start only the daemon and keep it in the foreground until Ctrl+C. Useful with `monctl` or a separately started dashboard. |
| `-Config Debug\|Release` | Build configuration. Default `Release`. |
| `-IntervalMs N` | Sampling interval, 100 to 10000 ms. Default 1000. Can also be changed live from the dashboard. |
| `-History N` | History buffer size in snapshots. Default 600 (ten minutes at one sample per second). |
| `-Socket PATH` | Use a custom socket path instead of `%LOCALAPPDATA%\monitord\monitor.sock`. Must be under 108 characters. |
| `-LogLevel debug\|info\|warn\|error` | Daemon log level. Default `info`. |
| `-DaemonDir`, `-DashboardDir`, `-BuildDir` | Override project locations if auto-detection does not fit your layout. |

Examples:

```powershell
.\run-demo.ps1 -NoBuild -IntervalMs 500 -History 1200     # faster sampling, longer history
.\run-demo.ps1 -DaemonOnly -LogLevel debug                 # only the daemon, with verbose logs
.\run-demo.ps1 -Config Debug -Test                         # run the tests against a Debug build
Get-Help .\run-demo.ps1 -Full                              # all the details
```

**How it works**

1. **Locates the projects and checks the tools** (`cmake`, `java` 17 or newer, `mvn`), and stops with a specific message if one is missing.
2. **Builds** the daemon with CMake (`-DCMAKE_BUILD_TYPE` and `--config` are both passed, so Visual Studio and Ninja generators both work) and the dashboard with `mvn compile`. It finds `monitord.exe` either in `build\<Config>\` (Visual Studio generator) or in `build\` (Ninja).
3. **Starts the daemon** with a log file at `%TEMP%\monitord-demo.log` and waits until the log says it is listening. If the daemon exits early (another instance owns the socket, for example), the script prints the end of the log and stops.
4. **Starts the dashboard** with the environment variable `MONITOR_SOCKET` set to the socket path. This way the dashboard and daemon always agree on the path, and paths with spaces never go through command-line quoting.
5. **Stops the daemon** when the dashboard window closes. The script started the daemon with `--exit-on-stdin-eof` and holds the write end of the daemon's standard-input pipe. When the script finishes, is interrupted with Ctrl+C, or is killed, the pipe closes and the daemon shuts down cleanly by itself. A forced kill is only a fallback if the daemon has not exited after five seconds, and in that case the script also removes the leftover socket file.

## Manual build and run

**Daemon**

```powershell
cd monitord
cmake -S . -B build -DCMAKE_BUILD_TYPE=Release
cmake --build build --config Release
ctest --test-dir build -C Release --output-on-failure
build\Release\monitord.exe          # with Ninja the executable is build\monitord.exe
```

Executables produced: `monitord` (the daemon), `monctl` (debug client), `selftest` (sampler self-test), and `test_proto` (protocol unit tests).

**Dashboard**

```powershell
cd dashboard
mvn test                       # unit tests
mvn javafx:run                 # the dashboard
```

The dashboard picks its socket path in this order: the first command-line argument, the `MONITOR_SOCKET` environment variable, then the default `%LOCALAPPDATA%\monitord\monitor.sock`:

```powershell
mvn javafx:run "-Djavafx.args=C:\monitor\m.sock"
$env:MONITOR_SOCKET = "C:\monitor\m.sock"; mvn javafx:run     # also fine with spaces in the path
```

There is also a console client that prints everything the connection layer decodes, which is handy for checking the Java side without the UI:

```powershell
mvn -q compile exec:java "-Dexec.args=--history --ping --seconds 12"
```

## Command-line reference

### `monitord`

| Option | Meaning |
|---|---|
| `--interval-ms N` | Sampling interval. The sampler clamps it to 100 to 10000 ms. Default 1000. |
| `--history N` | History ring size, in snapshots (1 to 100000). Default 600. A snapshot is about 2.5 KB at its largest, so 600 is about 1.5 MB. |
| `--socket PATH` | Socket path. Default `%LOCALAPPDATA%\monitord\monitor.sock`. Under 108 characters; an ASCII path is safest. |
| `--client-queue-kb N` | Per-client send queue size in KB. Default 256, minimum 64. |
| `--log-level L` | `debug`, `info` (default), `warn`, or `error`. |
| `--log-file PATH` | Also append log lines to this file. |
| `--exit-on-stdin-eof` | Shut down cleanly when standard input is closed. The launch script uses this so the daemon can never outlive it. |

Exit codes: `0` normal stop, `1` could not start (the reason is logged), `2` bad command line. Ctrl+C stops it cleanly and removes the socket file.

### `monctl` (debug client)

Connects to the socket, optionally sends commands, and prints every line the daemon sends.

| Option | Meaning |
|---|---|
| `--socket PATH` | Socket to connect to. Default: the daemon's default path. |
| `--brief` | One summary line per message instead of the raw JSON. |
| `--ping` | Send a ping. |
| `--interval MS` | Send `set_interval`. |
| `--history N` | Ask for the last N snapshots (`0` for everything the daemon has). |
| `--until-last` | With `--history`: exit when the final history chunk arrives. |
| `--count N` | Exit after N lines. |
| `--timeout S` | Exit after S seconds. |
| `--send LINE` | Send a raw line (repeatable). For hand-written or deliberately invalid input. |
| `--long-line` | Send a 1.5 KB line to test the daemon's line limit and resynchronization. |
| `--stall S` | Connect, then do not read for S seconds. Used to test backpressure. |

At exit `monctl` prints a summary on stderr: lines received, snapshots missing (counted from gaps in `seq`), the daemon's own `dropped` counter, and whether a requested history was contiguous. Exit codes: `0` normal, `1` could not connect, `2` bad usage, `3` connection lost.

```
monctl --brief                                    # watch the live stream, one line per message
monctl --history 0 --brief --until-last           # fetch and verify the history
monctl --interval 200 --brief --count 12          # change the sampling rate
monctl --send "not json" --long-line --ping       # error handling and resynchronization
```

### Environment

| Variable | Used by | Meaning |
|---|---|---|
| `MONITOR_SOCKET` | dashboard, `Probe` | Socket path, used when no path argument is given. The launch script sets it. |
| `LOCALAPPDATA` | everything | Base of the default socket path. Windows sets it. |

## Architecture

```
 +-------------------------------------------------------------------------------+
 | monitord (C)                                                                  |
 |                                                                               |
 |   sampler thread                                                              |
 |     PdhCollectQueryData once per cycle, then each collector fills its         |
 |     own section of a fixed-size snapshot                                      |
 |             | push (copy)                                                     |
 |             v                                                                 |
 |   ring buffer (history, reader-writer lock)                                   |
 |             ^ newest snapshot / history copies                                |
 |             |                                                                 |
 |   server thread (select loop, non-blocking sockets)                           |
 |     accept, parse commands, serialize, per-client send queue                  |
 +------------------------------------|------------------------------------------+
                                      | AF_UNIX stream socket, newline-delimited JSON
 +------------------------------------v------------------------------------------+
 | dashboard (Java 17 + JavaFX)                                                  |
 |                                                                               |
 |   connection thread: connect, reconnect with backoff, read lines, decode      |
 |             | EventQueue (lock-free hand-off)                                 |
 |             v                                                                 |
 |   FX thread: AnimationTimer drains a bounded batch per frame                  |
 |             -> Session (per-connection rules) -> MetricsModel                 |
 |             -> pure view models (chart series, axes, usage rows, table rows)  |
 |             -> Canvas views and tables                                        |
 |                                                                               |
 |   writer thread: sends commands (set_interval, get_history) off the UI thread |
 +-------------------------------------------------------------------------------+
```

### Daemon modules (`monitord/src`)

| File | Responsibility |
|---|---|
| `collectors/win/pdh_session.c` | One shared PDH query. Each collector adds its counters, and the sampler calls `PdhCollectQueryData` once per cycle so all sections describe the same instant. Reference-counted open and close. |
| `collectors/win/{cpu,mem,disk,proc}_pdh.c` | One collector each, behind a small `collector_t` interface (`init`, `sample`, `shutdown`). A collector fills only its own section and leaves it invalid on failure. |
| `snapshot.h` | The snapshot type: fixed-size, pointer-free structs with a validity flag per section. |
| `sampler.c` | Timing loop on absolute deadlines (no drift), failure isolation between collectors, log-once-per-failure-episode. |
| `ringbuf.c` | Fixed-capacity history. Copies in and out, so no pointer into the buffer ever escapes the lock. |
| `proto.c` | Wire format: bounded JSON writer and a hardened command parser. Pure functions, no I/O. |
| `server.c` | AF_UNIX listener, `select` loop, per-client queues with drop-oldest backpressure, history streaming. |
| `sockpath.c` | The default socket location, shared with `monctl`. |
| `log.c` | Thread-safe leveled logger that works before initialization. |
| `main.c` | Option parsing, wiring, and shutdown order. |

### Dashboard packages (`dashboard/src/main/java/com/example/monitor`)

| Package | Responsibility |
|---|---|
| `protocol` | Message types (a sealed interface of records), the decoder, and command builders. Unknown fields and message types are ignored by design. |
| `net` | `ConnectionManager` (connect, read, decode, reconnect), `ReconnectPolicy`, a bounded `LineReader`, and `SocketPaths`. |
| `session` | `EventQueue` (thread hand-off) and `Session` (rules that must hold per connection). |
| `model` | `HistoryBuffer` (sorted, de-duplicated by `seq`, bounded) and `MetricsModel` (JavaFX properties, one `revision` bump per frame). |
| `viewmodel` | Everything that decides what is drawn, with no JavaFX painting: chart series, axis scales, units, usage rows, table rows. Plain Java, fully unit-tested. |
| `ui` | Canvas views and panels. They only paint what the view models describe. |

### One sample, end to end

1. The sampler thread wakes on its deadline and calls `PdhCollectQueryData`.
2. Each collector reads its counters and fills its section of a new `snapshot_t`. A failed collector leaves its section invalid, and the other sections are unaffected.
3. The snapshot is copied into the ring buffer.
4. The server thread notices a new sequence number (it polls every 50 ms), serializes the snapshot once per client, and queues it. If a client's queue is full, its oldest unsent snapshots are dropped and counted.
5. The dashboard's connection thread reads the line, decodes it, and puts the message on the `EventQueue`.
6. On the next frame the `AnimationTimer` applies up to 200 queued events to the model, then calls `commit()` once. The model bumps its `revision`, and each visible panel redraws once.

## Where the numbers come from

Windows has no `/proc`-style files for this data. The same information is available through the performance-counter system, which this project queries through **PDH** (Performance Data Helper). Counter paths are added with `PdhAddEnglishCounterW`, so they work on non-English Windows.

| Metric | Source | Notes |
|---|---|---|
| Total CPU | `\Processor(_Total)\% Processor Time` | PDH computes the rate from two collections. |
| Per-core CPU | `\Processor(*)\% Processor Time` | One instance per logical core, up to 64. |
| Memory in use | `\Memory\Available Bytes` and total RAM | Total RAM is not a counter: read once at startup with `GlobalMemoryStatusEx`. |
| Commit charge | `\Memory\Committed Bytes`, `\Memory\Commit Limit` | |
| Disk capacity | `GetDiskFreeSpaceExW` per fixed drive | Exact bytes. Deriving size from `% Free Space` would be lossy. |
| Disk throughput and queue | `\LogicalDisk(*)\Disk Read Bytes/sec`, `\Disk Write Bytes/sec`, `\Avg. Disk Queue Length` | Instances are drive letters (`C:`), matching the snapshot directly. |
| Process CPU | `\Process(*)\% Processor Time` | Relative to one core, so the daemon divides by the logical core count. |
| Process memory and PID | `\Process(*)\Working Set`, `\Process(*)\ID Process` | The three process counters are joined by instance name; PIDs come from `ID Process`. Real image names come from `QueryFullProcessImageNameW`, falling back to the PDH instance name for protected processes. |

Rate counters need two collections, so the **first snapshot after startup has no CPU, disk, or process data** (`warming_up` is true). Memory is available immediately.

## Wire protocol

Transport: an **AF_UNIX stream socket** at `%LOCALAPPDATA%\monitord\monitor.sock` by default. Framing: **one JSON object per line** (UTF-8, `\n`-terminated), in both directions. Every message carries `"v":1` (the protocol version) and a `"type"`.

### Daemon to client

| Type | When | Fields |
|---|---|---|
| `hello` | On connect, and again whenever the sampling interval changes | `daemon`, `cores`, `interval_ms`, `history_capacity` |
| `snapshot` | Once per sample | `seq`, `ts_ms`, `warming_up`, `cpu`, `mem`, `disks`, `top_procs`, `dropped` |
| `history` | In reply to `get_history`, in chunks | `count`, `last`, `snapshots` (entries without the process list) |
| `error` | In reply to a bad command | `code`, `message` |
| `pong` | In reply to `ping` | `id` (if one was sent) |

A snapshot looks like this (line breaks added for readability):

```json
{"v":1,"type":"snapshot","seq":7,"ts_ms":1763302801002,"warming_up":false,
 "cpu":{"total_pct":37.5,"cores_pct":[41.0,33.2]},
 "mem":{"total_bytes":17179869184,"avail_bytes":6442450944,"commit_bytes":12884901888,"commit_limit_bytes":25769803776},
 "disks":[{"name":"C:","total_bytes":512110190592,"free_bytes":201326592000,"read_bps":1048576,"write_bps":262144,"queue":0.40}],
 "top_procs":[{"pid":4120,"name":"chrome.exe","cpu_pct":12.1,"rss_bytes":412000000}],
 "dropped":3}
```

Rules every client must follow:

- **A section the daemon could not read is `null`** (`"cpu":null`), never zeroed. An empty but valid list is `[]`.
- **Ignore unknown fields and unknown message types.** That is how the protocol can grow without breaking old clients.
- **`dropped` is cumulative per connection**: how many snapshots the daemon discarded for this client because its queue was full. Clients can also see drops directly as gaps in `seq`.
- **`hello` can arrive more than once per connection.** Request history only on the first one.
- **History arrives in chunks of up to 16 snapshots, oldest first**, with `"last":true` on the final chunk. Live snapshots can arrive between chunks, so match entries by `seq`.

### Client to daemon

| Command | Fields | Effect |
|---|---|---|
| `ping` | optional `id` | The daemon replies `pong`, echoing `id`. |
| `set_interval` | `interval_ms` | Sets the sampling interval (clamped to 100 to 10000 ms). The daemon replies with a `hello` carrying the effective value: to every client if it changed, to the requester only if it did not. **Send this only in response to user action, never in response to a `hello`** (the two would feed each other in a loop). |
| `get_history` | optional `count` | Streams the last `count` snapshots (everything if omitted). |

Error codes: `bad_json`, `bad_version`, `unknown_type`, `missing_field`, `bad_value`, `line_too_long`, `busy` (a history reply is already in progress), `too_many_clients`, `internal`.

### Limits

| Limit | Value |
|---|---|
| Client line length | 1024 bytes. A longer line gets `line_too_long`, and the daemon resynchronizes at the next newline. |
| Invalid lines in a row | 10, then the client is disconnected. |
| Concurrent clients | 8 by default, at most 16. |
| Per-client send queue | 256 KB by default (`--client-queue-kb`). |
| Stalled client | Disconnected after 10 seconds without send progress. |
| Message size from the daemon | Under 64 KB, even in the worst case. |

## Testing

`.\run-demo.ps1 -Test` runs all of the following and prints a summary.

| Suite | What it covers |
|---|---|
| **Protocol tests** (`test_proto`, via CTest) | Exact wire format of every message, null and empty sections, string escaping and UTF-8 repair, non-finite numbers, a buffer-overflow sweep (every too-small buffer must fail cleanly), worst-case message sizes, every command-parser case, and 100,000 fuzzed inputs parsed from exactly-sized heap blocks (run under AddressSanitizer to catch over-reads). About 8,000 checks. |
| **Sampler self-test** (`selftest`) | The real collectors and sampler for 12 seconds: contiguous sequence numbers, timing within tolerance and no drift, values that agree with independent OS calls (`GlobalMemoryStatusEx`, `GetProcessMemoryInfo`, `GetDiskFreeSpaceExW`), first-sample warm-up behavior, and prompt shutdown. |
| **Dashboard unit tests** (Maven, 88 tests) | The decoder against the daemon's real wire format; reconnect logic, line reading, and the connection manager against a fake daemon built from JDK sockets; history merge and eviction; session rules (including a regression test for the old `hello`/`set_interval` feedback loop); the event queue under load; and all view-model code (chart series, decimation, axes, units, usage rows, table rows). |
| **End-to-end smoke test** | Starts a private daemon on a temporary socket, then checks that `monctl` receives a complete, contiguous history, that a ping gets a pong, and that the Java client gets history and a pong. |

Manual checks that are not automated: the failure behaviors in [Demo walkthrough](#demo-walkthrough) (reconnect, stale socket recovery, backpressure) and the graphs themselves.

## Demo walkthrough

A five-to-ten-minute tour. Start with `.\run-demo.ps1` and keep an eye on the terminal, where the daemon's log shows each client connecting and each interval change. The load generators below are PowerShell one-liners; run them in a second terminal.

**1. The tabs.** Click through CPU, Memory, Disks, and Processes. Note the connection indicator, the daemon version and core count, and the history counter in the footer. The graphs were full at once because the dashboard fetched history on connect.

**2. CPU.** Saturate every core, then clean up:

```powershell
1..([Environment]::ProcessorCount) | ForEach-Object { Start-Job { while ($true) {} } }
# later:
Get-Job | Remove-Job -Force
```

The line climbs toward 100%, every core bar turns red, and the stats line shows latest, average, and peak.

**3. Processes.** Start a single busy process and find it in the table:

```powershell
powershell -NoProfile -Command "while ($true) {}"
```

It appears near the top at roughly `100 / cores` percent, because process CPU is normalized to the whole machine. Click the Memory header to re-sort, select a row, and watch it stay selected while the numbers change.

**4. Memory.** Allocate about 1 GB and touch every page so it is really resident:

```powershell
$b = New-Object byte[] 1GB
for ($i = 0; $i -lt $b.Length; $i += 4096) { $b[$i] = 1 }
```

The "Physical" bar and the line rise by about 1 GiB, and fall when you close that window. Untouched pages would raise commit charge but not physical use, which is a good thing to be able to explain.

**5. Disks.** Write 1 GiB through to disk (needs that much free space in `%TEMP%`) and delete it:

```powershell
$f = "$env:TEMP\disktest.bin"
$buf = New-Object byte[] (4MB)
$fs = [IO.File]::Create($f, 4096, [IO.FileOptions]::WriteThrough)
1..250 | ForEach-Object { $fs.Write($buf, 0, $buf.Length) }
$fs.Close(); Remove-Item $f
```

On the Disks tab, the write line rises and the y-axis rescales and relabels in MiB/s. When the write ends, the scale relaxes toward its 1 MiB/s floor.

**6. Live interval change.** Pick 100 ms in the interval box. The graphs become smoother, the daemon log shows one `set the interval` line, and the footer shows the buffer now covers a shorter span.

**7. Resilience: the daemon dies.** In the second terminal:

```powershell
taskkill /F /IM monitord.exe
```

The dashboard keeps its last data under a "Reconnecting" banner, with an amber status dot and the retry delay. Start a daemon by hand (`monitord.exe` from the build folder). Its log says it is removing a stale socket file, the dashboard reconnects within a few seconds, and the history refills from the new daemon.

**8. Backpressure** (optional deep dive). Start a daemon with a small queue and a client that stops reading:

```powershell
monitord.exe --interval-ms 100 --client-queue-kb 64 --socket C:\monitor\bp.sock
monctl.exe --socket C:\monitor\bp.sock --stall 8 --brief --timeout 15
```

After the stall, `monctl` prints a `GAP: N missing` line and snapshots with a non-zero `dropped`, while the daemon kept sampling and serving other clients. Stalling for more than 10 seconds shows the disconnect. If no drops appear, try a longer stall (under 10 seconds) or a shorter interval, because Windows' own socket buffers absorb the first part.

**9. The protocol is readable.** `monctl --brief`, or without `--brief` for the raw JSON. Try `monctl --send "not json" --long-line --ping --count 6` to see the error replies and that the connection survives.

### Talking points

- **Why PDH and not `/proc`:** Windows has no such files. Counters are the documented, stable interface, and the collector interface would let a Linux `/proc` backend slot in without touching the sampler, server, or dashboard.
- **Cumulative counters and rates:** PDH does the delta math between two collections, which is why the first sample is "warming up".
- **Drift-free sampling:** the sampler schedules from the previous deadline, not from "now", so work time never accumulates into drift. An overrun skips ahead instead of bursting.
- **Isolation:** one failed collector nulls only its section; one slow client cannot slow sampling or other clients.
- **Backpressure policy:** snapshots are droppable and control messages are not. The oldest unstarted snapshots go first, and the loss is visible through `dropped` and gaps in `seq`.
- **UI threading:** the connection thread never touches the UI. A lock-free queue is drained in bounded batches on the FX thread, then the model publishes once per frame.
- **Testable UI:** everything that decides what is drawn is plain Java in `viewmodel`, tested without a display. The Canvas code only paints.
- **A real bug and its fix:** a client that sent `set_interval` on every `hello` created a feedback loop (the daemon answers `set_interval` with a `hello`). It was fixed in the client (`Session`, with a regression test) and hardened in the daemon.

## Design decisions and trade-offs

| Decision | Why | Cost |
|---|---|---|
| PDH instead of raw system calls | One uniform API for all metrics, with the rate math done for you. | A dependency on the counter registry being intact (`lodctr /R` repairs it), and instance-name quirks for processes. |
| AF_UNIX instead of loopback TCP | No port is opened; access is governed by file permissions. | Needs Windows 10 1803 or later and Java 16 or later; ordinary network debug tools cannot connect, hence `monctl`. |
| Newline-delimited JSON | Readable, trivial to parse in any language, easy to debug. | Larger than a binary format. Irrelevant at one message per second. |
| Fixed-size, pointer-free snapshots | Safe to copy with `memcpy`, so the ring buffer stores by value, and the sampling loop does not allocate. | Hard caps: 64 cores, 8 drives, 16 processes. |
| A single server thread with `select` | Simple ownership: one thread owns every client, with no per-client locking. | Fine for a handful of local clients; not meant for hundreds. |
| Per-client serialization | The `dropped` field differs per client. | A few microseconds of extra work per client per sample. |
| History streamed lazily in chunks | A large history never swamps a client's queue or starves live snapshots. | Live snapshots interleave with history chunks, so clients merge by `seq`. |
| Clear history on each new connection | Sequence numbers restart when the daemon restarts, so old and new cannot be mixed. | A reconnect to the same daemon re-fetches history it already had. |
| Stale-socket detection by connecting | Never steals a live socket, always recovers from a crash. | One extra connect attempt at startup. |
| Stdin-close shutdown for the launch script | Graceful, needs no console-event tricks, and cannot leave an orphaned daemon. | One more option on the daemon. |

## Limits and known gaps

- **Windows only**, by design of the collectors. The interface leaves room for another backend.
- **Up to 64 logical cores** are reported. On a machine with more than 64 logical processors, only the first 64 are shown. Handling more needs the `Processor Information` counters.
- **Process list is the daemon's 16**, not every process; sorting reorders those 16.
- **Process names** for protected processes appear without `.exe` (for example `System`), because they cannot be opened.
- **Process CPU can blip for one sample** when a process exits, because PDH renumbers its `#N` instance suffixes between collections.
- **History is not persisted.** Restarting the daemon starts a new history.
- **The socket path must be short and, ideally, ASCII** (the `sun_path` limit is 108 bytes, and the path goes through ANSI APIs). A non-ASCII user name can cause trouble; use `--socket` with a plain path.
- **Drives mounted while the daemon is running** are expected to appear, but this has not been verified.
- **The dashboard has a light theme only**, and no hover values on the graphs; exact values are in each tab's stats line.
- **Access control** is file permissions on the socket's directory, with no authentication. See [Security notes](#security-notes).

## Troubleshooting

| Symptom | Likely cause and fix |
|---|---|
| `'cmake' was not found on PATH` (or `java`, `mvn`) | Install the tool and reopen the terminal so PATH updates. The script checks Java 17 or newer. |
| CMake: no C compiler found | Install Visual Studio (or Build Tools) with "Desktop development with C++", or run from a Developer PowerShell. |
| CMake: generator or cache mismatch | The build folder was made with a different generator. Delete it, or pass `-BuildDir` with a new path. |
| `monitord.exe was not found under ...` | The daemon has not been built, or it is in an unexpected folder. Run without `-NoBuild`, or pass `-BuildDir`. |
| Script is blocked by execution policy | Use `run-demo.cmd`, or `powershell -ExecutionPolicy Bypass -File .\run-demo.ps1`. |
| `cannot create an AF_UNIX socket (WSA ...)` | Windows older than 10 version 1803 or Server 2019. |
| `another monitord is already listening on ...` | Another daemon owns the socket. Stop it, or choose another path with `-Socket`. |
| `bind(...) failed` | The directory does not exist or is not writable, or the path is too long. The script creates the directory for a custom `-Socket`. |
| `removing stale socket file ...` | Informational. A previous daemon was killed without cleaning up, and the new one recovered. |
| `cannot add PDH counter ... (0x800007D0)` | The performance counter registry is damaged. Run `lodctr /R` in an elevated prompt, then retry. |
| The dashboard stays on "Disconnected" | The daemon is not running, or the two disagree on the path (the launch script sets `MONITOR_SOCKET` to keep them in sync). Check the daemon log. |
| The graphs show "Waiting for data..." | Normal for about a second after connecting; CPU, disk, and process data need two collections. If it persists, look for `collector ... failed` lines in the daemon log. |
| The Disks tab is empty | Only fixed drives are reported. A drive whose free space cannot be read (for example a locked volume) is skipped. |
| A test fails in the smoke stage | Read the detail printed beside the failed check and `%TEMP%\mon-*.log` if present. A `never arrived` message means the final history chunk did not arrive in time. |
| `monctl` connects but prints nothing | Check the daemon is sampling (its log), and run `monctl --brief --timeout 5`. Report the exact output if it persists. |

## Repository layout

The launch script finds the daemon project either at the repository root or in `monitord\`; the layout below is the tidy form.

```
repo/
  run-demo.ps1            launch script (build, test, run)
  run-demo.cmd            wrapper for cmd.exe / double-click-free use without execution-policy changes
  README.md
  docs/                   design document
  monitord/               the C daemon
    CMakeLists.txt
    src/                  sampler, ring buffer, protocol, server, logger, main
      collectors/win/     PDH session and the four collectors
    tests/                protocol tests and the sampler self-test
    tools/                monctl, the debug client
  dashboard/              the JavaFX dashboard
    pom.xml
    src/main/java/com/example/monitor/
      protocol/ net/ session/ model/ viewmodel/ ui/
    src/test/java/...     unit tests
```

## Security notes

- **No network exposure.** The only interface is a socket file in a per-user directory. Nothing listens on a TCP port.
- **Access control is the file system.** The default directory is under `%LOCALAPPDATA%`, which Windows normally restricts to your account. Check it with `icacls "%LOCALAPPDATA%\monitord"` if it matters for your setup.
- **Data is read-only.** The only command that changes anything is `set_interval`, and it is range-clamped.
- **Input is bounded.** Line length, nesting depth, invalid-line count, and client count all have limits, and the command parser is fuzz-tested to never read past its input.
- **Not designed for hostile local users sharing your account.** There is no authentication beyond file permissions.

## Roadmap

- Self-measurement: expose per-collector time per cycle, so the daemon can report its own overhead.
- Fault injection: a flag that makes one collector fail for a few cycles, to test failure isolation end to end.
- Dashboard polish: dark theme, hover values, and an overview page.
- A Linux `/proc` backend behind the collector interface.
- Run the daemon as a Windows service.
- More metrics: network throughput, per-process disk I/O.
