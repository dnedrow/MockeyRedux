# Request inspectors

An inspector is code that looks at the requests which services get. It says what it finds in a message, and
Mockey puts that message into the history entry of the request. Use an inspector for a check that the
[request rules](rules.md) of a service cannot make, such as the check of a signature or of a session token.

- A **global** inspector inspects the requests of every service.
- Any other inspector inspects the requests of the services that name it. A service names one inspector at
  most.

An inspector has no say in how a request is answered. When an inspector fails, the request is answered as
usual, and its history entry gets a message that names the inspector that failed.

Mockey finds the installed inspectors when it starts. It lists them with their names and says for each
whether it is global.

## Writing an inspector

An inspector is a class that implements the interface `io.github.dnedrow.mockey.core.inspection.Inspector`
of the library `mockey-core`:

| Member | Meaning |
|---|---|
| `name` | The name by which a service names the inspector, and under which the list of inspectors shows it. It must not be blank, and no other installed inspector can have it. |
| `isGlobal` | Whether the inspector inspects the requests of every service. An inspector that does not say so is not global. |
| `inspect(request)` | Looks at one request and returns the message for its history entry. It returns `null` or a blank text when there is nothing to say. |

The request is a `io.github.dnedrow.mockey.core.request.MockRequest`. It gives an inspector what the client
sent:

| Member | Meaning |
|---|---|
| `method` | The HTTP method. |
| `url` | The URL that the client addressed, as the client wrote it and without its query string, such as `http://mockey.test:8080/service/customer/123`. |
| `query` | The query string as the client wrote it, or `null`. |
| `headers` | Every header, in the order of the request. `header(name)` gives the first value of a header, and `headerValues(name)` gives all of its values. The letter case of the name does not count. |
| `parameters` | The parameters of the query string and the fields of a URL-encoded form, decoded and in the order of the request. `parameterValues(name)` gives the values of the parameter with exactly that name. |
| `bodyText` | The body as text, in the charset that the request declares or in UTF-8. |
| `body` | The body as bytes. |
| `contentType` | The value of the `Content-Type` header, or `null`. |

Keep these points in mind:

- Mockey creates one instance of an inspector, and that instance inspects all requests. Several threads can
  call it at the same time. Keep nothing about a request in a field.
- Mockey reads `name` and `isGlobal` once, when it installs the inspector.
- An inspector runs while its request is handled, so it must be quick.
- A history entry holds a message once, also when two inspectors return the same text.
- An exception that `inspect` throws does no harm to the request. The history entry then says that the
  inspector failed, and why.

In Java, the members are the methods `getName()`, `isGlobal()`, and `inspect(MockRequest)`. `isGlobal()` has
a default implementation, which returns `false`.

## The sample inspector

This inspector reports every request that carries the parameter `foobarKey`. It is the sample inspector of
Mockey 2.x, ported:

```kotlin
package com.example.inspectors

import io.github.dnedrow.mockey.core.inspection.Inspector
import io.github.dnedrow.mockey.core.request.MockRequest

/**
 * The sample inspector of Mockey 2.x, ported. It reports every request that carries the parameter `foobarKey`.
 */
class SampleRequestInspector : Inspector {
    /** The class name of the inspector in Mockey 2.x, so that the services that named it there still name it. */
    override val name: String = "com.mockey.plugin.SampleRequestInspector"

    /** The inspector inspects the requests of every service, as it did in Mockey 2.x. */
    override val isGlobal: Boolean = true

    override fun inspect(request: MockRequest): String? =
        if (request.parameters.any { it.name == PARAMETER }) {
            "Howdy! This isn't a real error. This is here to show you that you can use request inspectors to " +
                "validate incoming requests. You are seeing this message because the request parameter " +
                "'$PARAMETER' was in the request."
        } else {
            null
        }

    private companion object {
        const val PARAMETER = "foobarKey"
    }
}
```

## Installing an inspector

1. Compile the class against `mockey-core`. The class must be public and must have a public constructor
   without parameters.
2. Put the class into a jar. Add to the jar the file
   `META-INF/services/io.github.dnedrow.mockey.core.inspection.Inspector`, which holds the name of the class.
   The file holds one line for each inspector of the jar:

   ```text
   com.example.inspectors.SampleRequestInspector
   ```

3. Put the jar on the class path of the program that runs Mockey.

Mockey leaves out an inspector that it cannot install, and installs the others. That is the case for a class
that cannot be loaded or created, for an inspector without a name, and for an inspector whose name another
inspector has: of two inspectors with the same name, the first one that Mockey finds is installed.

## Naming an inspector in a service

A service has an inspector name. Set it to the `name` of an inspector, letter for letter, and that inspector
inspects the requests of the service. A global inspector needs no service to name it.

A service can name an inspector that is not installed. Its requests are answered as usual, no inspector
inspects them on behalf of that name, and the definition diagnostics report the service with the name of the
missing inspector.

## Porting an inspector of Mockey 2.x

Mockey does not run the inspectors of Mockey 2.x as they are. A port is small, because the new interface asks
for less. This is the sample inspector of Mockey 2.x, shortened:

```java
package com.mockey.plugin;

public class SampleRequestInspector implements IRequestInspector {
    private final String FOOBAAR = "foobarKey";
    private String errorMessage = null;

    public void analyze(RequestFromClient request) {
        Map<String, String[]> parametersAsMap = request.getParameters();
        if (parametersAsMap.get(FOOBAAR) != null) {
            this.errorMessage = "Howdy! This isn't a real error. ...";
        }
    }

    public String getPostAnalyzeResultMessage() {
        return this.errorMessage;
    }

    public boolean isGlobal() {
        return true;
    }
}
```

"The sample inspector" above shows its port. To port an inspector, change these things:

| Mockey 2.x | Mockey |
|---|---|
| The class implements `com.mockey.plugin.IRequestInspector`. | The class implements `io.github.dnedrow.mockey.core.inspection.Inspector`. |
| `analyze(request)` keeps a message in a field, and `getPostAnalyzeResultMessage()` returns it. | `inspect(request)` returns the message. |
| Mockey creates an instance of the class for every request. | One instance inspects all requests. Remove the field that keeps the message. |
| `isGlobal()` | `isGlobal`. It can be left out for an inspector that is not global. |
| A service names the inspector by the name of its class. | A service names the inspector by its `name`. Give the port the full class name of the old inspector as its `name`. The services that are imported from Mockey 2.x then name the port, and need no change. |
| The package of the class has the annotation `@MockeyRequestInspector`, and the jar is built into `Mockey.jar`. | The jar names the class in `META-INF/services/io.github.dnedrow.mockey.core.inspection.Inspector`, and it is put on the class path. See "Installing an inspector". |

The request has another type, and it shows more of the request than `RequestFromClient` did:

| `RequestFromClient` | `MockRequest` |
|---|---|
| `getMethod()` | `method` |
| `getRequestURL()` | `url` |
| `getParameters()`, a map from each name to its values, without the fields of a form | `parameters`, a list of names and values in the order of the request, with the fields of a URL-encoded form. `parameterValues(name)` gives the values of one name. |
| `getHeaderInfoAsMap()`, without `Host`, `Cookie`, `Content-Length`, `Accept-Encoding`, and `If-None-Match` | `headers`, with every header. `headerValues(name)` gives the values of one header. |
| `getBodyInfo()` and `hasPostBody()` | `bodyText`, which is empty for a request without a body |
| `getHttpClientCookies()` | The values of the `Cookie` header: `headerValues("Cookie")` |

`docs/deviations.md` describes what an inspector sees differently: every header (DEV-16), the fields of a
form (DEV-17), and the body in the charset that the request declares (DEV-57).
