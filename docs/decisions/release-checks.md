# The integration and release checks

Decided in tasks 15.1 to 15.5 of the change `kotlin-rewrite-modular`, on 2026-11-26.

The last tasks of the change check the system as a whole: the two programs together, the build on a clean
checkout, the documents against the legacy system, the quick start on a machine that has nothing but Java,
and the artifacts of a release. This record says what each check does and why it's built the way it is.

## The end-to-end check

Task 15.1 asks for a scripted check that starts the server and the UI from the built distributions, imports
the legacy sample document through the UI, calls a mock URL, and finds the history entry in the UI.

Until this task, no test ran the two programs together. The tests of the UI use a stand-in for the server,
because `mockey-ui` must not depend on the server module, and the tests of the server send their requests
to the admin API themselves. Both sides are held against the description of the admin API, so they agree on
the wire. The end-to-end check shows that they also work as two programs, from what a person downloads.

`scripts/end-to-end-check.sh` is the check. It takes the two distribution archives and does this:

1. It unpacks the archives and starts the server and then the UI, each with its start script.
2. It imports `sample.xml`, the document that Mockey 2.x came with, through the import page of the UI.
3. It sends a request to `/service/customer/123`, a mock URL of the document, and expects the response of
   the scenario `123 Customer GET`.
4. It finds the request in the history that the UI shows, opens the page of the entry, and expects the
   request and the response there.
5. It tells both programs to stop, and expects each to end with the status 0.

The Gradle task `endToEndCheck` builds the two archives and runs the script with them. The task belongs to
`check`, so every run of `./gradlew check` includes it, and so does the CI job.

### Decisions

**The check is a shell script with `curl`.** The task asks for a script. The other checks that run a whole
program are shell scripts as well: `scripts/probe-deviations.sh` and `scripts/record-cases.sh`. A script
also runs without the build, against the archives of a release.

**The script takes the archives, not the directories that the build installs.** An archive is what a person
downloads. Unpacking it checks what the tests of the modules don't see: that the archive holds the start
script, and that the script can be run after it's unpacked.

**The script goes the way of a browser.** It loads the import page first, with a cookie jar, and it sends
the form with the token of that page. The UI takes a form only that way, see [ui-forms.md](ui-forms.md). A
check that called the admin API directly would say nothing about the UI.

**Both programs take free ports on the loopback address.** The script reads the ports from the logs. So the
check runs beside a server that someone has started, and beside the tests of the modules, which use the
default ports.

**The programs run on the Java 21 runtime of the build's toolchain.** The Gradle task gives the start
scripts that runtime as `JAVA_HOME`. The machine's own `java` can be an older one, and then the programs
wouldn't start. A person who runs the script directly sets `JAVA_HOME` or has a Java 21 runtime on the path.

**The task is done for the archives that it has passed with.** Gradle runs it again when an archive, the
script, or the sample document changes. On Windows, Gradle skips the task, because the script needs a shell.

**A failure names its step and shows the logs.** The script says which step failed and prints the last
lines of the logs of both programs. It stops what still runs and removes what it wrote.

### What the first run showed

The check passed on its first run: the two programs work together as built. Five changed copies of the
script were run to see that each step can fail: no import, a form without its token, no mock request, a
request for another customer, and a UI that is killed instead of stopped. Each copy failed at its step with
a message that names it.

## The CI job

Task 15.2 asks that all module tests, the parity suite, and the OpenAPI comparison run in one CI job on a
clean checkout, and that the job is green.

The repository has one workflow with one job, `check`, which runs for every push. The job checks the
repository out, sets up a Java 21 runtime, and runs `./gradlew check --no-daemon`. It keeps nothing from one
run to the next, so every run starts from the sources alone. That one command is the whole check of the
build:

| What it checks | Where |
|---|---|
| The tests of every module | The `test` task of each of the six modules. |
| The parity suite | The tests of `mockey-parity-tests`. `NewServerParityTest` replays all 267 recordings against the new server. |
| The OpenAPI comparison | `AdminApiDescriptionTest` in `mockey-server`. It compares the description of the admin API with the routes of the API, in both directions. |
| The code style | The ktlint tasks of each module. |
| The dependencies between the modules | The task `checkModuleDependencies`. |
| The two programs together | The task `endToEndCheck`, see "The end-to-end check". |

After that command, the job publishes the core library to the local Maven repository and runs the tests of
the embedded sample with it.

So the job already ran what the task asks for, and the workflow didn't have to change. What was missing was
a way to see that in the log of a run.

### Decisions

**The log of a build says how many tests of each module ran.** After the tests of a module, the build writes
a line such as `mockey-parity-tests: 91 tests, 0 failed, 0 skipped`. A module whose tests were left out
shows there. The line comes from `TestSummary`, a test listener that the convention plugin for the modules
adds to every test task.

**In the CI job, no test is left out.** A test that needs something from its machine, such as a free port
or `curl`, is skipped on a machine that doesn't have it. Where the variable `CI` is `true`, as it is in the
job, such a test fails instead (`TestEnvironment`). So a green job ran every test.

### The run that was verified

[Run 45874878385](https://github.com/dnedrow/MockeyRedux/actions/runs/45874878385) checked the commit
`7b200dc`, and it's green. Its log shows what the job ran:

| Module | Tests | Failed | Skipped |
|---|---|---|---|
| `mockey-core` | 1271 | 0 | 0 |
| `mockey-api` | 4 | 0 | 0 |
| `mockey-legacy` | 115 | 0 | 0 |
| `mockey-server` | 647 | 0 | 0 |
| `mockey-ui` | 386 | 0 | 0 |
| `mockey-parity-tests` | 91 | 0 | 0 |

That's 2514 tests. The build executed all 97 of its tasks, and none was taken over from an earlier build.
The end-to-end check passed on the Linux machine of the job as it had on macOS. The check took 12 minutes
and 48 seconds, and the embedded sample 22 seconds more.

### What stays open

Two things would need a change to the workflow, which is the owner's to decide:

- The workflow is named "Run tests for embedded samples", and its job checks the whole build.
- A run downloads Gradle and every library anew. Run 45873953490 failed before it ran any task, because
  Maven Central answered the downloads with the status 429. A cache for what Gradle downloads would make
  such a failure less likely, and the job faster.

## The traceability table and the deviation register

Task 15.3 asks for an automated check that the traceability table has no unmapped legacy servlet or
parameter, and that the deviation register matches the labels of the parity cases.

Three checks do that. Two are tests, which run with every build and in the CI job. The third compares the
table with the sources of Mockey 2.x, so it needs a checkout of them and is run by hand.

| Check | What it holds |
|---|---|
| `TraceabilityTest` | No row of the table is open. Every row ends in a requirement, a parity case, or a non-goal. Every requirement, parity case, and deviation that the table names is there. |
| `ParityReviewTest` | Every label names deviations of the register. The IDs of the register count up without a gap. Every recording that the register names is there, and the register accounts for every recording that carries the label of a deviation. |
| `scripts/check-traceability.sh` | The table has a row for every servlet mapping, every documented parameter, and every startup option of Mockey 2.x, and no row is open. |

### What the checks found

**The table had three open points, and they're closed now.** Each was a question that the table had left to
whoever owns the specifications. The answers are the ones that change nothing in the programs:

- `/validateSyntax` checks a JSON Schema by itself, and no page of Mockey 2.x calls it. The design names it
  as a non-goal now, next to the sample loader of the same validation pages.
- `/setup` can set the response mode of every service at once, which the home page of Mockey 2.x offers as
  three buttons. No requirement asks for such an operation, and the new system has none. The register
  lists that as DEV-75, and the migration guide and the UI document say what takes its place.
- The requirement for `/plan/setup` didn't say that the endpoint takes `transient_state` and ignores it.
  The register and a recording said so, and the shim does it. The requirement says it now.

**The register and the labels disagreed in five entries.** DEV-03, DEV-11, and DEV-27 didn't name every
recording that carries their label. DEV-20 and DEV-47 stated a number of recordings that was one short. The
register is corrected, and no label had to change.

### Decisions

**The register and the labels match in both directions.** A label names only deviations of the register,
which `ParityReviewTest` has held since task 2.9. In the other direction, an entry of the register accounts
for every recording that carries its label. It names each of them, or it names some and says how many more
there are, or it states their number and names examples. So a recording that gets a label shows up in the
register, and a number in the register stays true.

**An entry may name a recording that doesn't carry its label.** The evidence of an entry is what shows the
behavior of Mockey 2.x. A recording can show that while the new server answers it the same way, as
`proxy-headers-application` does for DEV-69. The check only asks that such a recording exists.

**A requirement is looked up by its name in the main specifications.** The table cites a requirement as its
capability and its name. The test reads the names from `openspec/specs`, where each capability has a
directory with its `spec.md`, and from nowhere else. While the change was in progress, its delta
specifications were the only specifications that the project had, so the test read every `spec.md` below
`openspec/`. When the change was archived, its requirements went into the main specifications, and the
owner pointed the test at those alone. The archive keeps the delta specifications as they were, so a
requirement that a later change renames or removes would still be found there. A requirement that a later
change adds can be cited in the table once that change is synced to the main specifications.

**The script reads the sources of Mockey 2.x, not a list of what they hold.** A servlet of Mockey 2.x
declares the parameters of its endpoint for the API documentation, mostly by constants. The script follows
a constant to the class that declares it: two interfaces have a constant with the same name, one for
`transientState` and one for `transient_state`. The script reads the startup options from the calls that
register them with the runner.

**The last part of the table, "Legacy tests", isn't checked.** It maps test classes to their ports, and the
task is about servlets and parameters.

### What stays open

DEV-75 records that the three buttons are gone. That's what the specifications say today, not a decision
that they should stay gone. If they're wanted back, the UI can offer them with the operations that the
admin API has.

## The quick start of the README

Task 15.4 asks that the quick start of the README is followed verbatim in a container that has only a Java
21 runtime, and that the server and the UI start and a mock request is answered.

The README had no quick start. Now it has one, which takes a reader from two downloaded files to a first
mock answer in four commands:

1. `java -jar` with the runnable jar of the server starts the server.
2. `java -jar` with the runnable jar of the UI starts the UI.
3. A request to the admin API imports a service with one scenario.
4. A request to the mock URL of the service gets the response of the scenario.

After that, the reader opens the UI in a browser, and Ctrl+C stops each program. The first two commands are
the "documented two-command quick start" that the design names as the answer to two programs being harder
to start than one.

`scripts/quick-start-check.sh` follows the quick start in a container. It takes the commands from the
README itself, so there's no second copy of them that could differ:

- Every block of the section "Quick start" that's marked `shell` runs as it stands, in a shell of its own,
  in a directory that holds the two jars and nothing else.
- A block that starts a program leaves the program running. The script goes on when the output of the
  program says where it accepts connections, which is when the README calls a program ready.
- Every other block has to end with the status 0. The answer to the mock request has to be the text that
  the README shows below the command.
- The page that the README tells its reader to open answers with the status 200 and lists the service. Its
  link "History" leads to a page that shows the request with the scenario that answered it.
- The interrupt signal, which is what Ctrl+C sends, stops each program, and each ends with the status 0.

The Gradle task `quickStartCheck` builds the two jars and runs the script with them.

### Decisions

**The quick start uses the runnable jars.** A jar needs no unpacking, and its command is the same on every
system. The start scripts of the distributions need a POSIX shell with `xargs`, which an image with a Java
runtime alone doesn't always have. The documents of the two programs describe the distributions.

**The first mock is one command.** The quick start has to reach a mock answer, and it has to consist of
commands, so that it can be followed word for word. Through the admin API, a service with a scenario takes
two requests, and the second one needs the identifier from the answer to the first one. An import takes one
request, because its document states the identifiers. The quick start says that the pages of the UI do the
same without a command.

**The container is of the image `eclipse-temurin:21-jre`, and it has no network.** The image holds a Java
21 runtime and no JDK, on Ubuntu. The script refuses an image with a JDK or with another Java version.
Besides Java, the commands of the quick start need `curl`, which the image has. Without a network, the
container can fetch nothing, so the check shows that the quick start needs nothing that it doesn't name.

**The two jars of the build stand in for the download.** The README tells its reader to download the jars
from the releases page, or to build them. The script copies the jars of the build into the container. That's
the one step of the quick start that the check doesn't follow as it's written.

**The files are copied into the container, not mounted.** A mount needs a directory that the container
engine sees. An engine that runs in a virtual machine, as on macOS, sees only some directories of its host.
A copy works with every engine.

**Each block runs as a job with a process group of its own.** That's what a command is in a terminal, and
Ctrl+C there sends the interrupt signal to the process group. So the script switches job control on and
sends that signal to the group of the block. Without job control, a program that a script starts in the
background ignores the interrupt signal, and the script would have to stop it in another way than the
reader does.

**The script states what the reader sees in the UI.** The service `Greeting`, the link `History`, and the
answer `Scenario served: Hello` come from the example of the README, and the script can't take them from
its sentences. They're three settings at the top of the script, which change when the example does.

**The check isn't a part of `check`.** It needs a container tool with a running engine and an image, which
not every machine that builds the project has. What needs no container is held by tests of the build:
`ServerDocumentsTest` and `UiDocumentsTest` fail when the quick start doesn't start the runnable jars of
the build's version.

### The run that was verified

The check passed for the jars of the version 3.0.0-SNAPSHOT, in a container of `eclipse-temurin:21-jre`
with Temurin 21.0.13 on Ubuntu 26.04, under Docker 29 on macOS. A run takes about 8 seconds, of which the
UI needs 5 to stop.

35 changed copies were run to see that each part of the check can fail: 24 of the README, 5 of the two
scripts, and 6 of what the check runs with, such as an image with a JDK. Each copy fails with a message
that names what's wrong. At first, one of them didn't: the script ended without a message when the page had
no link with the expected text. That defect of the script is corrected.

### What stays open

- When the check ran for this task, no release was published, so nobody could follow the download step.
  The release candidate is published now, and the check has passed with the two jars from its page, see
  "The publication".
- The script takes `podman` where there's no `docker`, with the same commands. That wasn't tried, because
  the machine of the run has no engine for it.
- The CI job doesn't run the check. A step with `./gradlew quickStartCheck` would do it, and that's a change
  to the workflow, which is the owner's to decide.

## The release candidate

Task 15.5 asks for the 3.0.0 release candidate, with release notes that include the deviation register, and
for a check that the artifacts that the release notes list exist and start.

The candidate is the version 3.0.0-rc.1. A release is a directory with these files:

| File | What it is |
|---|---|
| `mockey-server-<version>-runnable.jar`, `mockey-ui-<version>-runnable.jar` | Each program as one file. |
| `mockey-server-<version>.zip` and `.tar.gz`, `mockey-ui-<version>.zip` and `.tar.gz` | Each program as a distribution with start scripts. |
| `SHA256SUMS` | The checksums of those six files. |
| `RELEASE-NOTES.md` | The release notes with the deviation register. |

Three Gradle tasks and a workflow make a release:

- `./gradlew releaseFiles` builds the six files of the programs and collects the release in `build/release`.
- `./gradlew releaseCheck` does the same and then checks the release.
- `./gradlew quickStartCheck` follows the quick start of the README with the two runnable jars.
- The workflow "Release" runs when the tag of a version is pushed. It checks the build, runs the two checks
  above, and publishes the release on GitHub.

### The release notes

`docs/release-notes/3.0.0.md` holds the release notes of 3.0.0, which are the notes of its candidates too.
They say what the release is, what it needs, which files it has, how to start, and what changes for someone
who comes from Mockey 2.x. Their last section introduces the deviation register and ends with a line that
stands for it. When a release is assembled, the table of `docs/deviations.md` takes the place of that line,
with all 75 entries as the register has them.

### The release check

`scripts/release-check.sh` takes a release directory, and it checks what the task asks for:

1. The release notes list seven files in the table of their section "The files": the six files of the
   programs and the checksums. Each of them is in the directory, and the directory holds no other file.
2. The release notes have every entry of the register word for word, and no entry that the register
   doesn't have.
3. The checksums are those of the files.
4. Each distribution unpacks and has its start scripts for Unix and for Windows. From each of its three
   files, each program says that it's of the version of the release. Then it starts, answers a request,
   and stops with the status 0. The server is asked for its status and, through the admin API, for its
   version. The UI is asked for a page, which shows the version of the UI and that of its server.

### Decisions

**The candidate has a version of its own.** The owner chose 3.0.0-rc.1. The programs say that version, the
files carry it in their names, and the documents name the files by it. So nobody takes a file of the
candidate for a file of the release. The release 3.0.0 is one more change of the version and one more
build.

**A script sets the version.** Eight files name it: the build, the README, three documents of the programs,
the document of the library, the embedded sample, and the release notes. `scripts/set-version.sh` changes
them together, and it changes nothing when one of them doesn't name the current version. It leaves the
release notes of an earlier release as they are. The tests of the build hold the result: the document tests
of the server and of the UI fail when one of those files names another version. The script changes no
sentence. A person reads again what the README and the release notes say of the state of the project.

**The register is put into the release notes when a release is assembled.** The repository has the register
once, in `docs/deviations.md`. A second copy in the release notes would have to be kept the same by hand.
The release check compares the assembled notes with the register, entry by entry.

**The links of the release notes lead to the documents of the tag.** The page of a release doesn't resolve
a link that's relative to a file of the repository. So the notes name each document by its URL at the tag of
the version, which the version script keeps in step. Until the tag is pushed, those links lead nowhere.

**The release check starts each program from each of its files.** That's six starts for the three forms of
the two programs, and it takes about 25 seconds. A distribution without a start script, a jar that can't
run, and a file of another version each fail the check. The start scripts for Windows are only looked for,
because the check runs in a Unix shell.

**The checks of a release aren't parts of `check`.** Release notes belong to a release, and a version on
the way to a release that has no notes yet is no mistake. The workflow runs the checks when a release is
published.

**A workflow publishes the release.** The owner chose that as well. The GitHub CLI on the machine that
built the candidate is signed in with an account that can only read the repository, so that machine can
push a tag and can't publish a release. The workflow needs no secret of a person. It uses the token of its
run, which its one job lets write the contents of the repository. It uses no third-party action either:
`gh` is on the runner, and `scripts/publish-release.sh` calls it.

**The workflow checks before it publishes.** It runs `./gradlew check` like the job for every push, and
then the release check and the quick-start check. A tag that isn't `v` and the version of the build fails
the job at its first step. So a release is published only from a commit whose build is green and whose
files start.

**A version that goes on after a hyphen becomes a pre-release.** 3.0.0-rc.1 is published as a pre-release.
A tag such as `v3.0.0` is published as a release, because nothing in its version says otherwise. The owner
asked for the pre-release of the candidate, and what happens for a release follows from that rule in
`scripts/publish-release.sh`.

### The run that was verified

The candidate was built and checked on macOS, and the scripts were run on Linux as well:

- `./gradlew releaseCheck` passed. The release notes list the seven files, the directory holds them and no
  other, the notes have the 75 entries of the register, the checksums are right, and each of the two
  programs starts from each of its three files as the version 3.0.0-rc.1.
- `./gradlew quickStartCheck` passed with the two runnable jars of the candidate, which the README names
  now.
- In a container of Ubuntu with the tools of that system, the release check passed for the same files, and
  the assembly wrote the same release notes and the same checksums as on macOS.

25 changed copies of the release were given to the release check, such as a release without a file that
the notes list, release notes without an entry of the register, a jar of another version, and a
distribution without its start script. The check failed for each with a message that names what's wrong.
The assembly refused 8 kinds of wrong input, and the version script was tried for a candidate, a release,
and the version after a release. The script that publishes was run with a stand-in for `gh`.

### The publication

The tag `v3.0.0-rc.1` is on the commit `1113d73`, whose CI job was green before the tag was pushed. The push
of the tag started the workflow "Release".
[Its run 45891913650](https://github.com/dnedrow/MockeyRedux/actions/runs/45891913650) passed at the first
attempt, in under 16 minutes. It checked the build with its 2522 tests, ran the release check and the
quick-start check, and published the pre-release
[Mockey Redux 3.0.0-rc.1](https://github.com/dnedrow/MockeyRedux/releases/tag/v3.0.0-rc.1) with the seven
files. The page of the release shows the release notes with the register.

After that, the published release was checked as a reader gets it:

- The seven files were downloaded from the page of the release, without signing in.
- The release check passed for the downloaded files, with the release notes of the page.
- The quick-start check passed with the two downloaded jars. So the quick start of the README works from
  its first step, the download, to its last.
- Every link of the release notes leads to a page that's there.
- The checksums of the published files are those of the files that were built on macOS before the tag. The
  runner and that machine built the same files, byte for byte.

### The second candidate

The owner tried the first candidate on a network that inspects TLS traffic. There the server answered a
request for an `https` target with the status 502 and the words of the Java runtime,
`PKIX path building failed`, which don't say what to do. Since the commit `464d25b`, that answer also says
that the trust store of the runtime needs the root certificate of the issuer, and the server document has
the section "The certificate of a real service". [upstream-client.md](upstream-client.md) records that
change. The owner asked for a second candidate with it.

The second candidate is the version 3.0.0-rc.2. It was made by the steps of "Making a release" below:

- `scripts/set-version.sh 3.0.0-rc.2` changed the version in the eight files.
- Two things in the release notes were written by hand, because the script changes no sentence. The notes
  say that this is the second candidate, and their new section "What changed since the first candidate"
  describes the hint and links to the new section of the server document. Both are about a candidate, so
  they change again when the notes become those of the release.
- The README needed no change. It says that the releases page has the release candidates.
- `./gradlew check releaseCheck quickStartCheck` passed on macOS. While it ran, a server and a UI of the
  owner held the default ports, so the five tests that need those ports were skipped there: the four that
  run the commands of the documents, and the one that starts the server with its defaults. The CI job runs
  them, and the tag is pushed only after that job is green.

Nothing but the hint is different in the two programs and in the library. The other commits since the first
candidate change tests, the traceability check, documents, and the files of OpenSpec.

### Making a release

1. Set the version with `scripts/set-version.sh <version>`, and read what the README and the release notes
   say of the state of the project. The first version of a new release needs release notes of its own,
   `docs/release-notes/<release>.md`, with the table of the files and the line that stands for the register.
   For another candidate of a release, the notes say which candidate it is and what changed since the one
   before it.
2. Run `./gradlew check releaseCheck quickStartCheck`.
3. Commit, push, and wait until the CI job is green.
4. Tag that commit and push the tag: `git tag -a v<version> -m "Mockey Redux <version>"`, and then
   `git push origin v<version>`.
5. The workflow "Release" publishes the release. Look at its page, and download a file.
6. After a release, set the version that comes next, so that a later build isn't taken for the release.
   After a candidate, the version stays, so that the README names files that the releases page has.

### What stays open

- The version of the repository is that of the latest candidate, 3.0.0-rc.2, so that the README names
  files that the releases page has. The next change of the version is `scripts/set-version.sh 3.0.0` for the
  release, or the version of another candidate.
- The library `mockey-core` isn't published to an artifact repository. The design leaves open whether it
  is, and where.
- What the trial of the second candidate finds decides what comes next: another candidate, or the release.

## Repeating the checks

```shell
./gradlew check
./gradlew endToEndCheck
./gradlew quickStartCheck
./gradlew releaseCheck
scripts/check-traceability.sh /path/to/Mockey
```

The first command is what the CI job runs. It includes the second one, which builds the two archives and
runs the end-to-end check alone. The end-to-end check needs `bash`, `tar`, and `curl`. To run it again for
archives that it has passed with, add `--rerun`. The third command needs Docker or Podman with a running
engine. The tool pulls the image when it isn't there, and `-PquickStartImage=` names another image. The
fourth command needs `unzip` as well, and the release notes of the release that the version leads to. The
fifth command needs a checkout of Mockey 2.x at the commit `88e31ed`.
