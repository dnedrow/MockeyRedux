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

## Write a definitions directory

Started with `-t false`, legacy is in writable mode. It stores its definitions in the directory that `-l`
names: `mock_service_definitions.xml` is the main file, and the files of the services are under
`mockey_def_depot`. The tests of the importer in `mockey-legacy` import such a directory,
`mockey-legacy/src/test/resources/legacy/directory`. Legacy wrote it from the export document
`legacy/export.xml` next to it, which is the export of the instance that wrote the directory.

From the legacy `dist` directory:

```shell
mkdir -p /tmp/mockey-legacy-writable
java -Dfile.encoding=UTF-8 -jar Mockey.jar \
  -p 18080 \
  -t false \
  -H true \
  -l /tmp/mockey-legacy-writable/definitions
```

Then, with the path of the export document in the checkout of this repository:

```shell
EXPORT=/path/to/mockey-kt/mockey-legacy/src/test/resources/legacy/export.xml
curl "http://127.0.0.1:18080/home?action=init&transientState=false&type=json&file=$EXPORT"
curl "http://127.0.0.1:18080/twisting/toggle?twist-id=1&twist-enable=true&response-type=json"
curl --output export.xml "http://127.0.0.1:18080/export/definitions.xml"
```

The first request loads the document and stores it in `/tmp/mockey-legacy-writable/definitions`. Legacy
enables no twist configuration when it loads a document (DEV-32), so the second request enables the one of
the fixture, which puts `universal_twist_info_id` into the main file. The third request is the export.

Apart from the `timestamp` attribute of the main file and of the export, both come out as they are in this
repository. Legacy keeps its log, `mockeyDebugFile.log`, in the definitions directory as well. It is no part
of the fixture.
