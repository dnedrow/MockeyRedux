# Third-party files

Most of what Mockey takes from others is a library. Gradle resolves the libraries from the version catalog
in `gradle/libs.versions.toml`, and none of them is in the source tree. This page lists the files of others
that are in the source tree, with where each one comes from and how to replace it.

## htmx

htmx is the one script of the web UI. Every page loads it, and it updates a part of a page in place where
the page is marked for it. [decisions/ui-stack.md](decisions/ui-stack.md) says how the UI uses it.

| Fact | Value |
|---|---|
| File | `mockey-ui/src/main/resources/io/github/dnedrow/mockey/ui/assets/htmx.min.js` |
| Version | 2.0.11 |
| Size | 52,182 bytes |
| SHA-256 | `d6fdc75f204e6bdefa99b69bf1e6d4ac69b8a364f77929f45c13476b4000f717` |
| Origin | The file `dist/htmx.min.js` of the npm package `htmx.org`, version 2.0.11 |
| Package | `https://registry.npmjs.org/htmx.org/-/htmx.org-2.0.11.tgz` |
| Integrity of the package | `sha512-Thx/WtpeOQqSrqBCw/A1cwGJGg4UrVa3+sW0GmrM3p4gJgO89ecH4qtbnyzDDWFvBTqjnIMCgELTNt636dtamA==` |
| Project | `https://github.com/bigskysoftware/htmx` |
| License | Zero-Clause BSD (`0BSD`). The text is in `htmx-LICENSE.txt`, next to the file. |

The file is the published one, byte for byte:

- The package was downloaded from the npm registry, and its SHA-512 checksum matches the integrity value
  that the registry states for the version.
- The file of the package is the same as `dist/htmx.min.js` at the tag `v2.0.11` of the project on GitHub.
- The test `theScriptIsTheFileThatWasVendoredAndEveryPageLoadsItFromTheUi` in `UiPagesTest` fails if the
  file has another checksum or states another version than this page.

The license file is a resource of the program, so it's part of the jar of the UI. The UI doesn't serve it.

### Updating htmx

1. Read the release notes of every version in between. The UI depends on the behavior that
   [decisions/ui-stack.md](decisions/ui-stack.md) lists under "What the UI relies on".
2. Download the package of the new version from the npm registry, and compare its SHA-512 checksum with
   the integrity value that `https://registry.npmjs.org/htmx.org/<version>` states:

   ```shell
   curl -sO https://registry.npmjs.org/htmx.org/-/htmx.org-<version>.tgz
   openssl dgst -sha512 -binary htmx.org-<version>.tgz | base64
   ```

3. Unpack the package, and copy `package/dist/htmx.min.js` and `package/LICENSE` over `htmx.min.js` and
   `htmx-LICENSE.txt`.
4. Update the table above, and the constants `SCRIPT_VERSION` and `SCRIPT_SHA_256` in `UiPagesTest`:

   ```shell
   shasum -a 256 mockey-ui/src/main/resources/io/github/dnedrow/mockey/ui/assets/htmx.min.js
   ```

5. Run `./gradlew :mockey-ui:test`.
6. Go through the checks under "A check in a browser" in [decisions/ui-stack.md](decisions/ui-stack.md).
   The tests run no browser, so they can't tell whether the new version still behaves the way they assume.
