package com.portfolio.weave.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * One persisted RGA text operation. The server stores it opaquely (the {@code payload} is the wire
 * op verbatim) and relays it — convergence happens on the clients. {@code opKey} + {@code opType}
 * give idempotency: re-sending the same insert/delete is stored once.
 */
@Entity
@Table(name = "text_op")
public class TextOpEntity {

	@Id
	private UUID id;

	@Column(name = "room_id", nullable = false)
	private String roomId;

	@Column(name = "shape_id", nullable = false)
	private UUID shapeId;

	@Column(name = "op_type", nullable = false)
	private String opType;

	@Column(name = "op_key", nullable = false)
	private String opKey;

	@JdbcTypeCode(SqlTypes.JSON)
	@Column(name = "payload", nullable = false)
	private String payload;

	@Column(name = "created_at", nullable = false)
	private Instant createdAt;

	public TextOpEntity() {
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

	public String getOpType() {
		return opType;
	}

	public void setOpType(String opType) {
		this.opType = opType;
	}

	public String getOpKey() {
		return opKey;
	}

	public void setOpKey(String opKey) {
		this.opKey = opKey;
	}

	public String getPayload() {
		return payload;
	}

	public void setPayload(String payload) {
		this.payload = payload;
	}

	public Instant getCreatedAt() {
		return createdAt;
	}

	public void setCreatedAt(Instant createdAt) {
		this.createdAt = createdAt;
	}
}
