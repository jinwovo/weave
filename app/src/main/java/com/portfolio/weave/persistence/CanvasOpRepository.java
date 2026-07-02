package com.portfolio.weave.persistence;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface CanvasOpRepository extends JpaRepository<CanvasOpEntity, UUID> {

	/** Replay a room's op-log in (HLC, actor) total order — any order converges; this one reads naturally. */
	List<CanvasOpEntity> findByRoomIdOrderByHlcLAscHlcCAscActorIdAsc(String roomId);

	/** The tail beyond a snapshot's watermark — what cold-start replay actually folds. */
	List<CanvasOpEntity> findByRoomIdAndSeqGreaterThanOrderBySeqAsc(String roomId, long seq);

	/**
	 * The snapshot-builder's tail: past the watermark but older than the grace horizon, so the
	 * watermark never advances over a row whose inserting transaction might not have committed
	 * yet when we read. (Refolding rows the grace window makes us re-read is harmless — the
	 * document fold is idempotent.)
	 */
	@Query("select e from CanvasOpEntity e where e.roomId = :room and e.seq > :after and e.createdAt < :horizon order by e.seq asc")
	List<CanvasOpEntity> tailForSnapshot(@Param("room") String room, @Param("after") long after, @Param("horizon") Instant horizon);

	/** Rooms whose un-snapshotted tail has grown past the threshold — the sweeper's work list. */
	@Query(value = """
			select o.room_id from canvas_op o
			left join canvas_snapshot s on s.room_id = o.room_id
			where o.seq > coalesce(s.upto_seq, 0)
			group by o.room_id
			having count(*) >= :threshold
			""", nativeQuery = true)
	List<String> roomsWithTailAtLeast(@Param("threshold") long threshold);

	long countByRoomId(String roomId);

	/** Op counts per room id sharing a prefix — lists hourly archive buckets (room "base@<hour>"). */
	@Query("select e.roomId as roomId, count(e) as cnt from CanvasOpEntity e where e.roomId like :prefix group by e.roomId")
	List<RoomCount> countByRoomIdPrefix(@Param("prefix") String prefix);

	interface RoomCount {
		String getRoomId();

		long getCnt();
	}
}
