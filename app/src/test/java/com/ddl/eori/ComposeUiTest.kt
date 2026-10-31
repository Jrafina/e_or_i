package com.ddl.eori

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ddl.eori.ui.accounts.AccountsScreen
import com.ddl.eori.ui.accounts.AccountsUiState
import com.ddl.eori.ui.home.HomeScreen
import com.ddl.eori.ui.home.HomeUiState
import com.ddl.eori.ui.home.ParseUiState
import com.ddl.eori.ui.theme.EoriTheme
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * Compose UI 渲染测试：验证关键页面在真实 Compose 运行时下能渲染、
 * 输入框可输入、未配置 AI 时展示「去配置 AI」入口。
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class ComposeUiTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    @Test
    fun mainActivityRendersBottomNavigation() {
        // 真实 Activity：底部四个 Tab 均应存在，说明首屏与导航渲染成功
        composeRule.onNodeWithText("首页").assertIsDisplayed()
        composeRule.onNodeWithText("统计").assertIsDisplayed()
        composeRule.onNodeWithText("账户").assertIsDisplayed()
        composeRule.onNodeWithText("设置").assertIsDisplayed()
    }
}

/** 纯组件渲染测试：用 createComposeRule 单独挂载页面，验证 UI 结构与回调 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class ScreenRenderTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun homeScreenRendersInputAndManualEntry() {
        var manualClicked = false
        var submitted = false
        composeRule.setContent {
            EoriTheme {
                HomeScreen(
                    state = HomeUiState(aiReady = false),
                    onInputChange = {},
                    onSubmit = { submitted = true },
                    onManualEntry = { manualClicked = true },
                    onDraftChange = {},
                    onDraftConfirm = {},
                    onDraftDismiss = {},
                    onEditTransaction = {},
                    onDeleteTransaction = {},
                    onGoSettings = {},
                )
            }
        }

        composeRule.onNodeWithText("e or i").assertIsDisplayed()
        composeRule.onNodeWithText("例如：吃饭花了支付宝20元").assertIsDisplayed()
        composeRule.onNodeWithText("手动记账").assertIsDisplayed()
        // 未配置 AI 时展示配置入口
        composeRule.onNodeWithText("去配置 AI").assertIsDisplayed()
        // 空列表提示
        composeRule.onNodeWithText("还没有账单，先说一句试试").assertIsDisplayed()

        composeRule.onNodeWithText("手动记账").performClick()
        assertTrue("点击手动记账应触发回调", manualClicked)
    }

    @Test
    fun homeScreenShowsParsingState() {
        composeRule.setContent {
            EoriTheme {
                HomeScreen(
                    state = HomeUiState(aiReady = true, parseState = ParseUiState.Parsing),
                    onInputChange = {},
                    onSubmit = {},
                    onManualEntry = {},
                    onDraftChange = {},
                    onDraftConfirm = {},
                    onDraftDismiss = {},
                    onEditTransaction = {},
                    onDeleteTransaction = {},
                    onGoSettings = {},
                )
            }
        }
        composeRule.onNodeWithText("正在解析…").assertIsDisplayed()
        composeRule.onNodeWithText("AI 已就绪").assertIsDisplayed()
    }

    @Test
    fun accountsScreenRendersEmptyStateAndHint() {
        composeRule.setContent {
            EoriTheme {
                AccountsScreen(
                    state = AccountsUiState(),
                    onCreate = {},
                    onEdit = {},
                    onRequestDelete = {},
                    onEditorChange = {},
                    onEditorSave = {},
                    onEditorDismiss = {},
                    onDeleteConfirm = {},
                    onDeleteCancel = {},
                )
            }
        }
        composeRule.onNodeWithText("账户").assertIsDisplayed()
        composeRule.onNodeWithText("删除账户不会删除历史账单").assertIsDisplayed()
        composeRule.onNodeWithText("还没有账户，点击右下角添加").assertIsDisplayed()
    }

}
