CREATE OR REPLACE VIEW metrics_reputation_dow_week AS
SELECT DATE_TRUNC('week', received)::DATE AS week, EXTRACT(ISODOW FROM received) AS dow, COUNT(1) AS count
FROM reputation_log
GROUP BY week, dow
ORDER BY week DESC, dow ASC;

CREATE OR REPLACE VIEW metrics_reputation_dow_month AS
SELECT DATE_TRUNC('month', week)::DATE AS month, dow, AVG(count)::INTEGER AS count
FROM metrics_reputation_dow_week
GROUP BY month, dow
ORDER BY month DESC;

CREATE OR REPLACE VIEW metrics_reputation_dow_year AS
SELECT DATE_TRUNC('year', week)::DATE AS year, dow, AVG(w.count)::INTEGER AS count
FROM metrics_reputation_dow_week w
GROUP BY year, dow
ORDER BY year DESC;

CREATE OR REPLACE VIEW metrics_reputation_week AS
SELECT DATE_TRUNC('week', received)::DATE AS week, COUNT(1) AS count
FROM reputation_log
GROUP BY week
ORDER BY week DESC;

CREATE OR REPLACE VIEW metrics_reputation_month AS
SELECT DATE_TRUNC('month', received)::DATE AS month, COUNT(1) AS count
FROM reputation_log
GROUP BY month
ORDER BY month DESC;

CREATE OR REPLACE VIEW metrics_reputation_total_week AS
SELECT week, SUM(count) OVER (ORDER BY week) AS count
FROM metrics_reputation_week m
ORDER BY week DESC;

CREATE OR REPLACE VIEW metrics_reputation_total_month AS
SELECT month, SUM(count) OVER (ORDER BY month) AS count
FROM metrics_reputation_month m
ORDER BY month DESC;

CREATE OR REPLACE VIEW metrics_messages_total_day AS
SELECT day, SUM(count) OVER (ORDER BY day) AS count
FROM metrics_message_analyzed_day m
ORDER BY day DESC;

CREATE OR REPLACE VIEW metrics_messages_total_week AS
SELECT week, SUM(count) OVER (ORDER BY week) AS count
FROM metrics_message_analyzed_week m
ORDER BY week DESC;

CREATE OR REPLACE VIEW metrics_messages_total_month AS
SELECT month, SUM(count) OVER (ORDER BY month) AS count
FROM metrics_message_analyzed_month m
ORDER BY month DESC;

CREATE OR REPLACE VIEW metrics_commands_executed_week AS
SELECT DATE_TRUNC('week', day)::DATE AS week, SUM(count) AS count
FROM metrics_commands
GROUP BY week
ORDER BY week DESC;

CREATE OR REPLACE VIEW metrics_commands_executed_month AS
SELECT DATE_TRUNC('month', day)::DATE AS month, SUM(count) AS count
FROM metrics_commands
GROUP BY month
ORDER BY month DESC;
