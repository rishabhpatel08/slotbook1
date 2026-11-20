-- Fix 20 refund payment amounts that were calculated with truncation instead of
-- BigDecimal HALF_UP rounding.  The correct values match the payment gateway's
-- settlement report (docs/gateway-refunds-2026-10.csv).

-- 135.45 * 0.9 = 121.905 → truncated to 121.90, correct (HALF_UP) is 121.91
update payments set amount = 121.91 where id in (950001, 950007, 950013, 950019, 950025);

-- 333.33 * 0.9 = 299.997 → truncated to 299.99, correct (HALF_UP) is 300.00
update payments set amount = 300.00 where id in (950002, 950008, 950014, 950020, 950026);

-- 129.95 * 0.9 = 116.955 → truncated to 116.95, correct (HALF_UP) is 116.96
update payments set amount = 116.96 where id in (950005, 950011, 950017, 950023, 950029);

-- 135.45 * 0.5 = 67.725  → truncated to 67.72, correct (HALF_UP) is 67.73
update payments set amount = 67.73 where id in (950006, 950012, 950018, 950024, 950030);
