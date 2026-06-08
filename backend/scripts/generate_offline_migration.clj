;; 线下数据库迁移脚本生成工具
;; 用于 GaussDB 环境：将全部 SQL 迁移文件合并为单个可线下发布的脚本
;;
;; 用法：
;;   cd backend
;;   clojure -M scripts/generate_offline_migration.clj > offline_migration.sql
;;
;; 生成物使用 GaussDB 高权限用户执行，执行完毕后 Penpot 以
;; PENPOT_DISABLE_AUTO_MIGRATION=true 启动即可

(require '[clojure.java.io :as io]
         '[clojure.string :as str])

;; =======================================================================
;; GaussDB 兼容性转换
;; =======================================================================
;; 问题: CREATE TABLE 内联 PRIMARY KEY 和 ALTER TABLE ADD PRIMARY KEY 在
;; PostgreSQL 与 GaussDB 中自动生成的约束名可能不一致, 导致后续
;; DROP CONSTRAINT <猜测名> 找不到约束而失败, 进而 ADD 新主键时报
;; "cannot have multiple primary key"。
;;
;; 修复策略:
;;   1. 脚本头部创建 drop_primary_key() 函数, 通过 pg_constraint
;;      系统表动态查找并删除主键约束, 无需硬编码约束名
;;   2. 所有 DROP CONSTRAINT <xxx>_pkey 替换为 SELECT drop_primary_key('<table>')
;;   3. ADD PRIMARY KEY 不带 CONSTRAINT 的, 显式加上 CONSTRAINT <t>_pkey
;; =======================================================================

(def ^:private drop-pk-function
  "DO $$ 块, 通过系统表查找并删除指定表的主键约束。"
  (str "CREATE OR REPLACE FUNCTION drop_primary_key(tbl text) RETURNS void AS $$\n"
       "DECLARE\n"
       "    pk_name text;\n"
       "BEGIN\n"
       "    SELECT con.conname INTO pk_name\n"
       "      FROM pg_constraint con\n"
       "      JOIN pg_class rel ON rel.oid = con.conrelid\n"
       "     WHERE rel.relname = tbl\n"
       "       AND con.contype = 'p';\n"
       "    IF FOUND THEN\n"
       "        EXECUTE 'ALTER TABLE ' || tbl || ' DROP CONSTRAINT ' || quote_ident(pk_name);\n"
       "    END IF;\n"
       "END;\n"
       "$$ LANGUAGE plpgsql;\n"))

(defn- gaussdb-compatible
  "转换为 GaussDB 兼容语法。"
  [content]
  (-> content
      ;; 1. DROP CONSTRAINT <xxx>_pkey → SELECT drop_primary_key('<table>')
      (str/replace
       #"(?im)^(\s*ALTER\s+TABLE\s+(\S+)\s+)DROP\s+CONSTRAINT\s+\S+_pkey(\s*;?\s*)$"
       (fn [match]
         (let [table (second (re-find #"(?i)ALTER\s+TABLE\s+(\S+)" match))]
           (str "SELECT drop_primary_key('" table "');"))))
      ;; 2. ADD PRIMARY KEY (cols) 不带 CONSTRAINT → 显式命名
      (str/replace
       #"(?im)^(\s*ALTER\s+TABLE\s+(\S+)\s+)ADD\s+PRIMARY\s+KEY(\s*\([^)]+\)\s*;?\s*)$"
       (fn [match]
         (let [prefix (second (re-find #"(?im)^(\s*ALTER\s+TABLE\s+\S+\s+)" match))
               table  (second (re-find #"(?i)ALTER\s+TABLE\s+(\S+)" match))
               cols   (second (re-find #"(?i)PRIMARY\s+KEY(\s*\([^)]+\))" match))]
           (str prefix "ADD CONSTRAINT " table "_pkey PRIMARY KEY" cols ";"))))))

(def migrations-dir "src/app/migrations/sql")

;; 迁移名称列表 —— 与 app.migrations/migrations 完全一致
;; [SQL文件名 迁移注册名], nil 表示 Clojure 迁移 (仅登记)
(def migration-entries
  [["0001-add-extensions.sql"                    "0001-add-extensions"]
   ["0002-add-profile-tables.sql"                "0002-add-profile-tables"]
   ["0003-add-project-tables.sql"                "0003-add-project-tables"]
   ["0004-add-tasks-tables.sql"                  "0004-add-tasks-tables"]
   ["0005-add-libraries-tables.sql"              "0005-add-libraries-tables"]
   ["0006-add-presence-tables.sql"               "0006-add-presence-tables"]
   ["0007-drop-version-field-from-page-table.sql" "0007-drop-version-field-from-page-table"]
   ["0008-add-generic-token-table.sql"           "0008-add-generic-token-table"]
   ["0009-drop-profile-email-table.sql"          "0009-drop-profile-email-table"]
   ["0010-add-http-session-table.sql"            "0010-add-http-session-table"]
   ["0011-add-session-id-field-to-page-change-table.sql" "0011-add-session-id-field-to-page-change-table"]
   ["0012-make-libraries-linked-to-a-file.sql"   "0012-make-libraries-linked-to-a-file"]
   ["0013-mark-files-shareable.sql"              "0013-mark-files-shareable"]
   ["0014-refactor-media-storage.sql"            "0014-refactor-media-storage.sql"]
   ["0015-improve-tasks-tables.sql"              "0015-improve-tasks-tables"]
   ["0016-truncate-and-alter-tokens-table.sql"   "0016-truncate-and-alter-tokens-table"]
   ["0017-link-files-to-libraries.sql"           "0017-link-files-to-libraries"]
   ["0018-add-file-trimming-triggers.sql"        "0018-add-file-trimming-triggers"]
   ["0019-add-improved-scheduled-tasks.sql"      "0019-add-improved-scheduled-tasks"]
   ["0020-minor-fixes-to-media-object.sql"       "0020-minor-fixes-to-media-object"]
   ["0021-http-session-improvements.sql"         "0021-http-session-improvements"]
   ["0022-page-file-refactor.sql"                "0022-page-file-refactor"]
   ;; 0023 是 Clojure 迁移，仅登记 (将旧 page 表合并到 file.data)
   [nil                                           "0023-adapt-old-pages-and-files"]
   ["0024-mod-profile-table.sql"                 "0024-mod-profile-table"]
   ["0025-del-generic-tokens-table.sql"          "0025-del-generic-tokens-table"]
   ["0026-mod-file-library-rel-table-synced-date.sql" "0026-mod-file-library-rel-table-synced-date"]
   ["0027-mod-file-table-ignore-sync.sql"        "0027-mod-file-table-ignore-sync"]
   ["0028-add-team-project-profile-rel-table.sql" "0028-add-team-project-profile-rel-table"]
   ["0029-del-project-profile-rel-indexes.sql"   "0029-del-project-profile-rel-indexes"]
   ["0030-mod-file-table-add-missing-index.sql"  "0030-mod-file-table-add-missing-index"]
   ["0031-add-conversation-related-tables.sql"   "0031-add-conversation-related-tables"]
   ["0032-del-unused-tables.sql"                 "0032-del-unused-tables"]
   ["0033-mod-comment-thread-table.sql"          "0033-mod-comment-thread-table"]
   ["0034-mod-profile-table-add-props-field.sql" "0034-mod-profile-table-add-props-field"]
   ["0035-add-storage-tables.sql"                "0035-add-storage-tables"]
   ["0036-mod-storage-referenced-tables.sql"     "0036-mod-storage-referenced-tables"]
   ["0037-del-obsolete-triggers.sql"             "0037-del-obsolete-triggers"]
   ["0038-add-storage-on-delete-triggers.sql"    "0038-add-storage-on-delete-triggers"]
   ["0039-fix-some-on-delete-triggers.sql"       "0039-fix-some-on-delete-triggers"]
   ["0040-add-error-report-tables.sql"           "0040-add-error-report-tables"]
   ["0041-mod-pg-storage-options.sql"            "0041-mod-pg-storage-options"]
   ["0042-add-server-prop-table.sql"             "0042-add-server-prop-table"]
   ["0043-drop-old-tables-and-fields.sql"        "0043-drop-old-tables-and-fields"]
   ["0044-add-storage-refcount.sql"              "0044-add-storage-refcount"]
   ["0045-add-index-to-file-change-table.sql"    "0045-add-index-to-file-change-table"]
   ["0046-add-profile-complaint-table.sql"       "0046-add-profile-complaint-table"]
   ["0047-mod-file-change-table.sql"             "0047-mod-file-change-table"]
   ["0048-mod-storage-tables.sql"                "0048-mod-storage-tables"]
   ["0049-mod-http-session-table.sql"            "0049-mod-http-session-table"]
   ["0050-mod-server-prop-table.sql"             "0050-mod-server-prop-table"]
   ["0051-mod-file-library-rel-table.sql"        "0051-mod-file-library-rel-table"]
   ["0052-del-legacy-user-and-team.sql"          "0052-del-legacy-user-and-team"]
   ["0053-add-team-font-variant-table.sql"       "0053-add-team-font-variant-table"]
   ["0054-add-audit-log-table.sql"               "0054-add-audit-log-table"]
   ["0055-mod-file-media-object-table.sql"       "0055-mod-file-media-object-table"]
   ["0056-add-missing-index-on-deleted-at.sql"   "0056-add-missing-index-on-deleted-at"]
   ["0057-del-profile-on-delete-trigger.sql"     "0057-del-profile-on-delete-trigger"]
   ["0058-del-team-on-delete-trigger.sql"        "0058-del-team-on-delete-trigger"]
   ["0059-mod-audit-log-table.sql"               "0059-mod-audit-log-table"]
   ["0060-mod-file-change-table.sql"             "0060-mod-file-change-table"]
   ["0061-mod-file-table.sql"                    "0061-mod-file-table"]
   ["0062-fix-metadata-media.sql"                "0062-fix-metadata-media"]
   ["0063-add-share-link-table.sql"              "0063-add-share-link-table"]
   ["0064-mod-audit-log-table.sql"               "0064-mod-audit-log-table"]
   ["0065-add-trivial-spelling-fixes.sql"        "0065-add-trivial-spelling-fixes"]
   ["0066-add-frame-thumbnail-table.sql"         "0066-add-frame-thumbnail-table"]
   ["0067-add-team-invitation-table.sql"         "0067-add-team-invitation-table"]
   ["0068-mod-storage-object-table.sql"          "0068-mod-storage-object-table"]
   ["0069-add-file-thumbnail-table.sql"          "0069-add-file-thumbnail-table"]
   ["0070-del-frame-thumbnail-table.sql"         "0070-del-frame-thumbnail-table"]
   ["0071-add-file-object-thumbnail-table.sql"   "0071-add-file-object-thumbnail-table"]
   ["0072-mod-file-object-thumbnail-table.sql"   "0072-mod-file-object-thumbnail-table"]
   ["0073-mod-file-media-object-constraints.sql" "0073-mod-file-media-object-constraints"]
   ["0074-mod-file-library-rel-constraints.sql"  "0074-mod-file-library-rel-constraints"]
   ["0075-mod-share-link-table.sql"              "0075-mod-share-link-table"]
   ["0076-mod-storage-object-table.sql"          "0076-mod-storage-object-table"]
   ["0077-mod-comment-thread-table.sql"          "0077-mod-comment-thread-table"]
   ["0078-mod-file-media-object-table-drop-cascade.sql" "0078-mod-file-media-object-table-drop-cascade"]
   ["0079-mod-profile-table.sql"                 "0079-mod-profile-table"]
   ["0080-mod-index-names.sql"                   "0080-mod-index-names"]
   ["0081-add-deleted-at-index-to-file-table.sql" "0081-add-deleted-at-index-to-file-table"]
   ["0082-add-features-column-to-file-table.sql" "0082-add-features-column-to-file-table"]
   ["0083-add-file-data-fragment-table.sql"      "0083-add-file-data-fragment-table"]
   ["0084-add-features-column-to-file-change-table.sql" "0084-add-features-column-to-file-change-table"]
   ["0085-add-webhook-table.sql"                 "0085-add-webhook-table"]
   ["0086-add-webhook-delivery-table.sql"        "0086-add-webhook-delivery-table"]
   ["0087-mod-task-table.sql"                    "0087-mod-task-table"]
   ["0088-mod-team-profile-rel-table.sql"        "0088-mod-team-profile-rel-table"]
   ["0089-mod-project-profile-rel-table.sql"     "0089-mod-project-profile-rel-table"]
   ["0090-mod-http-session-table.sql"            "0090-mod-http-session-table"]
   ["0091-mod-team-project-profile-rel-table.sql" "0091-mod-team-project-profile-rel-table"]
   ["0092-mod-team-invitation-table.sql"         "0092-mod-team-invitation-table"]
   ["0093-del-file-share-tokens-table.sql"       "0093-del-file-share-tokens-table"]
   ["0094-del-profile-attr-table.sql"            "0094-del-profile-attr-table"]
   ["0095-del-storage-data-table.sql"            "0095-del-storage-data-table"]
   ["0096-del-storage-pending-table.sql"         "0096-del-storage-pending-table"]
   ["0098-add-quotes-table.sql"                  "0098-add-quotes-table"]
   ["0099-add-access-token-table.sql"            "0099-add-access-token-table"]
   ["0100-mod-profile-indexes.sql"               "0100-mod-profile-indexes"]
   ["0101-mod-server-error-report-table.sql"     "0101-mod-server-error-report-table"]
   ["0102-mod-access-token-table.sql"            "0102-mod-access-token-table"]
   ["0103-mod-file-object-thumbnail-table.sql"   "0103-mod-file-object-thumbnail-table"]
   ["0104-mod-file-thumbnail-table.sql"          "0104-mod-file-thumbnail-table"]
   ["0105-mod-file-change-table.sql"             "0105-mod-file-change-table"]
   ["0105-mod-server-error-report-table.sql"     "0105-mod-server-error-report-table"]
   ["0106-add-file-tagged-object-thumbnail-table.sql" "0106-add-file-tagged-object-thumbnail-table"]
   ["0106-mod-team-table.sql"                    "0106-mod-team-table"]
   ["0107-mod-file-tagged-object-thumbnail-table.sql" "0107-mod-file-tagged-object-thumbnail-table"]
   ["0107-add-deletion-protection-trigger-function.sql" "0107-add-deletion-protection-trigger-function"]
   ["0108-mod-file-thumbnail-table.sql"          "0108-mod-file-thumbnail-table"]
   ["0109-mod-file-tagged-object-thumbnail-table.sql" "0109-mod-file-tagged-object-thumbnail-table"]
   ["0110-mod-file-media-object-table.sql"       "0110-mod-file-media-object-table"]
   ["0111-mod-file-data-fragment-table.sql"      "0111-mod-file-data-fragment-table"]
   ["0112-mod-profile-table.sql"                 "0112-mod-profile-table"]
   ["0113-mod-team-font-variant-table.sql"       "0113-mod-team-font-variant-table"]
   ["0114-mod-team-table.sql"                    "0114-mod-team-table"]
   ["0115-mod-project-table.sql"                 "0115-mod-project-table"]
   ["0116-mod-file-table.sql"                    "0116-mod-file-table"]
   ["0117-mod-file-object-thumbnail-table.sql"   "0117-mod-file-object-thumbnail-table"]
   ["0118-mod-task-table.sql"                    "0118-mod-task-table"]
   ["0119-mod-file-table.sql"                    "0119-mod-file-table"]
   ["0120-mod-audit-log-table.sql"               "0120-mod-audit-log-table"]
   ["0121-mod-file-data-fragment-table.sql"      "0121-mod-file-data-fragment-table"]
   ["0122-mod-file-table.sql"                    "0122-mod-file-table"]
   ["0122-mod-file-data-fragment-table.sql"      "0122-mod-file-data-fragment-table"]
   ["0123-mod-file-change-table.sql"             "0123-mod-file-change-table"]
   ["0124-mod-profile-table.sql"                 "0124-mod-profile-table"]
   ["0125-mod-file-table.sql"                    "0125-mod-file-table"]
   ["0126-add-team-access-request-table.sql"     "0126-add-team-access-request-table"]
   ["0127-mod-storage-object-table.sql"          "0127-mod-storage-object-table"]
   ["0128-mod-task-table.sql"                    "0128-mod-task-table"]
   ["0129-mod-file-change-table.sql"             "0129-mod-file-change-table"]
   ["0130-mod-file-change-table.sql"             "0130-mod-file-change-table"]
   ["0131-mod-webhook-table.sql"                 "0131-mod-webhook-table"]
   ["0132-mod-file-change-table.sql"             "0132-mod-file-change-table"]
   ["0133-mod-file-table.sql"                    "0133-mod-file-table"]
   ["0134-mod-file-change-table.sql"             "0134-mod-file-change-table"]
   ["0135-mod-team-invitation-table.sql"         "0135-mod-team-invitation-table.sql"]
   ["0136-mod-comments-mentions.sql"             "0136-mod-comments-mentions.sql"]
   ["0137-add-file-migration-table.sql"          "0137-add-file-migration-table.sql"]
   ["0138-mod-file-data-fragment-table.sql"      "0138-mod-file-data-fragment-table.sql"]
   ["0139-mod-file-change-table.sql"             "0139-mod-file-change-table.sql"]
   ["0140-mod-file-change-table.sql"             "0140-mod-file-change-table.sql"]
   ["0140-add-locked-by-column-to-file-change-table.sql" "0140-add-locked-by-column-to-file-change-table"]
   ["0141-add-idx-to-file-library-rel.sql"       "0141-add-idx-to-file-library-rel"]
   ["0141-add-file-data-table.sql"               "0141-add-file-data-table.sql"]
   ["0142-add-sso-provider-table.sql"            "0142-add-sso-provider-table"]
   ["0143-add-http-session-v2-table.sql"         "0143-http-session-v2-table"]
   ["0144-mod-server-error-report-table.sql"     "0144-mod-server-error-report-table"]
   ;; 0145 是 Clojure 迁移，仅登记 (修复旧 plugin URL)
   [nil                                           "0145-fix-plugins-uri-on-profile"]])

(defn sql-file-path [filename]
  (io/file migrations-dir filename))

(defn generate-script []
  (println "-- =======================================================================")
  (println "-- Penpot 数据库离线迁移脚本 (GaussDB v505.2.1.SPC0800)")
  (println "-- 生成时间: " (java.time.LocalDateTime/now))
  (println "-- 总迁移数: " (count migration-entries))
  (println "-- ")
  (println "-- 执行方式:")
  (println "--   gsql -d penpot -U <高权限用户> -f offline_migration.sql")
  (println "-- ")
  (println "-- 执行完毕后,Penpot 启动需要设置:")
  (println "--   export PENPOT_DISABLE_AUTO_MIGRATION=true")
  (println "-- =======================================================================")
  (println "")
  (println "BEGIN;")
  (println "")

  ;; 1. 创建 migrations 追踪表
  (println "-- =======================================================================")
  (println "-- [SETUP] 创建迁移追踪表")
  (println "-- =======================================================================")
  (println "CREATE TABLE IF NOT EXISTS migrations (")
  (println "  module    TEXT,")
  (println "  step      TEXT,")
  (println "  created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,")
  (println "  UNIQUE(module, step)")
  (println ");")
  (println "")

  ;; 1.1 创建 GaussDB 兼容辅助函数
  (println "-- =======================================================================")
  (println "-- [SETUP] 创建主键删除辅助函数 (GaussDB 兼容)")
  (println "-- =======================================================================")
  (println drop-pk-function)
  (println "")

  ;; 2. 按顺序输出每个 SQL 迁移文件
  (doseq [[filename mig-name] migration-entries]
    (println (str "-- ---------------------------------------------------------------------"))
    (println (str "-- [" mig-name "]"))
    (if filename
      (let [f (sql-file-path filename)]
        (if (.exists f)
          (do
            (println (gaussdb-compatible (slurp f)))
            (println ""))
          (println (str "-- !! 错误: SQL文件不存在 -> " filename))))
      ;; CLJ 迁移，仅登记
      (do
        (println "-- (Clojure 迁移, 无需执行 SQL，仅登记)")
        (println "")))
    (println ""))

  ;; 3. 注册所有迁移记录
  (println "-- =======================================================================")
  (println "-- [注册迁移记录] 向 migrations 表插入所有已完成迁移")
  (println "-- =======================================================================")
  (println "")
  (doseq [[_filename mig-name] migration-entries]
    (println (str "INSERT INTO migrations (module, step) VALUES ('main', '" mig-name "');")))

  (println "")
  (println "COMMIT;")
  (println "")
  (println "-- =======================================================================")
  (println "-- 迁移脚本执行完毕")
  (println "-- 请验证: SELECT module, count(*) FROM migrations GROUP BY module;")
  (println "-- 预期结果: main | " (count migration-entries))
  (println "-- ======================================================================="))

(generate-script)
