/*
 * 橘瓣 OrangeChat
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

package me.rerere.rikkahub.ui.components.ui.icons

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

/**
 * 橘瓣 - 自定义"思考中"图标 (替代 HugeIcons.Idea01)
 * 由用户提供的 SVG (viewBox 0 0 739 739) 精确转换而来
 */
public val OrangePetalIcon: ImageVector
    get() {
        if (_orangePetalIcon != null) {
            return _orangePetalIcon!!
        }
        _orangePetalIcon = ImageVector.Builder(
            name = "OrangePetal",
            defaultWidth = 16.dp,
            defaultHeight = 16.dp,
            viewportWidth = 739f,
            viewportHeight = 739f
        ).apply {
            path(
                fill = SolidColor(Color(0xFF282828)),
                fillAlpha = 1.0f,
                stroke = null,
                strokeAlpha = 1.0f,
                strokeLineWidth = 0.0f,
                pathFillType = PathFillType.EvenOdd
            ) {
                moveTo(616.735f, 152.076f)
                curveToRelative(22.969f, -3.549f, 26.914f, 7.8f, 32.079f, 29.806f)
                curveToRelative(16.114f, 67.24f, 5.862f, 140.236f, -25.853f, 204.916f)
                curveToRelative(-20.358f, 40.271f, -46.37f, 74.89f, -80.976f, 102.4f)
                arcToRelative(311.987f, 311.987f, 0f, false, true, -47.358f, 30.909f)
                curveTo(455.6f, 542.144f, 397.162f, 557.075f, 349.118f, 555.9f)
                curveToRelative(-43.236f, -0.849f, -86.473f, -8.25f, -130.683f, -28.227f)
                curveToRelative(-26.853f, -12.746f, -48.357f, -27.276f, -70.869f, -43.346f)
                curveToRelative(-37.387f, -37.6f, -61.113f, -61.648f, -83.245f, -133.845f)
                curveToRelative(-8.866f, -26.393f, -6.006f, -54.03f, 20.906f, -55.891f)
                curveToRelative(87.964f, -23.055f, 175.928f, -48.995f, 265.382f, -75.687f)
                curveToRelative(88.461f, -11.4f, 176.921f, -44.972f, 265.382f, -66.833f)
                curveTo(617.482f, 151.718f, 617.482f, 151.718f, 616.735f, 152.076f)
                close()
                moveToRelative(-2.235f, 13.06f)
                horizontalLineToRelative(11.927f)
                curveToRelative(3.231f, -1.739f, -0.248f, 3.231f, 3.9f, 3.063f)
                curveToRelative(19.83f, 59.713f, 15.015f, 120.707f, -5.922f, 181.057f)
                curveToRelative(-20.884f, 54.561f, -42.255f, 81.662f, -75.51f, 114.062f)
                curveToRelative(-0.5f, 0.994f, 1.242f, 4.224f, -2.321f, 3.643f)
                curveToRelative(-15.651f, 14.664f, -33.591f, 27.04f, -54.369f, 38.647f)
                curveToRelative(-15.347f, 8.43f, -33.833f, 17.825f, -65.565f, 26.563f)
                arcToRelative(302.378f, 302.378f, 0f, false, true, -142.1f, 3.116f)
                curveToRelative(-81.481f, -21.44f, -112.766f, -46.679f, -150.568f, -84.82f)
                curveToRelative(-17.449f, -18.477f, -31.037f, -38.839f, -43.581f, -64.676f)
                curveTo(82.277f, 368.4f, 78f, 351f, 75.83f, 332.856f)
                curveToRelative(-9.678f, -23.386f, 10.806f, -25.314f, 30.472f, -30f)
                curveToRelative(5.465f, 43.523f, 27.89f, 79.629f, 40.137f, 98.041f)
                curveToRelative(34.282f, 42.015f, 59.974f, 67.184f, 132.606f, 92.328f)
                curveToRelative(51.685f, 14.109f, 103.37f, 11.24f, 157.877f, -6.282f)
                curveToRelative(75.037f, -27.19f, 101.638f, -60.91f, 139.569f, -110.778f)
                curveToRelative(12.5f, -16.714f, 31.591f, -58.564f, 38.547f, -99.209f)
                curveToRelative(3.56f, -19.382f, 2.213f, -38.764f, 2.729f, -59.637f)
                curveToRelative(-1.636f, -9.442f, -2.285f, -18.885f, -5.094f, -29.91f)
                curveTo(613.23f, 179.068f, 599.676f, 165.217f, 614.5f, 165.136f)
                close()
                moveToRelative(-26.16f, 8.606f)
                curveToRelative(15.539f, 4.686f, 11.618f, 32.918f, 15.429f, 49.54f)
                curveToRelative(2.265f, 35.285f, -3.335f, 70.57f, -18.91f, 108.144f)
                curveToRelative(-20.2f, 47.233f, -46.845f, 77.985f, -83.4f, 106.819f)
                curveToRelative(-13.564f, 9.3f, -27.314f, 18.407f, -43.194f, 25.68f)
                curveToRelative(-35.577f, 16.805f, -80.108f, 25.516f, -121.3f, 23.307f)
                curveToRelative(-28.955f, 0.724f, -62.575f, -8.783f, -89.191f, -20.968f)
                arcToRelative(223.04f, 223.04f, 0f, false, true, -79.5f, -60.646f)
                arcToRelative(270.446f, 270.446f, 0f, false, true, -28.57f, -41.564f)
                curveToRelative(-5.932f, -17.829f, -22.959f, -45.185f, -15.712f, -65.868f)
                curveToRelative(77.03f, -21.4f, 154.061f, -42.447f, 232.582f, -66.034f)
                curveTo(433.6f, 220.341f, 510.633f, 194.542f, 588.34f, 173.742f)
                close()
                moveToRelative(-275.18f, 82.2f)
                curveToRelative(10.844f, -0.022f, 17.626f, 3.8f, 21.235f, 10f)
                curveToRelative(0.082f, 21.149f, -10.089f, 28.05f, -16.489f, 39.875f)
                curveToRelative(-24.975f, 38.59f, -46.984f, 75.469f, -76.972f, 109.376f)
                curveToRelative(-2.576f, 8.526f, -27.373f, 11.565f, -35.225f, 2.189f)
                curveToRelative(-13.531f, -9.33f, -24.236f, -21.485f, -34.49f, -36.475f)
                curveToRelative(-8.152f, -9.514f, -17.8f, -31.935f, -22.579f, -58.693f)
                curveToRelative(6.174f, -21.715f, 38.617f, -23.945f, 72.26f, -37.429f)
                quadTo(267.118f, 269.67f, 313.16f, 255.944f)
                close()
                moveToRelative(-1.315f, 10.484f)
                curveToRelative(3.479f, 0.966f, 6.958f, -1.573f, 12.758f, 2.161f)
                curveToRelative(-3.127f, 21.8f, -16.566f, 29.485f, -24.6f, 47.211f)
                curveToRelative(-16.309f, 23.449f, -31.2f, 48.311f, -48.041f, 72.723f)
                curveToRelative(-5.963f, 8.449f, -12.364f, 16.46f, -20.774f, 22.984f)
                curveToRelative(-14.883f, 7.156f, -21.9f, -5.357f, -29.372f, -9.611f)
                curveToRelative(-11.53f, -11.331f, -21.308f, -24.413f, -31.688f, -39.353f)
                curveToRelative(-2.414f, -8.826f, -17.738f, -21.572f, -11.691f, -43.021f)
                curveToRelative(15.859f, -11.5f, 50.111f, -19.256f, 74.393f, -28.079f)
                curveTo(259.167f, 282.172f, 285.506f, 274.874f, 311.845f, 266.428f)
                close()
                moveToRelative(40.11f, 11.418f)
                curveToRelative(29.213f, -8.766f, 26.819f, 26.631f, 34.18f, 51.29f)
                curveToRelative(3.84f, 21.37f, 8.568f, 42.74f, 11.892f, 65.6f)
                curveToRelative(2.41f, 18.885f, 9.812f, 37.77f, 0.645f, 57.387f)
                curveToRelative(-16.092f, 20.568f, -62.539f, 19.742f, -92.29f, 13.013f)
                curveToRelative(-28.985f, -8.032f, -44.366f, -11.4f, -43.665f, -43.254f)
                curveToRelative(11.841f, -25.809f, 29.058f, -50.068f, 42.7f, -75.321f)
                curveToRelative(15.659f, -22.607f, 25.852f, -50.68f, 45.775f, -69.024f)
                curveTo(352.1f, 276.955f, 352.1f, 276.955f, 351.955f, 277.846f)
                close()
                moveToRelative(10.878f, 7.278f)
                curveToRelative(7.433f, 8.042f, 7.742f, 25.164f, 11.588f, 38.049f)
                curveToRelative(6.963f, 39.757f, 17.454f, 79.515f, 17.321f, 120.014f)
                curveToRelative(-4.712f, 17.942f, -38.721f, 14f, -60.733f, 14.37f)
                curveToRelative(-21.3f, -1.41f, -52.506f, -7.784f, -60.445f, -21.529f)
                curveToRelative(10f, -39.806f, 28.041f, -50.093f, 42.27f, -82.048f)
                curveToRelative(7.352f, -11.533f, 14.131f, -23.639f, 21.241f, -36.742f)
                curveTo(343.87f, 306.724f, 345.619f, 291.093f, 362.833f, 285.124f)
                close()
                moveTo(404.282f, 265.1f)
                curveToRelative(3.479f, -1.27f, 6.957f, 2.625f, 11.6f, 1.746f)
                curveToRelative(21.58f, 12.711f, 41.787f, 26.8f, 63.258f, 41.106f)
                curveToRelative(18.688f, 15.6f, 42.152f, 26.43f, 57.855f, 47.081f)
                curveToRelative(3.254f, 39.531f, -21.231f, 45.637f, -36.563f, 62.576f)
                curveToRelative(-15.6f, 9.532f, -23.614f, 19.394f, -52.307f, 24.831f)
                curveToRelative(-34.891f, 1.588f, -28.23f, -49.77f, -43.672f, -83.48f)
                curveToRelative(-4.256f, -17.394f, -8.586f, -34.788f, -12.844f, -53.453f)
                curveTo(384.819f, 287.118f, 387.108f, 266.239f, 404.282f, 265.1f)
                close()
                moveToRelative(-0.771f, 9.648f)
                curveToRelative(19.447f, 2.421f, 27.019f, 16.715f, 42.9f, 24.187f)
                curveToRelative(23.072f, 15.195f, 45.388f, 31.146f, 68.53f, 48.268f)
                curveToRelative(6.778f, 4.066f, 19.053f, 12.317f, 11.747f, 28.312f)
                curveToRelative(-9.269f, 16.077f, -24.344f, 26.348f, -38.789f, 38.573f)
                curveToRelative(-12.59f, 7.339f, -20f, 16.229f, -41.418f, 20f)
                curveToRelative(-22.237f, -5.677f, -19.318f, -48.157f, -31.655f, -73.815f)
                curveToRelative(-2.41f, -27.862f, -21.341f, -58.745f, -14.159f, -85.356f)
                curveTo(402.791f, 275.464f, 402.791f, 275.464f, 403.511f, 274.743f)
                close()
                moveToRelative(146.5f, -68.908f)
                curveToRelative(30.243f, -4.366f, 35.961f, 17.255f, 35.691f, 47.867f)
                curveToRelative(-1.063f, 21.706f, -7.473f, 55.863f, -20f, 69.546f)
                curveToRelative(-4.927f, 15.3f, -36.309f, 10.367f, -47.518f, -0.676f)
                curveToRelative(-18.514f, -11.3f, -37.389f, -22.247f, -56.037f, -34.908f)
                curveToRelative(-8.713f, -6.2f, -18.335f, -11.483f, -28.146f, -18.072f)
                curveToRelative(-6.552f, -8.357f, -19.488f, -10.33f, -15.622f, -30.316f)
                curveToRelative(6.7f, -15.572f, 40.023f, -16.039f, 61.932f, -21.712f)
                curveTo(503.676f, 213.392f, 527.033f, 209.274f, 550.013f, 205.835f)
                close()
                moveToRelative(0.378f, 9.992f)
                curveToRelative(3.479f, 0f, 6.957f, 0f, 12.2f, 0.449f)
                curveToRelative(18.036f, 4.579f, 12.737f, 36.628f, 10.885f, 56.3f)
                curveToRelative(-4.49f, 19.817f, -8.205f, 48.719f, -28.6f, 51.366f)
                curveToRelative(-24.689f, -9.537f, -40.12f, -22.645f, -61.888f, -34.749f)
                curveToRelative(-18.38f, -12.928f, -38.923f, -23.694f, -56.339f, -40.57f)
                arcToRelative(16.267f, 16.267f, 0f, false, false, -1.337f, -6.635f)
                curveToRelative(17.032f, -8.222f, 41.443f, -10.926f, 62.465f, -15.534f)
                curveTo(508.645f, 222.853f, 529.518f, 219.361f, 550.391f, 215.827f)
                close()
            }
        }.build()
        return _orangePetalIcon!!
    }

private var _orangePetalIcon: ImageVector? = null
