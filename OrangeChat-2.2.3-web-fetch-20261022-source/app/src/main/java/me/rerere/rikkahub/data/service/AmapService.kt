/*
 * 橘瓣 OrangeChat
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

package me.rerere.rikkahub.data.service

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

/**
 * 高德地图API服务
 * 用于坐标转换和逆地理编码
 */
class AmapService(
    private val apiKey: String,
    private val okHttpClient: OkHttpClient = defaultClient
) {
    companion object {
        private const val BASE_URL = "https://restapi.amap.com/v3"
        
        private val defaultClient = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .build()
        
        /**
         * 坐标系类型
         */
        enum class CoordType(val code: String) {
            GPS("gps"),           // GPS原始坐标 (WGS84)
            AMAP("autonavi"),     // 高德坐标 (GCJ02)
            BAIDU("baidu")        // 百度坐标 (BD09)
        }
    }
    
    /**
     * 逆地理编码响应
     */
    @Serializable
    data class RegeoResponse(
        val status: String,
        val info: String,
        val infocode: String,
        val regeocode: RegeoCode? = null
    )
    
    @Serializable
    data class RegeoCode(
        val formatted_address: String? = null,
        val addressComponent: AddressComponent? = null
    )
    
    @Serializable
    data class AddressComponent(
        val country: String? = null,
        val province: String? = null,
        val city: String? = null,
        val citycode: String? = null,
        val district: String? = null,
        val adcode: String? = null,
        val street: String? = null,
        val streetNumber: String? = null,
        val neighborhood: Neighborhood? = null,
        val building: Building? = null,
        val township: String? = null
    )
    
    @Serializable
    data class Neighborhood(
        val name: String? = null,
        val type: String? = null
    )
    
    @Serializable
    data class Building(
        val name: String? = null,
        val type: String? = null
    )
    
    /**
     * 坐标转换响应
     */
    @Serializable
    data class ConvertResponse(
        val status: String,
        val info: String,
        val infocode: String,
        val locations: String? = null
    )
    
    /**
     * 地址信息结果
     */
    @Serializable
    data class AddressResult(
        val success: Boolean,
        val formattedAddress: String? = null,
        val province: String? = null,
        val city: String? = null,
        val district: String? = null,
        val street: String? = null,
        val streetNumber: String? = null,
        val neighborhood: String? = null,
        val building: String? = null,
        val adcode: String? = null,
        val citycode: String? = null,
        val error: String? = null
    )
    
    /**
     * 将GPS坐标转换为高德坐标
     * 
     * @param latitude 纬度
     * @param longitude 经度
     * @return 转换后的坐标 (longitude,latitude)
     */
    suspend fun convertToAmapCoord(latitude: Double, longitude: Double): Pair<Double, Double>? {
        val url = "$BASE_URL/assistant/coordinate/convert?" +
            "key=$apiKey&" +
            "locations=$longitude,$latitude&" +
            "coordsys=${CoordType.GPS.code}&" +
            "output=JSON"
        
        return try {
            val request = Request.Builder().url(url).build()
            val response = okHttpClient.newCall(request).execute()
            
            if (response.isSuccessful) {
                val body = response.body?.string() ?: return null
                val result = Json { ignoreUnknownKeys = true }.decodeFromString<ConvertResponse>(body)
                
                if (result.status == "1" && result.locations != null) {
                    val parts = result.locations.split(",")
                    if (parts.size == 2) {
                        Pair(parts[1].toDouble(), parts[0].toDouble())
                    } else null
                } else null
            } else null
        } catch (e: Exception) {
            null
        }
    }
    
    /**
     * 逆地理编码 - 将坐标转换为地址
     * 
     * @param latitude 纬度 (高德坐标系)
     * @param longitude 经度 (高德坐标系)
     * @return 地址信息
     */
    suspend fun reverseGeocode(latitude: Double, longitude: Double): AddressResult {
        val url = "$BASE_URL/geocode/regeo?" +
            "key=$apiKey&" +
            "location=$longitude,$latitude&" +
            "extensions=all&" +
            "output=JSON"
        
        return try {
            val request = Request.Builder().url(url).build()
            val response = okHttpClient.newCall(request).execute()
            
            if (response.isSuccessful) {
                val body = response.body?.string() ?: return AddressResult(
                    success = false,
                    error = "Empty response body"
                )
                
                val result = Json { ignoreUnknownKeys = true }.decodeFromString<RegeoResponse>(body)
                
                if (result.status == "1" && result.regeocode != null) {
                    val regeo = result.regeocode
                    val addr = regeo.addressComponent
                    
                    AddressResult(
                        success = true,
                        formattedAddress = regeo.formatted_address,
                        province = addr?.province,
                        city = addr?.city ?: addr?.province,
                        district = addr?.district,
                        street = addr?.street,
                        streetNumber = addr?.streetNumber,
                        neighborhood = addr?.neighborhood?.name,
                        building = addr?.building?.name,
                        adcode = addr?.adcode,
                        citycode = addr?.citycode
                    )
                } else {
                    AddressResult(
                        success = false,
                        error = "API returned error: ${result.info} (${result.infocode})"
                    )
                }
            } else {
                AddressResult(
                    success = false,
                    error = "HTTP error: ${response.code}"
                )
            }
        } catch (e: Exception) {
            AddressResult(
                success = false,
                error = "Exception: ${e.message}"
            )
        }
    }
    
    /**
     * 获取完整地址信息（包含坐标转换）
     * 
     * @param latitude GPS纬度
     * @param longitude GPS经度
     * @return 地址信息
     */
    suspend fun getAddressFromGps(latitude: Double, longitude: Double): AddressResult {
        // 先将GPS坐标转换为高德坐标
        val amapCoord = convertToAmapCoord(latitude, longitude)
        
        if (amapCoord == null) {
            return AddressResult(
                success = false,
                error = "Failed to convert GPS coordinates to Amap coordinates"
            )
        }
        
        // 使用高德坐标进行逆地理编码
        return reverseGeocode(amapCoord.first, amapCoord.second)
    }
    
    /**
     * POI 搜索结果
     */
    data class PoiResult(
        val name: String,
        val address: String,
        val distance: Int,
        val latitude: Double,
        val longitude: Double,
        val type: String = "",
        val tel: String = ""
    )

    /**
     * 周边POI搜索
     *
     * @param latitude 纬度 (高德坐标系)
     * @param longitude 经度 (高德坐标系)
     * @param keyword 搜索关键词
     * @param radius 搜索半径(米)
     * @param type POI类型
     * @param limit 最大返回数量
     * @return POI列表
     */
    suspend fun searchNearbyPoi(
        latitude: Double,
        longitude: Double,
        keyword: String = "",
        radius: Int = 1000,
        type: String = "",
        limit: Int = 10
    ): List<PoiResult> = withContext(Dispatchers.IO) {
        try {
            // 先转换坐标
            val amapCoord = convertToAmapCoord(latitude, longitude)
            val searchLat = amapCoord?.first ?: latitude
            val searchLon = amapCoord?.second ?: longitude

            val urlBuilder = StringBuilder().apply {
                append("$BASE_URL/place/around?")
                append("key=$apiKey")
                append("&location=$searchLon,$searchLat")
                append("&radius=$radius")
                append("&offset=$limit")
                append("&page=1")
                append("&extensions=base")
                if (keyword.isNotBlank()) append("&keywords=${URLEncoder.encode(keyword, "UTF-8")}")
                if (type.isNotBlank()) append("&types=${URLEncoder.encode(type, "UTF-8")}")
            }

            val request = Request.Builder().url(urlBuilder.toString()).build()
            val response = okHttpClient.newCall(request).execute()
            val body = response.body?.string() ?: return@withContext emptyList()

            val json = JSONObject(body)
            if (json.optString("status") != "1") {
                return@withContext emptyList()
            }

            val pois = json.getJSONArray("pois")
            (0 until minOf(pois.length(), limit)).mapNotNull { i ->
                try {
                    val poi = pois.getJSONObject(i)
                    val location = poi.optString("location", "").split(",")
                    PoiResult(
                        name = poi.optString("name", ""),
                        address = poi.optString("address", ""),
                        distance = poi.optString("distance", "0").toIntOrNull() ?: 0,
                        latitude = location.getOrNull(1)?.toDoubleOrNull() ?: 0.0,
                        longitude = location.getOrNull(0)?.toDoubleOrNull() ?: 0.0,
                        type = poi.optString("type", ""),
                        tel = poi.optString("tel", "")
                    )
                } catch (e: Exception) {
                    null
                }
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    /**
     * 格式化地址为可读字符串
     */
    fun formatAddress(result: AddressResult): String {
        if (!result.success) return ""
        
        val parts = mutableListOf<String>()
        
        result.province?.let { parts.add(it) }
        result.city?.let { 
            if (it != result.province) parts.add(it) 
        }
        result.district?.let { parts.add(it) }
        
        val streetParts = mutableListOf<String>()
        result.street?.let { streetParts.add(it) }
        result.streetNumber?.let { streetParts.add(it) }
        if (streetParts.isNotEmpty()) parts.add(streetParts.joinToString(""))
        
        result.neighborhood?.let { parts.add(it) }
        result.building?.let { parts.add(it) }
        
        return parts.joinToString("")
    }
}