package com.scoopai.cli.error

sealed class CliError(
    val exit: ExitCode,
    message: String,
    cause: Throwable? = null,
) : RuntimeException(message, cause)

class UsageError(message: String, cause: Throwable? = null) : CliError(ExitCode.USAGE, message, cause)
class ConfigError(message: String, cause: Throwable? = null) : CliError(ExitCode.CONFIG, message, cause)
class NoAssetsError(message: String, cause: Throwable? = null) : CliError(ExitCode.NO_ASSETS, message, cause)
class LlmError(message: String, cause: Throwable? = null) : CliError(ExitCode.LLM_ERROR, message, cause)
class VerifyFailedError(message: String, cause: Throwable? = null) : CliError(ExitCode.VERIFY_FAILED, message, cause)
class IoError(message: String, cause: Throwable? = null) : CliError(ExitCode.IO_ERROR, message, cause)
class GeneralError(message: String, cause: Throwable? = null) : CliError(ExitCode.GENERAL, message, cause)
