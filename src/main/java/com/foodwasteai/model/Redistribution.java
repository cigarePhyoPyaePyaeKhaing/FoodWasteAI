package com.foodwasteai.model;

import java.io.Serializable;
import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * Model representing recorded surplus food donation dispatches.
 */
public class Redistribution implements Serializable {
    private static final long serialVersionUID = 1L;

    public enum Status {
        PENDING,
        CONFIRMED,
        COLLECTED,
        COMPLETED,
        CANCELLED
    }

    private Long id;
    private Long foodItemId;
    private String foodItemName; // Joined
    private Long recipientId;
    private String recipientName; // Joined
    private BigDecimal quantity;
    private String unit;
    private LocalDateTime pickupTime;
    private Status status;
    private String notes;
    private String notesEn;
    private String notesMy;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    public Redistribution() {}

    // Getters and Setters
    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Long getFoodItemId() {
        return foodItemId;
    }

    public void setFoodItemId(Long foodItemId) {
        this.foodItemId = foodItemId;
    }

    public String getFoodItemName() {
        return foodItemName;
    }

    public void setFoodItemName(String foodItemName) {
        this.foodItemName = foodItemName;
    }

    public Long getRecipientId() {
        return recipientId;
    }

    public void setRecipientId(Long recipientId) {
        this.recipientId = recipientId;
    }

    public String getRecipientName() {
        return recipientName;
    }

    public void setRecipientName(String recipientName) {
        this.recipientName = recipientName;
    }

    public BigDecimal getQuantity() {
        return quantity;
    }

    public void setQuantity(BigDecimal quantity) {
        this.quantity = quantity;
    }

    public String getUnit() {
        return unit;
    }

    public void setUnit(String unit) {
        this.unit = unit;
    }

    public LocalDateTime getPickupTime() {
        return pickupTime;
    }

    public void setPickupTime(LocalDateTime pickupTime) {
        this.pickupTime = pickupTime;
    }

    public Status getStatus() {
        return status;
    }

    public void setStatus(Status status) {
        this.status = status;
    }

    public String getNotes() {
        return notes;
    }

    public void setNotes(String notes) {
        this.notes = notes;
        if (this.notesEn == null) {
            this.notesEn = notes;
        }
    }

    public String getNotesEn() {
        return notesEn != null ? notesEn : notes;
    }

    public void setNotesEn(String notesEn) {
        this.notesEn = notesEn;
        if (this.notes == null) {
            this.notes = notesEn;
        }
    }

    public String getNotesMy() {
        return notesMy;
    }

    public void setNotesMy(String notesMy) {
        this.notesMy = notesMy;
    }

    public String getNotes(String lang) {
        if ("mm".equalsIgnoreCase(lang) || "my".equalsIgnoreCase(lang)) {
            return (notesMy != null && !notesMy.isEmpty()) ? notesMy : getNotesEn();
        }
        return getNotesEn();
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }

    public LocalDateTime getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(LocalDateTime updatedAt) {
        this.updatedAt = updatedAt;
    }
}
