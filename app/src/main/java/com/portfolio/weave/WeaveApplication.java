package com.portfolio.weave;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * weave sync server. Wraps the dependency-free {@code crdt-core} with transport (WebSocket),
 * durability (an append-only op-log on PostgreSQL) and cross-instance fan-out (Redis pub/sub).
 * The server is a relay, not the source of truth — convergence lives in the CRDT.
 */
@SpringBootApplication
@EnableScheduling // the snapshot sweeper (SnapshotStore.sweep) runs on a fixed delay
public class WeaveApplication {

	public static void main(String[] args) {
		SpringApplication.run(WeaveApplication.class, args);
	}
}
