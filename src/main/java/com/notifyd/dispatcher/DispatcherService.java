package com.notifyd.dispatcher;

import com.notifyd.config.NotifyProperties;
import com.notifyd.model.HTTPRequest;
import com.notifyd.model.Job;
import com.notifyd.model.JobStatus;
import com.notifyd.storage.JobRepository;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

@Service
public class DispatcherService {
	private static final Logger log = LoggerFactory.getLogger(DispatcherService.class);

	private final NotifyProperties props;
	private final JobRepository repo;
	private final HttpClient httpClient;
	private final Random rnd = new Random();

	private volatile ExecutorService executor;
	private final AtomicLong lastRequeueMs = new AtomicLong(0);

	public DispatcherService(NotifyProperties props, JobRepository repo) {
		this.props = props;
		this.repo = repo;
		this.httpClient = HttpClient.newBuilder()
			.connectTimeout(props.getHttpTimeout())
			.followRedirects(HttpClient.Redirect.NEVER)
			.build();
	}

	@EventListener(ApplicationReadyEvent.class)
	public void start() {
		int workers = Math.max(1, props.getWorkers());
		executor = Executors.newFixedThreadPool(workers);
		for (int i = 0; i < workers; i++) {
			int workerId = i + 1;
			executor.submit(() -> workerLoop(workerId));
		}
		log.info("dispatcher started, workers={}", workers);
	}

	private void workerLoop(int workerId) {
		while (!Thread.currentThread().isInterrupted()) {
			try {
				Instant now = Instant.now();
				maybeRequeueStuck(now);

				Job job = repo.claimDueJob(now);
				if (job == null) {
					Thread.sleep(props.getPollInterval().toMillis());
					continue;
				}
				if (job.getStatus() != JobStatus.DELIVERING) {
					continue;
				}

				deliverAndUpdate(job);
			} catch (InterruptedException ie) {
				Thread.currentThread().interrupt();
				return;
			} catch (Exception e) {
				log.warn("worker={} loop error: {}", workerId, e.toString());
				try {
					Thread.sleep(500);
				} catch (InterruptedException ie) {
					Thread.currentThread().interrupt();
					return;
				}
			}
		}
	}

	private void maybeRequeueStuck(Instant now) {
		long nowMs = now.toEpochMilli();
		long prev = lastRequeueMs.get();
		if (nowMs - prev < 5000) {
			return;
		}
		if (!lastRequeueMs.compareAndSet(prev, nowMs)) {
			return;
		}
		try {
			int n = repo.requeueStuckDelivering(now, props.getDeliveringStuckAfter().toMillis());
			if (n > 0) {
				log.warn("requeued stuck delivering jobs: count={}", n);
			}
		} catch (Exception e) {
			log.warn("requeue stuck delivering failed: {}", e.toString());
		}
	}

	private void deliverAndUpdate(Job job) {
		try {
			HTTPRequest req = job.getRequest();
			deliver(req);
			repo.markSucceeded(job.getId(), Instant.now());
		} catch (Exception deliverErr) {
			String lastErr = deliverErr.getMessage() == null ? deliverErr.toString() : deliverErr.getMessage();
			int attempts = job.getAttempts();
			Instant now = Instant.now();
			if (attempts >= props.getMaxAttempts()) {
				repo.markDead(job.getId(), lastErr, now);
				return;
			}

			Duration delay = computeBackoff(attempts);
			Instant next = now.plusMillis(delay.toMillis());
			repo.markRetry(job.getId(), next, lastErr, now);
		}
	}

	private Duration computeBackoff(int attempt) {
		int safeAttempt = Math.max(1, attempt);
		double exp = Math.pow(2.0, safeAttempt - 1);
		double baseMs = props.getBaseBackoff().toMillis();
		long delayMs = (long) Math.min(props.getMaxBackoff().toMillis(), baseMs * exp);

		double jitter = props.getJitterPercent();
		if (jitter <= 0) {
			return Duration.ofMillis(delayMs);
		}
		double delta = delayMs * jitter;
		double v = delayMs + (rnd.nextDouble() * 2.0 - 1.0) * delta;
		long j = (long) Math.max(0, v);
		return Duration.ofMillis(j);
	}

	private void deliver(HTTPRequest req) throws IOException, InterruptedException {
		if (req == null) {
			throw new IllegalArgumentException("request is null");
		}
		String url = req.getUrl();
		String method = req.getMethod() == null || req.getMethod().isBlank()
			? "POST"
			: req.getMethod().trim().toUpperCase();
		if (url == null || url.isBlank()) {
			throw new IllegalArgumentException("url is required");
		}

		URI uri;
		try {
			uri = new URI(url);
		} catch (URISyntaxException e) {
			throw new IllegalArgumentException("bad url", e);
		}
		String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase();
		if (!scheme.equals("http") && !scheme.equals("https")) {
			throw new IllegalArgumentException("unsupported url scheme: " + uri.getScheme());
		}

		byte[] bodyBytes = decodeBody(req);
		HttpRequest.BodyPublisher publisher = HttpRequest.BodyPublishers.ofByteArray(bodyBytes);
		if ("GET".equals(method) && (req.getBody() == null || bodyBytes.length == 0)) {
			publisher = HttpRequest.BodyPublishers.noBody();
		}

		HttpRequest.Builder builder = HttpRequest.newBuilder()
			.uri(uri)
			.method(method, publisher)
			.timeout(overrideTimeout(req))
			.header("Accept", "*/*");

		Map<String, String> headers = req.getHeaders();
		if (headers != null) {
			for (Map.Entry<String, String> e : headers.entrySet()) {
				if (e.getKey() == null || e.getKey().isBlank()) continue;
				if (e.getValue() == null) continue;
				builder.header(e.getKey(), e.getValue());
			}
		}

		HttpResponse<Void> resp = httpClient.send(builder.build(), HttpResponse.BodyHandlers.discarding());
		int code = resp.statusCode();
		if (code < 200 || code >= 300) {
			throw new IOException("non-2xx status: " + code);
		}
	}

	private Duration overrideTimeout(HTTPRequest req) {
		Integer timeoutMs = req.getTimeoutMs();
		if (timeoutMs == null || timeoutMs <= 0) {
			return props.getHttpTimeout();
		}
		return Duration.ofMillis(timeoutMs);
	}

	private byte[] decodeBody(HTTPRequest req) {
		String body = req.getBody();
		Boolean bodyBase64 = req.getBodyBase64();
		boolean isB64 = bodyBase64 != null && bodyBase64;
		if (body == null) return new byte[0];
		if (!isB64) return body.getBytes(StandardCharsets.UTF_8);
		return Base64.getDecoder().decode(body);
	}

	@PreDestroy
	public void stop() {
		if (executor == null) return;
		executor.shutdownNow();
		try {
			executor.awaitTermination(5, TimeUnit.SECONDS);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		}
	}
}

