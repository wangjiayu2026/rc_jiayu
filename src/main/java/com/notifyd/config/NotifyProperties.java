package com.notifyd.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

@ConfigurationProperties(prefix = "notify")
public class NotifyProperties {
	private String dbPath = "./data/notify.db";
	private int httpPort = 8080;
	private int workers = 1;
	private int maxAttempts = 12;
	private Duration baseBackoff = Duration.ofMillis(500);
	private Duration maxBackoff = Duration.ofMinutes(10);
	private Duration httpTimeout = Duration.ofSeconds(5);
	private Duration pollInterval = Duration.ofMillis(250);
	private double jitterPercent = 0.2;
	private Duration deliveringStuckAfter = Duration.ofSeconds(30);

	public String getDbPath() {
		return dbPath;
	}

	public void setDbPath(String dbPath) {
		this.dbPath = dbPath;
	}

	public int getHttpPort() {
		return httpPort;
	}

	public void setHttpPort(int httpPort) {
		this.httpPort = httpPort;
	}

	public int getWorkers() {
		return workers;
	}

	public void setWorkers(int workers) {
		this.workers = workers;
	}

	public int getMaxAttempts() {
		return maxAttempts;
	}

	public void setMaxAttempts(int maxAttempts) {
		this.maxAttempts = maxAttempts;
	}

	public Duration getBaseBackoff() {
		return baseBackoff;
	}

	public void setBaseBackoff(Duration baseBackoff) {
		this.baseBackoff = baseBackoff;
	}

	public Duration getMaxBackoff() {
		return maxBackoff;
	}

	public void setMaxBackoff(Duration maxBackoff) {
		this.maxBackoff = maxBackoff;
	}

	public Duration getHttpTimeout() {
		return httpTimeout;
	}

	public void setHttpTimeout(Duration httpTimeout) {
		this.httpTimeout = httpTimeout;
	}

	public Duration getPollInterval() {
		return pollInterval;
	}

	public void setPollInterval(Duration pollInterval) {
		this.pollInterval = pollInterval;
	}

	public double getJitterPercent() {
		return jitterPercent;
	}

	public void setJitterPercent(double jitterPercent) {
		this.jitterPercent = jitterPercent;
	}

	public Duration getDeliveringStuckAfter() {
		return deliveringStuckAfter;
	}

	public void setDeliveringStuckAfter(Duration deliveringStuckAfter) {
		this.deliveringStuckAfter = deliveringStuckAfter;
	}
}

