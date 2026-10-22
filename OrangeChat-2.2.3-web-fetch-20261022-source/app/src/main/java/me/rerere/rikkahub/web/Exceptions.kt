/*
 * 橘瓣 OrangeChat
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

package me.rerere.rikkahub.web

import io.ktor.http.HttpStatusCode

sealed class ApiException(
    override val message: String,
    val status: HttpStatusCode
) : RuntimeException(message)

class BadRequestException(message: String) : ApiException(message, HttpStatusCode.BadRequest)
class NotFoundException(message: String) : ApiException(message, HttpStatusCode.NotFound)
class UnauthorizedException(message: String) : ApiException(message, HttpStatusCode.Unauthorized)
class ForbiddenException(message: String) : ApiException(message, HttpStatusCode.Forbidden)
