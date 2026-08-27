-- T19 步 13（ADR-0011）：切換。HapeeTrail 服務（Spring Boot）成為資料進出的唯一路徑，
-- v3.3 的五支契約 RPC 與六支 helper 從資料庫消失。
--
-- 前提：iOS 已改打 v4，不再呼叫 `/rest/v1/rpc/*`。這支套上去之後 v3.3 的 client 會全面 404。
--
-- 為什麼是 drop 而不是 revoke：留著沒人呼叫的 SECURITY DEFINER 函式，等於留著一組
-- 以表擁有者身分執行的入口，只靠 grant 表擋著——ADR-0011 的一刀切就是不留這個。
--
-- 收回殘餘 EXECUTE 不需要另外寫：drop 會連同該函式的所有 grant 一起消失。
-- 套用後 `authenticated`／`anon` 在 public schema 沒有任何可執行物件，對 public.notes
-- 也早已零權限（T12，`20260728080000_close_direct_read.sql`）。
--
-- ponytail: 不帶參數清單——每個名字在此刻的 schema 都只有一支（migration 鏈決定，
-- 非巧合），Postgres 因而解析得出唯一目標；帶了反而要跟著 6 個 create/drop 版本追型別。
-- 哪天同名多載回來，這裡會直接報錯而不是靜默漏刪。

-- 五支契約 RPC
drop function if exists public.drop_note;
drop function if exists public.nearby_notes;
drop function if exists public.pickup_note;
drop function if exists public.my_notes;
drop function if exists public.my_collection;

-- 六支內部 helper（wire 格式、游標、距離、TTL）——邏輯已搬進 NoteService
drop function if exists public.as_note_wire;
drop function if exists public.as_wire_ts;
drop function if exists public.as_cursor;
drop function if exists public.parse_cursor;
drop function if exists public.distance_m;
drop function if exists public.note_ttl;

-- 表註解裡的「一律經五支 RPC 存取」已不再成立；ADR-0007 的保證改由服務承接。
comment on table public.notes is
  '便條。client 角色（anon／authenticated）對本表與 public schema 的函式皆無任何權限——資料進出的唯一路徑是 HapeeTrail 服務（以 hapeetrail_api 連線）。ADR-0007 的保證在 ADR-0011 的新架構下延續。';
