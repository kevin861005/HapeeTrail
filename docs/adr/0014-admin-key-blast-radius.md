# ADR-0014：註銷用的 admin 金鑰，爆炸半徑止於 GoTrue

日期：2026-09-23　狀態：已採納（T28 票 05；migration `20260923000000_revoke_service_role_notes.sql`）

## 背景

T28 票 04 起，服務為了註銷帳號持有一把 Supabase secret key（`sb_secret_…`，
ADR-0011 之後的第一把高權限憑證）。它經 hosted gateway 換成 `service_role` 的短效 JWT，
再打 GoTrue Admin API 硬刪使用者。

票 05 的獨立安全複核指出：這把鑰匙的權力不只 GoTrue。Supabase 對 `public` schema 設了
default privileges，`notes` 建表當下就把全部權限給了 `service_role`；而 `service_role`
帶 `BYPASSRLS`，policy 對它不生效。**同一把鑰匙因此也打開 `/rest/v1/notes`**：
全部便條的座標與內容可讀、可改、可刪。實測（本機同映像）：
`has_table_privilege('service_role','public.notes','select')` 為 true。

這與 T28 spec 的 User Story 17（「安全姿態不因這個功能退化」）字面衝突。

## 決策

**收回 `service_role` 對 `public.notes` 的全部權限**（`revoke all on public.notes from service_role`）。
金鑰外洩或服務被攻破時，攻擊者拿到的能力**止於 GoTrue Admin API**（列出／修改／刪除使用者），
不包含直接讀寫全體旅人的便條內容與座標。

不改的：
- **走 GoTrue Admin API 註銷**（T28 grilling Q4）不變——用廠商的公開契約管廠商的資料。
- **服務連 DB 仍是 `hapeetrail_api`**（ADR-0011）。它從來不用 `service_role` 碰 notes，
  所以這次收回對服務零影響。
- RLS 與既有 policy 不動。

## 為什麼

- **RLS 擋不住 `BYPASSRLS` 的角色**，表權限是唯一的鎖——要嘛在這裡收，要嘛不收。
- 金鑰的用途單一（刪一個使用者），能力卻是整個專案的最高權限；把用不到的那一半拿掉，
  是這個功能唯一能做的縱深防禦。
- 代價幾乎為零：沒有任何元件以 `service_role` 讀寫 notes。
  代價不是零的地方是**人**——日後想用 secret key 直接打 `/rest/v1/notes` 撈資料會 403，
  那正是本決策要的結果（要撈資料請用 Dashboard 的 SQL Editor，它以 `postgres` 連線）。

## 邊界（這一刀沒有解決的事）

- **只收了 `notes` 這一張表**，也就是 `public` 目前唯一的物件。Supabase 的 default privileges
  仍會把權限自動給回**往後新建的**物件——根治要 `alter default privileges`，
  是專案級慣例變更，屬 **T24**，不在這裡做。
- 金鑰仍能做 GoTrue admin 的一切事（包含為任何帳號產生登入連結 ⇒ 以那個帳號的身分
  合法取得 token）。這是「用 Admin API 註銷」的固有代價，本決策不推翻它。
- 防線：`SmokeTest.theAdminKeyBuysNothingOnNotes` 每次 `mvn test` 都向 pg 目錄問一次；
  哪天有人重建 notes 或新增物件把權限拿回去，它會紅。

## 後果

- 部署順序：這支 migration 與程式無關，先 push 或後 push 都不會讓服務壞掉。
- 若日後真的需要以 secret key 經 PostgREST 存取 notes（例如營運腳本），
  要重開這個決策，而不是靜默補一句 grant。
