package com.notifyd.util;

import org.springframework.stereotype.Component;

import java.util.UUID;

@Component
public class JobIdGenerator {
	public String newJobId() {
		return "j_" + UUID.randomUUID();
	}
}

