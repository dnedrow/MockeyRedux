# The integration and release checks

Decided in tasks 15.1 and 15.2 of the change `kotlin-rewrite-modular`, on 2026-11-26.

The last tasks of the change check the system as a whole: the two programs together, the build on a clean
machine, the documents against the legacy system, and the artifacts of a release. This record says what each
check does and why it's built the way it is.

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

## Repeating the checks

```shell
./gradlew check
./gradlew endToEndCheck
```

The first command is what the CI job runs. It includes the second one, which builds the two archives and
runs the end-to-end check alone. The end-to-end check needs `bash`, `tar`, and `curl`. To run it again for
archives that it has passed with, add `--rerun`.
