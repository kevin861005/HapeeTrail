# 票 01 — Supabase／Apple／Google 三邊設定

主要是 console 手動操作（Kevin），Claude 陪跑核對。逐項設定值出處：研究檔 Q3／Q4／Q5。

## Apple Developer

- [x] 取得夥伴 App 的 **Bundle ID**：`tw.com.rrrrrwei.HapeeTrail`（2026-09-06 Kevin 提供）
- [ ] App ID 開啟 **Sign in with Apple** capability
- [ ] 純原生不需要：Services ID／Team ID／Key ID／.p8（那些是 web flow 才要）

## Google Cloud Console

- [x] 建 **Web application** 類型 OAuth Client ID ✅ 2026-09-06（專案 `hapeetrail-test`，
      redirect URI 已加 Supabase callback）：
      `134868178961-6sfrod5c5a158j1c77lrhbd4k8mmrful.apps.googleusercontent.com`
- [x] 建 **iOS** 類型 OAuth Client ID（Bundle ID `tw.com.rrrrrwei.HapeeTrail`）✅ 2026-09-06：
      `134868178961-ke5tqllirfgm1515013pggqo3o3dgkpe.apps.googleusercontent.com`
- [x] 同意畫面：External、Testing 模式、test users 已加（Kevin＋夥伴）；
      **刻意不傳 logo**（傳了就要送審）、隱私權/ToS 連結留待上架前

## Supabase Dashboard（Authentication → Providers）

- [x] **Apple**：啟用；Client IDs = `tw.com.rrrrrwei.HapeeTrail`，其餘欄位留空 ✅ 2026-09-06
- [x] **Google**：啟用；Client IDs =「Web, iOS」逗號分隔（值見上方 Google 段）；
      **Skip nonce check 已開**；secret 留空 ✅ 2026-09-06
- [x] **Email**：停用 ✅ 2026-09-06
- [x] **Anonymous**：維持啟用 ✅
- [x] **「Allow new users to sign up」維持開啟** ✅（settings 實測 `disable_signup: false`）
- [x] 「Enable Manual Linking」：**維持關閉**，票 02 兩態實測後定案 ✅

## 驗收 ✅ 2026-09-06（curl 對真 hosted）

- `GET /auth/v1/settings`：`{google: True, apple: True, email: False, anonymous_users: True}`、
  `disable_signup: False`
- email/password 註冊 → **400 `email_provider_disabled`**（＝spec 驗收 4 的「被擋」半，提前收掉）
- 匿名登入對照組 → access_token 正常取得

**票 01 剩最後一項：Apple Developer 的 capability（見上方 Apple Developer 段，待確認帳號歸屬）**
