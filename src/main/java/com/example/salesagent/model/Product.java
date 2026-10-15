package com.example.salesagent.model;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

@Document("products")
public record Product(@Id String id, String name, String category, double price) {
}
