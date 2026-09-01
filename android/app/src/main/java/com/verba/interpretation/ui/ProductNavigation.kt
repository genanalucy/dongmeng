package com.verba.interpretation.ui

enum class ProductNavigationMode { AUTHENTICATION, USER, ADMIN_TEST }

/** Primary product destinations shown in the bottom navigation. */
enum class ProductDestination(val label: String) {
    TRANSLATE("翻译"),
    INTERPRETATION("同传"),
    FACE_TO_FACE("面对面"),
    HISTORY("历史"),
    PROFILE("我的"),
    ADMIN_TEST("测试"),
}

/** Screens are intentionally local; workbenches and settings are not fake back-stack destinations. */
enum class AccountSecondaryDestination { HISTORY, SERVICE_SETTINGS }

enum class ProductScreen {
    TRANSLATE,
    INTERPRETATION_WORKBENCH,
    FACE_TO_FACE_WORKBENCH,
    HISTORY,
    PROFILE,
    ENDPOINT_SETTINGS,
    ACCOUNT,
    ACCOUNT_USAGE,
    ACCOUNT_SETTINGS,
    ADMIN_TEST,
}

data class ProductShell(
    val showBottomBar: Boolean,
    val destinations: List<ProductDestination>,
)

/** Immutable local navigation history for product roots and their secondary routes. */
class ProductNavigationStack private constructor(
    private val screens: List<ProductScreen>,
) {
    val current: ProductScreen
        get() = screens.last()

    val canPop: Boolean
        get() = screens.size > 1

    fun push(screen: ProductScreen): ProductNavigationStack = ProductNavigationStack(screens + screen)

    fun pop(): ProductNavigationStack = if (canPop) ProductNavigationStack(screens.dropLast(1)) else this

    fun selectPrimary(destination: ProductDestination): ProductNavigationStack =
        ProductNavigationStack(listOf(ProductNavigationPolicy.screenFor(destination)))

    companion object {
        fun initial(mode: ProductNavigationMode): ProductNavigationStack =
            ProductNavigationStack(listOf(ProductNavigationPolicy.initialScreen(mode)))
    }
}

object ProductNavigationPolicy {
    fun accountSecondaryScreen(destination: AccountSecondaryDestination): ProductScreen = when (destination) {
        AccountSecondaryDestination.HISTORY -> ProductScreen.HISTORY
        AccountSecondaryDestination.SERVICE_SETTINGS -> ProductScreen.ENDPOINT_SETTINGS
    }

    fun initialScreen(mode: ProductNavigationMode): ProductScreen = when (mode) {
        ProductNavigationMode.AUTHENTICATION -> ProductScreen.ACCOUNT
        ProductNavigationMode.USER -> ProductScreen.FACE_TO_FACE_WORKBENCH
        ProductNavigationMode.ADMIN_TEST -> ProductScreen.ADMIN_TEST
    }

    fun destinationsFor(mode: ProductNavigationMode): List<ProductDestination> = when (mode) {
        ProductNavigationMode.AUTHENTICATION -> emptyList()
        ProductNavigationMode.USER -> listOf(
            ProductDestination.FACE_TO_FACE,
            ProductDestination.INTERPRETATION,
            ProductDestination.PROFILE,
        )
        ProductNavigationMode.ADMIN_TEST -> listOf(ProductDestination.ADMIN_TEST, ProductDestination.PROFILE)
    }

    fun screenFor(destination: ProductDestination): ProductScreen = when (destination) {
        ProductDestination.TRANSLATE -> ProductScreen.TRANSLATE
        ProductDestination.INTERPRETATION -> ProductScreen.INTERPRETATION_WORKBENCH
        ProductDestination.FACE_TO_FACE -> ProductScreen.FACE_TO_FACE_WORKBENCH
        ProductDestination.HISTORY -> ProductScreen.HISTORY
        ProductDestination.PROFILE -> ProductScreen.PROFILE
        ProductDestination.ADMIN_TEST -> ProductScreen.ADMIN_TEST
    }

    fun selectedDestination(screen: ProductScreen): ProductDestination = when (screen) {
        ProductScreen.TRANSLATE -> ProductDestination.TRANSLATE
        ProductScreen.INTERPRETATION_WORKBENCH -> ProductDestination.INTERPRETATION
        ProductScreen.FACE_TO_FACE_WORKBENCH -> ProductDestination.FACE_TO_FACE
        ProductScreen.HISTORY -> ProductDestination.HISTORY
        ProductScreen.PROFILE,
        ProductScreen.ENDPOINT_SETTINGS,
        ProductScreen.ACCOUNT,
        ProductScreen.ACCOUNT_USAGE,
        ProductScreen.ACCOUNT_SETTINGS,
        -> ProductDestination.PROFILE
        ProductScreen.ADMIN_TEST -> ProductDestination.ADMIN_TEST
    }

    fun shellFor(mode: ProductNavigationMode, screen: ProductScreen): ProductShell {
        val destinations = destinationsFor(mode)
        val selected = selectedDestination(screen)
        val showsBottomBar = selected in destinations && screenFor(selected) == screen
        return ProductShell(
            showBottomBar = showsBottomBar,
            destinations = if (showsBottomBar) destinations else emptyList(),
        )
    }

}

enum class HistoryFilter(val label: String) {
    ALL("全部"),
    INTERPRETATION("同传"),
    FACE_TO_FACE("面对面"),
}

object HistoryEmptyStatePolicy {
    fun message(query: String, filter: HistoryFilter): String = when {
        query.isNotBlank() -> "没有找到与“${query.trim()}”相关的记录"
        filter == HistoryFilter.INTERPRETATION -> "还没有同传记录"
        filter == HistoryFilter.FACE_TO_FACE -> "还没有面对面翻译记录"
        else -> "完成一次翻译后，记录会保存在这里"
    }
}
