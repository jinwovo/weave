package com.portfolio.weave.persistence;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TextOpRepository extends JpaRepository<TextOpEntity, UUID> {

	/** All text ops for a room, oldest first — replayed into per-shape RGAs when a client joins. */
	List<TextOpEntity> findByRoomIdOrderByCreatedAtAsc(String roomId);
}
