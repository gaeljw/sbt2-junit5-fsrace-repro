# sbt2-junit5-fsrace-repro

Minimal, **Cucumber-free** reproduction of a `java.nio.file.FileSystemAlreadyExistsException`
during JUnit Platform classpath scanning under sbt 2.x, tracked upstream as
[sbt/sbt#9625](https://github.com/sbt/sbt/issues/9625).

Just plain Scala + JUnit 5 (Jupiter) + `sbt-jupiter-interface` + the JUnit
Platform Suite engine (`@Suite`/`@SelectPackages`) — no Cucumber anywhere.

There's also a build-tool-agnostic (pure Java/Maven) reproduction of the same
underlying JUnit Platform bug next to this project, at `../junit-fsrace-maven`.
This project instead demonstrates it arising naturally out of an ordinary sbt
2.x build.

## Run it

No local sbt/JDK install needed — via Docker:

```
docker build -t sbt2-junit5-fsrace-repro .
docker run --rm sbt2-junit5-fsrace-repro
```

The image pre-fetches dependencies and does one full build at build time (so
`docker run` is fast, ~20s, and works offline) — that does **not** prevent
the race from reproducing on `docker run`: it lives in a JVM-process-static
map that starts empty on every fresh sbt/JVM process, i.e. on every
container run, regardless of what's already on disk in the image layer. Run
it a few times in a row (`for i in 1 2 3; do docker run --rm
sbt2-junit5-fsrace-repro; done`) if the first one happens to pass.

Or locally, with sbt + JDK 25 already installed:

```
sbt "reload; cleanFull; testFull"
```

Always use `reload` after touching `build.sbt` (sbt's background server can
otherwise keep serving a stale build definition — see "Pitfalls" below), and
`cleanFull`/`testFull` rather than `clean`/`test` (sbt 2's `test` behaves like
`testQuick` — it skips test classes it considers unchanged, so a plain
`clean; test` can silently report success without actually re-running
anything; `testFull` forces genuine re-execution).

In local testing (14 cores) this fails **most runs, most projects** — around
14-15 out of 16 consumer projects on a typical run — which is far more
reliable than "intermittent" but does vary run to run, as you'd expect from an
actual race (unlike an earlier, invalid version of this repro that failed
100% of the time regardless of the race — see "Pitfalls").

Expect something like:

```
[error] Failed: Total 1, Failed 1, Errors 0, Passed 0
...
[error] Test consumertest.RunSharedTests failed: org.junit.platform.suite.engine.NoTestsDiscoveredException: Suite [consumertest.RunSharedTests] did not discover any tests, took 0.005s
```

preceded by the actual root cause, logged as a WARNING rather than thrown:

```
WARNING: Error scanning files for URI jar:file:/home/you/.cache/sbt/v2/cas/sha256-...-.../shared
java.nio.file.FileSystemAlreadyExistsException
	at jdk.zipfs/jdk.nio.zipfs.ZipFileSystemProvider.newFileSystem(...)
	...
	at org.junit.platform.commons.util.CloseablePath.createForJarFileSystem(CloseablePath.java:74)
	...
	at org.junit.platform.commons.util.DefaultClasspathScanner.scanForResourcesInPackage(...)
```

and a handful of consumers (typically 1-2 out of 16) genuinely winning the
race and correctly reporting `Passed: Total 500`.

## Shape of this build

- **`shared`**: one library project containing 500 plain, checked-in, trivial
  do-nothing `@Test`-annotated classes (`shared/src/main/scala/shared/Test1.scala`
  .. `Test500.scala`, all in package `shared`), produced once by
  `generate-shared-tests.py` — nothing sbt-specific, just files on disk. sbt 2
  defaults `exportJars` to `true`, so `shared`'s compiled output is packaged
  into **one** content-addressed jar under `~/.cache/sbt/v2/cas/`, compiled
  exactly once.
- **`consumer1` .. `consumer16`**: 16 independent projects, each
  `.dependsOn(shared)`, each with its own physical copy of the same JUnit
  Platform `@Suite @SelectPackages(Array("shared"))` class
  (`consumerN/src/test/scala/consumertest/RunSharedTests.scala` — 16 identical
  copies, one per consumer). Only `shared`'s own compiled output needs to be
  byte-identical across consumers for the race to happen, and it trivially is,
  since it's compiled exactly once and just referenced by all 16.

Discovering `@SelectPackages(Array("shared"))` makes JUnit Platform scan the
classpath for classes in package `shared`, which means opening `shared`'s one
compiled-output jar as a `java.nio.file.FileSystem`.

Tests run **unforked** (no `Test / fork` setting — sbt 1.x's default, still
the sbt 2.x default), so all 16 consumers' `Test` tasks run inside the *same*
sbt JVM, each under its *own* project-local classloader. sbt schedules
independent projects' `Test` tasks concurrently (bounded by available
processors), so most of the 16 end up calling into JUnit Platform's classpath
scanner **at the same time**, all targeting the exact same physical `shared`
jar file.

## Why a single module isn't enough

An earlier version of this repro put all 16 `@Suite` classes in the *same*
project as the 500 `shared.*` classes (one classloader, no separate `shared`
dependency project). **That does not reproduce the bug** — 16 concurrent
`@SelectPackages` scans of the same jar, all from the same classloader, come
back clean every time (8500/8500 tests passed, 0 exceptions, verified over
multiple clean runs).

That's expected once you look at why the race exists at all (next section):
`CloseablePath`'s deduplication is implemented with a
`ConcurrentHashMap#compute()`, which *is* correctly atomic per key for
multiple threads sharing one classloader. The race only appears once you have
**two or more independent classloaders**, each with its own copy of that map,
racing to open the *same physical jar* — which is exactly what sbt's
per-project classloader isolation gives you across `consumer1..consumer16`,
but never gives you within a single project.

## Why this happens

`org.junit.platform.commons.util.CloseablePath` de-duplicates concurrent opens
of the same jar URI using a `static final ConcurrentMap<URI, ManagedFileSystem>`
field, with an atomic `compute()`-based retain/release scheme. That correctly
prevents `FileSystemAlreadyExistsException` when multiple threads *inside a
single classloader* race to open the same jar (see above).

But that map is `static`, i.e. **one instance per classloader** that has
loaded `CloseablePath`. Each of the 16 consumer projects gets its own
classloader (that's how sbt isolates each subproject's dependencies/classpath),
so there are 16 independent, mutually-unaware copies of that map. The
underlying `jdk.nio.zipfs.ZipFileSystemProvider` registry that actually backs
`FileSystems.newFileSystem(URI, ...)`, however, is **process-wide**, not per
classloader. So whichever consumer loses the race gets
`FileSystemAlreadyExistsException` — which `DefaultClasspathScanner` swallows
into a log WARNING, silently returning zero resources for that classpath root,
which is why it surfaces as `NoTestsDiscoveredException` rather than a hard
crash.

This is only reachable because sbt 2's new content-addressable build cache
(`~/.cache/sbt/v2/cas`) plus `exportJars` defaulting to `true` means a
project's compiled *output* is now a cached jar file on the classpath (just
like a regular external dependency jar) rather than a plain directory like in
sbt 1.x — directories never need `FileSystems.newFileSystem()` at all, so this
race was structurally impossible before sbt 2.

## Pitfalls hit while building this repro (worth knowing before you tweak it)

- **Class naming matters, silently.** A `@Suite`'s discovery automatically
  applies `ClassNameFilter.STANDARD_INCLUDE_PATTERN`
  (`^(Test.*|.+[.$]Test.*|.*Tests?)$`) on top of `@SelectPackages`. An earlier
  version of this repro named the generated classes `GeneratedTest1` ..
  `GeneratedTest500` — which **doesn't match that pattern** (it neither starts
  nor ends with "Test"). Every suite reported `NoTestsDiscoveredException`
  100% of the time, with or without the classloader race, making it look like
  a rock-solid always-reproduces bug when it was actually a naming bug wearing
  the race's clothes. That's also why the single-module variant above was
  originally (wrongly) believed to reproduce the bug too. Classes here are
  named `Test1` .. `Test500` (matches `Test.*`) specifically to avoid this.
- **sbt's background server can serve a stale build definition.** Editing
  `build.sbt` and then just running `sbt test` again, in the same shell
  session, doesn't reliably reload it if a server from an earlier session (or
  an earlier interactive `set` command) is still running — `show` and other
  commands can silently answer against the *old* settings. Always chain
  `reload` after a `build.sbt` edit, e.g. `sbt "reload; cleanFull; testFull"`.
  (An earlier debugging session momentarily "confirmed" that
  `exportJars := false` didn't fix the issue — that was this pitfall, not a
  real finding; see "Making it (not) happen" below for the actually-confirmed
  result.)

## Making it (not) happen

- The number of classes in `shared` (500, see `generate-shared-tests.py` —
  re-run it with a different `COUNT` and regenerate) controls how long the
  scan takes, which affects how wide the race window is.
- The number of consumer projects (16, hardcoded as explicit `lazy val`s —
  sbt discovers projects via static analysis of `build.sbt`, so this can't be
  a runtime loop) controls how many concurrent racers there are.
- Confirmed workarounds that make this go away (see the sibling
  `cucumber-jvm-scala` project's `build.sbt` for where these landed for real).
  Both were re-verified against this exact repro, with a proper `reload` each
  time, over 3 consecutive `cleanFull; testFull` runs each — 16/16 consumers
  passing (`Test run finished: 0 failed, ..., 500 total`) and 0
  `FileSystemAlreadyExistsException`, every time:
  - `exportJars := false` — reverts to directory-based classpath entries, so
    there's no jar to race on opening in the first place. Uncomment the line
    in `commonSettings` in `build.sbt` to try it yourself.
  - `Test / fork := true` — puts each project's tests in their own OS
    process, so there's no shared in-JVM `ZipFileSystemProvider` registry to
    race on (this doesn't address the sibling write-side race in sbt's
    `ActionCache`, see [sbt/sbt#9043](https://github.com/sbt/sbt/issues/9043),
    but it does address this specific exception).
