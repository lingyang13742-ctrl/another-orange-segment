/*
 * 橘瓣 OrangeChat
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

package me.rerere.rikkahub.data.service

import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

/**
 * 定位结果，明确标注数据来源和新鲜度，调用方不应该"看起来正常就当作新鲜数据用"。
 */
data class FetchedLocation(
    val location: Location,
    /** true = 本次实际发起了新定位并拿到结果，或者缓存仍在新鲜期内；false = 使用的是已过期的系统缓存兜底 */
    val isFresh: Boolean,
    /** 这份数据距离现在过去了多久（毫秒），isFresh=true 时应接近 0 */
    val ageMs: Long,
)

internal object DeviceLocationFetcher {

    /** 认为缓存定位仍然新鲜、可以直接使用而不必重新定位的最大时长（与原 LocationHelper.MAX_CACHE_AGE_MS 保持一致） */
    private const val MAX_FRESH_AGE_MS = 5 * 60 * 1000L

    /** 单个 provider 等待新定位结果的超时时间 */
    private const val PER_PROVIDER_TIMEOUT_MS = 8_000L

    @SuppressLint("MissingPermission")
    suspend fun fetch(context: Context): FetchedLocation? = withContext(Dispatchers.IO) {
        val lm = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
            ?: return@withContext null

        val cached = getCachedLocation(lm)
        val now = System.currentTimeMillis()
        val cachedAge = cached?.let { now - it.time }

        if (cached != null && cachedAge != null && cachedAge <= MAX_FRESH_AGE_MS) {
            return@withContext FetchedLocation(cached, isFresh = true, ageMs = cachedAge)
        }

        // 缓存为空或已过期：并发向 GPS + NETWORK 两个 provider 请求，谁先返回用谁，
        // 而不是只挑一个 provider 死等，避免室内/GPS信号差场景下白白等满超时。
        val fresh = requestFreshLocationRacing(lm)
        if (fresh != null) {
            return@withContext FetchedLocation(fresh, isFresh = true, ageMs = 0L)
        }

        // 新定位彻底失败：如果确实没有任何缓存，返回 null；
        // 如果有缓存但已过期，仍然返回它，但必须如实标注 isFresh=false + 真实的 ageMs，
        // 交给调用方自己决定要不要用、要不要在展示里提示"位置可能不准"。
        // 绝不能像之前那样把过期缓存悄悄包装成看起来正常的数据返回。
        if (cached != null && cachedAge != null) {
            return@withContext FetchedLocation(cached, isFresh = false, ageMs = cachedAge)
        }
        null
    }

    @SuppressLint("MissingPermission")
    private fun getCachedLocation(lm: LocationManager): Location? {
        return try {
            val providers = lm.getProviders(true)
            providers.mapNotNull { lm.getLastKnownLocation(it) }
                .maxByOrNull { it.accuracy }
        } catch (e: Exception) {
            android.util.Log.e("DeviceLocationFetcher", "getCachedLocation failed", e)
            null
        }
    }

    /**
     * 并发请求 NETWORK_PROVIDER 和 GPS_PROVIDER，谁先给出结果就用谁，
     * 避免像之前那样只挑 providers.firstOrNull() 这一个 provider 死等到超时。
     * 室内/弱信号场景下 NETWORK_PROVIDER 通常比 GPS_PROVIDER 快得多。
     */
    @SuppressLint("MissingPermission")
    private suspend fun requestFreshLocationRacing(lm: LocationManager): Location? = coroutineScope {
        val availableProviders = try {
            lm.getProviders(true)
        } catch (e: Exception) {
            android.util.Log.e("DeviceLocationFetcher", "getProviders failed", e)
            emptyList()
        }

        val candidateProviders = listOfNotNull(
            LocationManager.NETWORK_PROVIDER.takeIf { it in availableProviders },
            LocationManager.GPS_PROVIDER.takeIf { it in availableProviders },
        ).ifEmpty { availableProviders } // 两个都不可用时，退回原有的"能用啥用啥"

        if (candidateProviders.isEmpty()) return@coroutineScope null

        val deferredResults = candidateProviders.map { provider ->
            async { requestSingleProviderLocation(lm, provider) }
        }

        // 谁先返回非空结果就用谁；全部超时/失败则返回 null
        var result: Location? = null
        for (deferred in deferredResults) {
            val loc = try {
                deferred.await()
            } catch (e: Exception) {
                android.util.Log.e("DeviceLocationFetcher", "provider request failed", e)
                null
            }
            if (loc != null && result == null) {
                result = loc
            }
        }
        // 取消还没完成的其余请求，避免残留的 LocationListener 一直挂着
        deferredResults.forEach { if (it.isActive) it.cancel() }
        result
    }

    @SuppressLint("MissingPermission")
    private suspend fun requestSingleProviderLocation(lm: LocationManager, provider: String): Location? {
        return try {
            withTimeoutOrNull(PER_PROVIDER_TIMEOUT_MS) {
                suspendCancellableCoroutine<Location?> { cont ->
                    val listener = object : LocationListener {
                        override fun onLocationChanged(location: Location) {
                            lm.removeUpdates(this)
                            if (cont.isActive) cont.resume(location)
                        }

                        override fun onProviderDisabled(p: String) {
                            lm.removeUpdates(this)
                            if (cont.isActive) cont.resume(null)
                        }

                        @Deprecated("Deprecated in Java")
                        override fun onStatusChanged(p: String?, status: Int, extras: Bundle?) {}

                        override fun onProviderEnabled(p: String) {}
                    }

                    cont.invokeOnCancellation {
                        try {
                            lm.removeUpdates(listener)
                        } catch (e: Exception) {
                            android.util.Log.e("DeviceLocationFetcher", "removeUpdates on cancel failed, provider=$provider", e)
                        }
                    }

                    try {
                        lm.requestLocationUpdates(provider, 0L, 0f, listener)
                    } catch (e: Exception) {
                        android.util.Log.e("DeviceLocationFetcher", "requestLocationUpdates failed, provider=$provider", e)
                        if (cont.isActive) cont.resume(null)
                    }
                }
            }
        } catch (e: Exception) {
            android.util.Log.e("DeviceLocationFetcher", "requestSingleProviderLocation failed, provider=$provider", e)
            null
        }
    }
}