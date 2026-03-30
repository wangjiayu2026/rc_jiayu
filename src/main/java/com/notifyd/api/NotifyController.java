package com.notifyd.api;

import com.notifyd.model.HTTPRequest;
import com.notifyd.model.Job;
import com.notifyd.storage.JobRepository;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.time.format.DateTimeFormatter;
import java.util.Map;

@RestController
public class NotifyController {
	private final JobRepository repo;

	public NotifyController(JobRepository repo) {
		this.repo = repo;
	}

	@PostMapping("/v1/notify")
	public ResponseEntity<Map<String, Object>> enqueue(@RequestBody(required = false) HTTPRequest req) {
		if (req == null) {
			return ResponseEntity.badRequest().body(Map.of("error", "missing request body"));
		}
		if (req.getUrl() == null || req.getUrl().isBlank()) {
			return ResponseEntity.badRequest().body(Map.of("error", "`url` is required"));
		}
		try {
			Job job = repo.enqueue(req, req.getIdempotencyKey());
			return ResponseEntity.status(HttpStatus.ACCEPTED).body(Map.of(
				"job_id", job.getId(),
				"status", job.getStatus().name()
			));
		} catch (IllegalArgumentException e) {
			return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
		} catch (Exception e) {
			return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(Map.of("error", "failed to enqueue"));
		}
	}

	@GetMapping("/v1/jobs/{jobId}")
	public ResponseEntity<Map<String, Object>> getJob(@PathVariable String jobId) {
		if (jobId == null || jobId.isBlank()) {
			return ResponseEntity.badRequest().body(Map.of("error", "missing job id"));
		}
		Job job = repo.getJob(jobId);
		if (job == null) {
			return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", "job not found"));
		}

		DateTimeFormatter fmt = DateTimeFormatter.ISO_INSTANT;
		return ResponseEntity.ok(Map.of(
			"id", job.getId(),
			"status", job.getStatus().name(),
			"attempts", job.getAttempts(),
			"next_attempt_at", job.getNextAttemptAt() == null ? null : fmt.format(job.getNextAttemptAt()),
			"last_error", job.getLastError(),
			"created_at", job.getCreatedAt() == null ? null : fmt.format(job.getCreatedAt()),
			"updated_at", job.getUpdatedAt() == null ? null : fmt.format(job.getUpdatedAt()),
			"request", job.getRequest()
		));
	}
}

