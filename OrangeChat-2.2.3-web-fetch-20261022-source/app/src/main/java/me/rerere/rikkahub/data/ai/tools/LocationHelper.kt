/*
 * 橘瓣 OrangeChat
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

package me.rerere.rikkahub.data.ai.tools

import android.content.Context
import android.location.Location
import me.rerere.rikkahub.data.service.DeviceLocationFetcher

/**
 * 位置获取工具
 *
 * 底层"怎么拿到新鲜定位"的逻辑已统一收敛到 [DeviceLocationFetcher]，
 * 这里只做一层薄封装，保持原有调用方（SystemTools.locationTool / ExploreNearbyTool）的 API 不变。
 */
internal object LocationHelper {

    /**
     * 获取一次较新的定位。返回 null 表示彻底获取失败（无缓存也无法定位）。
     * 注意：返回非 null 不代表一定是"刚定位到的"，如果新定位请求失败会兜底用旧缓存，
     * 调用方如果需要知道数据新鲜度，请直接用 [DeviceLocationFetcher.fetch]。
     */
    suspend fun getCurrentLocation(context: Context): Location? {
        return DeviceLocationFetcher.fetch(context)?.location
    }
}