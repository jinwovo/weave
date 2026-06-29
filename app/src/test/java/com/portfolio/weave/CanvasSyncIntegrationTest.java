package com.portfolio.weave;

import static org.assertj.core.api.Assertions.assertThat;

import com.portfolio.weave.persistence.CanvasOpRepository;
import com.portfolio.weave.web.Wire;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
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

/**
 * End-to-end proof of the P1 sync server against real Postgres + Redis: ops authored by one client
 * fan out to another, land in the durable op-log exactly once (a duplicate is absorbed), and a
 * late-joining third client converges to the identical board <em>from the snapshot alone</em> —
 * i.e. purely by replaying the op-log through the CRDT, with no live messages.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
class CanvasSyncIntegrationTest {

	@Container
	@ServiceConnection
	static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

	@Container
	@ServiceConnection(name = "redis")
	static GenericContainer<?> redis = new GenericContainer<>(DockerImageName.parse("redis:7-alpine"))
			.withExposedPorts(6379);

	@Value("${local.server.port}")
	int port;

	@Autowired
	ObjectMapper mapper;

	@Autowired
	CanvasOpRepository repo;

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
	void opsFanOutPersistOnceAndLateJoinerConvergesFromSnapshot() throws Exception {
		String room = "r1";

		Collector b = new Collector();
		connect(room, "B", b);
		awaitKind(b, "snapshot"); // B's (empty) board on join

		Collector a = new Collector();
		WebSocketSession aSession = connect(room, "A", a);
		awaitKind(a, "snapshot");

		UUID shape = UUID.randomUUID();
		aSession.sendMessage(create(shape, "A", 1, 100, 100, "#ff0000"));
		aSession.sendMessage(setPos(shape, "A", 2, 250, 180));
		aSession.sendMessage(setColor(shape, "A", 3, "#00ff00"));

		// B sees all three ops fanned out (in order, on a single Redis channel)
		List<Wire.Op> seen = nextOps(b, 3);
		assertThat(seen).extracting(Wire.Op::type).containsExactly("CREATE", "SET", "SET");
		assertThat(seen.get(0).shapeId()).isEqualTo(shape);

		// op-log holds exactly the three ops ...
		waitForCount(room, 3);
		// ... and re-sending an identical op (same actor + HLC) is absorbed idempotently
		aSession.sendMessage(setColor(shape, "A", 3, "#00ff00"));
		Thread.sleep(400);
		assertThat(repo.countByRoomId(room)).isEqualTo(3L);

		// a brand-new client converges to the final state purely from the snapshot
		Collector c = new Collector();
		connect(room, "C", c);
		Wire.Snapshot snapshot = mapper.readValue(awaitKind(c, "snapshot"), Wire.Snapshot.class);
		assertThat(snapshot.shapes()).hasSize(1);
		Wire.ShapeView view = snapshot.shapes().get(0);
		assertThat(view.id()).isEqualTo(shape);
		assertThat(view.x()).isEqualTo(250.0);
		assertThat(view.y()).isEqualTo(180.0);
		assertThat(view.color()).isEqualTo("#00ff00");
	}

	// --- helpers ---

	private WebSocketSession connect(String room, String actor, Collector collector) throws Exception {
		String url = "ws://localhost:" + port + "/ws?room=" + room + "&actor=" + actor;
		return new StandardWebSocketClient().execute(collector, url).get(5, TimeUnit.SECONDS);
	}

	private TextMessage create(UUID id, String actor, long l, double x, double y, String color) {
		Wire.Op op = new Wire.Op(id, "CREATE", new Wire.Ts(l, 0, actor),
				new Wire.Shape("RECT", new Wire.Vec(x, y), new Wire.Vec(40, 40), color, "", "a0"), null, null, null);
		return frame(new Wire.Inbound("op", op, null, null, null, null, null));
	}

	private TextMessage setPos(UUID id, String actor, long l, double x, double y) {
		Wire.Op op = new Wire.Op(id, "SET", new Wire.Ts(l, 0, actor), null, "POSITION", new Wire.Vec(x, y), null);
		return frame(new Wire.Inbound("op", op, null, null, null, null, null));
	}

	private TextMessage setColor(UUID id, String actor, long l, String color) {
		Wire.Op op = new Wire.Op(id, "SET", new Wire.Ts(l, 0, actor), null, "COLOR", null, color);
		return frame(new Wire.Inbound("op", op, null, null, null, null, null));
	}

	private TextMessage frame(Wire.Inbound inbound) {
		return new TextMessage(mapper.writeValueAsString(inbound));
	}

	private String awaitKind(Collector collector, String kind) throws Exception {
		long deadline = System.currentTimeMillis() + 5000;
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
		long deadline = System.currentTimeMillis() + 5000;
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

	private void waitForCount(String room, long expected) throws InterruptedException {
		long deadline = System.currentTimeMillis() + 5000;
		while (repo.countByRoomId(room) < expected && System.currentTimeMillis() < deadline) {
			Thread.sleep(50);
		}
		assertThat(repo.countByRoomId(room)).isEqualTo(expected);
	}
}
