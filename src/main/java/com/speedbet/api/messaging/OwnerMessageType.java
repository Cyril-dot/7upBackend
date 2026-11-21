package com.speedbet.api.messaging;

/**
 * The kind of an owner broadcast message (and of a super-admin reply).
 * Stored as a STRING in the DB.
 */
public enum OwnerMessageType {
    TEXT,
    IMAGE,
    FILE,
    LINK,
    VOICE
}
