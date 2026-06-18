package com.scoopai.cli.diagnostic

/**
 * A single health check that can be run by [DoctorCommand].
 */
interface HealthCheck {
    val name: String
    suspend fun run(): HealthResult
}

data class HealthResult(
    val status: Status,
    val message: String,
    val details: String? = null,
)

enum class Status { OK, WARN, FAIL }
