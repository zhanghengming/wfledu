-- Dedicated new phase1 databases only. One-time synthetic fixture, not an application migration.
-- Abort if tables exist; do not replay into an existing customer database.
SET time_zone = '+00:00';

USE de_phase1_ga;
CREATE TABLE biz_school_dim (
    school_code VARCHAR(64) NOT NULL COMMENT 'School global business code',
    school_name VARCHAR(128) NOT NULL COMMENT 'Display name, not an authorization key',
    mapping_revision BIGINT NOT NULL COMMENT 'Trusted school mapping revision',
    status VARCHAR(16) NOT NULL DEFAULT 'DISABLED' COMMENT 'ACTIVE or DISABLED',
    source_updated_at DATETIME(6) NOT NULL COMMENT 'Source revision time in UTC',
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) COMMENT 'Created at UTC',
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) COMMENT 'Explicitly updated UTC timestamp',
    PRIMARY KEY (school_code),
    KEY ix_biz_school_status (status, school_code),
    CONSTRAINT ck_biz_school_code CHECK (CHAR_LENGTH(school_code) > 0 AND school_code = TRIM(school_code)),
    CONSTRAINT ck_biz_school_revision CHECK (mapping_revision > 0),
    CONSTRAINT ck_biz_school_status CHECK (status IN ('ACTIVE', 'DISABLED'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_bin COMMENT='Group school statistical dimension';

CREATE TABLE biz_finance_monthly_fact (
    id BIGINT NOT NULL COMMENT 'Positive fact ID; fixed IDs are synthetic fixtures only',
    school_code VARCHAR(64) NOT NULL COMMENT 'School global business code',
    stat_month DATE NOT NULL COMMENT 'First day of statistical month',
    category_code VARCHAR(64) NOT NULL COMMENT 'Approved financial classification',
    currency_code CHAR(3) NOT NULL COMMENT 'Uppercase currency code',
    income_amount DECIMAL(20,4) NOT NULL COMMENT 'Synthetic monthly income',
    expense_amount DECIMAL(20,4) NOT NULL COMMENT 'Synthetic monthly expense',
    source_ref VARCHAR(128) NOT NULL COMMENT 'Synthetic batch reference',
    source_version BIGINT NOT NULL COMMENT 'Positive source revision',
    quality_status VARCHAR(16) NOT NULL DEFAULT 'PENDING' COMMENT 'PENDING, VALID or REJECTED',
    source_updated_at DATETIME(6) NOT NULL COMMENT 'Source time in UTC',
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) COMMENT 'Created at UTC',
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) COMMENT 'Explicitly updated UTC timestamp',
    PRIMARY KEY (id),
    UNIQUE KEY uk_biz_finance_grain (school_code, stat_month, category_code, currency_code),
    CONSTRAINT fk_biz_finance_school FOREIGN KEY (school_code) REFERENCES biz_school_dim (school_code) ON DELETE RESTRICT,
    CONSTRAINT ck_biz_finance_id CHECK (id > 0),
    CONSTRAINT ck_biz_finance_version CHECK (source_version > 0),
    CONSTRAINT ck_biz_finance_month CHECK (DAYOFMONTH(stat_month) = 1),
    CONSTRAINT ck_biz_finance_quality CHECK (quality_status IN ('PENDING', 'VALID', 'REJECTED')),
    CONSTRAINT ck_biz_finance_currency CHECK (REGEXP_LIKE(currency_code, '^[A-Z]{3}$', 'c'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_bin COMMENT='Group school monthly financial statistics';

CREATE TABLE biz_teaching_monthly_fact (
    id BIGINT NOT NULL COMMENT 'Positive fact ID; fixed IDs are synthetic fixtures only',
    school_code VARCHAR(64) NOT NULL COMMENT 'School global business code',
    stat_month DATE NOT NULL COMMENT 'First day of statistical month',
    student_count BIGINT NOT NULL COMMENT 'Synthetic nonnegative student count',
    teacher_count BIGINT NOT NULL COMMENT 'Synthetic nonnegative teacher count',
    class_count BIGINT NOT NULL COMMENT 'Synthetic nonnegative class count',
    source_ref VARCHAR(128) NOT NULL COMMENT 'Synthetic batch reference',
    source_version BIGINT NOT NULL COMMENT 'Positive source revision',
    quality_status VARCHAR(16) NOT NULL DEFAULT 'PENDING' COMMENT 'PENDING, VALID or REJECTED',
    source_updated_at DATETIME(6) NOT NULL COMMENT 'Source time in UTC',
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) COMMENT 'Created at UTC',
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) COMMENT 'Explicitly updated UTC timestamp',
    PRIMARY KEY (id),
    UNIQUE KEY uk_biz_teaching_grain (school_code, stat_month),
    CONSTRAINT fk_biz_teaching_school FOREIGN KEY (school_code) REFERENCES biz_school_dim (school_code) ON DELETE RESTRICT,
    CONSTRAINT ck_biz_teaching_id CHECK (id > 0),
    CONSTRAINT ck_biz_teaching_version CHECK (source_version > 0),
    CONSTRAINT ck_biz_teaching_month CHECK (DAYOFMONTH(stat_month) = 1),
    CONSTRAINT ck_biz_teaching_counts CHECK (student_count >= 0 AND teacher_count >= 0 AND class_count >= 0),
    CONSTRAINT ck_biz_teaching_quality CHECK (quality_status IN ('PENDING', 'VALID', 'REJECTED'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_bin COMMENT='Group school monthly teaching statistics';

INSERT INTO biz_school_dim (school_code, school_name, mapping_revision, status, source_updated_at) VALUES
('G-A-S1', 'A School 1 (synthetic)', 1, 'ACTIVE', '2026-10-01 00:00:00.000000'),
('G-A-S2', 'A School 2 (synthetic)', 1, 'ACTIVE', '2026-10-01 00:00:00.000000');
INSERT INTO biz_finance_monthly_fact
(id, school_code, stat_month, category_code, currency_code, income_amount, expense_amount, source_ref, source_version, quality_status, source_updated_at) VALUES
(100001, 'G-A-S1', '2026-10-01', 'SYNTHETIC', 'CNY', 1001.0000, 101.0000, 'phase1-synthetic-v1', 1, 'VALID', '2026-10-01 00:00:00.000000'),
(100002, 'G-A-S2', '2026-10-01', 'SYNTHETIC', 'CNY', 1002.0000, 202.0000, 'phase1-synthetic-v1', 1, 'VALID', '2026-10-01 00:00:00.000000');
INSERT INTO biz_teaching_monthly_fact
(id, school_code, stat_month, student_count, teacher_count, class_count, source_ref, source_version, quality_status, source_updated_at) VALUES
(100011, 'G-A-S1', '2026-10-01', 1111, 11, 6, 'phase1-synthetic-v1', 1, 'VALID', '2026-10-01 00:00:00.000000'),
(100012, 'G-A-S2', '2026-10-01', 1222, 22, 12, 'phase1-synthetic-v1', 1, 'VALID', '2026-10-01 00:00:00.000000');

USE de_phase1_gb;
CREATE TABLE biz_school_dim (
    school_code VARCHAR(64) NOT NULL COMMENT 'School global business code',
    school_name VARCHAR(128) NOT NULL COMMENT 'Display name, not an authorization key',
    mapping_revision BIGINT NOT NULL COMMENT 'Trusted school mapping revision',
    status VARCHAR(16) NOT NULL DEFAULT 'DISABLED' COMMENT 'ACTIVE or DISABLED',
    source_updated_at DATETIME(6) NOT NULL COMMENT 'Source revision time in UTC',
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) COMMENT 'Created at UTC',
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) COMMENT 'Explicitly updated UTC timestamp',
    PRIMARY KEY (school_code),
    KEY ix_biz_school_status (status, school_code),
    CONSTRAINT ck_biz_school_code CHECK (CHAR_LENGTH(school_code) > 0 AND school_code = TRIM(school_code)),
    CONSTRAINT ck_biz_school_revision CHECK (mapping_revision > 0),
    CONSTRAINT ck_biz_school_status CHECK (status IN ('ACTIVE', 'DISABLED'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_bin COMMENT='Group school statistical dimension';

CREATE TABLE biz_finance_monthly_fact (
    id BIGINT NOT NULL COMMENT 'Positive fact ID; fixed IDs are synthetic fixtures only',
    school_code VARCHAR(64) NOT NULL COMMENT 'School global business code',
    stat_month DATE NOT NULL COMMENT 'First day of statistical month',
    category_code VARCHAR(64) NOT NULL COMMENT 'Approved financial classification',
    currency_code CHAR(3) NOT NULL COMMENT 'Uppercase currency code',
    income_amount DECIMAL(20,4) NOT NULL COMMENT 'Synthetic monthly income',
    expense_amount DECIMAL(20,4) NOT NULL COMMENT 'Synthetic monthly expense',
    source_ref VARCHAR(128) NOT NULL COMMENT 'Synthetic batch reference',
    source_version BIGINT NOT NULL COMMENT 'Positive source revision',
    quality_status VARCHAR(16) NOT NULL DEFAULT 'PENDING' COMMENT 'PENDING, VALID or REJECTED',
    source_updated_at DATETIME(6) NOT NULL COMMENT 'Source time in UTC',
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) COMMENT 'Created at UTC',
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) COMMENT 'Explicitly updated UTC timestamp',
    PRIMARY KEY (id),
    UNIQUE KEY uk_biz_finance_grain (school_code, stat_month, category_code, currency_code),
    CONSTRAINT fk_biz_finance_school FOREIGN KEY (school_code) REFERENCES biz_school_dim (school_code) ON DELETE RESTRICT,
    CONSTRAINT ck_biz_finance_id CHECK (id > 0),
    CONSTRAINT ck_biz_finance_version CHECK (source_version > 0),
    CONSTRAINT ck_biz_finance_month CHECK (DAYOFMONTH(stat_month) = 1),
    CONSTRAINT ck_biz_finance_quality CHECK (quality_status IN ('PENDING', 'VALID', 'REJECTED')),
    CONSTRAINT ck_biz_finance_currency CHECK (REGEXP_LIKE(currency_code, '^[A-Z]{3}$', 'c'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_bin COMMENT='Group school monthly financial statistics';

CREATE TABLE biz_teaching_monthly_fact (
    id BIGINT NOT NULL COMMENT 'Positive fact ID; fixed IDs are synthetic fixtures only',
    school_code VARCHAR(64) NOT NULL COMMENT 'School global business code',
    stat_month DATE NOT NULL COMMENT 'First day of statistical month',
    student_count BIGINT NOT NULL COMMENT 'Synthetic nonnegative student count',
    teacher_count BIGINT NOT NULL COMMENT 'Synthetic nonnegative teacher count',
    class_count BIGINT NOT NULL COMMENT 'Synthetic nonnegative class count',
    source_ref VARCHAR(128) NOT NULL COMMENT 'Synthetic batch reference',
    source_version BIGINT NOT NULL COMMENT 'Positive source revision',
    quality_status VARCHAR(16) NOT NULL DEFAULT 'PENDING' COMMENT 'PENDING, VALID or REJECTED',
    source_updated_at DATETIME(6) NOT NULL COMMENT 'Source time in UTC',
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) COMMENT 'Created at UTC',
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) COMMENT 'Explicitly updated UTC timestamp',
    PRIMARY KEY (id),
    UNIQUE KEY uk_biz_teaching_grain (school_code, stat_month),
    CONSTRAINT fk_biz_teaching_school FOREIGN KEY (school_code) REFERENCES biz_school_dim (school_code) ON DELETE RESTRICT,
    CONSTRAINT ck_biz_teaching_id CHECK (id > 0),
    CONSTRAINT ck_biz_teaching_version CHECK (source_version > 0),
    CONSTRAINT ck_biz_teaching_month CHECK (DAYOFMONTH(stat_month) = 1),
    CONSTRAINT ck_biz_teaching_counts CHECK (student_count >= 0 AND teacher_count >= 0 AND class_count >= 0),
    CONSTRAINT ck_biz_teaching_quality CHECK (quality_status IN ('PENDING', 'VALID', 'REJECTED'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_bin COMMENT='Group school monthly teaching statistics';

INSERT INTO biz_school_dim (school_code, school_name, mapping_revision, status, source_updated_at) VALUES
('G-B-S1', 'B School 1 (synthetic)', 1, 'ACTIVE', '2026-10-01 00:00:00.000000'),
('G-B-S2', 'B School 2 (synthetic)', 1, 'ACTIVE', '2026-10-01 00:00:00.000000');
INSERT INTO biz_finance_monthly_fact
(id, school_code, stat_month, category_code, currency_code, income_amount, expense_amount, source_ref, source_version, quality_status, source_updated_at) VALUES
(900001, 'G-B-S1', '2026-10-01', 'SYNTHETIC', 'CNY', 9001.0000, 101.0000, 'phase1-synthetic-v1', 1, 'VALID', '2026-10-01 00:00:00.000000'),
(900002, 'G-B-S2', '2026-10-01', 'SYNTHETIC', 'CNY', 9002.0000, 202.0000, 'phase1-synthetic-v1', 1, 'VALID', '2026-10-01 00:00:00.000000');
INSERT INTO biz_teaching_monthly_fact
(id, school_code, stat_month, student_count, teacher_count, class_count, source_ref, source_version, quality_status, source_updated_at) VALUES
(900011, 'G-B-S1', '2026-10-01', 9111, 11, 6, 'phase1-synthetic-v1', 1, 'VALID', '2026-10-01 00:00:00.000000'),
(900012, 'G-B-S2', '2026-10-01', 9222, 22, 12, 'phase1-synthetic-v1', 1, 'VALID', '2026-10-01 00:00:00.000000');
