package com.cryptopilot.common.exception;

/**
 * Thrown when a resource addressed by id does not exist, or exists but is not visible to the
 * caller. Both cases answer 404 deliberately: telling a caller that someone else's plan exists
 * would already be a leak.
 *
 * <p>Rule: TECHNICAL_DESIGN section 5.1 ({@code ResourceNotFoundException} → 404, message MSG41).
 */
public class ResourceNotFoundException extends BusinessException {

    private static final long serialVersionUID = 1L;

    /**
     * @param resource the resource type as the API names it, for example {@code "TradingPlan"}
     * @param id the identifier that was looked up
     */
    public ResourceNotFoundException(String resource, Object id) {
        super(ErrorCode.RESOURCE_NOT_FOUND, describe(resource, id));
    }

    private static String describe(String resource, Object id) {
        if (resource == null || resource.isBlank()) {
            throw new IllegalArgumentException("resource must not be blank");
        }
        return resource + " " + id + " was not found";
    }
}
