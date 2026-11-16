package com.verba.interpretation.ui.facetoface

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.verba.interpretation.export.ExportFormat
import com.verba.interpretation.ui.FaceToFaceState
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class ConversationExportMenuTest {
    @get:Rule val compose = createComposeRule()

    @Test fun menuDispatchesSaveRetryAndBothExplicitPlaintextFormats() {
        var saves = 0
        var retries = 0
        val formats = mutableListOf<ExportFormat>()
        compose.setContent {
            MaterialTheme {
                FaceToFaceOverflowMenu(
                    state = FaceToFaceState(), onSelectMode = {}, onStopAuto = {},
                    onSaveToHistory = { saves++ }, onRetryHistorySave = { retries++ },
                    onExport = { formats.add(it) },
                )
            }
        }
        fun select(label: String) {
            compose.onNodeWithContentDescription("面对面翻译更多选项").performClick()
            compose.onNodeWithText(label).performClick()
        }
        select("保存到历史")
        select("重试历史保存")
        select("导出 TXT（明文）")
        select("导出 JSON（明文）")
        assertEquals(1, saves)
        assertEquals(1, retries)
        assertEquals(listOf(ExportFormat.TXT, ExportFormat.JSON), formats)
    }

    @Test fun busyExportDisablesBothDocumentActions() {
        compose.setContent {
            MaterialTheme {
                FaceToFaceOverflowMenu(
                    state = FaceToFaceState(), onSelectMode = {}, onStopAuto = {},
                    documentExportBusy = true,
                )
            }
        }
        compose.onNodeWithContentDescription("面对面翻译更多选项").performClick()
        compose.onAllNodesWithText("导出处理中…")[0].assertIsNotEnabled()
        compose.onAllNodesWithText("导出处理中…")[1].assertIsNotEnabled()
    }
}
