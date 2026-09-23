# T28 登出立即失效＋註銷帳號 — 施工紀錄（已歸檔）

完工 2026-09-23。**這裡是歷史文件**：當時的路徑、版本、決策依據原樣保留，不隨後續改動更新。
現在的行為以 `docs/api/`（契約）、`docs/adr/0013`、`docs/adr/0014` 為準。

| 票 | 內容 | 結果 |
|---|---|---|
| [01](issues/01-package-reorg.md) | 分包整理（package by feature，行為零改變） | ✅ `fb7456f` |
| [02](issues/02-research-pin-facts.md) | 研究釘死登出／Admin 刪除／金鑰三事實 | ✅ `eb555ae`（推翻 spec 的單一 GRANT） |
| [03](issues/03-session-liveness.md) | session 存活驗證＋ADR-0013 | ✅ `d06c022` |
| [04](issues/04-delete-me-and-contract.md) | `DELETE /v1/me` ＋契約 v4.1.0 | ✅ `ac00d33` |
| [05](issues/05-hosted-verify-and-closeout.md) | hosted 實證＋兩個獨立複核＋收尾 | ✅ `f124c51`、`f95b7f2` |

驗收：`./mvnw test` 215 綠、hosted-smoke 47/47、newman 3 輪 57 斷言 0 失敗。
未完的一件事留在 T23 ⑤：含 GoTrue 逾時的 revision 尚未部署。
