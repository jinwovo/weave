package com.portfolio.weave.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * The materialised fold of one room's op-log up to {@code uptoSeq}: the whole CRDT document —
 * every register's value <em>and</em> timestamp, tombstones included — as jsonb. Cold-start
 * recovery loads this and folds only the ops beyond the watermark, so replay work is bounded by
 * the snapshot cadence instead of the room's lifetime.
 */
@Entity
@Table(name = "canvas_snapshot")
public class CanvasSnapshotEntity {

	@Id
	@Column(name = "room_id")
	private String roomId;

	@Column(name = "upto_seq", nullable = false)
	private long uptoSeq;

	@JdbcTypeCode(SqlTypes.JSON)
	@Column(name = "state", nullable = false)
	private String state;

	@Column(name = "shape_count", nullable = false)
	private int shapeCount;

	@Column(name = "op_count", nullable = false)
	private long opCount;

	@Column(name = "updated_at", nullable = false)
	private Instant updatedAt;

	public CanvasSnapshotEntity() {
		// no-arg constructor required by JPA
	}

	public String getRoomId() {
		return roomId;
	}

	public void setRoomId(String roomId) {
		this.roomId = roomId;
	}

	public long getUptoSeq() {
		return uptoSeq;
	}

	public void setUptoSeq(long uptoSeq) {
		this.uptoSeq = uptoSeq;
	}

	public String getState() {
		return state;
	}

	public void setState(String state) {
		this.state = state;
	}

	public int getShapeCount() {
		return shapeCount;
	}

	public void setShapeCount(int shapeCount) {
		this.shapeCount = shapeCount;
	}

	public long getOpCount() {
		return opCount;
	}

	public void setOpCount(long opCount) {
		this.opCount = opCount;
	}

	public Instant getUpdatedAt() {
		return updatedAt;
	}

	public void setUpdatedAt(Instant updatedAt) {
		this.updatedAt = updatedAt;
	}
}
