package com.scoopai.cli.error

enum class ExitCode(val code: Int) {
    SUCCESS(0),
    GENERAL(1),
    USAGE(2),
    CONFIG(3),
    NO_ASSETS(64),
    LLM_ERROR(65),
    VERIFY_FAILED(66),
    IO_ERROR(70),
}
