package com.foodwasteai.model;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * Model representing redistribution recipient partner organizations (Food Banks, Shelters, Community Kitchens, Sanctuaries, Compost Hubs).
 */
public class RedistributionRecipient implements Serializable {
    private static final long serialVersionUID = 1L;

    private Long id;
    private String name;
    private String organizationType;
    private String contactPerson;
    private String phone;
    private String email;
    private String address;
    private boolean active;
    private LocalDateTime createdAt;

    public RedistributionRecipient() {}

    public RedistributionRecipient(Long id, String name, String organizationType, String contactPerson, String phone, String email, String address, boolean active) {
        this.id = id;
        this.name = name;
        this.organizationType = organizationType;
        this.contactPerson = contactPerson;
        this.phone = phone;
        this.email = email;
        this.address = address;
        this.active = active;
    }

    // Getters and Setters
    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getOrganizationType() {
        return organizationType;
    }

    public void setOrganizationType(String organizationType) {
        this.organizationType = organizationType;
    }

    public String getContactPerson() {
        return contactPerson;
    }

    public void setContactPerson(String contactPerson) {
        this.contactPerson = contactPerson;
    }

    public String getPhone() {
        return phone;
    }

    public void setPhone(String phone) {
        this.phone = phone;
    }

    public String getEmail() {
        return email;
    }

    public void setEmail(String email) {
        this.email = email;
    }

    public String getAddress() {
        return address;
    }

    public void setAddress(String address) {
        this.address = address;
    }

    public boolean isActive() {
        return active;
    }

    public void setActive(boolean active) {
        this.active = active;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }
}
