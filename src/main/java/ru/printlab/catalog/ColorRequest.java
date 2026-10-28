package ru.printlab.catalog;

import java.util.UUID;

public record ColorRequest(UUID id, UUID orderId, String description, String hexCode, String status, UUID approvedColorId) { }
