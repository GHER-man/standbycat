# 仓库维护手册

仓库已上线：**https://github.com/GHER-man/standbycat**
安装包与宣传片在 Releases：**https://github.com/GHER-man/standbycat/releases/tag/v7.3**

## 改了代码之后怎么更新

在 `E:\workbuddy\xiaommi8\StandByCat` 下：

```bash
git add -A
git commit -m "说明这次改了什么"
git push
```

> 本机的 GitHub 登录已经完成（账号 `GHER-man`），推送不会再要求输入密码。
> 命令行工具在 `E:\workbuddy\xiaommi8\.tools\bin\gh.exe`。

## 发新版本（Release）

1. 改 `app/build.gradle` 里的 `versionCode` / `versionName`（例如 51 / 7.4）；
2. 构建并复制成 ASCII 文件名：

   ```bash
   gradle assembleRelease
   cp app/build/outputs/apk/release/app-release.apk 静夜时钟-v7.4.apk
   ```

3. 发布（把版本号替换掉）：

   ```bash
   gh release create v7.4 --title "静夜时钟 v7.4" --notes "这次改了什么" 静夜时钟-v7.4.apk
   ```

## 仓库里有什么 / 没有什么

已提交：源码、`README.md`、`LICENSE`(MIT)、`demo/` 真机截图、宣传片合成脚本、功能说明书。

**没有**提交（见 `.gitignore`）：

- `standbycat.keystore` 与 `keystore.properties` —— 签名密钥和密码，绝不入库
- `*.apk` / `*.mp4` / 构建产物 / `.gradle` 缓存

别人 clone 下来、没有 `keystore.properties` 时会自动回退到 debug 签名，可以直接构建。

## 换一台电脑继续开发

```bash
git clone https://github.com/GHER-man/standbycat.git
cd standbycat
cp keystore.properties.example keystore.properties   # 填入自己的签名信息（可选）
```

## 撤销这台电脑的 GitHub 授权

GitHub → Settings → Applications → 找到 **GitHub CLI** → Revoke。
撤销后本机 `gh` 与 `git push` 都会失效，需要重新 `gh auth login`。
