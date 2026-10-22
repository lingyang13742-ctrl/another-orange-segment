/*
 * 橘瓣 OrangeChat
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

package me.rerere.rikkahub.data.datastore

import kotlinx.serialization.Serializable

/**
 * QQ Bot (QQ 开放平台 API v2, WebSocket 网关) 配置.
 *
 * 和微信 bot 同类: QQ bot 是当前助手的"QQ 私聊通道". 只处理私聊 (C2C_MESSAGE_CREATE).
 * 每个用户自己部署: 去 q.qq.com 注册机器人, 拿 AppID/Secret 填进来.
 *
 * 字段:
 *  - [enabled]: 总开关. 开启后启动 QqBotService 建立 WebSocket 连接.
 *  - [appId] / [appSecret]: 在 QQ 开放平台 (q.qq.com) 创建机器人后获得.
 *  - [accessToken] / [accessTokenExpireAt]: getAppAccessToken 返回的凭证缓存 (过期前刷新).
 *    持久化是为了重启后不立即重新拿 token, 但过期仍会自动刷新.
 */
@Serializable
data class QqBotSetting(
    val enabled: Boolean = false,
    val appId: String = "",
    val appSecret: String = "",
    val accessToken: String = "",
    val accessTokenExpireAt: Long = 0,
)
