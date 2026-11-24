# Embedding Mockey

`mockey-core` is a JVM library. It opens no listener and has no HTTP server
dependency: an application creates an engine, defines services in code, and
hands requests to the engine as values.

## Dependency

Publish the library to a local Maven repository while developing:

```shell
./gradlew :mockey-core:publishToMavenLocal
```

Then add the snapshot to a Kotlin/JVM application:

```kotlin
repositories {
    mavenLocal()
    mavenCentral()
}

dependencies {
    implementation("io.github.dnedrow:mockey-core:3.0.0-SNAPSHOT")
}
```

The runnable example in [`samples/embedded`](../samples/embedded) uses this
setup and is built independently of the root project.

## Define and handle a request

```kotlin
import io.github.dnedrow.mockey.core.engine.MockeyEngine
import io.github.dnedrow.mockey.core.request.MockRequest
import io.github.dnedrow.mockey.core.model.ResponseMode

suspend fun answerCustomerRequest(): String {
    val engine = MockeyEngine.create()
    try {
        val service = engine.services.create {
            it.copy(
                name = "Customers",
                mockUrl = "customer/{id}",
                responseMode = ResponseMode.STATIC,
            )
        }.getOrThrow()

        engine.scenarios.create(service.id) {
            it.copy(name = "found", responseBody = """{"id": 1}""")
        }.getOrThrow()

        val handled = engine.handle(MockRequest.forTarget("GET", "customer/1"))
        return handled.response.bodyText
    } finally {
        engine.close()
    }
}
```

`MockeyEngine.create` defaults to in-memory definitions, the built-in upstream
client, installed inspectors, and the system clock. Pass an
`EngineOptions` configuration block to replace storage, the upstream client,
inspectors, or clock. Close the engine when it is no longer needed; `use`
does that automatically.
