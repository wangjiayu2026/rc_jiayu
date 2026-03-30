package com.notifyd.storage;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.notifyd.config.NotifyProperties;
import com.notifyd.model.HTTPRequest;
import com.notifyd.model.Job;
import com.notifyd.model.JobStatus;
import com.notifyd.util.JobIdGenerator;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import javax.annotation.PostConstruct;
import javax.sql.DataSource;
import java.io.File;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.Optional;

@Repository
public class JobRepository {
	private final JdbcTemplate jdbc;
	private final DataSource dataSource;
	private final ObjectMapper objectMapper;
	private final NotifyProperties notifyProperties;
	private final JobIdGenerator jobIdGenerator;

	public JobRepository(JdbcTemplate jdbc,
	                     DataSource dataSource,
	                     ObjectMapper objectMapper,
	                     NotifyProperties notifyProperties,
	                     JobIdGenerator jobIdGenerator) {
		this.jdbc = jdbc;
		this.dataSource = dataSource;
		this.objectMapper = objectMapper;
		this.notifyProperties = notifyProperties;
		this.jobIdGenerator = jobIdGenerator;
	}

	@PostConstruct
	public void init() {
		ensureDbDir();
		ensureSchema();
	}

	private void ensureDbDir() {
		File f = new File(notifyProperties.getDbPath());
		File parent = f.getParentFile();
		if (parent != null) {
			parent.mkdirs();
		}
	}

	private void ensureSchema() {
		jdbc.execute("""
			CREATE TABLE IF NOT EXISTS jobs (
			  id TEXT PRIMARY KEY,
			  status TEXT NOT NULL,
			  attempts INTEGER NOT NULL,
			  next_attempt_at_ms INTEGER NOT NULL,
			  last_error TEXT NOT NULL,
			  request_json TEXT NOT NULL,
			  idempotency_key TEXT,
			  created_at_ms INTEGER NOT NULL,
			  updated_at_ms INTEGER NOT NULL
			);
			CREATE UNIQUE INDEX IF NOT EXISTS idx_jobs_idempotency_key
			  ON jobs(idempotency_key)
			  WHERE idempotency_key IS NOT NULL AND idempotency_key != '';
			CREATE INDEX IF NOT EXISTS idx_jobs_next_attempt ON jobs(status, next_attempt_at_ms);
		""");
	}

	public Job getJob(String jobId) {
		return jdbc.query(
			"""
			SELECT id, status, attempts, next_attempt_at_ms, last_error, request_json, created_at_ms, updated_at_ms, idempotency_key
			FROM jobs WHERE id = ?
			""",
			new Object[]{jobId},
			(rs, i) -> mapRow(rs)
		).stream().findFirst().orElse(null);
	}

	public Optional<Job> findByIdempotencyKey(String idempotencyKey) {
		if (idempotencyKey == null || idempotencyKey.isBlank()) return Optional.empty();
		Job j = jdbc.query(
			"""
			SELECT id, status, attempts, next_attempt_at_ms, last_error, request_json, created_at_ms, updated_at_ms, idempotency_key
			FROM jobs WHERE idempotency_key = ?
			""",
			new Object[]{idempotencyKey},
			(rs, i) -> mapRow(rs)
		).stream().findFirst().orElse(null);
		return Optional.ofNullable(j);
	}

	public Job enqueue(HTTPRequest req, String idempotencyKey) {
		Instant now = Instant.now();
		String jobId = jobIdGenerator.newJobId();

		String requestJson;
		try {
			requestJson = objectMapper.writeValueAsString(req);
		} catch (Exception e) {
			throw new IllegalArgumentException("invalid request json", e);
		}

		try {
			jdbc.update(
				"""
				INSERT INTO jobs(id, status, attempts, next_attempt_at_ms, last_error, request_json, idempotency_key, created_at_ms, updated_at_ms)
				VALUES(?, ?, ?, ?, ?, ?, ?, ?, ?)
				""",
				jobId,
				JobStatus.PENDING.name(),
				0,
				now.toEpochMilli(),
				"",
				requestJson,
				(idempotencyKey == null || idempotencyKey.isBlank()) ? null : idempotencyKey,
				now.toEpochMilli(),
				now.toEpochMilli()
			);
			Job job = new Job();
			job.setId(jobId);
			job.setStatus(JobStatus.PENDING);
			job.setAttempts(0);
			job.setNextAttemptAt(now);
			job.setLastError("");
			job.setRequest(req);
			job.setCreatedAt(now);
			job.setUpdatedAt(now);
			job.setIdempotencyKey(idempotencyKey);
			return job;
		} catch (Exception e) {
			if (idempotencyKey != null && !idempotencyKey.isBlank() && isUniqueConstraintViolation(e)) {
				return findByIdempotencyKey(idempotencyKey)
					.orElseThrow(() -> new IllegalStateException("idempotency conflict but job not found", e));
			}
			throw e;
		}
	}

	public Job claimDueJob(Instant now) {
		try (Connection conn = dataSource.getConnection()) {
			conn.setAutoCommit(false);
			conn.createStatement().execute("BEGIN IMMEDIATE");

			PreparedStatement ps = conn.prepareStatement("""
				SELECT id, status, attempts, next_attempt_at_ms, last_error, request_json, created_at_ms, updated_at_ms, idempotency_key
				FROM jobs
				WHERE status IN ('PENDING','RETRY') AND next_attempt_at_ms <= ?
				ORDER BY next_attempt_at_ms ASC
				LIMIT 1
			""");
			ps.setLong(1, now.toEpochMilli());

			ResultSet rs = ps.executeQuery();
			if (!rs.next()) {
				conn.rollback();
				return null;
			}

			Job current = mapRow(rs);

			int newAttempts = current.getAttempts() + 1;
			PreparedStatement upd = conn.prepareStatement("""
				UPDATE jobs
				SET status = 'DELIVERING', attempts = ?, updated_at_ms = ?
				WHERE id = ?
			""");
			upd.setInt(1, newAttempts);
			upd.setLong(2, now.toEpochMilli());
			upd.setString(3, current.getId());
			upd.executeUpdate();

			conn.commit();

			current.setStatus(JobStatus.DELIVERING);
			current.setAttempts(newAttempts);
			current.setUpdatedAt(now);
			return current;
		} catch (SQLException e) {
			throw new RuntimeException(e);
		}
	}

	public void markSucceeded(String jobId, Instant now) {
		jdbc.update("""
			UPDATE jobs SET status = 'SUCCEEDED', last_error = '', updated_at_ms = ?
			WHERE id = ?
		""", now.toEpochMilli(), jobId);
	}

	public void markRetry(String jobId, Instant nextAttemptAt, String lastError, Instant now) {
		jdbc.update("""
			UPDATE jobs
			SET status = 'RETRY', next_attempt_at_ms = ?, last_error = ?, updated_at_ms = ?
			WHERE id = ?
		""", nextAttemptAt.toEpochMilli(), safe(lastError), now.toEpochMilli(), jobId);
	}

	public void markDead(String jobId, String lastError, Instant now) {
		jdbc.update("""
			UPDATE jobs
			SET status = 'DEAD', last_error = ?, updated_at_ms = ?
			WHERE id = ?
		""", safe(lastError), now.toEpochMilli(), jobId);
	}

	public int requeueStuckDelivering(Instant now, long stuckAfterMs) {
		long cutoff = now.toEpochMilli() - Math.max(0, stuckAfterMs);
		return jdbc.update("""
			UPDATE jobs
			SET status = 'RETRY',
			    next_attempt_at_ms = ?,
			    last_error = CASE
			      WHEN last_error IS NULL OR last_error = '' THEN 'requeued: previous DELIVERING was stuck (likely crash)'
			      ELSE last_error
			    END,
			    updated_at_ms = ?
			WHERE status = 'DELIVERING' AND updated_at_ms <= ?
		""", now.toEpochMilli(), now.toEpochMilli(), cutoff);
	}

	private boolean isUniqueConstraintViolation(Exception e) {
		String s = String.valueOf(e);
		return s.contains("UNIQUE constraint failed") || s.toLowerCase().contains("constraint failed");
	}

	private String safe(String s) {
		return s == null ? "" : s;
	}

	private Job mapRow(ResultSet rs) throws SQLException {
		Job job = new Job();
		job.setId(rs.getString("id"));
		job.setStatus(JobStatus.valueOf(rs.getString("status")));
		job.setAttempts(rs.getInt("attempts"));
		job.setNextAttemptAt(Instant.ofEpochMilli(rs.getLong("next_attempt_at_ms")));
		job.setLastError(rs.getString("last_error"));
		job.setCreatedAt(Instant.ofEpochMilli(rs.getLong("created_at_ms")));
		job.setUpdatedAt(Instant.ofEpochMilli(rs.getLong("updated_at_ms")));
		job.setIdempotencyKey(rs.getString("idempotency_key"));

		String requestJson = rs.getString("request_json");
		try {
			job.setRequest(objectMapper.readValue(requestJson, HTTPRequest.class));
		} catch (Exception ex) {
			throw new SQLException("invalid request_json", ex);
		}
		return job;
	}
}

