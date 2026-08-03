package com.example.lcb.music.internal

import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

/**
 * 使用 OkHttp 异步调用并把协程取消传递给 Call。搜索关键词变化或页面销毁时会立即释放 socket，
 * 不占用阻塞线程等待 readTimeout，也避免过期请求继续消耗平台 Key 配额。
 */
internal suspend fun OkHttpClient.getJson(request: Request): String = suspendCancellableCoroutine { continuation ->
    val call = newCall(request)
    continuation.invokeOnCancellation { call.cancel() }
    call.enqueue(
        object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (continuation.isCancelled) return
                continuation.resumeWith(
                    Result.failure(
                        ProviderRequestException(null, message = "Network request failed", cause = e),
                    ),
                )
            }

            override fun onResponse(call: Call, response: Response) {
                response.use {
                    val result = runCatching { response.requireJsonBody(request) }
                    result.onSuccess { body ->
                        if (continuation.isActive) continuation.resumeWith(Result.success(body))
                    }.onFailure { failure ->
                        if (!continuation.isCancelled) {
                            continuation.resumeWith(Result.failure(failure))
                        }
                    }
                }
            }
        },
    )
}

private fun Response.requireJsonBody(request: Request): String {
    if (!isSuccessful) {
        val retryAfter = header("Retry-After")?.let(::parseRetryAfter)
        throw ProviderRequestException(
            statusCode = code,
            retryAfterMs = retryAfter,
            message = "HTTP $code from ${request.url.host}",
        )
    }
    return body?.string() ?: throw ProviderRequestException(
        statusCode = code,
        message = "Empty response from ${request.url.host}",
    )
}

private fun parseRetryAfter(value: String): Long? {
    value.toLongOrNull()?.let { return it.coerceAtLeast(0) * 1_000 }
    return runCatching {
        (ZonedDateTime.parse(value, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli() -
            System.currentTimeMillis()).coerceAtLeast(0)
    }.getOrNull()
}

/**
 * 只有凭据失效或单 Key 限流才切换凭据。
 *
 * 断网、DNS、超时和平台 5xx 与具体 Key 无关：继续轮询 Key 不仅会放大无效请求，还会让所有
 * Key 进入冷却，导致网络恢复后的用户 Retry 无法真正发出请求。这类错误直接交给聚合层，
 * 由聚合层尝试其他平台；下一次业务 Retry 仍可立即使用原凭据。
 */
internal suspend fun <Credential, Result> withKeyFailover(
    pool: KeyPool<Credential>,
    block: suspend (Credential) -> Result,
): Result {
    val candidates = pool.candidates()
    if (candidates.isEmpty()) throw MusicSdkException("No healthy credential is currently available")
    var lastFailure: ProviderRequestException? = null
    for (key in candidates) {
        try {
            return block(key)
        } catch (failure: ProviderRequestException) {
            val credentialSpecificFailure = failure.invalidCredential || failure.rateLimited
            if (!credentialSpecificFailure) throw failure
            pool.markFailure(key, failure)
            lastFailure = failure
        }
    }
    throw MusicSdkException("All credentials failed", lastFailure)
}
