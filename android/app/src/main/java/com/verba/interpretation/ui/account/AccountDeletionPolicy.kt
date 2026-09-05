package com.verba.interpretation.ui.account

/**
 * 账户自助删除的产品策略。
 *
 * - 管理员账户：Cloud 明确拒绝（403 forbidden），入口直接隐藏，不提供任何旁路。
 * - 旧版账户（无用户名，展示名为 [LegacyFallbackUsername]）：Cloud 永远无法匹配
 *   确认用户名（409 conflict），按一致产品决策禁用入口并说明原因，不提供
 *   身份编辑/恢复流程作为删除前置步骤。
 * - 普通用户：必须输入与当前展示用户名精确一致的确认串。
 */
object AccountDeletionPolicy {
    const val LegacyFallbackUsername = "旧版用户"
    const val MismatchMessage = "输入的用户名与当前账户不一致，请核对后重试。"
    const val AdminUnavailableMessage = "管理员账户不支持自助删除。"
    const val LegacyUnavailableMessage = "该账户尚未设置用户名，暂不支持自助删除。"

    /** 删除入口是否可用；管理员隐藏，旧版账户视为不可用。 */
    fun deletionAvailability(displayedUsername: String?, isAdmin: Boolean): Availability = when {
        isAdmin -> Availability.HIDDEN
        displayedUsername == null || displayedUsername == LegacyFallbackUsername -> Availability.DISABLED
        else -> Availability.AVAILABLE
    }

    /** 删除确认是显式的破坏性操作，必须逐字符匹配当前展示的用户名。 */
    fun normalizedConfirmation(confirmation: String): String = confirmation

    fun confirmationMatches(displayedUsername: String, confirmation: String): Boolean =
        confirmation == displayedUsername && confirmation.isNotBlank()

    enum class Availability { AVAILABLE, DISABLED, HIDDEN }
}
