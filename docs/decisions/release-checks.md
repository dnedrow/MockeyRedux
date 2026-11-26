# The integration and release checks

Decided in task 15.1 of the change `kotlin-rewrite-modular`, on 2026-11-26.

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

## Repeating the checks

```shell
./gradlew endToEndCheck
```

The command builds the two archives and runs the end-to-end check. It needs `bash`, `tar`, and `curl`. To
run the check again for archives that it has passed with, add `--rerun`.
