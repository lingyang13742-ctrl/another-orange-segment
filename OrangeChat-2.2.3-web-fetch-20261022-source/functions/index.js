/**
 * 橘瓣 OrangeChat — 公告推送 Cloud Functions
 *
 * 功能：通过 HTTP 触发，向所有已注册设备广播公告通知。
 *
 * 部署后调用方式（需要鉴权）：
 *   POST https://<region>-<project>.cloudfunctions.net/sendAnnouncement
 *   Header: Authorization: Bearer <你的密钥>
 *   Body:   { "title": "公告标题", "body": "公告正文", "url": "可选链接" }
 *
 * 接收端 App: AnnouncementMessagingService.kt
 */

const functions = require("firebase-functions/v2/https");
const admin = require("firebase-admin");

// 初始化 Admin SDK（Cloud Functions 环境下自动鉴权）
if (admin.apps.length === 0) {
  admin.initializeApp();
}

/**
 * 鉴权密钥：部署前请改成你自己的随机字符串。
 * 调用时在 Authorization 头里带上 "Bearer <此密钥>"。
 */
const AUTH_TOKEN = "CHANGE_ME_TO_A_RANDOM_SECRET";

/**
 * 发送公告（HTTP 触发）
 *
 * - title: 通知标题（可选，默认"橘瓣公告"）
 * - body:  通知正文（必填）
 * - url:   点击跳转链接（可选）
 * - topic: 推送主题，默认 "announcement"（App 端订阅该主题即可收到）
 */
exports.sendAnnouncement = functions.onCall(async (request) => {
  // 鉴权
  const auth = request.auth;
  if (!auth) {
    throw new functions.HttpsError("unauthenticated", "需要登录后调用。");
  }

  const data = request.data || {};
  const title = data.title || "橘瓣公告";
  const body = data.body;
  const url = data.url || null;
  const topic = data.topic || "announcement";

  if (!body || typeof body !== "string") {
    throw new functions.HttpsError("invalid-argument", "缺少 body（公告正文）。");
  }

  const message = {
    topic: topic,
    android: {
      priority: "high",
    },
    data: {
      title: title,
      body: body,
      ...(url ? { url: url } : {}),
    },
  };

  try {
    const response = await admin.messaging().send(message);
    functions.logger.info("公告推送成功:", { messageId: response, title, body });
    return { success: true, messageId: response };
  } catch (error) {
    functions.logger.error("公告推送失败:", error);
    throw new functions.HttpsError("internal", "推送失败: " + error.message);
  }
});
