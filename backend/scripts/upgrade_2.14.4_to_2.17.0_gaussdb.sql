-- ============================================================================
-- Penpot 数据库升级脚本：2.14.4 → 2.17.0 (GaussDB/OpenGauss 兼容版)
-- ============================================================================
-- 生成时间: 2026-08-07
-- 源分支:   cmb-dev-2.14.4 → cmb-dev-2.17.0
-- 目标DB:   OpenGauss 5.0+
-- ============================================================================
-- 注意事项:
--   1. 执行前请备份数据库
--   2. 建议在事务中逐段执行，确认无误后再提交
--   3. 已对 PostgreSQL 特有语法做 GaussDB 适配
--   4. 所有 DDL 均使用 IF NOT EXISTS / IF EXISTS 保证幂等性
-- ============================================================================

BEGIN;

-- ============================================================================
-- 第1步: 索引优化 — audit_log 表
-- ============================================================================

-- 1.1 为遥测批量采集模式添加索引 (source, created_at)
CREATE INDEX IF NOT EXISTS audit_log__source__created_at__idx
    ON audit_log (source, created_at ASC);

-- 1.2 为 audit_log 添加条件索引 (归档/未归档分离)
CREATE INDEX IF NOT EXISTS audit_log__created_at__idx
    ON audit_log (created_at) WHERE archived_at IS NULL;

CREATE INDEX IF NOT EXISTS audit_log__archived_at__idx
    ON audit_log (archived_at) WHERE archived_at IS NOT NULL;


-- ============================================================================
-- 第2步: access_token 表 — 新增 type 列
-- ============================================================================

ALTER TABLE access_token
    ADD COLUMN IF NOT EXISTS type text;


-- ============================================================================
-- 第3步: 新建 upload_session 表 (分片上传会话)
-- ============================================================================

CREATE TABLE IF NOT EXISTS upload_session (
    id            uuid PRIMARY KEY,
    created_at    timestamp with time zone NOT NULL DEFAULT now(),
    profile_id    uuid NOT NULL REFERENCES profile(id) ON DELETE CASCADE,
    total_chunks  integer NOT NULL
);

CREATE INDEX IF NOT EXISTS upload_session__profile_id__idx
    ON upload_session (profile_id);

CREATE INDEX IF NOT EXISTS upload_session__created_at__idx
    ON upload_session (created_at);


-- ============================================================================
-- 第4步: team_invitation 表 — 支持组织级邀请
-- ============================================================================

ALTER TABLE team_invitation
    ADD COLUMN IF NOT EXISTS org_id uuid;

ALTER TABLE team_invitation
    ALTER COLUMN team_id DROP NOT NULL;

-- 确保 team_id 或 org_id 至少有一个不为空
DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint WHERE conname = 'team_invitation_team_or_org_not_null'
    ) THEN
        ALTER TABLE team_invitation
            ADD CONSTRAINT team_invitation_team_or_org_not_null
            CHECK (team_id IS NOT NULL OR org_id IS NOT NULL);
    END IF;
END $$;

-- org 级邀请唯一索引 (仅当 team_id 为空时生效)
CREATE UNIQUE INDEX IF NOT EXISTS team_invitation_org_unique
    ON team_invitation (org_id, email_to)
    WHERE team_id IS NULL;


-- ============================================================================
-- 第5步: team_font_variant 表 — 新增 variant_name 列
-- ============================================================================

ALTER TABLE team_font_variant
    ADD COLUMN IF NOT EXISTS variant_name text;


-- ============================================================================
-- 第6步: 新建 file_library_sync 表并迁移数据
-- ============================================================================

-- 检查 file_library_rel 表是否存在 (GaussDB 中可能名称不同)
DO $$
BEGIN
    IF EXISTS (
        SELECT 1 FROM information_schema.tables
        WHERE table_name = 'file_library_rel'
    ) THEN
        -- 创建新表
        CREATE TABLE IF NOT EXISTS file_library_sync (
            file_id         uuid NOT NULL,
            library_file_id uuid NOT NULL,
            synced_at       timestamp with time zone NOT NULL DEFAULT clock_timestamp(),
            PRIMARY KEY (file_id, library_file_id)
        );

        -- 从旧表迁移数据 (幂等: 已有数据不会重复插入)
        INSERT INTO file_library_sync (file_id, library_file_id, synced_at)
            SELECT file_id, library_file_id, synced_at
              FROM file_library_rel
              ON CONFLICT (file_id, library_file_id) DO NOTHING;
    END IF;
END $$;

-- 为旧表 synced_at 列添加废弃注释 (GaussDB 兼容语法)
COMMENT ON COLUMN file_library_rel.synced_at IS
    'DEPRECATED: will be removed in a future migration; kept temporarily for backward compatibility';


-- ============================================================================
-- 第7步: http_session_v2 表 — 新增 props JSONB 列
-- ============================================================================

ALTER TABLE http_session_v2
    ADD COLUMN IF NOT EXISTS props jsonb;


-- ============================================================================
-- 第8步: storage_object 表 — 为 upload-id 元数据添加索引
-- ============================================================================

CREATE INDEX IF NOT EXISTS storage_object__metadata_upload_id__idx
    ON storage_object ((metadata->>'~:upload-id'))
    WHERE deleted_at IS NULL;


-- ============================================================================
-- 第9步: file_tagged_object_thumbnail 表 — 添加 object_id 索引
-- ============================================================================

CREATE INDEX IF NOT EXISTS file_tagged_object_thumbnail__object_id__idx
    ON file_tagged_object_thumbnail (object_id);


-- ============================================================================
-- 验证查询 (执行后可取消注释以验证)
-- ============================================================================

-- 验证新建表
-- SELECT table_name FROM information_schema.tables
--   WHERE table_name IN ('upload_session', 'file_library_sync')
--   ORDER BY table_name;

-- 验证新增列
-- SELECT table_name, column_name FROM information_schema.columns
--   WHERE table_name IN ('access_token', 'team_invitation', 'team_font_variant',
--                         'http_session_v2')
--     AND column_name IN ('type', 'org_id', 'variant_name', 'props')
--   ORDER BY table_name, column_name;

-- 验证索引
-- SELECT indexname FROM pg_indexes
--   WHERE indexname IN (
--     'audit_log__source__created_at__idx',
--     'audit_log__created_at__idx',
--     'audit_log__archived_at__idx',
--     'team_invitation_org_unique',
--     'storage_object__metadata_upload_id__idx',
--     'file_tagged_object_thumbnail__object_id__idx'
--   )
--   ORDER BY indexname;

COMMIT;
