package com.portfolio.weave.web;

import com.portfolio.weave.canvas.OpCodec;
import com.portfolio.weave.persistence.CanvasOpRepository;
import java.util.Comparator;
import java.util.List;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

/**
 * Exposes a room's full, ordered op-log over HTTP. This is the product payoff of the event-sourced
 * design: because every edit is an immutable op, the client can fetch the whole history and
 * <em>replay</em> the board to any point in time — something a state-only whiteboard cannot do.
 */
@RestController
@CrossOrigin(origins = "*") // dev: the Next client is served from a different origin (:3009)
public class HistoryController {

	private final CanvasOpRepository repo;
	private final OpCodec codec;

	public HistoryController(CanvasOpRepository repo, OpCodec codec) {
		this.repo = repo;
		this.codec = codec;
	}

	@GetMapping("/api/rooms/{room}/history")
	public List<Wire.Op> history(@PathVariable String room) {
		return repo.findByRoomIdOrderByHlcLAscHlcCAscActorIdAsc(room).stream()
				.map((e) -> codec.toDto(codec.fromEntity(e)))
				.toList();
	}

	/** List the hourly archive buckets ("base@<hour>") that hold any ops, newest first. */
	@GetMapping("/api/rooms/{base}/epochs")
	public List<EpochInfo> epochs(@PathVariable String base) {
		return repo.countByRoomIdPrefix(base + "@%").stream()
				.map((rc) -> {
					String rid = rc.getRoomId();
					String suffix = rid.substring(rid.lastIndexOf('@') + 1);
					long epoch = !suffix.isEmpty() && suffix.chars().allMatch(Character::isDigit) ? Long.parseLong(suffix) : -1;
					return new EpochInfo(epoch, rc.getCnt());
				})
				.filter((e) -> e.epoch() >= 0)
				.sorted(Comparator.comparingLong(EpochInfo::epoch).reversed())
				.toList();
	}

	public record EpochInfo(long epoch, long count) {
	}
}
