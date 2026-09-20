# sbt2-junit5-fsrace-repro

Minimal, **Cucumber-free** reproduction of an intermittent
`java.nio.file.FileSystemAlreadyExistsException` during JUnit Platform
classpath scanning under sbt 2.x, tracked upstream as
[sbt/sbt#9625](https://github.com/sbt/sbt/issues/9625).

Just plain Scala + JUnit 5 (Jupiter) + `sbt-jupiter-interface` + the JUnit
Platform Suite engine (`@Suite`/`@SelectPackages`) — no Cucumber anywhere.

## Run it

```
sbt "cleanFull; testFull"
```

In local testing this fails **every single project, every single run (16/16)** — far more reliable than
"intermittent".

Expect a wall of:

```
[info] Test run started (JUnit Platform Suite)
[error] Test consumertest.RunSharedTests failed: org.junit.platform.suite.engine.NoTestsDiscoveredException: Suite [consumertest.RunSharedTests] did not discover any tests, took 0.005s
```

preceded by the actual root cause, logged as a WARNING rather than thrown (see
"Why this happens" below):

```
WARNING: Error scanning files for URI jar:file:/home/you/.cache/sbt/v2/cas/sha256-...-.../shared
java.nio.file.FileSystemAlreadyExistsException
	at jdk.zipfs/jdk.nio.zipfs.ZipFileSystemProvider.newFileSystem(...)
	...
	at org.junit.platform.commons.util.CloseablePath.createForJarFileSystem(CloseablePath.java:74)
	...
	at org.junit.platform.commons.util.DefaultClasspathScanner.scanForResourcesInPackage(...)
```

## Shape of this build

- **`shared`**: one library project containing 500 plain, checked-in, trivial
  do-nothing `@Test`-annotated classes (`shared/src/main/scala/shared/GeneratedTest1.scala`
  .. `GeneratedTest500.scala`, all in package `shared`), produced once by
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
  since it's compiled exactly once and just referenced by all 16 — the
  `RunSharedTests` copies don't need to be shared/identical at all, they're
  only there to trigger the same classpath scan from each consumer.

Discovering `@SelectPackages(Array("shared"))` makes JUnit Platform scan the
classpath for classes in package `shared`, which means opening `shared`'s one
compiled-output jar as a `java.nio.file.FileSystem`.

Tests run **unforked** (no `Test / fork` setting — sbt 1.x's default, still
the sbt 2.x default), so all 16 consumers' `Test` tasks run inside the *same*
sbt JVM, each under its *own* project-local classloader. sbt schedules
independent projects' `Test` tasks concurrently (bounded by available
processors), so most/all of the 16 end up calling into JUnit Platform's
classpath scanner **at the same time**, all targeting the exact same physical
`shared` jar file.

## Why this happens

`org.junit.platform.commons.util.CloseablePath` de-duplicates concurrent opens
of the same jar URI using a `static final ConcurrentMap<URI, ManagedFileSystem>`
field, with an atomic `compute()`-based retain/release scheme. That correctly
prevents `FileSystemAlreadyExistsException` when multiple threads *inside a
single classloader* race to open the same jar.

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

## Making it (not) happen

- The number of classes in `shared` (500, see `generate-shared-tests.py` —
  re-run it with a different `COUNT` and regenerate) controls how long the
  scan takes, which affects how wide the race window is.
- The number of consumer projects (16, hardcoded as explicit `lazy val`s —
  sbt discovers projects via static analysis of `build.sbt`, so this can't be
  a runtime loop) controls how many concurrent racers there are.
- Confirmed workarounds that make this go away (see the sibling
  `cucumber-jvm-scala` project's `build.sbt` for where these landed for real):
  - `exportJars := false` — reverts to directory-based classpath entries, so
    there's no jar to race on opening in the first place.
  - `Test / fork := true` — puts each project's tests in their own OS
    process, so there's no shared in-JVM `ZipFileSystemProvider` registry to
    race on (this doesn't address the sibling write-side race in sbt's
    `ActionCache`, see [sbt/sbt#9043](https://github.com/sbt/sbt/issues/9043),
    but it does address this specific exception).
