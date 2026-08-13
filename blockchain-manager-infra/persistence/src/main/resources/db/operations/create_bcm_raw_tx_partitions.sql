-- Deployment-only operation. Example:
-- psql "$DATABASE_URL" -v ON_ERROR_STOP=1 -v start_month=202608 -v month_count=3 \
--   -f blockchain-manager-infra/persistence/src/main/resources/db/operations/create_bcm_raw_tx_partitions.sql
SELECT set_config('bcm.raw_tx_partition_start_month', :'start_month', false);
SELECT set_config('bcm.raw_tx_partition_month_count', :'month_count', false);

DO $bcm_raw_tx_partition$
DECLARE
  start_month_text TEXT := current_setting('bcm.raw_tx_partition_start_month');
  month_count_text TEXT := current_setting('bcm.raw_tx_partition_month_count');
  start_month DATE;
  month_count INT;
  offset_month INT;
  partition_start DATE;
  partition_end DATE;
BEGIN
  IF start_month_text !~ '^[0-9]{6}$' THEN
    RAISE EXCEPTION 'start_month must be YYYYMM: %', start_month_text;
  END IF;
  start_month := to_date(start_month_text, 'YYYYMM');
  IF to_char(start_month, 'YYYYMM') <> start_month_text THEN
    RAISE EXCEPTION 'start_month must be a valid YYYYMM: %', start_month_text;
  END IF;

  IF month_count_text !~ '^[0-9]+$' THEN
    RAISE EXCEPTION 'month_count must be a positive integer: %', month_count_text;
  END IF;
  month_count := month_count_text::INT;
  IF month_count < 1 OR month_count > 24 THEN
    RAISE EXCEPTION 'month_count must be between 1 and 24: %', month_count;
  END IF;

  FOR offset_month IN 0..month_count - 1 LOOP
    partition_start := (start_month + make_interval(months => offset_month))::DATE;
    partition_end := (partition_start + INTERVAL '1 month')::DATE;
    EXECUTE format(
      'CREATE TABLE IF NOT EXISTS %I PARTITION OF bcm_raw_tx_l FOR VALUES FROM (%L) TO (%L)',
      'bcm_raw_tx_l_' || to_char(partition_start, 'YYYYMM'),
      to_char(partition_start, 'YYYYMMDD'),
      to_char(partition_end, 'YYYYMMDD')
    );
  END LOOP;
END
$bcm_raw_tx_partition$;
