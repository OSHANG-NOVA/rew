# ============================================================
#  Recipe Editor Workshop —— 一键上传到 GitHub
#
#  用法：
#    1. 在下面【配置区】填好 GitHub 用户名、仓库名、邮箱
#    2. 右键本文件 → 使用 PowerShell 运行
#    3. 按提示粘贴 GitHub Personal Access Token
#       （不是登录密码！生成地址： https://github.com/settings/tokens
#         选 "Fine-grained token" 或经典 token，权限勾选 repo 即可）
#    4. 脚本自动完成：构建 → 建远程仓库 → 提交 → 推送
#
#  说明：
#    - 仓库已存在时不会报错，直接推送
#    - Token 只在本次运行中使用，不会写入 .git/config 或任何文件
#    - 若构建失败会中止，不会推送半成品
# ============================================================

# ---------------------------- 配置区 ----------------------------
$GitHubUser  = "OSHANG-NOVA"
$RepoName    = "rew"
$GitHubEmail = "oshang4@outlook.com"
$RepoDesc    = "Visual GTCEu Modern recipe editor for modpack authors"
$CommitMsg   = "Release 0.1.0: GTCEu visual recipe editor"
# ---------------------------------------------------------------

$ErrorActionPreference = "Stop"
$RepoDir = $PSScriptRoot
# 本机 JVM 不信任证书，wrapper 拉不到发行包，因此直接用本机已解压的 Gradle。
$GradleBat = "C:\Users\Administrator\.gradle-dist\wrapper\dists\gradle-8.8-all\bo7hcaw14xmdcxz9k8j80wqn9\gradle-8.8\bin\gradle.bat"

Write-Host "=== Recipe Editor Workshop 上传脚本 ===" -ForegroundColor Cyan

if ($GitHubUser -eq "你的GitHub用户名") {
    Write-Host "请先编辑本脚本，填写配置区的 GitHub 用户名等信息。" -ForegroundColor Red
    Read-Host "按回车退出"
    exit 1
}

# ---------- 0. 收集 Token ----------
Write-Host "`n[0/5] 需要 GitHub Personal Access Token（权限勾 repo）" -ForegroundColor Yellow
Write-Host "      生成地址：https://github.com/settings/tokens" -ForegroundColor Gray
$secure = Read-Host "      请粘贴 Token（输入时不显示）" -AsSecureString
$Token = [Runtime.InteropServices.Marshal]::PtrToStringAuto(
    [Runtime.InteropServices.Marshal]::SecureStringToBSTR($secure))
if ([string]::IsNullOrWhiteSpace($Token)) {
    Write-Host "Token 为空，已中止。" -ForegroundColor Red
    Read-Host "按回车退出"
    exit 1
}

# 仅本次进程内关闭证书校验，不写全局配置。
$env:GIT_SSL_NO_VERIFY = "true"

# ---------- 1. 构建 ----------
Write-Host "`n[1/5] 构建中..." -ForegroundColor Yellow
if (Test-Path $GradleBat) {
    & $GradleBat -p $RepoDir build --offline --console=plain
} else {
    & "$RepoDir\gradlew.bat" -p $RepoDir build --console=plain
}
if ($LASTEXITCODE -ne 0) {
    Write-Host "构建失败，已中止（不会推送）。" -ForegroundColor Red
    Read-Host "按回车退出"
    exit 1
}
Write-Host "构建成功。" -ForegroundColor Green

# ---------- 2. 建远程仓库（已存在则跳过） ----------
Write-Host "`n[2/5] 检查远程仓库..." -ForegroundColor Yellow
$headers = @{
    Authorization = "token $Token"
    Accept        = "application/vnd.github+json"
    "User-Agent"  = "rew-upload-script"
}
$repoApi = "https://api.github.com/repos/$GitHubUser/$RepoName"
$exists = $false
try {
    Invoke-RestMethod -Uri $repoApi -Headers $headers -Method Get | Out-Null
    $exists = $true
    Write-Host "仓库已存在，直接推送。" -ForegroundColor Gray
} catch {
    $exists = $false
}

if (-not $exists) {
    Write-Host "仓库不存在，正在创建..." -ForegroundColor Gray
    $body = @{
        name        = $RepoName
        description = $RepoDesc
        private     = $false
        auto_init   = $false
    } | ConvertTo-Json
    try {
        Invoke-RestMethod -Uri "https://api.github.com/user/repos" -Headers $headers `
            -Method Post -Body $body -ContentType "application/json" | Out-Null
        Write-Host "仓库创建成功。" -ForegroundColor Green
    } catch {
        Write-Host "创建仓库失败：$_" -ForegroundColor Red
        Write-Host "常见原因：Token 没有 repo 权限，或仓库名已被占用。" -ForegroundColor Gray
        Read-Host "按回车退出"
        exit 1
    }
}

# ---------- 3. 提交 ----------
Write-Host "`n[3/5] 提交本地改动..." -ForegroundColor Yellow
git -C $RepoDir config user.name  $GitHubUser
git -C $RepoDir config user.email $GitHubEmail
git -C $RepoDir add -A
$staged = git -C $RepoDir diff --cached --name-only
if ($staged) {
    git -C $RepoDir commit -m $CommitMsg | Out-Null
    Write-Host "已提交 $((($staged | Measure-Object).Count)) 个文件。" -ForegroundColor Gray
} else {
    Write-Host "没有需要提交的改动。" -ForegroundColor Gray
}
git -C $RepoDir branch -M main

# ---------- 4. 设置远程地址（不带 Token，避免写入 .git/config） ----------
Write-Host "`n[4/5] 设置远程仓库..." -ForegroundColor Yellow
$remoteUrl = "https://github.com/$GitHubUser/$RepoName.git"
$existing = git -C $RepoDir remote
if ($existing -contains "origin") {
    git -C $RepoDir remote set-url origin $remoteUrl
} else {
    git -C $RepoDir remote add origin $remoteUrl
}

# ---------- 5. 推送（Token 只出现在这一条命令里） ----------
Write-Host "`n[5/5] 推送中..." -ForegroundColor Yellow
$pushUrl = "https://$($GitHubUser):$Token@github.com/$GitHubUser/$RepoName.git"
git -C $RepoDir -c http.sslVerify=false push -u $pushUrl main

if ($LASTEXITCODE -eq 0) {
    # 推送用的临时地址不留在配置里，改回干净地址。
    git -C $RepoDir remote set-url origin $remoteUrl
    Write-Host "`n上传成功！" -ForegroundColor Green
    Write-Host "仓库地址：https://github.com/$GitHubUser/$RepoName" -ForegroundColor Cyan
} else {
    Write-Host "`n推送失败。常见原因：" -ForegroundColor Red
    Write-Host "  1) Token 权限不足 —— 重新生成时勾选 repo"
    Write-Host "  2) Token 已过期或被撤销"
    Write-Host "  3) 网络问题 —— 可开启代理后重试"
}

Write-Host "`n按任意键退出..."
$null = $Host.UI.RawUI.ReadKey("NoEcho,IncludeKeyDown")
