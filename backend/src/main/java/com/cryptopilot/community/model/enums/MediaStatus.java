package com.cryptopilot.community.model.enums;

/**
 * Where an attached file stands in the media storage. A file is checked against the storage before it is attached, so
 * every attached file is {@link #READY}; the other values belong to the schema's lifecycle of a file still being
 * processed.
 *
 * <p>Rule: BR-44; SRS 3.7.2.
 */
public enum MediaStatus {
    UPLOADING,
    READY,
    FAILED
}
