package com.nuvio.tv.data.repository

import com.nuvio.tv.BuildConfig
import com.nuvio.tv.data.remote.api.CreditAnalyzeRequest
import com.nuvio.tv.data.remote.api.CreditAnalyzerApi
import com.nuvio.tv.data.remote.api.CreditAnalyzerJobResponse
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException

@Singleton
class CreditAnalyzerRepository @Inject constructor(
    private val api: CreditAnalyzerApi
) {
    val isConfigured: Boolean
        get() = BuildConfig.CREDIT_ANALYZER_BASE_URL.isNotBlank() &&
            BuildConfig.CREDIT_ANALYZER_TOKEN.isNotBlank()

    suspend fun submit(request: CreditAnalyzeRequest): Result<CreditAnalyzerJobResponse> {
        if (!isConfigured) return Result.failure(IllegalStateException("Credit analyzer is not configured"))
        return suspendResult {
            val response = api.analyze(authorization(), request)
            response.body().takeIf { response.isSuccessful && it != null }
                ?: error("Credit analyzer submit failed with HTTP ${response.code()}")
        }
    }

    suspend fun getJob(jobId: String): Result<CreditAnalyzerJobResponse> = suspendResult {
        val response = api.getJob(authorization(), jobId)
        response.body().takeIf { response.isSuccessful && it != null }
            ?: error("Credit analyzer poll failed with HTTP ${response.code()}")
    }

    private fun authorization(): String = "Bearer ${BuildConfig.CREDIT_ANALYZER_TOKEN}"
}

private suspend inline fun <T> suspendResult(crossinline block: suspend () -> T): Result<T> {
    return try {
        Result.success(block())
    } catch (error: CancellationException) {
        throw error
    } catch (error: Throwable) {
        Result.failure(error)
    }
}
