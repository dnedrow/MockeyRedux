# Running the Legacy Mockey Reference

The parity reference is legacy Mockey commit `88e31ed5c0473b641e1ed195ffb781991e17dd95`.
Run it from an isolated checkout or archive so generated build output and runtime state do
not modify the legacy working tree.

## Prerequisites

- Apache Ant 1.10 or newer
- JDK 8

JDK 21 can compile the legacy Java sources, but the bundled JSP compiler is not compatible
with modern class files. JDK 8 is therefore required for the complete web application build.

## Build

From the legacy checkout:

```shell
export JAVA_HOME=/path/to/jdk-8
export PATH="$JAVA_HOME/bin:$PATH"
ant clean webapp create-jar
```

The executable is written to `dist/Mockey.jar`.

The legacy `ant dist` target also runs the historical TestNG suite. At commit `88e31ed`,
that suite reports three pre-existing failures, so the parity reference build uses the
equivalent packaging targets above without treating those unrelated test failures as a
packaging failure.

## Start headless and read-only

From the legacy `dist` directory:

```shell
mkdir -p /tmp/mockey-legacy-readonly
java -jar Mockey.jar \
  -p 18080 \
  -t true \
  -H true \
  -l /tmp/mockey-legacy-readonly
```

The options mean:

- `-p 18080`: listen on port 18080.
- `-t true`: use transient/read-only mode.
- `-H true`: do not open a browser.
- `-l /tmp/mockey-legacy-readonly`: keep runtime state outside the checkout.

## Verify the reference instance

```shell
curl --fail --silent --show-error http://127.0.0.1:18080/status
```

The response must be JSON containing `"status": "running"`, for example:

```json
{
  "status": "running",
  "since": "11/22/2026 | 11:10:56",
  "repoPath": "/tmp/mockey-legacy-readonly"
}
```
