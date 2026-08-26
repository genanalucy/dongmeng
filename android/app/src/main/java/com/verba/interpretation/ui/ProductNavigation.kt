package com.verba.interpretation.ui

/** Primary product destinations shown in the bottom navigation. */
enum class ProductDestination(val label: String) {
    TRANSLATE("翻译"),
    INTERPRETATION("同传"),
    FACE_TO_FACE("面对面"),
    HISTORY("历史"),
    PROFILE("我的"),
}

/** Screens are intentionally local; workbenches and settings are not fake back-stack destinations. */
enum class ProductScreen {
    TRANSLATE,
    INTERPRETATION_WORKBENCH,
    FACE_TO_FACE_WORKBENCH,
    HISTORY,
    PROFILE,
    ENDPOINT_SETTINGS,
    ACCOUNT,
}

object ProductNavigationPolicy {
    fun screenFor(destination: ProductDestination): ProductScreen = when (destination) {
        ProductDestination.TRANSLATE -> ProductScreen.TRANSLATE
        ProductDestination.INTERPRETATION -> ProductScreen.INTERPRETATION_WORKBENCH
        ProductDestination.FACE_TO_FACE -> ProductScreen.FACE_TO_FACE_WORKBENCH
        ProductDestination.HISTORY -> ProductScreen.HISTORY
        ProductDestination.PROFILE -> ProductScreen.PROFILE
    }

    fun selectedDestination(screen: ProductScreen): ProductDestination = when (screen) {
        ProductScreen.TRANSLATE -> ProductDestination.TRANSLATE
        ProductScreen.INTERPRETATION_WORKBENCH -> ProductDestination.INTERPRETATION
        ProductScreen.FACE_TO_FACE_WORKBENCH -> ProductDestination.FACE_TO_FACE
        ProductScreen.HISTORY -> ProductDestination.HISTORY
        ProductScreen.PROFILE,
        ProductScreen.ENDPOINT_SETTINGS,
        ProductScreen.ACCOUNT,
        -> ProductDestination.PROFILE
    }

    fun showsBottomBar(screen: ProductScreen): Boolean = when (screen) {
        ProductScreen.TRANSLATE,
        ProductScreen.HISTORY,
        ProductScreen.PROFILE,
        -> true
        ProductScreen.INTERPRETATION_WORKBENCH,
        ProductScreen.FACE_TO_FACE_WORKBENCH,
        ProductScreen.ENDPOINT_SETTINGS,
        ProductScreen.ACCOUNT,
        -> false
    }

    fun exitTarget(screen: ProductScreen): ProductScreen = when (screen) {
        ProductScreen.ENDPOINT_SETTINGS,
        ProductScreen.ACCOUNT,
        -> ProductScreen.PROFILE
        ProductScreen.INTERPRETATION_WORKBENCH,
        ProductScreen.FACE_TO_FACE_WORKBENCH,
        -> ProductScreen.TRANSLATE
        else -> screen
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
