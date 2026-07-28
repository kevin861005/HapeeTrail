# ADR-0007：client 角色對 `notes` 表沒有任何直接權限

日期：2026-07-28　狀態：已採納（T12 實作）

## 決策

`anon` 與 `authenticated` 對 `public.notes` 的所有權限一律收回；資料只經五支
SECURITY DEFINER 的 RPC 進出。兩支列表 RPC（`my_notes`／`my_collection`）因此由
SECURITY INVOKER 改為 DEFINER。內部 helper（`as_note_wire`／`as_wire_ts`／`as_cursor`／
`parse_cursor`／`distance_m`）同樣不授權給任何 client 角色。

本 ADR 取代 spec（`.scratch/api-contract-v3/spec.md`「函式簽名與權限」）中
「兩支列表維持 SECURITY INVOKER、權限由 RLS 與資料表授權把關」那一條。

## 理由

- RLS 是「自己寫的或自己撿的」，加上整表 SELECT grant，於是**便條作者直讀自己那一列
  就拿得到 `picked_up_by` ＝ 撿走它的人的 `auth.users.id`**。ADR 之外的既有決定
  （T7：便條不帶 uuid 身分欄位）理由是「帳號綁定後 uuid 會變成可連結真人身分的穩定
  識別字，且已發出的資料收不回來」——那個理由對這條路徑一樣成立，只是當時沒收。
- 收掉之後，`author_id`／`picked_up_by`／`location` 這些不上 wire 的欄位再也沒有 client
  路徑讀得到，「新增表欄位預設不外洩」這個保證從只覆蓋 RPC 擴大到覆蓋全部路徑。
- 曾否決的替代方案是**欄位級 grant**：兩支列表當時是 INVOKER，其 WHERE 引用
  `author_id`／`picked_up_by` 同樣受欄位權限管制，會 permission denied
  （紀錄在 `20260712010000_my_lists.sql`）。改 DEFINER 才讓收回整表權限成為可能。

## 關鍵取捨：兩支列表失去 RLS 這層防禦

改 DEFINER 後，`my_notes`／`my_collection` 不再受 RLS 約束，**WHERE 子句成為唯一的
隔離保證**（各一行：`author_id = v_uid`／`picked_up_by = v_uid`）。接受的理由：

- 這兩行本來就存在，RLS 只是第二層；不是把防線從有變無，是從兩層變一層。
- 測試對此有**跨使用者的正面斷言**：同一時刻 B 走完 29 張自己的便條，而沒有投放任何
  便條的 A 拿到的 `my_notes` 必須是空的、`my_collection` 恰為 1
  （`supabase/tests/notes.test.sql`）。WHERE 若掉了，這兩條會立刻紅。

## 已知缺口

RLS policy `notes_select_own` **保留但休眠**——目前沒有任何角色有 SELECT，所以它不生效，
也因此**沒有任何測試守著它**。保留而不刪除，是因為哪天真的需要重開表權限時，它是現成的
第二層，而不是要重新想一次；但**重開權限前必須先補回 RLS 的測試覆蓋**，否則等於在沒有
驗證的情況下依賴它。

## 對契約的影響

**零。** 五支 RPC 的參數、回傳形狀、錯誤 token 全部不變，iOS 不需要改任何東西。
受影響的只有 `docs/api/notes.md` §10 的揭露清單（大幅縮水）。
