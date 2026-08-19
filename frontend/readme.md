# 构建命令

如果你需要一次性完成完整生产构建（含 wasm 和静态资源），应该执行：
```bash
pnpm run build:wasm && pnpm run build:app:assets && pnpm run build:app
```

---

# uat-2.17.0 分支创建流程

## 1. 仓库远程配置

版本 tag（如 2.14.4、2.17.0）在官方仓库 penpot/penpot 上，自定义 uat 分支在 fork 仓库 koalaofpoint/penpot-frontend 上，因此需要配置两个远程：

| 远程 | 地址 | 说明 |
|------|------|------|
| `upstream` | `git@github.com:penpot/penpot.git` | 官方仓库（tag 来源） |
| `origin` | `git@github.com:koalaofpoint/penpot-frontend.git` | fork 仓库（uat 分支来源） |

```bash
# 如果还没有 upstream，先添加
git remote add upstream git@github.com:penpot/penpot.git
```

## 2. 目录结构对应关系

`upstream` 是 penpot 的全量 monorepo（含 backend、frontend、common 等），tag 2.17.0 指向的提交包含完整项目结构，其中 `frontend/` 是其子目录。本仓库只包含 `frontend/` 部分，通过 `git subtree split` 提取，去掉了 `frontend/` 前缀：

| 仓库 | 路径示例 | 说明 |
|------|----------|------|
| upstream (penpot/penpot) | `frontend/src/app/main.ts` | tag 2.17.0 = `bdce5817` |
| 本地 (penpot-frontend) | `src/app/main.ts` | base commit = `8a7aaaaa` |

本地仓库的 base commit 是一个孤儿提交（无父节点），通过以下方式生成：

```bash
# 从 monorepo 提取 frontend 子目录，形成独立分支
git subtree split --prefix=frontend -b frontend-only
```

`git subtree split --prefix=frontend` 会：
- 把 monorepo 中 `frontend/` 目录下的文件提取出来
- 将 `frontend/src/...` → `src/...`（去掉 `frontend/` 前缀）
- 重写提交历史，只保留 frontend 相关的变更

> **注意**：base commit（如 `8a7aaaaa`）已由维护者提前通过 subtree split 创建好并推送，后续操作直接从 base commit 开始即可，无需自己执行 subtree split。

## 3. 创建分支

tag 2.17.0 指向 upstream monorepo 提交（目录结构为 `frontend/src/...`），与本仓库目录结构（`src/...`）不同，**不能直接 checkout tag**。需要从已经过 subtree split 的 base commit 创建分支：

```bash
# 基于 base commit 创建分支（base commit 已通过 subtree split 提取好）
git checkout -b uat-2.17.0 <base-commit-hash>

# 可选：从 upstream 拉取指定 tag 作为参考
git fetch upstream tag 2.17.0
```

## 4. Cherry-pick uat 分支的提交

### 概念区分

| 概念 | 说明 | 示例 |
|------|------|------|
| **uat 基线 tag** | uat 分支从哪个版本切出来的 | `2.14.4`（uat 最早基于此版本修改） |
| **目标 tag** | 要升级到的新版本 | `2.17.0`（新分支要基于此版本） |

### 分析思路

uat 分支是从原始 penpot 某个基础版本 tag（uat 基线，如 2.14.4）切出来的，之后所有的提交都是自定义增量修改。通过对比 uat 与 uat 基线 tag 的差异，可以拿到所有需要迁移的提交：

```bash
# 以 uat 基线 tag 为参照，列出 uat 分支上所有增量提交
git log <uat-基线-tag>..uat --oneline
```

这个命令列出 uat 分支上有、但 uat 基线 tag 上没有的所有提交，即所有自定义改动。

### 操作步骤

```bash
# 1. 查看 uat 分支相对于 uat 基线 tag 的所有提交（从旧到新）
git log --oneline --reverse <uat-基线-tag>..uat

# 2. 切换到目标分支，按顺序 cherry-pick
git checkout uat-2.17.0
git cherry-pick <commit-hash-1>
git cherry-pick <commit-hash-2>
# ...
```

## 5. 补充缺失文件

如果 uat 分支有文件未包含在 cherry-pick 的提交中，可直接检出：

```bash
# 从 uat 分支检出特定文件
git checkout uat -- readme.md scripts/proxy-server.js
```

## 6. 推送分支

```bash
git push origin uat-2.17.0
```
