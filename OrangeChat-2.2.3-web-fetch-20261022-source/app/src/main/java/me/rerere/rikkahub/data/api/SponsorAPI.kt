/*
 * 橘瓣 OrangeChat
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

package me.rerere.rikkahub.data.api

import me.rerere.rikkahub.data.model.Sponsor
import me.rerere.rikkahub.utils.JsonInstant
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import retrofit2.http.GET

interface SponsorAPI {
    @GET("/sponsors")
    suspend fun getSponsors(): List<Sponsor>

    companion object {
        fun create(httpClient: OkHttpClient): SponsorAPI {
            return Retrofit.Builder()
                .client(httpClient)
                .baseUrl("https://sponsors.rikka-ai.com")
                .addConverterFactory(JsonInstant.asConverterFactory("application/json".toMediaType()))
                .build()
                .create(SponsorAPI::class.java)
        }
    }
}
