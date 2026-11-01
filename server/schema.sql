-- e or i 云同步：Cloudflare D1 建表脚本
-- 用法：
--   wrangler d1 execute eori-db --remote --file=./schema.sql
--   （本地预览用 --local）

-- 账户
CREATE TABLE IF NOT EXISTS accounts (
  uid          TEXT PRIMARY KEY,
  name         TEXT    NOT NULL,
  type         TEXT    NOT NULL,
  balance      REAL,          -- 余额，单位：元，保留两位小数
  createdAt    INTEGER NOT NULL,
  updatedAt    INTEGER NOT NULL
);

-- 账单
CREATE TABLE IF NOT EXISTS transactions (
  uid          TEXT PRIMARY KEY,
  amount       REAL    NOT NULL,   -- 金额，单位：元，两位小数
  type         TEXT    NOT NULL,
  accountUid   TEXT,
  accountName  TEXT,
  note         TEXT    NOT NULL DEFAULT '',
  rawInput     TEXT    NOT NULL DEFAULT '',
  occurredAt   INTEGER NOT NULL,
  createdAt    INTEGER NOT NULL,
  updatedAt    INTEGER NOT NULL,
  deleted      INTEGER NOT NULL DEFAULT 0
);

-- 增量拉取靠这几列
CREATE INDEX IF NOT EXISTS idx_tx_updated  ON transactions (updatedAt);
CREATE INDEX IF NOT EXISTS idx_acc_updated ON accounts (updatedAt);
CREATE INDEX IF NOT EXISTS idx_tx_account  ON transactions (accountUid);
