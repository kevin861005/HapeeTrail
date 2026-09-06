# 免費（或近乎免費）部署平台調查——Spring Boot Docker 容器公開上網

- 調查日期：2026-09-06
- 目的：找出能把 HapeeTrail 的 Spring Boot 4.1 / Java 21 Docker image（531MB、multi-stage build、非 root、冷啟動實測約 2.1s、300MB RAM 內可跑）免費公開到網路上，供夥伴用手機（脫離 Tailscale tailnet）連測的平台
- 資料庫：Supabase Postgres，東京 ap-northeast-1（含 PostGIS）——每個 API 請求都會打 DB，部署平台的地理位置直接影響每請求延遲
- 用途：MVP 測試，個位數使用者，流量極低；需要能設 6 個環境變數（含 DB 密碼），最好不是明文儲存

## 查證方法與限制

- 只採信各平台官方定價頁 / 官方文件 / 官方 changelog 作為事實依據；Reddit、部落格排行榜、Stack Overflow 只用來找線索，不作為結論依據
- 每一條事實在下文都附官方來源網址
- 部分官方頁面（尤其 `cloud.google.com/run/pricing`、`fly.io/pricing`、IBM Cloud 部分頁面）因為是重度 JS 頁面，本次抓取工具多次回傳「內容被截斷」，因此改用內容較完整的鄰近官方文件頁（例如 Google 改用 `docs.cloud.google.com/free/docs/free-cloud-features`）；凡是「無法從官方原文逐字確認」的地方，下文會明確標記「未能完全驗證」，不會用二手來源硬填
- 各官方頁面若有列出「最後更新」日期，一併記錄，藉此判斷資訊新鮮度

---

## 1. 總覽表

| 平台 | 真的免費？ | 需要信用卡？ | 離東京最近的區域 | Sleep / 冷啟動行為 | 免費層 RAM | 對本專案的致命缺點 |
|---|---|---|---|---|---|---|
| **Google Cloud Run** | 是（額度遠超個位數測試用量） | 需要（開帳單帳戶時） | **asia-northeast1（東京本身）** | scale-to-zero；冷啟動時間官方未給保證值 | 依部署設定自訂（無固定上限，通常設 256–512MB 即可） | 免費出流量額度僅涵蓋「北美」1GB/月，東京區域出站流量恐怕全額計費（金額對個位數用戶而言極小，但非嚴格「零成本」） |
| **Render** | 是（免卡） | 不需要 | Singapore（無東京） | 閒置 15 分鐘後 sleep，下次請求約 1 分鐘喚醒 | 512MB / 0.1 CPU | 沒有東京節點，且每次醒來要等 ~1 分鐘，對「隨手打開 App 測試」的體驗不佳 |
| **Koyeb** | 是，但免費層排除東京 | 需要（僅驗證卡片，防詐用） | Tokyo 現為 Koyeb 核心區域**之一**，但**免費層只能選 Frankfurt 或 Washington D.C.** | 免費層文件未說明是否 scale-to-zero | 512MB / 0.1 vCPU | 免費層物理上無法部署到東京，本研究要解決的「靠近東京降低 DB 延遲」需求完全不成立 |
| **Railway** | 部分（有 $0/月 Free plan，但額度極低） | Trial 不需要；轉正的 Free plan 未明說 | Singapore（無東京） | 未特別說明 sleep 機制；資源額度用完即停 | Free plan 0.5GB／Trial 1GB | Free plan 每月僅 $1 usage credit，不足以撐起一個持續運行的 Java 服務；Trial 30 天到期後打回 Free plan 或需升級付費 |
| **Oracle Always Free (Ampere A1)** | 理論上是（若佔得到位） | 官方帳號建立本身即需付款方式驗證身分（本次未能重新逐字確認頁面，但這是 Oracle 一貫要求） | ap-tokyo-1 可選為 home region，但**容量常態不足**（Oracle 官方文件亦承認會出現 out-of-capacity 錯誤） | 非 serverless，是常駐 VM；閒置 7 天（CPU/網路/記憶體都低於 20%）會被強制回收 | 2 OCPU / 12GB（2026-06 減半後的現行額度，本次確認與先前結論一致） | 是要自己維運的裸 VM，不是 managed 容器平台；東京容量能否佔到位無法保證；長期閒置會被強制回收 |
| **Zeabur** | 是（免卡） | 不需要 | 官方文件**未公開**免費層可用區域 | 閒置後 sleep，下次請求喚醒約數秒 | 官方文件**未公開**確切 RAM/CPU 數字 | 官方頁面對 free plan 的硬體規格與可用區域完全沒有公布具體數字，等同黑盒，無法在動手測試前確認能否跑得動 Spring Boot |
| **Fly.io** | 否，無免費層 | 需要 | 可選（付費），例如東京附近區域 | 不適用 | 不適用 | 完全沒有免費方案；最小 shared-cpu-1x/1GB 約 US$5.92/月（持續運行） |
| **Northflank（Sandbox）** | 號稱是（宣稱 always-on 不 sleep） | 官方 FAQ 未明講免費方案是否強制輸入卡 | 官方列出「Asia East」，未確認是否等於東京或鄰近城市 | 官方宣稱 Sandbox 不 sleep | 官方頁面**未公開**確切 RAM/CPU 數字 | 免費層的規格與亞洲區域細節官方頁面都沒寫清楚，可信度不足以直接推薦 |
| **Hugging Face Spaces (Docker SDK)** | 不確定，疑似已收緊為付費 | 不確定 | 官方文件未提供以東京為主的區域資訊 | 不適用 | CPU basic 2 vCPU / 16GB（但可能僅適用一般 Space，非 Docker SDK） | 官方定價頁把「Host…Docker Spaces」的敘述放在 PRO 方案底下，暗示 Docker SDK 目前可能不再免費可用——且產品定位是 ML demo，非設計給長駐 API 後端 |
| **IBM Cloud Code Engine** | 官方確認有免費層，但確切額度數字本次無法逐字驗證 | 需要（建帳號需付款方式，未消費不收費） | **jp-tok（東京）officially 列為可用區域**——本次調查中少見「免費層 + 有東京節點」的組合 | 官方頁面未查到明確的 scale-to-zero / 冷啟動說明 | 二手來源引用「100,000 vCPU-seconds + 200,000 GB-seconds/月」，**未能用官方原文逐字驗證**，需標記為未完全驗證 | 免費層具體額度無法 100% 確認；IBM Cloud 帳號設定與介面相對複雜，對「MVP 快速測試」而言上手成本較高 |
| **Sevalla** | 否（Application Hosting 無免費層，只有靜態網站免費） | 未查（不符合「真的免費」門檻，未深入查證） | 未查 | 不適用 | 不適用 | 官方定價頁清楚寫明 Application Hosting 沒有免費方案；最低付費層 H1 為 $5/月、0.3 CPU/0.3GB RAM |

---

## 2. 逐平台詳情

### 2.1 Google Cloud Run

- **免費額度**（Always Free，官方頁面標示「Last updated 2026-08-26 UTC」）：每月 200 萬次請求、36 萬 GB-seconds 記憶體、18 萬 vCPU-seconds 運算時間、每月 1GB「來自北美」的出站資料傳輸免費。來源：[Free Google Cloud features and trial offer](https://docs.cloud.google.com/free/docs/free-cloud-features)
- **信用卡要求**：需要。官方原文：「During the sign up, you must provide a credit card or other payment method that is valid for the period of the Free Trial」，且「A Google Cloud billing account is required to access the Google Cloud Free Tier」。同上來源。
- **東京區域可用性**：`asia-northeast1`（Tokyo）確認可用，列在 Tier 1 定價區域清單中。來源：[Cloud Run locations](https://docs.cloud.google.com/run/docs/locations)
- **免費層是否為全球加總 / 逐區域計算**：官方文件**未明確標註**。同一份免費層文件中，其他服務（如 Compute Engine、Cloud Storage）有明確寫出「僅限特定美國區域」的字樣，但 Cloud Run 段落沒有這種地理限定語句，暗示可能是「依帳單帳戶全球加總」，但**這點官方文件沒有白紙黑字寫死**，本研究無法完全確認，建議視為存疑。
- **冷啟動 ~2.1s 是否可接受**：官方頁面沒有給出冷啟動時間的保證值或 SLA，本研究未能在官方文件中找到具體數字佐證。以 Cloud Run 的 request-based billing 與 scale-to-zero 架構推論，2.1 秒的冷啟動落在一般人手動測試 App 可接受的範圍內（多數使用者不會覺得「壞掉」），但這是基於架構推論，非官方保證。
- **對個位數測試用戶是否夠用**：200 萬請求／月、18 萬 vCPU-seconds／月，相較個位數用戶的測試流量是數量級的過剩，額度本身完全不是問題。
- **值得注意的坑**：免費出站流量額度明確寫「1 GB of outbound data transfer **from North America**」——如果服務部署在東京區域，一般網路出站流量可能完全不在這 1GB 免費額度內，會被計費（雖然對個位數用戶而言，實際流量金額應該是幾分錢等級，但嚴格說不是「zero cost」）。

### 2.2 Render

- **Sleep / 喚醒行為**：免費 web service 在「沒有收到任何入站流量 15 分鐘後」自動 spin down，下次請求進來時約 1 分鐘後恢復（原文：「restarts in approximately one minute」）。來源：[Deploy for Free – Render Docs](https://render.com/docs/free)
- **RAM / CPU**：免費 Web Service 為 0.1 CPU、512 MB RAM。來源：[Render Compute Plans](https://render.com/docs/compute-plans)
- **每月時數上限**：每個 workspace 每月 750 小時免費 instance 時數，用完後所有免費服務暫停到下個月；閒置中的服務不消耗這個額度。同上（render.com/docs/free）。
- **信用卡要求**：不需要。Render 官方部落格明確寫「No credit card is required」。來源：[Platforms with a real free tier for developers in 2026](https://render.com/articles/platforms-with-a-real-free-tier-for-developers-in-2026)（Render 自家部落格，屬第一方來源）
- **可用區域**：Oregon（US）、Ohio（US）、Virginia（US）、Frankfurt（EU）、Singapore（Asia）。沒有東京，Singapore 是唯一的亞洲節點。來源：[Regions – Render Docs](https://render.com/docs/regions)

### 2.3 Koyeb

- **區域清單**：官方核心區域現為 Frankfurt (FRA)、Washington D.C. (WAS)、Singapore (SIN)、**Tokyo, Japan (TYO)**、Paris (PAR)、AWS us-east-1，San Francisco (SFO) 為 preview 中。來源：[Regions | Koyeb](https://www.koyeb.com/docs/reference/regions)——**Tokyo 現在確實是 Koyeb 的核心區域之一**，這點相較先前的認知（Koyeb 完全沒有東京）有變化。
- **免費層規格與區域限制**：每個組織可獲得一個免費 web Service，512MB RAM、0.1 vCPU、2GB SSD；但**免費 instance 僅能選 Frankfurt 或 Washington D.C.**。免費 PostgreSQL 資料庫則限 5 小時 active time／月、1GB 儲存。來源：[Pricing FAQ | Koyeb](https://www.koyeb.com/docs/faqs/pricing)
- **信用卡要求**：需要，官方說明是為了「prevent fraud and abuse」，會做一次驗證性質的暫時性授權，不會直接收費。同上來源。
- **對本專案的意義**：Koyeb 平台整體「有沒有東京」這件事**變了**（從無到有），但因為免費層被鎖在 Frankfurt/Washington D.C.，實際上對本專案「免費 + 靠近東京」的需求**沒有幫助**——這是一個容易被誤讀成「利多」但其實無關的變化，值得特別提醒。

### 2.4 Railway

- **Trial（新帳號）**：一次性 $5 credit，30 天內用完或到期；不需要信用卡；資源上限 2 vCPU / 1GB RAM / 2 replicas / 7 天 log 保留。來源：[Pricing Plans | Railway Docs](https://docs.railway.com/pricing/plans)、[Railway Pricing](https://railway.com/pricing)
- **Free plan（試用期後的常駐方案）**：$0/月，附帶每月 $1 usage credit；資源上限 1 vCPU / 0.5GB RAM / 1 replica / 3 天 log 保留。同上兩來源交叉確認一致。
- **能否讓容器持續在線**：Free plan 的 $1/月 credit 額度極小，實務上不足以支撐一個 24/7 運行、300MB RAM 等級的 Java 服務（會很快把 $1 額度耗盡而被停用）；Trial 的 $5 credit 用完或滿 30 天同樣會回落到 Free plan 或必須升級付費。
- **區域**：US West (California)、US East (Virginia)、EU West (Amsterdam)、Southeast Asia (Singapore)；**沒有東京**。來源：[Regions | Railway Docs](https://docs.railway.com/reference/regions)

### 2.5 Oracle Cloud Always Free（Ampere A1）

- **目前 A1 配額**：1,500 OCPU-hours/月 + 9,000 GB-hours/月，換算成常態配置為 **2 OCPU / 12GB RAM**（可選一台 2 OCPU/12GB，或兩台各 1 OCPU）。來源：[Always Free Resources](https://docs.oracle.com/en-us/iaas/Content/FreeTier/freetier_topic-Always_Free_Resources.htm)——這個數字與先前「2026-06 起減半」的結論**一致（確認未變）**，即現行額度就是減半後的結果，頁面本身沒有列出明確的「最後更新」日期（頁尾只有 Copyright 2026 字樣）。
- **閒置回收政策**：連續 7 天內 CPU 使用率（p95）< 20%、網路使用率 < 20%、記憶體使用率 < 20%（僅 A1 shape）同時成立，即視為閒置並可能被回收。同上來源。
- **Tokyo 區域可用性**：Always Free 資源必須建立在租戶的 **home region**（帳號建立時選定、之後基本固定）。A1 運算「可以在任何 availability domain 建立，除了 South Korea North (Chuncheon)」，意即 `ap-tokyo-1` 理論上是合格的 home region 選項。但官方文件同時承認：「若收到 'out of host capacity' 錯誤，代表 home region 暫時沒有 Always Free shape 可用」——即官方自己承認熱門區域常態性缺貨，但**沒有針對 Tokyo 給出具體的容量保證或現況**，這點本研究**無法完全驗證**（僅能引用二手社群回報作為線索，不作為結論依據）。來源：[Always Free Resources reference](https://docs.oracle.com/en-us/iaas/Content/FreeTier/resourceref.htm)
- **信用卡要求**：本次未能重新抓取 `oracle.com/cloud/free/`（回傳 403），故**未能在本次研究中逐字重新確認**這頁的措辭；但 Oracle 帳號註冊流程一貫要求信用卡做身分驗證，這點延續先前認知，未發現改變的跡象。
- **性質差異**：這不是 managed 容器平台，是一台要自己裝 Docker、管作業系統更新、管理 systemd 的裸 VM，維運成本明顯高於其他選項。

### 2.6 Zeabur

- **免卡與 sleep 行為**：官方文件明確寫「No credit card required — just sign up and start deploying」，且服務「automatically sleep after a period of inactivity. They wake up on the next incoming request, which may cause a few seconds of cold-start latency」。來源：[Free Plan – Zeabur Docs](https://zeabur.com/docs/en-US/pricing/free-plan)
- **Docker 支援**：官方部署方式包含 Dockerfile 與自訂 Docker Image。同上來源。
- **RAM/CPU/區域**：官方 [Pricing](https://zeabur.com/pricing) 頁的方案比較表只列出非運算類限制（單檔上傳 50MB、可管理伺服器數 1 台、log 保留 48 小時、email/API keys/domains/webhooks 額度為 0），**完全沒有列出 free plan 的 RAM、CPU 或可部署區域數字**。這代表在不實際註冊測試的情況下，無法從官方公開頁面確認 Zeabur 免費層是否吃得下一個 300MB RAM 等級的 Java 服務，也無法確認是否有東京節點。

### 2.7 Fly.io

- **免費層**：官方定價文件沒有列出任何免費額度／免費層段落，且明確要求信用卡：「All organizations (except for Linked Organizations) require a credit card on file」。與先前「Fly.io 無免費方案」的結論**一致（確認未變）**。來源：[Pricing – Fly.io Docs](https://fly.io/docs/about/pricing/)
- **shared-cpu-1x 最低月費**：依 RAM 級距各自定價：256MB ≈ US$2.02/月、512MB ≈ US$3.32/月、**1GB ≈ US$5.92/月**（皆為持續運行的價格）。同上來源。
- **與先前結論比較**：先前結論是「shared-cpu-1x/1GB ≈ US$5.70/月」，本次確認為 ≈US$5.92/月，數字有小幅變動（漲了約 4%），但「無免費層、需要信用卡、最小持續運行機器約 5–6 美元/月」這個結論性判斷**本質上未變**。

### 2.8 其他在研究過程中發現、且官方明確宣稱有免費層可跑任意 Docker 容器的平台

**Northflank（Sandbox 方案）**：免費 Sandbox 提供 2 個服務、1 個資料庫、2 個 cron job，官方標榜「Always-on-compute – no sleeping」。支援「Buildpacks and custom Dockerfiles」。可用區域列出「US West, US Central, US East, EU West, Asia East」，但官方頁面未進一步說明 Asia East 具體是哪個城市（很可能是新加坡或香港，非東京，但無法從官方頁面確認）。信用卡要求方面，FAQ 只寫「when you enter a card we only verify the card. Your card is only charged at the end of the billing cycle」，並未明確講清楚免費方案在註冊當下是否強制要求輸入卡片。來源：[Northflank Pricing](https://northflank.com/pricing)。RAM/CPU 具體數字官方頁面同樣未公開。整體而言，Northflank 的免費層資訊揭露不夠完整，無法在不實測的情況下確認是否適合本專案。

**Hugging Face Spaces（Docker SDK）**：官方文件詳細說明 Docker SDK 的用法（自訂 Dockerfile、secrets/env vars 管理、非 root user 等），技術上完全支援跑任意 Docker 容器並對外提供 HTTP API。來源：[Docker Spaces – Hugging Face Docs](https://huggingface.co/docs/hub/en/spaces-sdks-docker)。免費 CPU basic 硬體為 2 vCPU / 16GB RAM / 無 GPU。來源：[Hugging Face Pricing](https://huggingface.co/pricing)。**但**同一份官方定價頁的措辭把「Host ZeroGPU, Gradio & Docker Spaces」列在 **PRO** 會員權益底下，暗示 Docker SDK 這個功能目前可能已經被收緊到需要付費方案才能實際「host」（跑起來對外提供服務），而非過去認知中「免費帳號也能開 Docker Space」。本研究**未能取得一句明確寫死「免費帳號不能建立/執行 Docker Space」的官方原文**，因此標記為「不確定，疑似已收緊」，且產品定位本身也偏向 ML demo 展示用途而非長駐 API 後端，不建議依賴。

**IBM Cloud Code Engine**：官方文件確認存在免費層：「Code Engine includes a free tier so that you can experiment with Code Engine before you commit」，頁面標示「last-updated: 2026-01-27」——距今約 8 個月，新鮮度不如 Google 那份（2026-08-26）。來源：[Pricing for Code Engine](https://cloud.ibm.com/docs/codeengine?topic=codeengine-pricing)。免費層的確切數字（vCPU-seconds、GB-seconds、requests）本次**無法從官方原文逐字擷取**（該頁的抓取結果被截斷，只確認有免費層存在，數字部分只能引用二手來源「約 100,000 vCPU-seconds + 200,000 GB-seconds/月」，**未經官方原文驗證，需視為未確認**）。**東京區域（jp-tok）官方明確列為可用區域**，屬於 Asia Pacific 三個區域之一（另兩個是 Sydney、Chennai）。來源：[Regions | Code Engine Docs](https://cloud.ibm.com/docs/codeengine?topic=codeengine-regions&locale=en)。信用卡要求：IBM Cloud 官方 FAQ 寫「Payment details are required up front, but you won't be charged until you consume a billable service; however, there will be a nominal hold placed on your card to verify its authenticity」。來源：[IBM Cloud Free Tier](https://www.ibm.com/cloud/free)。這是本次調查中唯一同時具備「官方確認有免費層」+「官方確認有東京區域」的平台，值得列為候選，但因確切額度數字無法完全驗證、且 IBM Cloud 平台介面與帳號體系相對複雜，實際導入前建議先用小規模實測驗證額度是否真的夠用。

**Sevalla**：官方定價頁清楚寫明 Application Hosting **沒有免費方案**，免費的只有靜態網站；最低付費層 Hobby (H1) 為 US$5/月、0.3 CPU / 0.3GB RAM。來源：[Application Hosting Pricing – Sevalla](https://sevalla.com/application-hosting/pricing/)。因不符合「真的免費」的門檻，未再深入查證信用卡要求與區域細節。

---

## 3. 先前結論（2026-08）逐項核對

| 先前結論 | 本次結果 | 判定 | 來源 |
|---|---|---|---|
| Fly.io 沒有免費方案；持續運行的 shared-cpu-1x/1GB 約 US$5.70/月，需要信用卡 | 確認沒有免費層、需要信用卡；shared-cpu-1x/1GB 現價約 US$5.92/月 | **本質未變**（價格有 ~4% 小幅上漲，結論性判斷不變） | [Fly.io Pricing Docs](https://fly.io/docs/about/pricing/) |
| Koyeb 沒有東京區域 | Koyeb 平台整體**已新增 Tokyo (TYO) 作為核心區域**，但**免費層仍被限制在 Frankfurt / Washington D.C.**，無法選東京 | **部分改變**——平台層級變了（有東京了），但對「免費層能不能用東京」這個實際問題結論不變（仍然不能） | [Koyeb Regions](https://www.koyeb.com/docs/reference/regions)、[Koyeb Pricing FAQ](https://www.koyeb.com/docs/faqs/pricing) |
| Render 免費層：512MB RAM，閒置 15 分鐘後 sleep | 確認 512MB RAM / 0.1 CPU；確認閒置 15 分鐘後 spin down，約 1 分鐘後喚醒 | **未變** | [Render Compute Plans](https://render.com/docs/compute-plans)、[Deploy for Free](https://render.com/docs/free) |
| Oracle Always Free：截至 2026-06，免費額度減半，且是 VM（非 managed 容器平台） | 確認現行額度為 2 OCPU / 12GB（即減半後的數字），確認仍是需要自行維運的 VM | **未變**（現況與先前結論一致） | [Always Free Resources](https://docs.oracle.com/en-us/iaas/Content/FreeTier/freetier_topic-Always_Free_Resources.htm) |

---

## 4. 建議排序（針對本專案的使用情境）

排序標準：真的免費、離東京近（降低打 Supabase DB 的延遲）、免費層資源真的跑得動一個 300MB RAM 等級的 JVM 服務、對「MVP 快速測試」而言上手與維運成本要低。

### 第 1 名：Google Cloud Run

免費額度（200 萬請求、18 萬 vCPU-seconds、36 萬 GiB-seconds／月）對個位數測試用戶而言是數量級過剩，且是本次調查中**唯一一個「部署區域本身就是東京」**的選項（`asia-northeast1`），不用忍受跨區延遲。scale-to-zero 架構下，2.1 秒的冷啟動對「夥伴偶爾打開手機測試」這種使用模式是可接受的。

代價：需要開通 Google Cloud 帳單帳戶並綁信用卡，設定上比單純的 PaaS（如 Render）多一層 GCP 專案/IAM 的學習曲線；另外免費出站流量的 1GB 額度官方文字寫明只涵蓋「北美」，部署在東京區域的一般網路出站流量可能不算在這免費額度內（雖然個位數用戶的實際流量金額應該是幾分錢等級，可接受但要注意帳單設定，避免意外扣款）。

### 第 2 名：IBM Cloud Code Engine

本次調查中唯一與 Cloud Run 並列「官方確認有免費層 + 官方確認有東京節點（`jp-tok`）」的平台，是值得認真考慮的備案。

代價：免費層確切額度數字本次未能完全用官方原文驗證（只確認「有免費層」，數字引用自二手來源，需要自己動手測試才能確認額度是否真的夠用）；IBM Cloud 主控台與帳號體系相對複雜，且該平台官方文件更新頻率似乎較低（定價頁面標示 2026-01-27，比 Google 那份舊了 8 個月），整體「MVP 快速上手」的摩擦力高於 Cloud Run。建議把它當作 Cloud Run 之外的第二選擇，或先花 10–15 分鐘實測額度是否符合預期，再決定要不要正式採用。

### 第 3 名：Render

全平台中最零摩擦的選項——不需要信用卡、部署流程簡單、Docker 原生支援。但沒有東京節點（最近是 Singapore），且免費層閒置 15 分鐘後 sleep，下次請求要等約 1 分鐘才會醒——如果夥伴是「偶爾心血來潮打開 App 測試」的使用模式，這個 1 分鐘等待會影響體驗（雖然對「反正是在測試」這件事本身不是不能接受）。

如果覺得 GCP／IBM Cloud 帳號設定的門檻太高，想要「五分鐘內部署好、完全不用管帳單設定」，Render 是最實際的退而求其次選項，代價是接受額外的跨區延遲與偶爾的冷啟動等待。

### 不建議：Oracle Always Free、Koyeb、Railway、Zeabur、Fly.io、Northflank、Hugging Face Spaces、Sevalla

- **Oracle Always Free**：雖然資源額度（2 OCPU/12GB）遠超所需，且是「常駐不閒置」的 VM，但（1）是要自己維運的裸機，不是 managed 容器平台，維運成本與本專案「MVP 快速測試」的目標不成比例；（2）`ap-tokyo-1` 的 Always Free 容量能否佔到位官方未給保證，是常見的已知痛點；（3）閒置 7 天會被強制回收，個位數測試用戶很容易有連續一週沒人用的空窗期，風險偏高。
- **Koyeb**：免費層物理上排除東京，本研究要解決的核心問題（靠近東京降低 DB 延遲）完全用不上，等於白繞一圈。
- **Railway**：Free plan 每月只有 $1 credit，撐不住一個持續運行的 Java 服務；Trial 只有 30 天，且無東京節點。
- **Zeabur**：官方頁面對免費層的 RAM/CPU/區域完全沒公開數字，屬於「不實測不知道」的黑盒，風險無法在動手前評估。
- **Fly.io**：確認完全沒有免費層，直接出局（不符合「genuinely free」的門檻）。
- **Northflank**：免費 Sandbox 雖然號稱不 sleep，但官方頁面同樣沒公開具體 RAM/CPU 數字與免費層可用區域是否含東京鄰近城市，資訊完整度不足以推薦。
- **Hugging Face Spaces**：官方定價頁措辭顯示 Docker SDK 的「host」權限疑似已收緊到 PRO 方案才能用，加上產品定位是 ML demo 而非長駐 API 後端，不建議依賴。
- **Sevalla**：官方明確表示 Application Hosting 沒有免費方案，直接排除。
