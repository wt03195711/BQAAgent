// Copyright 2026 BQAAgent (agents.io). All rights reserved.
// Licensed under the Apache License, Version 2.0.

package io.agents.bqaagent.agent

import io.agents.bqaagent.adb.LocalAdbDeviceDriver
import io.agents.bqaagent.adb.UiNode
import io.agents.bqaagent.utils.XLog

/**
 * Runtime guardrails for dump-only sensitive app tasks.
 *
 * Keep this policy small and generic: it forbids visual/file fallbacks, forces
 * credential and money-entry tools, and stops on login/financial blockers.
 */
object SensitiveAppPolicy {
    private const val TAG = "SensitiveAppPolicy"
    private const val HSBC_PAY_DEEP_LINK = "https://hbsg.dxp2.preprod.eu.dynp.cloud1.vv1865.com/pay"

    private val ownAccountTransferTerms = listOf(
        "转账至名下账户",
        "轉賬至名下賬戶",
        "轉帳至名下帳戶",
        "轉賬至名下帳戶",
        "名下账户",
        "名下賬戶",
        "名下帳戶",
        "own-account",
        "own account",
        "between own accounts",
    )

    private val sourceAccountLabels = listOf(
        "汇出账户",
        "匯出賬戶",
        "匯出帳戶",
        "source account",
        "from account",
        "debit account",
    )

    private val destinationAccountLabels = listOf(
        "入账账户",
        "入賬賬戶",
        "入帳帳戶",
        "收款账户",
        "收款賬戶",
        "收款帳戶",
        "destination account",
        "to account",
        "credit account",
    )

    private val debitAmountLabels = listOf(
        "汇出金额",
        "匯出金額",
        "debit amount",
        "from amount",
        "send amount",
    )

    private val creditAmountLabels = listOf(
        "入账金额",
        "入賬金額",
        "入帳金額",
        "到账金额",
        "到賬金額",
        "到帳金額",
        "credit amount",
        "to amount",
        "receive amount",
    )

    private val genericAmountLabels = listOf(
        "金额",
        "金額",
        "转账金额",
        "轉賬金額",
        "轉帳金額",
        "amount",
        "transfer amount",
    )

    val forbiddenTools: Set<String> = setOf("take_screenshot", "send_file")

    private val sensitivePackages = setOf(
        "sg.com.hsbc.hsbcsingapore.cert",
    )

    private val sensitiveTaskTerms = listOf(
        "hsbc",
        "汇丰",
        "滙豐",
        "bank transfer",
        "banking",
        "银行",
        "銀行",
        "singapore cert",
        "sg cert",
        "transfer money",
        "own-account transfer",
        "own account transfer",
    )

    private val fixedNavigationTerms = setOf(
        "home",
        "首页",
        "首頁",
        "主页",
        "主頁",
        "transfer",
        "transfers",
        "转账",
        "轉賬",
        "轉帳",
        "cards",
        "wealth",
        "products",
        "银行卡",
        "銀行卡",
        "财务",
        "財務",
        "财富",
        "財富",
        "产品",
        "產品",
    )

    @JvmStatic
    fun isSensitiveTask(request: String?): Boolean {
        if (!io.agents.bqaagent.utils.KVUtils.isSensitiveModeEnabled()) return false
        val lower = request?.lowercase().orEmpty()
        return sensitiveTaskTerms.any { lower.contains(it) } ||
                sensitivePackages.any { lower.contains(it.lowercase()) }
    }

    @JvmStatic
    fun isSensitiveContext(request: String?): Boolean {
        if (!io.agents.bqaagent.utils.KVUtils.isSensitiveModeEnabled()) return false
        if (isSensitiveTask(request)) return true
        val activePackage = try {
            LocalAdbDeviceDriver.activePackageName()
        } catch (e: Exception) {
            XLog.w(TAG, "Unable to read active package for sensitive policy", e)
            ""
        }
        return activePackage in sensitivePackages
    }

    @JvmStatic
    fun buildPromptSection(request: String?): String {
        val pinRule = if (hasProvidedPin(request)) {
            "- If a PIN/passcode screen appears, enter the exact user-provided value with secure_keypad_input. Do not use input_text or install/change input methods."
        } else {
            "- If a PIN/passcode screen appears and the current request did not provide a PIN/passcode, stop immediately with finish. Do not guess, reuse old credentials, or continue."
        }
        val finalActionRule = if (allowsFinalFinancialAction(request)) {
            "- If the review screen exactly matches the requested source account, destination account, and amounts, enabled Continue/Confirm/Submit actions may be tapped for this test/cert task. Never tap disabled financial actions."
        } else {
            "- For money movement, stop at the review screen before the final transaction action and ask the user to confirm."
        }

        return """

## Sensitive App Mode
- Use ADB UI-tree tools only. Visual-file and sharing tools are unavailable by policy. However, if get_screen_info returns SCREEN_TREE_UNUSABLE, analyze_screen_visual is allowed as a visual fallback to get guidance for the next step.
$pinRule
- For numeric credentials, use secure_keypad_input. For monetary amount fields, use input_amount.
- For HSBC Singapore Cert own-account transfers, prefer bank_own_account_transfer after the dashboard or Pay/Transfer page is visible so account selection and amount entry run as one ADB UI-tree flow.
- If the HSBC RootHub top tabs are visible (Home/Transfer/Cards/Wealth/Products, 首页/转账/银行卡/财富/产品, or 首頁/轉賬/銀行卡/財富/產品), use bank_own_account_transfer directly; it will return to dashboard/home first, then tap only the top Transfer tab and must not tap dashboard cards or quick actions.
- If a password or other credential screen appears and the current request did not provide that credential, stop immediately. Do not reuse the PIN as a password.
- If a provided PIN is tried twice and the app is still/again on a PIN/login screen, stop with a login blocker instead of re-entering it.
- Fixed top/bottom navigation tabs are not scrollable content. If a tab is visible, use tap_visible_text or tap_node.
- Use scroll_to_find only for scrollable content lists.
- If input_amount or bank_own_account_transfer reports BLOCKED, Continue/Next disabled, 12000, or a validation/backend error, stop with that blocker instead of rereading the same page.
$finalActionRule
"""
    }

    @JvmStatic
    fun hasProvidedPin(request: String?): Boolean {
        val text = request.orEmpty()
        if (text.isBlank()) return false
        return listOf(
            Regex("\\bpin\\b\\D{0,16}\\d{4,8}", RegexOption.IGNORE_CASE),
            Regex("\\bpasscode\\b\\D{0,16}\\d{4,8}", RegexOption.IGNORE_CASE),
            Regex("\\bpassword\\b\\D{0,16}\\d{4,8}", RegexOption.IGNORE_CASE),
            Regex("(密码|密碼)\\D{0,16}\\d{4,8}"),
        ).any { it.containsMatchIn(text) }
    }

    @JvmStatic
    fun hasProvidedPassword(request: String?): Boolean {
        val text = request.orEmpty()
        if (text.isBlank()) return false
        return listOf(
            Regex("\\bpassword\\b\\D{0,24}\\S{4,64}", RegexOption.IGNORE_CASE),
            Regex("(密码|密碼)\\D{0,24}\\S{4,64}"),
        ).any { it.containsMatchIn(text) }
    }

    @JvmStatic
    fun allowsReceiveAmountInput(request: String?): Boolean {
        val lower = request?.lowercase().orEmpty()
        return listOf(
            "receive amount",
            "credit amount",
            "to amount",
            "入账金额",
            "入賬金額",
            "入帳金額",
            "到账金额",
            "到賬金額",
        ).any { lower.contains(it) }
    }

    @JvmStatic
    fun allowsFinalFinancialAction(request: String?): Boolean {
        val lower = request?.lowercase().orEmpty()
        val compact = lower.replace("\\s+".toRegex(), "")
        if (!isSensitiveTask(request)) return false
        val wantsFinalAction = listOf(
            "confirm and submit",
            "confirm/submit",
            "complete the test transfer",
            "complete transfer",
            "submit the transfer",
            "完成转账",
            "完成轉賬",
            "完成轉帳",
            "完成测试转账",
            "完成測試轉賬",
            "完成測試轉帳",
            "提交",
            "确认",
            "確認",
        ).any { lower.contains(it) || compact.contains(it.replace(" ", "")) } ||
            (
                listOf("complete", "完成").any { compact.contains(it) } &&
                    listOf("transfer", "转账", "轉賬", "轉帳").any { compact.contains(it) }
            )
        val testContext = listOf("test", "cert", "测试", "測試").any { lower.contains(it) || compact.contains(it) }
        return wantsFinalAction && testContext
    }

    @JvmStatic
    fun isAllowedSensitiveDeepLink(url: String?, request: String?): Boolean {
        if (!isSensitiveTask(request)) return false
        val normalized = url.orEmpty().trim().lowercase()
        if (normalized.isBlank()) return false
        val allowedHosts = listOf(
            "https://hbsg.dxp2.preprod.eu.dynp.cloud1.vv1865.com",
            "https://m.hbsg.dxp2.preprod.eu.dynp.cloud1.vv1865.com",
        )
        val allowedPaths = listOf(
            "/pay",
            "/movemoney",
            "/account/summary",
        )
        return allowedHosts.any { host ->
            allowedPaths.any { path -> normalized == "$host$path" }
        }
    }

    @JvmStatic
    fun blockedOpenUrlMessage(toolName: String, params: Map<String, Any>, request: String?): String? {
        if (toolName != "open_url") return null
        if (!isSensitiveContext(request)) return null
        val url = params["url"]?.toString().orEmpty()
        val packageName = params["package_name"]?.toString().orEmpty()
        if (!isAllowedSensitiveDeepLink(url, request)) {
            return "Only the banking app's known internal deep links are allowed in sensitive-app mode. Do not open arbitrary URLs."
        }
        if (packageName.isNotBlank() && packageName !in sensitivePackages) {
            return "Sensitive banking deep links must target the banking app package, not another browser/app."
        }
        return null
    }

    @JvmStatic
    fun isPinLoginScreen(screenData: String?): Boolean {
        val lower = screenData
            ?.replace("\\p{Cf}".toRegex(), "")
            ?.lowercase()
            .orEmpty()
        if (lower.isBlank()) return false
        val compact = lower.replace("\\s+".toRegex(), "")
        val hasPin = compact.contains("pin") ||
            compact.contains("数码pin") ||
            compact.contains("數碼pin")
        if (!hasPin) return false
        val hasLogin = listOf(
            "log on",
            "log in",
            "login",
            "sign in",
            "登录",
            "登入",
            "请输入",
            "請輸入",
            "输入",
            "enter",
            "digit",
            "keypad",
        ).any { lower.contains(it) || compact.contains(it.replace(" ", "")) }
        return hasLogin
    }

    @JvmStatic
    fun isPasswordLoginScreen(screenData: String?): Boolean {
        val lower = screenData
            ?.replace("\\p{Cf}".toRegex(), "")
            ?.lowercase()
            .orEmpty()
        if (lower.isBlank()) return false
        val compact = lower.replace("\\s+".toRegex(), "")
        val hasPassword = compact.contains("password") ||
            compact.contains("密码") ||
            compact.contains("密碼")
        if (!hasPassword) return false
        val hasLoginAction = listOf(
            "continue",
            "log on",
            "log in",
            "login",
            "sign in",
            "submit",
            "继续",
            "繼續",
            "登录",
            "登入",
            "提交",
        ).any { lower.contains(it) || compact.contains(it.replace(" ", "")) }
        return hasLoginAction
    }

    @JvmStatic
    fun missingPinTerminalMessage(screenData: String?, request: String?): String? {
        if (!isSensitiveTask(request)) return null
        if (!isSensitiveAppScreen(screenData)) return null
        if (hasProvidedPin(request)) return null
        if (!isPinLoginScreen(screenData)) return null
        return "Task stopped: a PIN/passcode is required on the login screen, but the current request did not provide one. Return to BQAAgent and ask the user to include a temporary PIN/passcode for this test task."
    }

    @JvmStatic
    fun missingCredentialTerminalMessage(screenData: String?, request: String?): String? {
        missingPinTerminalMessage(screenData, request)?.let { return it }
        if (!isSensitiveTask(request)) return null
        if (!isSensitiveAppScreen(screenData)) return null
        if (hasProvidedPassword(request)) return null
        if (!isPasswordLoginScreen(screenData)) return null
        return "Task stopped: a password is required on the login screen, but the current request only provided a PIN/passcode. Return to BQAAgent and ask the user to include the required temporary password for this test task."
    }

    @JvmStatic
    fun repeatedPinTerminalMessage(screenData: String?, request: String?, attempts: Int): String? {
        if (!isSensitiveTask(request)) return null
        if (!isSensitiveAppScreen(screenData)) return null
        if (!hasProvidedPin(request)) return null
        if (attempts < 2) return null
        if (!isPinLoginScreen(screenData)) return null
        return "Task stopped: PIN login did not complete after $attempts secure keypad attempts. The app is still showing a PIN/login screen, so the agent will not re-enter the PIN again. Verify the PIN, session state, or whether the bank app rejected automated keypad input."
    }

    @JvmStatic
    fun loginTimeoutTerminalMessage(screenData: String?, request: String?, attempts: Int, deepLinkAttempts: Int): String? {
        if (!isSensitiveTask(request)) return null
        if (!isSensitiveAppScreen(screenData)) return null
        if (attempts < 1) return null
        if (!isLoginTimeoutScreen(screenData)) return null
        if (allowsPostPinDeepLinkRecovery(request) && deepLinkAttempts < 1) return null
        return "Task stopped: the banking app returned an operation-timeout/automatic-logout screen after PIN login. The PIN was entered and the app moved into processing, but the test environment timed out or rejected the session. The agent will not retry the PIN again; verify the test environment/session state before resuming."
    }

    @JvmStatic
    fun loginTimeoutRecoveryNotice(screenData: String?, request: String?, attempts: Int, deepLinkAttempts: Int): String? {
        if (!allowsPostPinDeepLinkRecovery(request)) return null
        if (!isSensitiveAppScreen(screenData)) return null
        if (attempts < 1 || deepLinkAttempts >= 1) return null
        if (!isLoginTimeoutScreen(screenData)) return null
        return "[System Notice] The app showed an operation-timeout/automatic-logout screen after PIN, but the HSBC session can expose Pay/Transfer via the app's own deep link. Do not tap Return to Login or re-enter the PIN. Use open_url(url=\"$HSBC_PAY_DEEP_LINK\", package_name=\"sg.com.hsbc.hsbcsingapore.cert\") exactly once, then continue from the Pay/Transfer screen."
    }

    @JvmStatic
    fun isSensitiveAppScreen(screenData: String?): Boolean {
        val lower = screenData
            ?.replace("\\p{Cf}".toRegex(), "")
            ?.lowercase()
            .orEmpty()
        if (lower.isBlank()) return false
        return sensitivePackages.any { pkg ->
            val packageName = pkg.lowercase()
            lower.contains("package=$packageName") ||
                lower.contains("package=\"$packageName\"") ||
                lower.contains("packagename: $packageName")
        }
    }

    private fun isLoginTimeoutScreen(screenData: String?): Boolean {
        val lower = screenData
            ?.replace("\\p{Cf}".toRegex(), "")
            ?.lowercase()
            .orEmpty()
        if (lower.isBlank()) return false
        val compact = lower.replace("\\s+".toRegex(), "")
        val timeout = compact.contains("操作已逾时") ||
            compact.contains("操作已逾時") ||
            compact.contains("operationtimedout") ||
            compact.contains("timedout")
        val logout = compact.contains("自动退出登录") ||
            compact.contains("自動退出登入") ||
            compact.contains("自動登出") ||
            compact.contains("automaticallyloggedout") ||
            compact.contains("loggedout")
        return timeout || logout
    }

    private fun allowsPostPinDeepLinkRecovery(request: String?): Boolean {
        val lower = request?.lowercase().orEmpty()
        return isSensitiveTask(request) && listOf(
            "transfer",
            "转账",
            "轉賬",
            "名下账户",
            "名下賬戶",
        ).any { lower.contains(it) }
    }

    @JvmStatic
    fun blockedToolMessage(toolName: String, request: String?): String? {
        if (toolName !in forbiddenTools) return null
        if (!isSensitiveContext(request)) return null
        return "Tool '$toolName' is unavailable in sensitive-app mode. Use get_screen_info and text UI-tree tools."
    }

    @JvmStatic
    fun blockedInputMessage(toolName: String, params: Map<String, Any>, request: String?): String? {
        if (!isSensitiveContext(request)) return null
        if (toolName == "secure_keypad_input" && !hasProvidedPin(request)) {
            return "Do not enter or guess a PIN/passcode that was not provided in the current request. Stop and ask the user to provide the temporary PIN/passcode."
        }
        if (toolName != "input_text") return null
        val text = params["text"]?.toString().orEmpty().trim()
        val lowerRequest = request?.lowercase().orEmpty()
        val looksLikePin = text.matches(Regex("\\d{4,8}")) &&
            listOf("pin", "passcode", "password", "密码", "密碼").any { lowerRequest.contains(it) }
        val looksLikeMoney = text.matches(Regex("\\d+(\\.\\d{1,2})?")) &&
            listOf("amount", "transfer", "money", "金额", "金額", "转账", "轉賬").any { lowerRequest.contains(it) }
        return when {
            looksLikePin -> "Do not use input_text for sensitive numeric credentials. Use secure_keypad_input instead."
            looksLikeMoney -> "Do not use input_text for sensitive monetary amount fields. Use input_amount instead."
            else -> null
        }
    }

    @JvmStatic
    fun blockedScrollMessage(toolName: String, params: Map<String, Any>, request: String?): String? {
        if (toolName != "scroll_to_find" && toolName != "find_and_tap") return null
        if (!isSensitiveContext(request)) return null
        val text = params["text"]?.toString()?.lowercase()?.trim().orEmpty()
        if (text !in fixedNavigationTerms) return null
        return "Do not scroll for fixed navigation tab '$text' in sensitive-app mode. Use tap_visible_text or tap_node on the visible tab."
    }

    @JvmStatic
    fun blockedCredentialSwitchMessage(toolName: String, params: Map<String, Any>, request: String?): String? {
        if (!isSensitiveContext(request)) return null
        if (hasProvidedPassword(request)) return null
        if (toolName !in setOf("tap_visible_text", "find_and_tap", "scroll_to_find")) return null
        val text = params["text"]?.toString().orEmpty()
        if (!looksLikePasswordSwitch(text)) return null
        return "Do not switch to password login in sensitive-app mode because the current request did not provide a password. Stay on the PIN flow or stop with the missing credential blocker."
    }

    @JvmStatic
    fun blockedTapMessage(toolName: String, params: Map<String, Any>, request: String?): String? {
        if (toolName !in setOf("tap", "tap_node", "tap_visible_text")) return null
        if (!isSensitiveContext(request)) return null
        val nodes = try {
            val current = LocalAdbDeviceDriver.currentMappedNodes()
            if (current.isNotEmpty()) current else {
                LocalAdbDeviceDriver.getScreenTree("actionable")
                LocalAdbDeviceDriver.currentMappedNodes()
            }
        } catch (e: Exception) {
            XLog.w(TAG, "Unable to inspect tap target for sensitive policy", e)
            return null
        }

        val targetNode = when (toolName) {
            "tap_node" -> {
                val nodeId = params["node_id"]?.toString()?.replace("[", "")?.replace("]", "")?.trim() ?: return null
                nodes.firstOrNull { it.nodeId == nodeId }
            }
            "tap_visible_text" -> {
                val text = params["text"]?.toString()?.trim().orEmpty()
                findNodeByVisibleText(nodes, text)
            }
            else -> {
                val x = params["x"].toIntOrNull() ?: return null
                val y = params["y"].toIntOrNull() ?: return null
                nodes.firstOrNull { it.bounds.contains(x, y) }
            }
        }

        if (isHsbcDashboardRootScreen(nodes)) {
            if (targetNode != null && isDashboardTopTabNode(targetNode, nodes)) {
                return null
            }
            return if (shouldUseBankTransferBatch(request)) {
                buildBankTransferBatchMessage(request)
            } else {
                "Do not tap dashboard content in HSBC sensitive-app mode. Only the fixed top dashboard tabs are allowed from the dashboard screen."
            }
        }

        if (shouldUseBankTransferBatch(request) && isBankTransferBatchScreen(nodes, request)) {
            return buildBankTransferBatchMessage(request)
        }

        if (targetNode != null && isPasswordCredentialNode(targetNode) && !hasProvidedPassword(request)) {
            return "Do not switch to or enter password login in sensitive-app mode because the current request did not provide a password. Stay on the PIN flow or stop with the missing credential blocker."
        }

        if (targetNode != null && shouldUseBankTransferBatch(request) && isBankTransferBatchTarget(targetNode, request)) {
            return buildBankTransferBatchMessage(request)
        }

        val disabledTarget = targetNode?.takeIf { !it.enabled && isFinancialActionNode(it) }
        return if (disabledTarget != null) {
            "Do not tap disabled financial action '${disabledTarget.displayLabel().ifBlank { disabledTarget.resourceId }}'. Inspect validation text or required fields first."
        } else null
    }

    @JvmStatic
    fun bankTransferBatchParamsIfReady(screenData: String?, request: String?): Map<String, Any>? {
        if (!isSensitiveAppScreen(screenData)) return null
        if (isPinLoginScreen(screenData) || isPasswordLoginScreen(screenData) || isLoginTimeoutScreen(screenData)) return null
        if (!isBankTransferBatchScreenText(screenData, request)) return null
        return bankTransferBatchParamsFromRequest(request)
    }

    @JvmStatic
    fun bankTransferBatchParamsFromRequest(request: String?): Map<String, Any>? {
        if (!shouldUseBankTransferBatch(request)) return null

        val params = linkedMapOf<String, Any>(
            "source_account" to extractSourceAccount(request),
            "destination_account" to extractDestinationAccount(request),
            "debit_amount" to extractDebitAmount(request),
            "submit_final" to allowsFinalFinancialAction(request),
        )
        extractCreditAmount(request).takeIf { it.isNotBlank() }?.let { credit ->
            params["credit_amount"] = credit
        }
        return params
    }

    private fun shouldUseBankTransferBatch(request: String?): Boolean {
        val lower = request?.lowercase().orEmpty()
        if (!isSensitiveTask(request)) return false
        val isHsbcCertRequest = lower.contains("hsbc") ||
            lower.contains("singapore cert") ||
            lower.contains("sg cert")
        if (!isHsbcCertRequest) return false
        return ownAccountTransferTerms.any { lower.contains(it) } &&
            extractSourceAccount(request).isNotBlank() &&
            extractDestinationAccount(request).isNotBlank() &&
            extractDebitAmount(request).isNotBlank()
    }

    private fun isBankTransferBatchTarget(node: io.agents.bqaagent.adb.UiNode, request: String?): Boolean {
        val label = listOf(node.text, node.contentDescription, node.resourceId)
            .joinToString(" ")
            .replace("\\p{Cf}".toRegex(), "")
            .lowercase()
        if (label.isBlank()) return false
        if (ownAccountTransferTerms.any { label.contains(it) }) {
            return true
        }
        val source = extractSourceAccount(request)
        val destination = extractDestinationAccount(request)
        return (source.isNotBlank() && label.contains(source.lowercase())) ||
            (destination.isNotBlank() && label.contains(destination.lowercase()))
    }

    private fun isBankTransferBatchScreen(nodes: List<io.agents.bqaagent.adb.UiNode>, request: String?): Boolean {
        val screenLabels = nodes.joinToString(" ") { node ->
            listOf(node.text, node.contentDescription, node.resourceId).joinToString(" ")
        }.replace("\\p{Cf}".toRegex(), "").lowercase()
        return isBankTransferBatchScreenText(screenLabels, request)
    }

    private fun isBankTransferBatchScreenText(screenData: String?, request: String?): Boolean {
        val screenLabels = screenData
            .orEmpty()
            .replace("\\p{Cf}".toRegex(), "")
            .lowercase()
        if (screenLabels.isBlank()) return false
        if (isHsbcDashboardTransferTabScreen(screenLabels)) {
            return true
        }
        if (ownAccountTransferTerms.any { screenLabels.contains(it) }) {
            return true
        }
        val source = extractSourceAccount(request)
        val destination = extractDestinationAccount(request)
        return (source.isNotBlank() && screenLabels.contains(source.lowercase())) ||
            (destination.isNotBlank() && screenLabels.contains(destination.lowercase()))
    }

    private fun isHsbcDashboardTransferTabScreen(screenLabels: String): Boolean {
        val compact = screenLabels.replace("\\s+".toRegex(), "")
        val hasTransferTab = listOf("转账", "轉賬", "轉帳", "transfer", "transfers")
            .any { compact.contains(it) }
        if (!hasTransferTab) return false
        val peerGroups = listOf(
            listOf("银行卡", "銀行卡", "cards"),
            listOf("财务", "財務", "财富", "財富", "wealth"),
            listOf("产品", "產品", "products"),
        )
        val peerCount = peerGroups.count { group -> group.any { compact.contains(it) } }
        return peerCount >= 2
    }

    private fun isHsbcDashboardRootScreen(nodes: List<UiNode>): Boolean {
        val visibleTopNodes = nodes.filter {
            it.enabled && hasVisibleBounds(it) && it.centerY <= dashboardTopNavigationMaxY(nodes)
        }
        val hasHome = visibleTopNodes.any { node ->
            dashboardTabGroups[0].any { matchesNodeLabel(node, it) }
        }
        val hasTransfer = visibleTopNodes.any { node ->
            dashboardTabGroups[1].any { matchesNodeLabel(node, it) }
        }
        val peerCount = dashboardTabGroups.drop(2).count { group ->
            visibleTopNodes.any { node -> group.any { matchesNodeLabel(node, it) } }
        }
        return hasHome && hasTransfer && peerCount >= 2
    }

    private fun isDashboardTopTabNode(node: UiNode, nodes: List<UiNode>): Boolean {
        if (!node.enabled || !hasVisibleBounds(node)) return false
        if (node.centerY > dashboardTopNavigationMaxY(nodes)) return false
        if (dashboardTabGroups.none { group -> group.any { matchesNodeLabel(node, it) } }) return false
        return dashboardTabPeerCountNear(node, nodes) >= 3
    }

    private val dashboardTabGroups = listOf(
        listOf("home", "首页", "首頁", "主页", "主頁"),
        listOf("transfer", "transfers", "转账", "轉賬", "轉帳"),
        listOf("cards", "银行卡", "銀行卡"),
        listOf("wealth", "财务", "財務", "财富", "財富"),
        listOf("products", "产品", "產品"),
    )

    private fun dashboardTabPeerCountNear(anchor: UiNode, nodes: List<UiNode>): Int {
        return dashboardTabGroups.count { group ->
            nodes.any { node ->
                node.enabled &&
                    hasVisibleBounds(node) &&
                    kotlin.math.abs(node.centerY - anchor.centerY) <= 140 &&
                    group.any { matchesNodeLabel(node, it) }
            }
        }
    }

    private fun dashboardTopNavigationMaxY(nodes: List<UiNode>): Int {
        val screenHeight = nodes.maxOfOrNull { it.bounds.bottom } ?: 2340
        return maxOf(420, (screenHeight * 0.40f).toInt())
    }

    private fun findNodeByVisibleText(nodes: List<UiNode>, text: String): UiNode? {
        if (text.isBlank()) return null
        return nodes
            .filter { it.enabled && hasVisibleBounds(it) }
            .filter { matchesNodeLabel(it, text) }
            .maxByOrNull { (if (it.clickable) 100_000 else 0) - it.centerY }
    }

    private fun matchesNodeLabel(node: UiNode, query: String): Boolean {
        val needle = normalizeLabel(query)
        if (needle.isBlank()) return false
        return listOf(node.text, node.contentDescription, node.resourceId)
            .map { normalizeLabel(it) }
            .any { label -> label == needle || label.contains(needle) }
    }

    private fun hasVisibleBounds(node: UiNode): Boolean {
        return node.bounds.width() > 0 && node.bounds.height() > 0
    }

    private fun normalizeLabel(value: String): String {
        return value
            .replace("\\p{Cf}".toRegex(), "")
            .lowercase()
            .replace("\\s+".toRegex(), "")
    }

    private fun buildBankTransferBatchMessage(request: String?): String {
        val source = extractSourceAccount(request)
        val destination = extractDestinationAccount(request)
        val debit = extractDebitAmount(request)
        val credit = extractCreditAmount(request)
        val submitFinal = allowsFinalFinancialAction(request)
        val creditArg = if (credit.isNotBlank()) """, credit_amount="$credit"""" else ""
        return "Use bank_own_account_transfer(source_account=\"$source\", destination_account=\"$destination\", debit_amount=\"$debit\"$creditArg, submit_final=$submitFinal) instead of manual card/account taps. This keeps the sensitive ADB-only transfer flow within the token budget."
    }

    private fun extractSourceAccount(request: String?): String {
        return extractAfterLabels(request, sourceAccountLabels)
    }

    private fun extractDestinationAccount(request: String?): String {
        return extractAfterLabels(request, destinationAccountLabels)
    }

    private fun extractDebitAmount(request: String?): String {
        return extractAmountAfterLabels(request, debitAmountLabels)
            .ifBlank { extractAmountAfterLabels(request, genericAmountLabels) }
    }

    private fun extractCreditAmount(request: String?): String {
        return extractAmountAfterLabels(request, creditAmountLabels)
    }

    private fun extractAfterLabels(request: String?, labels: List<String>): String {
        val text = request.orEmpty()
        labels.forEach { label ->
            Regex("${Regex.escape(label)}\\D{0,32}(\\d{3}-\\d{6}-\\d{3})", RegexOption.IGNORE_CASE)
                .find(text)
                ?.groupValues
                ?.getOrNull(1)
                ?.let { return it }
        }
        return ""
    }

    private fun extractAmountAfterLabels(request: String?, labels: List<String>): String {
        val text = request.orEmpty()
        labels.forEach { label ->
            Regex("${Regex.escape(label)}\\D{0,20}(\\d+(?:\\.\\d{1,2})?)", RegexOption.IGNORE_CASE)
                .find(text)
                ?.groupValues
                ?.getOrNull(1)
                ?.let { return it }
        }
        return ""
    }

    private fun isPasswordCredentialNode(node: io.agents.bqaagent.adb.UiNode): Boolean {
        val label = listOf(node.text, node.contentDescription, node.resourceId, node.className)
            .joinToString(" ")
            .lowercase()
            .replace("\\p{Cf}".toRegex(), "")
        val compact = label.replace("\\s+".toRegex(), "")
        val mentionsPassword = compact.contains("password") ||
            compact.contains("passcode") ||
            compact.contains("密码") ||
            compact.contains("密碼")
        if (!mentionsPassword) return false
        return listOf("log", "login", "sign", "switch", "use", "enter", "登录", "登入", "切换", "切換", "输入", "輸入")
            .any { compact.contains(it) }
    }

    private fun looksLikePasswordSwitch(text: String): Boolean {
        val compact = text
            .replace("\\p{Cf}".toRegex(), "")
            .lowercase()
            .replace("\\s+".toRegex(), "")
        val mentionsPassword = compact.contains("password") ||
            compact.contains("passcode") ||
            compact.contains("密码") ||
            compact.contains("密碼")
        if (!mentionsPassword) return false
        return listOf("log", "login", "sign", "switch", "use", "enter", "登录", "登入", "切换", "切換", "输入", "輸入")
            .any { compact.contains(it) }
    }

    private fun isFinancialActionNode(node: io.agents.bqaagent.adb.UiNode): Boolean {
        val label = listOf(node.text, node.contentDescription, node.resourceId, node.className)
            .joinToString(" ")
            .lowercase()
        return listOf(
            "continue",
            "next",
            "submit",
            "confirm",
            "transfer",
            "payment",
            "multistatebutton",
            "submitcontainer",
            "继续",
            "下一步",
            "确认",
            "確認",
            "提交",
            "转账",
            "轉賬",
        ).any { label.contains(it) }
    }

    private fun io.agents.bqaagent.adb.UiNode.displayLabel(): String {
        return listOf(text, contentDescription).firstOrNull { it.isNotBlank() }.orEmpty()
    }

    private fun Any?.toIntOrNull(): Int? = when (this) {
        is Number -> toInt()
        is String -> trim().toDoubleOrNull()?.toInt()
        else -> null
    }
}
