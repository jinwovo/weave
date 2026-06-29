package com.portfolio.weave;

import static org.assertj.core.api.Assertions.assertThat;

import com.portfolio.weave.web.Wire;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.handler.TextWebSocketHandler;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * The whole reason the relay publishes to Redis instead of broadcasting locally is horizontal scale:
 * a client on instance A and a client on instance B must still converge. This boots TWO full app
 * contexts in one JVM, both wired to the SAME Postgres + Redis, and proves an op authored on
 * instance 1 reaches a client on instance 2 (via Redis fan-out) and that a fresh client on instance 2
 * converges from the shared op-log.
 */
@Testcontainers
class MultiInstanceConvergenceTest {

	@Container
	static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

	@Container
	static GenericContainer<?> redis = new GenericContainer<>(DockerImageName.parse("redis:7-alpine"))
			.withExposedPorts(6379);

	static ConfigurableApplicationContext app1;
	static ConfigurableApplicationContext app2;
	static int port1;
	static int port2;

	final ObjectMapper mapper = JsonMapper.builder().build();

	@BeforeAll
	static void boot() {
		app1 = bootInstance();
		app2 = bootInstance();
		port1 = portOf(app1);
		port2 = portOf(app2);
	}

	@AfterAll
	static void shutdown() {
		if (app1 != null) {
			app1.close();
		}
		if (app2 != null) {
			app2.close();
		}
	}

	private static ConfigurableApplicationContext bootInstance() {
		// command-line args (--key=value) outrank application.yml, so server.port=0 (random) and the
		// datasource/redis overrides actually take effect (a .properties() default would be ignored).
		return new SpringApplicationBuilder(WeaveApplication.class)
				.run(
						"--server.port=0",
						"--spring.datasource.url=" + postgres.getJdbcUrl(),
						"--spring.datasource.username=" + postgres.getUsername(),
						"--spring.datasource.password=" + postgres.getPassword(),
						"--spring.data.redis.host=" + redis.getHost(),
						"--spring.data.redis.port=" + redis.getMappedPort(6379),
						"--management.endpoints.web.exposure.include=health");
	}

	private static int portOf(ConfigurableApplicationContext ctx) {
		return Integer.parseInt(ctx.getEnvironment().getProperty("local.server.port", "0"));
	}

	private record Kind(String kind) {
	}

	private static final class Collector extends TextWebSocketHandler {
		final BlockingQueue<String> messages = new LinkedBlockingQueue<>();

		@Override
		public void handleTextMessage(WebSocketSession session, TextMessage message) {
			messages.add(message.getPayload());
		}
	}

	@Test
	void opsFanOutAcrossInstancesAndNewJoinerConverges() throws Exception {
		String room = "multi";

		// B is connected to instance 2
		Collector b = new Collector();
		connect(port2, room, "B", b);
		awaitKind(b, "snapshot");

		// A is connected to instance 1
		Collector a = new Collector();
		WebSocketSession aSession = connect(port1, room, "A", a);
		awaitKind(a, "snapshot");

		UUID shape = UUID.randomUUID();
		aSession.sendMessage(create(shape, "A", 1, 100, 100, "#3b82f6"));
		aSession.sendMessage(setColor(shape, "A", 2, "#22c55e"));

		// B (a DIFFERENT instance) receives A's ops — only possible via the Redis fan-out
		List<Wire.Op> seen = nextOps(b, 2);
		assertThat(seen).extracting(Wire.Op::type).containsExactly("CREATE", "SET");
		assertThat(seen.get(0).shapeId()).isEqualTo(shape);

		// a brand-new client on instance 2 converges from the shared op-log snapshot alone
		Collector c = new Collector();
		connect(port2, room, "C", c);
		Wire.Snapshot snapshot = mapper.readValue(awaitKind(c, "snapshot"), Wire.Snapshot.class);
		assertThat(snapshot.shapes()).hasSize(1);
		Wire.ShapeView view = snapshot.shapes().get(0);
		assertThat(view.id()).isEqualTo(shape);
		assertThat(view.color()).isEqualTo("#22c55e");
	}

	// --- helpers ---

	private WebSocketSession connect(int port, String room, String actor, Collector collector) throws Exception {
		String url = "ws://localhost:" + port + "/ws?room=" + room + "&actor=" + actor;
		return new StandardWebSocketClient().execute(collector, url).get(5, TimeUnit.SECONDS);
	}

	private TextMessage create(UUID id, String actor, long l, double x, double y, String color) {
		Wire.Op op = new Wire.Op(id, "CREATE", new Wire.Ts(l, 0, actor),
				new Wire.Shape("RECT", new Wire.Vec(x, y), new Wire.Vec(40, 40), color, "", "a0"), null, null, null);
		return new TextMessage(mapper.writeValueAsString(new Wire.Inbound("op", op, null, null, null, null, null)));
	}

	private TextMessage setColor(UUID id, String actor, long l, String color) {
		Wire.Op op = new Wire.Op(id, "SET", new Wire.Ts(l, 0, actor), null, "COLOR", null, color);
		return new TextMessage(mapper.writeValueAsString(new Wire.Inbound("op", op, null, null, null, null, null)));
	}

	private String awaitKind(Collector collector, String kind) throws Exception {
		long deadline = System.currentTimeMillis() + 6000;
		while (System.currentTimeMillis() < deadline) {
			String msg = collector.messages.poll(deadline - System.currentTimeMillis(), TimeUnit.MILLISECONDS);
			if (msg == null) {
				break;
			}
			if (kind.equals(mapper.readValue(msg, Kind.class).kind())) {
				return msg;
			}
		}
		throw new AssertionError("did not receive a '" + kind + "' message in time");
	}

	private List<Wire.Op> nextOps(Collector collector, int n) throws Exception {
		List<Wire.Op> ops = new ArrayList<>();
		long deadline = System.currentTimeMillis() + 6000;
		while (ops.size() < n && System.currentTimeMillis() < deadline) {
			String msg = collector.messages.poll(deadline - System.currentTimeMillis(), TimeUnit.MILLISECONDS);
			if (msg == null) {
				break;
			}
			if ("op".equals(mapper.readValue(msg, Kind.class).kind())) {
				ops.add(mapper.readValue(msg, Wire.OpBroadcast.class).op());
			}
		}
		return ops;
	}
}
