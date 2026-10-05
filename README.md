# localReceiver

**[English](#english)** | **[繁體中文](#繁體中文)**

---

## English

Local file transfer between your devices — no cloud, no accounts, no
internet required.

localReceiver is a small **Java server app** (Windows/macOS/Linux) plus a
**browser web app (PWA)** (PC/Mac/iPhone/iPad/Android). Start the
server on one computer; every other device on your network opens a web
page that can **send files to** and **download files from** that
computer — with chunked, parallel, resumable transfers that survive
dropped connections and page reloads.

### Quick start

1. Get the jar from the
   [latest release](https://github.com/SotongDJ/localReceiver/releases/latest)
   (needs Java 25 or newer), or build it yourself — see
   [Development](#development). Release jars carry the version in
   their name: `localreceiver_v{x}r{y}n{z}.jar` is version v{x}.{y}.{z}
   (e.g. `localreceiver_v0r17n0.jar` is v0.17.0).
2. Copy the jar into the folder you want to share, e.g.
   `N:\the\target\path` or `/the/target/path`. That folder becomes the
   file area: received files land there, and files placed there are
   downloadable.
3. Run the jar (double-click, or `java -jar localreceiver.jar`). Press
   **Start** in the window — or run `java -jar localreceiver.jar --headless`
   on machines without a display (`--port <n>` to override the port,
   default 4646; `--http` to disable TLS — the shared folder is then
   read-only and each paired device can only write into its own `safe`
   folder, stored at `~/localReceiver/<device>/safe`; `--browse` to
   enable directory listing pages).
4. **Pair each device**: click **Pair device…** in the server window
   and let the other device scan the QR code (or open the site and
   type the one-time code), then give the device a name — lower-case
   letters, digits and `_`, unique on this server. Until a device
   pairs, it sees nothing. A newly paired device starts **read-only**
   in the shared folder; allow writing per device in the window (or
   start with `--new-devices-read-write` to pair them read+write), and
   narrow it by changing its folder or ticking per-subfolder
   Read/Write. Its own data (bin, upload
   staging, HTTP `safe` folder) lives apart in
   `~/localReceiver/<device id>/`, never in the shared folder.
   Devices can be renamed at any time; that changes the name only,
   never a folder (headless mode prints pairing codes on the console
   and takes the commands `devices` and `remove <name>` to list and
   revoke devices; `--open` disables pairing entirely).
5. On the paired device, open the URL the window shows — click it on
   the server to open it in your own browser, or scan the QR code it
   displays (`https://<your-ip>:4646/`). Accept the one-time
   certificate warning, or better: tap **Install the localReceiver
   certificate** in the page footer (also at `/ca.crt`) and trust it —
   localReceiver generates its own per-user certificate authority, so
   installing that one certificate removes the warnings on every
   future session and lets the page install as an app. **Before you
   trust it, check that the SHA-256 fingerprint in the device's own
   certificate details matches the one in the localReceiver window**
   (the install page says where each platform shows it; never trust a
   fingerprint shown by a web page) — if they differ, something on the
   network is impersonating the server. Nothing leaves your
   network. Drop files onto the page to send; tap listed files to
   download.

### Features

- **Private by default — one session per device**: nothing is visible
  without pairing via a one-time QR/text code and a user-chosen
  device name. Each paired device gets a working folder (the shared
  folder at first) plus per-device Read/Write/Browse switches and a
  per-subfolder Read/Write checklist in the server window, and a
  device folder `~/localReceiver/<device id>/` for its own data. Both
  are independent of its name: renaming a device never moves files, and
  several devices can be pointed at one shared folder without a rename
  disturbing it.
- **Chunked, parallel, resumable transfers** in both directions:
  interrupted uploads and downloads continue from the chunks already
  done — across page reloads (staged in the browser's private storage,
  OPFS) and across server restarts (staged in
  `~/localReceiver/<device>/.uploads/`, outside the shared folder). Any transfer can be cancelled with full clean-up.
- **Folder uploads**: pick a folder or drag one onto the page; the
  directory structure is recreated on the server. Folders in the list
  can be downloaded as a zip archive.
- **A file list styled like a GitHub repository page** — folders
  first, icons, sizes, and "n minutes ago" ages — both in the app and
  in the optional directory listing pages.
- **Directory browsing** (off by default): flip the toggle and opening
  a `/files/` URL directly in any browser shows a browsable listing
  page; whitelisted types (images, PDF, plain text, and HTML — sandboxed, with
  scripts and forms disabled) display inline.
- **A full file manager in the browser**: create folders, move
  (auto-renaming on conflict), rename, and delete — deletions go to a
  per-device **recycle bin** with restore, never straight to
  destruction. View modes from list and details (exact timestamps) to
  icon grids and a media gallery with image/video thumbnails. Access
  is controlled per device (Write switch + subfolder checklist).
- **Uploads you confirm**: chosen files wait in a queue — with media
  thumbnails, sizes, and original timestamps — while you pick the
  target folder from a drop-down; nothing transfers until you press
  Upload, and uploaded files keep their original modification time.
- **Share by QR**: the server window shows a QR code of its URL, and
  the page itself can display one (`/qr.png`) to pass to other
  devices.
- **Choose what you share**: change the shared folder from the window,
  set it with `--root <dir>`, or just drop the jar into the folder;
  optional start-on-launch.
- **A themed server window**: the GUI is styled by an embedded,
  dependency-free subset of the JaCross design system — Fluent 2 on
  Windows, Material 3 elsewhere, following the OS dark mode — with
  Noto Sans TC embedded so Traditional Chinese renders correctly on
  every system.
- The web app is **vanilla JS/CSS with browser-native APIs only** — no
  external libraries, no CDN, fully served by the jar itself.

### Notes

- Configuration is stored in `~/.config/localreceiver/`, never in the shared
  folder.
- On plain HTTP, browsers only grant OPFS/service-worker features to
  `localhost`; other devices still transfer files, but without
  reload-resume or app install. See `AGENT.md` for details.
- On Linux, localReceiver follows GNOME's display and text scaling
  automatically. If the window still looks too small or too large,
  set the scale yourself:
  `java -Dsun.java2d.uiScale=1.5 -jar localreceiver_v0r20n0.jar`
  (any factor works — 1.25, 1.5, 2).

### Development

The toolchain is managed by [pixi](https://pixi.sh) — it provides the
JDK, nothing else needs installing:

```sh
pixi run build   # produces dist/localreceiver.jar
pixi run run     # build + run
sh tests/browser/run.sh   # end-to-end browser tests (needs Node + Playwright)
```

- [`AGENT.md`](AGENT.md) — the maintainer guide: architecture, hard
  constraints, transfer protocols, workflow rules. Read it first,
  human or LLM.
- [`CHANGELOG`](CHANGELOG) — the changelog, and the source of truth
  for the notes on the
  [releases page](https://github.com/SotongDJ/localReceiver/releases);
  releases are cut by the manually triggered `release` GitHub Actions
  workflow.

### Licence

localReceiver is free software, released under the
[GNU General Public License v3.0](LICENSE).

---

## 繁體中文

在你的裝置之間直接傳輸檔案——不經雲端、不用帳號、不需網際網路。

localReceiver 由一個小巧的 **Java 伺服器程式**（Windows/macOS/Linux）加上
**瀏覽器網頁應用程式（PWA）**（PC/Mac/iPhone/iPad/Android）組成。
在一台電腦上啟動伺服器，網路上的其他裝置開啟網頁後即可
**傳送檔案至**該電腦、也可**從該電腦下載檔案**——採用分塊、並行、
可續傳的傳輸方式，即使連線中斷或頁面重新載入也能接續完成。

### 快速開始

1. 從[最新發行版本](https://github.com/SotongDJ/localReceiver/releases/latest)
   下載 jar 檔（需要 Java 25 或更新版本），或自行建置——見
   [開發](#開發)。發行版 jar 檔名帶有版本：`localreceiver_v{x}r{y}n{z}.jar`
   即 v{x}.{y}.{z} 版（例如 `localreceiver_v0r17n0.jar` 是 v0.17.0）。
2. 將 jar 檔複製到你想分享的資料夾，例如
   `N:\the\target\path` 或 `/the/target/path`。該資料夾即為檔案區：
   收到的檔案會存放於此，放在裡面的檔案也能被下載。
3. 執行 jar（雙擊，或 `java -jar localreceiver.jar`），在視窗中按下
   **Start**——沒有螢幕的機器可執行
   `java -jar localreceiver.jar --headless`（`--port <n>` 可更改連接埠，
   預設 4646；`--http` 停用 TLS——此時共享資料夾為唯讀，每台已配對
   裝置只能寫入自己的 `safe` 資料夾（存放於 `~/localReceiver/<裝置>/safe`）；
   `--browse` 可啟用目錄列表頁面）。
4. **為每台裝置配對**：在伺服器視窗點選 **Pair device…**，讓另一台
   裝置掃描 QR Code（或開啟網站後輸入一次性配對碼），並為裝置取一個
   名稱——小寫英文字母、數字與 `_`，且在此伺服器上不得重複。裝置在
   配對前看不到任何內容。新配對的裝置一開始以**唯讀**方式位於共享
   資料夾；可在視窗中為個別裝置允許寫入（或以
   `--new-devices-read-write` 啟動，讓新裝置配對後即可讀寫），並可
   更換其資料夾，或勾選／取消每個子資料夾的讀寫權限來縮小範圍。
   裝置自己的資料（資源回收筒、上傳暫存、HTTP `safe` 資料夾）另存於
   `~/localReceiver/<裝置 id>/`，不會放在共享資料夾中。裝置隨時可重新
   命名，這只會變更名稱，不會變更任何資料夾（無圖形介面模式會在主控台印出配對碼，並可輸入 `devices` 與
   `remove <名稱>` 指令列出及撤銷裝置；`--open` 可完全停用配對）。
5. 在配對好的裝置上開啟視窗顯示的網址——在伺服器端點選網址即可用
   本機預設瀏覽器開啟，或掃描視窗顯示的 QR Code
   （`https://<你的-ip>:4646/`）。接受一次性的憑證警告，或更好的做法：
   點選頁尾的 **Install the localReceiver certificate**（亦可於 `/ca.crt`
   取得）並信任它——localReceiver 會產生專屬於使用者的憑證授權單位（CA），
   安裝這一張憑證即可移除日後所有工作階段的警告，並允許將頁面安裝為
   應用程式。**信任前請先核對裝置本身憑證詳細資料中的 SHA-256 憑證指紋與
   localReceiver 視窗中顯示的是否相同**（安裝頁說明各平台在哪裡顯示；
   切勿相信網頁上顯示的指紋）——若不相同，表示網路上可能有人正在冒充
   這台伺服器。一切都不會離開你的網路。將檔案拖放到頁面即可傳送；
   點選清單中的檔案即可下載。

### 功能特色

- **預設保密——每台裝置一個工作階段**：未配對前看不到任何內容，
  配對透過一次性的 QR Code 或文字配對碼完成，並由使用者為裝置命名。
  每台已配對的裝置有一個工作資料夾（一開始是共享資料夾），可在伺服器
  視窗中個別設定讀取／寫入／瀏覽權限，還有逐一子資料夾的讀寫勾選清單；
  另有裝置資料夾 `~/localReceiver/<裝置 id>/` 存放它自己的資料。兩者
  都與裝置名稱互相獨立：重新命名裝置不會搬動任何檔案，也可讓多台
  裝置共用同一個資料夾，重新命名不會影響它。
- **分塊、並行、可續傳的雙向傳輸**：中斷的上傳與下載會從已完成的
  分塊接續——頁面重新載入（暫存於瀏覽器私有儲存空間 OPFS）與
  伺服器重啟（暫存於共享資料夾以外的 `~/localReceiver/<裝置>/.uploads/`）皆可續傳。
  任何傳輸都能取消並完整清除暫存。
- **資料夾上傳**：選取或拖放整個資料夾，伺服器會重建其目錄結構。
  清單中的資料夾可打包為 zip 壓縮檔下載。
- **仿 GitHub 儲存庫頁面風格的檔案清單**——資料夾優先、圖示、
  檔案大小與「幾分鐘前」的相對時間——應用程式內與選用的目錄列表
  頁面皆採此風格。
- **目錄瀏覽**（預設關閉）：開啟開關後，直接在任何瀏覽器開啟
  `/files/` 網址即可看到可瀏覽的目錄列表頁面；白名單類型
  （圖片、PDF、純文字，以及停用腳本與表單的沙箱化 HTML）會直接在瀏覽器中顯示。
- **瀏覽器中的完整檔案管理員**：建立資料夾、移動（同名時自動改名）、
  重新命名與刪除——刪除會進入各裝置專屬的**資源回收筒**，可隨時
  還原，絕不直接銷毀。檢視模式從清單、詳細資料（含確切時間戳記）
  到大小圖示格狀與媒體藝廊（圖片／影片縮圖）。存取權限依裝置控制
  （寫入開關＋子資料夾勾選清單）。
- **上傳前先確認**：選好的檔案會先進入上傳佇列——顯示媒體縮圖、
  大小與原始時間戳記——並可從下拉選單挑選目標資料夾；按下 Upload
  才開始傳輸，上傳後的檔案保留原始修改時間。
- **QR Code 分享**：伺服器視窗會顯示網址的 QR Code，頁面本身也能
  顯示（`/qr.png`）以便傳給其他裝置。
- **自由選擇分享內容**：可從視窗更換分享的資料夾、以 `--root <dir>`
  指定，或直接把 jar 放進目標資料夾；並可選擇啟動程式時自動開啟
  伺服器。
- **主題化的伺服器視窗**：圖形介面由內嵌、零相依的 JaCross 設計
  系統子集繪製——Windows 上為 Fluent 2、其他平台為 Material 3，
  並跟隨作業系統的深色模式——同時內嵌 Noto Sans TC 字型，讓正體
  中文在任何系統上都能正確顯示。
- 網頁應用程式**只使用原生 JS/CSS 與瀏覽器內建 API**——沒有外部
  函式庫、沒有 CDN，全部由 jar 本身提供。

### 注意事項

- 設定儲存於 `~/.config/localreceiver/`，絕不會存放在分享的資料夾內。
- 在純 HTTP 之下，瀏覽器只對 `localhost` 開放 OPFS 與 service
  worker 功能；其他裝置仍可傳輸檔案，但無法在重新載入後續傳、
  也無法安裝為應用程式。詳見 `AGENT.md`。
- 在 Linux 上，localReceiver 會自動跟隨 GNOME 的顯示與文字縮放設定。
  若視窗仍太小或太大，可自行指定縮放比例：
  `java -Dsun.java2d.uiScale=1.5 -jar localreceiver_v0r20n0.jar`
  （任何倍率皆可——1.25、1.5、2）。

### 開發

工具鏈由 [pixi](https://pixi.sh) 管理——它會提供 JDK，
不需另外安裝任何東西：

```sh
pixi run build   # 產生 dist/localreceiver.jar
pixi run run     # 建置並執行
sh tests/browser/run.sh   # 端對端瀏覽器測試（需要 Node 與 Playwright）
```

- [`AGENT.md`](AGENT.md)——維護者指南：架構、硬性限制、傳輸協定、
  工作流程規範。無論是人或 LLM，請先閱讀。
- [`CHANGELOG`](CHANGELOG)——變更紀錄，同時是
  [發行頁面](https://github.com/SotongDJ/localReceiver/releases)說明文字的
  唯一來源；發行版本由手動觸發的 `release` GitHub Actions 工作流程
  建立。

### 授權條款

localReceiver 是自由軟體，依
[GNU 通用公眾授權條款第三版（GPL-3.0）](LICENSE)發布。
