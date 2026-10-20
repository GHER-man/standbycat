# 推送到 GitHub（3 步）

本仓库已经初始化并完成首次提交（分支 `main`），只差一个远程地址。

## 第 1 步：在 GitHub 网页建一个空仓库

打开 https://github.com/new ，按下面填：

- **Repository name**：`standbycat`
- **Description**：静夜时钟 · 一盏安静、会呼吸的安卓床头钟（液态玻璃 / 倒计时 / 秒表）
- **Public** ✔
- **不要**勾选 "Add a README file"、"Add .gitignore"、"Choose a license"
  （本仓库里已经有了，勾了会产生冲突）

点 **Create repository**。

## 第 2 步：把本地仓库接上去并推送

打开终端（Windows Terminal / Git Bash 均可），逐行执行
（把 `<你的用户名>` 换成你的 GitHub 用户名）：

```bash
cd E:\workbuddy\xiaommi8\StandByCat

git remote add origin https://github.com/<你的用户名>/standbycat.git

git push -u origin main
```

第一次推送会要求登录：

- 如果弹出浏览器窗口 → 直接用 GitHub 账号授权即可；
- 如果只在终端里让你输入用户名 / 密码 → **密码处要填 Personal Access Token**
  （GitHub 早已不允许用账号密码推送）。在 https://github.com/settings/tokens
  生成一个带 `repo` 权限的 token，粘贴到密码处。

## 第 3 步（可选）：发一个 Release，把 APK 和宣传片挂上去

仓库里刻意没有提交 APK 与视频（避免仓库变大），它们适合放在 Releases：

1. 打开 `https://github.com/<你的用户名>/standbycat/releases/new`
2. Tag 填 `v7.3`，标题填 `静夜时钟 v7.3`
3. 说明里贴上更新点，附件拖入：
   - `静夜时钟-v7.3.apk`
   - `静夜时钟-宣传片-轻快版.mp4`、`静夜时钟-宣传片.mp4`
   - `静夜时钟-功能说明书-v7.3.html`
4. Publish release

## 仓库里有什么 / 没有什么

已提交：源码、`README.md`、`LICENSE`（MIT）、`demo/` 真机截图、宣传片合成脚本、功能说明书。

**没有**提交（见 `.gitignore`）：

- `standbycat.keystore` 与 `keystore.properties` —— 签名密钥和密码，绝不入库
- `*.apk` / `*.mp4` / 构建产物 / `.gradle` 缓存

别人 clone 下来、没有 `keystore.properties` 时会自动回退到 debug 签名，可以直接构建。

## 以后想再改代码并更新

```bash
cd E:\workbuddy\xiaommi8\StandByCat
git add -A
git commit -m "说明这次改了什么"
git push
```
