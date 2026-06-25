package com.portfolio.weave.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * One row of the append-only op-log. The primary key is a surrogate UUID (per the workspace
 * convention — no {@code @IdClass} composite keys, which break Hibernate 7 bootstrap); the
 * natural idempotency key {@code (roomId, actorId, hlcL, hlcC)} is enforced by a unique
 * constraint declared in Flyway, not here.
 */
@Entity
@Table(name = "canvas_op")
public class CanvasOpEntity {

	@Id
	private UUID id;

	@Column(name = "room_id", nullable = false)
	private String roomId;

	@Column(name = "shape_id", nullable = false)
	private UUID shapeId;

	@Enumerated(EnumType.STRING)
	@Column(name = "op_type", nullable = false)
	private OpType opType;

	@Column(name = "field")
	private String field;

	@JdbcTypeCode(SqlTypes.JSON)
	@Column(name = "payload", nullable = false)
	private String payload;

	@Column(name = "hlc_l", nullable = false)
	private long hlcL;

	@Column(name = "hlc_c", nullable = false)
	private int hlcC;

	@Column(name = "actor_id", nullable = false)
	private String actorId;

	@Column(name = "created_at", nullable = false)
	private Instant createdAt;

	public CanvasOpEntity() {
		// no-arg constructor required by JPA and used by the codec to build rows
	}

	public UUID getId() {
		return id;
	}

	public void setId(UUID id) {
		this.id = id;
	}

	public String getRoomId() {
		return roomId;
	}

	public void setRoomId(String roomId) {
		this.roomId = roomId;
	}

	public UUID getShapeId() {
		return shapeId;
	}

	public void setShapeId(UUID shapeId) {
		this.shapeId = shapeId;
	}

	public OpType getOpType() {
		return opType;
	}

	public void setOpType(OpType opType) {
		this.opType = opType;
	}

	public String getField() {
		return field;
	}

	public void setField(String field) {
		this.field = field;
	}

	public String getPayload() {
		return payload;
	}

	public void setPayload(String payload) {
		this.payload = payload;
	}

	public long getHlcL() {
		return hlcL;
	}

	public void setHlcL(long hlcL) {
		this.hlcL = hlcL;
	}

	public int getHlcC() {
		return hlcC;
	}

	public void setHlcC(int hlcC) {
		this.hlcC = hlcC;
	}

	public String getActorId() {
		return actorId;
	}

	public void setActorId(String actorId) {
		this.actorId = actorId;
	}

	public Instant getCreatedAt() {
		return createdAt;
	}

	public void setCreatedAt(Instant createdAt) {
		this.createdAt = createdAt;
	}
}
