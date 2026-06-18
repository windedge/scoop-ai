package com.scoopai.cli.error

private val SENSITIVE_ENV_VARS = setOf(
    "SCOOP_AI_API_KEY",
    "SCOOP_AI_DEBUG_HTTP",
    "DEEPSEEK_API_KEY",
    "OPENAI_API_KEY",
    "ANTHROPIC_API_KEY",
    "OPENROUTER_API_KEY",
    "GITHUB_TOKEN",
    "GH_TOKEN",
)

private const val REDACTED = "***REDACTED***"

/**
 * Masks sensitive information in the given input string.
 *
 * This is a pure function with no side effects - it does not access environment variables,
 * perform I/O, or rely on external state. It only transforms the input string based on
 * known patterns for credentials, API keys, and tokens.
 *
 * Patterns masked:
 * - Values of sensitive environment variables (SCOOP_AI_API_KEY, GITHUB_TOKEN, etc.)
 * - Authorization Bearer tokens
 * - api-key header values
 * - key=xxx and token=xxx patterns
 * - Standalone API key values starting with sk- prefix
 */
fun mask(input: String): String {
    if (input.isEmpty()) return input

    var result = input

    // 1. Mask sensitive environment variable values: VAR_NAME=value
    for (varName in SENSITIVE_ENV_VARS) {
        result = result.replace(
            Regex("""\b${Regex.escape(varName)}="?[^\s;'"]+""")
        ) { match ->
            val eqIdx = match.value.indexOf('=')
            val prefix = match.value.substring(0, eqIdx + 1)
            val rest = match.value.substring(eqIdx + 1)
            if (rest.startsWith("\"")) {
                "$prefix\"$REDACTED"
            } else {
                "$prefix$REDACTED"
            }
        }
    }

    // 2. Mask Authorization: Bearer/Token/Basic xxx header
    result = result.replace(
        Regex("""(?i)(Authorization:\s*(?:Bearer|Token|Basic)?\s*)\S+""")
    ) { match ->
        "${match.groupValues[1]}$REDACTED"
    }

    // 3. Mask api-key: xxx header
    result = result.replace(
        Regex("""(?i)(api-key:\s*)\S+""")
    ) { match ->
        "${match.groupValues[1]}$REDACTED"
    }

    // 4. Mask key=xxx or token=xxx
    result = result.replace(
        Regex("""\b(key|token)=[^\s;'"]+""")
    ) { match ->
        "${match.groupValues[1]}=$REDACTED"
    }

    // 5. Mask standalone API key values (preserve sk- prefix)
    result = result.replace(
        Regex("""(?<!\w)(sk-)\w{5,}""")
    ) { match ->
        "${match.groupValues[1]}$REDACTED"
    }

    // 6. Mask standalone GitHub PAT tokens (preserve prefix)
    result = result.replace(
        Regex("""(?<!\w)(gh[pousr]_|github_pat_)[A-Za-z0-9_]{10,}""")
    ) { match ->
        "${match.groupValues[1]}$REDACTED"
    }

    return result
}
