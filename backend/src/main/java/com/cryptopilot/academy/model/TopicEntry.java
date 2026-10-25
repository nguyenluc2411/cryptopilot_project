package com.cryptopilot.academy.model;

/**
 * A topic of the Academy with the number of its published lessons.
 *
 * @param slug the key the client addresses the topic by
 * @param name the heading of the topic
 * @param description one or two sentences about the topic
 * @param articles how many published lessons the topic holds
 */
public record TopicEntry(String slug, String name, String description, long articles) {}
