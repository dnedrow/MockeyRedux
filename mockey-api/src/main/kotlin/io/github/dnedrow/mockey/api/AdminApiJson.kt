package io.github.dnedrow.mockey.api

import kotlinx.serialization.json.Json

/** JSON settings shared by admin API clients and the server. */
public object AdminApiJson {
    /** JSON codec for admin API request and response bodies. */
    public val instance: Json =
        Json {
            classDiscriminator = "type"
            encodeDefaults = true
            explicitNulls = true
        }
}
