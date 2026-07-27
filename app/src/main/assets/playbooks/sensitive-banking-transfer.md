---
id: sensitive_banking_transfer
name: Sensitive Banking Transfer
triggers:
  - "hsbc singapore cert"
  - "own-account transfer"
  - "bank transfer"
  - "transfer money"
  - "转账至名下账户"
  - "轉賬至名下賬戶"
  - "名下账户"
  - "名下賬戶"
  - "hsbc"
  - "汇丰"
  - "滙豐"
---

Use this playbook for sensitive banking transfer tasks while keeping all actions screen-driven.

1. Open the requested banking app first. For HSBC Singapore Cert, use `open_app(package_name="sg.com.hsbc.hsbcsingapore.cert")`.
2. If the app shows a timeout or return-to-login page, tap the visible login/return action once, then inspect the screen.
3. If a PIN/passcode login screen appears:
   - If the current user request includes a PIN/passcode, enter it with `secure_keypad_input(digits="...", submit_after_entry=false)`.
   - The PIN tool clears stale focused input and can use numeric key events when the keyboard is hidden from UI dump.
   - After PIN entry, inspect the screen. Do not use `secure_keypad_input` itself to tap a login/password action.
   - If the app shows an operation-timeout / automatic-logout screen immediately after PIN, do not tap return-to-login and do not re-enter the PIN. Use the app's own Pay/Transfer deep link once: `open_url(url="https://hbsg.dxp2.preprod.eu.dynp.cloud1.vv1865.com/pay", package_name="sg.com.hsbc.hsbcsingapore.cert")`, then continue from the Pay/Transfer screen.
   - If the current user request does not include a PIN/passcode, call `finish` with a missing-PIN blocker. Do not guess or reuse old credentials.
   - If the same task already used `secure_keypad_input` twice and the app is still/again showing a PIN/login screen, call `finish` with a login blocker instead of trying the PIN again.
4. Before each HSBC transfer task, return to the dashboard/home tab when RootHub top navigation is visible. Then enter transfer only through the fixed top Transfer/转账/轉賬/轉帳 tab.
5. If the HSBC dashboard top tabs are visible (Home/Transfer/Cards/Wealth/Products or 首页/转账/银行卡/财富/产品 or 首頁/轉賬/轉帳/銀行卡/財富/產品), only the fixed top tabs may be tapped. Do not tap dashboard cards, quick actions, banners, account tiles, or any other dashboard content. Prefer the batch helper directly; it will return to dashboard/home first, then tap only the top Transfer tab.
6. Navigate to the Transfer tab with `tap_visible_text` or `tap_node` when the tab is visible. Do not use `scroll_to_find` for fixed top or bottom tabs.
7. When the HSBC Pay/Transfer page is visible, prefer the batch helper:
   `bank_own_account_transfer(source_account="...", destination_account="...", debit_amount="...", credit_amount="...", submit_final=true/false)`.
   Use `submit_final=true` only when the user explicitly asked to complete the transfer in a test/cert environment.
8. If the batch helper is unavailable after the Transfer/Pay page is already open, choose the own-account transfer card/list item and select source/destination accounts by exact account number from the user request. Never use this fallback from the dashboard.
9. Enter the debit/from amount with `input_amount(amount="...", field="debit")`.
10. If the user gave a generic Amount/金额/金額 instead of debit/credit-specific wording, treat it as the debit/from amount for own-account transfer.
11. If the user explicitly provided a receive/credit amount and the debit amount leaves Continue disabled, try exactly once with `input_amount(amount="...", field="credit", allow_receive_amount=true)`.
12. If any amount or batch result says BLOCKED, 12000, backend error, validation error, or Continue/Next disabled, call `finish` with that blocker. Do not keep re-reading the same screen.
13. Never tap a disabled Continue/Next/Confirm/Submit action.
14. Finish with a concise summary of the final state, including any blocker from the bank or validation layer.
