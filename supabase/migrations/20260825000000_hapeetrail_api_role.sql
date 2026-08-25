-- T19（ADR-0011）：HapeeTrail 服務（Spring Boot）連線用的最小權限角色。
--
-- 這是「準備」migration：只加東西、不動任何既有物件，所以與線上的 RPC 版並存無害，
-- 可以在 Java 服務部署之前先套上。真正的切換（drop 五支 RPC、收回殘餘 EXECUTE）
-- 是切換日當天的另一支 migration。
--
-- 密碼**不在這裡**：`create role ... login` 建出來的角色沒有密碼就登入不了，
-- 密碼以一次性的手動 SQL 設（`alter role hapeetrail_api password '…'`），值只存在
-- 部署平台的 secrets。migration 進 git，密碼不進 git。測試環境由 Testcontainers
-- 在套完 migration 後自己補一個本機密碼。
--
-- ponytail: 沒有 `if not exists` 的 guard——角色雖然是 cluster 級的，但實測
-- `supabase db reset` 會把整個 db 容器重建，角色跟著消失，重跑不會撞名（連跑三次驗過）。
create role hapeetrail_api login;

-- 服務只碰 public.notes 這一張表：
--   * 沒有 DELETE——契約沒有刪除路徑，給了就是白給攻擊面。
--   * 沒有 auth schema 的任何權限——author_id／picked_up_by 的 FK 檢查以表擁有者身分
--     執行，不需要呼叫者對 auth.users 有權限。
--   * extensions 的 USAGE 是給 PostGIS 的：距離與半徑一律在 SQL 語句內用 geography 算。
grant usage on schema public, extensions to hapeetrail_api;
grant select, insert, update on table public.notes to hapeetrail_api;

-- RLS 維持啟用，`notes_select_own` 保留休眠（ADR-0007）。這條全列 permissive policy 讓
-- hapeetrail_api 看得到整張表——它必須看得到別人的便條才做得到探索與撿取，授權邊界在
-- Java 的 WHERE 子句，不在 RLS。那為什麼不乾脆關掉 RLS：public schema 的 default
-- privileges 哪天讓某個 client 角色靜默重新拿到表權限時，休眠的 policy 仍把它限在
-- 自己的列——多一層不花錢的防線。
create policy notes_api_all on public.notes
  for all to hapeetrail_api
  using (true) with check (true);

comment on policy notes_api_all on public.notes is
  'HapeeTrail 服務的全列放行。服務角色只有 SELECT／INSERT／UPDATE，授權隔離由 Java 的 WHERE 子句負責；RLS 不關是為了讓 notes_select_own 在表權限意外回來時仍是第二層。';
