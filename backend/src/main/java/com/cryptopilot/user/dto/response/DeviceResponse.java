package com.cryptopilot.user.dto.response;

import com.cryptopilot.user.entity.DevicePlatform;
import java.util.UUID;

/**
 * A registered installation, as the application that registered it sees it.
 *
 * <p>The key is what the application keeps, to deactivate this installation later without sending
 * the messaging token again. The token itself is not returned: the caller already has it.
 *
 * <p>Rule: SRS 3.2.5.
 *
 * @param deviceId the key to deactivate the installation by
 * @param platform ANDROID or IOS
 * @param active whether push messages are sent to it
 */
public record DeviceResponse(UUID deviceId, DevicePlatform platform, boolean active) {}
