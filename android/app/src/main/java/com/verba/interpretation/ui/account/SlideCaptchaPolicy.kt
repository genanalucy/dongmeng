package com.verba.interpretation.ui.account

/**
 * 滑块拼图验证码的坐标几何策略：全部为纯函数，便于 JVM 单元测试。
 *
 * 约定：挑战与拼图尺寸均以“挑战图像像素”为单位，拖拽偏移以屏幕像素为单位，
 * 通过 [displayScale] 在两个坐标系之间换算；提交给 Cloud 的 captcha_x 是
 * 拼图左边缘在挑战图像中的最终整数像素位置，必须落在 0..challengeWidth-tileWidth。
 */
object SlideCaptchaGeometryPolicy {
    fun displayScale(displayWidthPx: Float, challengeWidthPx: Int): Float {
        require(challengeWidthPx > 0) { "challengeWidthPx 必须为正。" }
        require(displayWidthPx > 0f) { "displayWidthPx 必须为正。" }
        return displayWidthPx / challengeWidthPx
    }

    /** 拖拽偏移（屏幕像素）的合法区间：保证拼图始终完整落在挑战图内。 */
    fun dragOffsetBounds(startX: Int, tileWidth: Int, challengeWidth: Int, scale: Float): ClosedFloatingPointRange<Float> {
        require(tileWidth in 1..challengeWidth) { "tileWidth 必须落在挑战图内。" }
        require(startX in 0..(challengeWidth - tileWidth)) { "startX 必须落在挑战图内。" }
        require(scale.isFinite() && scale > 0f) { "scale 必须为正有限值。" }
        return (-startX * scale)..((challengeWidth - tileWidth - startX) * scale)
    }

    fun clampDragOffset(offsetPx: Float, startX: Int, tileWidth: Int, challengeWidth: Int, scale: Float): Float =
        offsetPx.coerceIn(dragOffsetBounds(startX, tileWidth, challengeWidth, scale))

    /** 松手后提交的最终拼图 x（挑战像素，整数），并再次钳制到合法区间。 */
    fun submittedTileX(dragOffsetPx: Float, startX: Int, tileWidth: Int, challengeWidth: Int, scale: Float): Int {
        val bounds = dragOffsetBounds(startX, tileWidth, challengeWidth, scale)
        val challengeX = startX + dragOffsetPx.coerceIn(bounds) / scale
        return challengeX.roundToInt().coerceIn(0, challengeWidth - tileWidth)
    }

    /** 提交值越界时拒绝提交（防御性：正常 UI 不会产生越界值）。 */
    fun isSubmittable(tileX: Int, challengeWidth: Int, tileWidth: Int): Boolean =
        tileX in 0..(challengeWidth - tileWidth).coerceAtLeast(0)

    private fun Float.roundToInt(): Int = Math.round(this)
}

/** 注册拼图提交入口的守卫：只放行几何合法的坐标。 */
object SlideCaptchaSubmissionPolicy {
    fun submit(
        dragOffsetPx: Float,
        startX: Int,
        tileWidth: Int,
        challengeWidth: Int,
        scale: Float,
        onSubmit: (Int) -> Unit,
    ): Boolean {
        val tileX = SlideCaptchaGeometryPolicy.submittedTileX(dragOffsetPx, startX, tileWidth, challengeWidth, scale)
        if (!SlideCaptchaGeometryPolicy.isSubmittable(tileX, challengeWidth, tileWidth)) return false
        onSubmit(tileX)
        return true
    }
}
