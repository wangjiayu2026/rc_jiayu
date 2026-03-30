package com.notifyd.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.Map;

@JsonIgnoreProperties(ignoreUnknown = true)
public class HTTPRequest {
	private String method;
	private String url;
	private Map<String, String> headers;
	private String body;
	@JsonProperty("body_base64")
	private Boolean bodyBase64 = false;
	@JsonProperty("timeout_ms")
	private Integer timeoutMs;
	@JsonProperty("idempotency_key")
	private String idempotencyKey;

	public String getMethod() {
		return method;
	}

	public void setMethod(String method) {
		this.method = method;
	}

	public String getUrl() {
		return url;
	}

	public void setUrl(String url) {
		this.url = url;
	}

	public Map<String, String> getHeaders() {
		return headers;
	}

	public void setHeaders(Map<String, String> headers) {
		this.headers = headers;
	}

	public String getBody() {
		return body;
	}

	public void setBody(String body) {
		this.body = body;
	}

	public Boolean getBodyBase64() {
		return bodyBase64;
	}

	public void setBodyBase64(Boolean bodyBase64) {
		this.bodyBase64 = bodyBase64;
	}

	public Integer getTimeoutMs() {
		return timeoutMs;
	}

	public void setTimeoutMs(Integer timeoutMs) {
		this.timeoutMs = timeoutMs;
	}

	public String getIdempotencyKey() {
		return idempotencyKey;
	}

	public void setIdempotencyKey(String idempotencyKey) {
		this.idempotencyKey = idempotencyKey;
	}
}

