# 票 01 — Supabase／Apple／Google 三邊設定

主要是 console 手動操作（Kevin），Claude 陪跑核對。逐項設定值出處：研究檔 Q3／Q4／Q5。

## Apple Developer

- [x] 取得夥伴 App 的 **Bundle ID**：`tw.com.rrrrrwei.HapeeTrail`（2026-09-06 Kevin 提供）
- [ ] App ID 開啟 **Sign in with Apple** capability
- [ ] 純原生不需要：Services ID／Team ID／Key ID／.p8（那些是 web flow 才要）

## Google Cloud Console

- [ ] 建 **Web application** 類型 OAuth Client ID（給 Supabase 伺服器端驗 id_token）
- [ ] 建 **iOS** 類型 OAuth Client ID（填 Bundle ID）

## Supabase Dashboard（Authentication → Providers）

- [ ] **Apple**：啟用；Client IDs 填 Bundle ID
- [ ] **Google**：啟用；Client IDs 填「Web Client ID, iOS Client ID」（逗號分隔，Web 在前）；
      打開 **Skip nonce check**（Google 原生 SDK 的 id_token 不帶雜湊 nonce，不開必炸）
- [ ] **Email**：停用（這就是「不開放自助註冊」的正確落點）
- [ ] **Anonymous**：確認仍啟用
- [ ] **「Allow new users to sign up」保持開啟**——關了連匿名一起死（研究檔 Q5，原始碼證據）
- [ ] 「Enable Manual Linking」：**先不動**，票 02 兩態實測後再定案要不要開

## 驗收

- 每個開關的最終狀態抄錄回本票（dashboard 撥完要按 Save 才生效——T2 踩過的坑）
- `curl` 匿名登入一次確認沒被誤傷
