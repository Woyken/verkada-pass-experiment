package com.woyken.verkadapasstestapp.data

private val sensitiveJsonKeys = listOf(
    "magicToken",
    "userToken",
    "publicKey",
)

private val sensitiveQueryKeys = listOf(
    "magicToken",
    "userToken",
    "X-Verkada-Auth",
)

fun redactSensitive(text: String): String {
    var redacted = text

    for (key in sensitiveJsonKeys) {
        val pattern = Regex("""(?i)("$key"\s*:\s*")([^"]+)(")""")
        redacted = redacted.replace(pattern) { matchResult ->
            "${matchResult.groupValues[1]}${maskSecret(matchResult.groupValues[2])}${matchResult.groupValues[3]}"
        }
    }

    for (key in sensitiveQueryKeys) {
        val queryPattern = Regex("""(?i)($key=)([^&\s]+)""")
        redacted = redacted.replace(queryPattern) { matchResult ->
            "${matchResult.groupValues[1]}${maskSecret(matchResult.groupValues[2])}"
        }
    }

    return redacted
}

fun redactHeaders(headers: Map<String, String>): Map<String, String> =
    headers.mapValues { (name, value) ->
        if (name.equals("X-Verkada-Auth", ignoreCase = true)) {
            maskSecret(value)
        } else {
            value
        }
    }

fun maskSecret(value: String, prefix: Int = 6, suffix: Int = 4): String {
    if (value.length <= prefix + suffix) {
        return "*".repeat(value.length.coerceAtLeast(4))
    }
    return value.take(prefix) + "*".repeat((value.length - prefix - suffix).coerceAtLeast(4)) + value.takeLast(suffix)
}

