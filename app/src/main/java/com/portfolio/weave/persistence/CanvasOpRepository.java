package com.portfolio.weave.persistence;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface CanvasOpRepository extends JpaRepository<CanvasOpEntity, UUID> {

	/** Replay a room's op-log in (HLC, actor) total order — any order converges; this one reads naturally. */
	List<CanvasOpEntity> findByRoomIdOrderByHlcLAscHlcCAscActorIdAsc(String roomId);

	long countByRoomId(String roomId);

	/** Op counts per room id sharing a prefix — lists hourly archive buckets (room "base@<hour>"). */
	@Query("select e.roomId as roomId, count(e) as cnt from CanvasOpEntity e where e.roomId like :prefix group by e.roomId")
	List<RoomCount> countByRoomIdPrefix(@Param("prefix") String prefix);

	interface RoomCount {
		String getRoomId();

		long getCnt();
	}
}
