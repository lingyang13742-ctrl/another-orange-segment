/*
 * 橘瓣 OrangeChat
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

@file:Suppress("unused")

package me.rerere.rikkahub.data.service

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class LocationInfo(
    val latitude: Double,
    val longitude: Double,
    val address: String = "",
    val city: String = "",
    val district: String = "",
    val street: String = "",
    val poiList: List<PoiInfo> = emptyList(),
    val isFresh: Boolean = true,   // 本次定位数据是否是新鲜的（刚定位到或在新鲜期内）；false 表示使用的是已过期缓存兜底
    val ageMs: Long = 0L,          // 这份数据距离现在过去了多久（毫秒），isFresh=true 时应接近 0
)

data class PoiInfo(
    val name: String,
    val address: String,
    val distance: Int,
    val type: String,
    val latitude: Double,
    val longitude: Double
)

class LocationService(
    private val context: Context,
    private val amapService: AmapService
) {
    suspend fun getCurrentLocation(amapApiKey: String): Result<LocationInfo> = withContext(Dispatchers.IO) {
        runCatching {
            val fetched = DeviceLocationFetcher.fetch(context)
                ?: throw IllegalStateException("无法获取位置信息")
            val location = fetched.location

            // GPS坐标(WGS84)需要先转换为高德坐标(GCJ02)才能正确逆地理编码
            val amapCoord = amapService.convertToAmapCoord(location.latitude, location.longitude)
            val lat = amapCoord?.first ?: location.latitude
            val lng = amapCoord?.second ?: location.longitude

            val address = amapService.reverseGeocode(lat, lng)
            if (!address.success) {
                android.util.Log.w("LocationService", "Reverse geocode failed: ${address.error}")
            }
            LocationInfo(
                latitude = location.latitude,
                longitude = location.longitude,
                address = address.formattedAddress ?: "",
                city = address.city ?: address.province ?: "",
                district = address.district ?: "",
                street = buildString {
                    append(address.street ?: "")
                    if (!address.streetNumber.isNullOrBlank()) {
                        append(address.streetNumber)
                    }
                },
                isFresh = fetched.isFresh,
                ageMs = fetched.ageMs
            )
        }
    }

    /**
     * 仅获取坐标，不需要高德API Key，不进行逆地理编码
     */
    suspend fun getCoordinatesOnly(): Result<LocationInfo> = withContext(Dispatchers.IO) {
        runCatching {
            val fetched = DeviceLocationFetcher.fetch(context)
                ?: throw IllegalStateException("无法获取位置信息")
            val location = fetched.location

            LocationInfo(
                latitude = location.latitude,
                longitude = location.longitude,
                isFresh = fetched.isFresh,
                ageMs = fetched.ageMs
            )
        }
    }

    suspend fun exploreNearby(
        amapApiKey: String,
        keyword: String = "",
        radius: Int = 1000,
        type: String = ""
    ): Result<List<PoiInfo>> = withContext(Dispatchers.IO) {
        runCatching {
            val fetched = DeviceLocationFetcher.fetch(context)
                ?: throw IllegalStateException("无法获取位置信息，请先开启定位")
            val location = fetched.location

            amapService.searchNearbyPoi(
                latitude = location.latitude,
                longitude = location.longitude,
                keyword = keyword,
                radius = radius,
                type = type
            ).map { poi ->
                PoiInfo(
                    name = poi.name,
                    address = poi.address,
                    distance = poi.distance,
                    type = poi.type,
                    latitude = poi.latitude,
                    longitude = poi.longitude
                )
            }
        }
    }
}