package com.cryptopilot.user.service;

import com.cryptopilot.user.UserApi;

/**
 * The use cases of {@link com.cryptopilot.user.service.impl.UserServiceImpl}: every method it offers is the module
 * facade's, so the interface only names the implementation's place in this package (D-48).
 *
 * <p>Rule: BR-01, BR-05; SRS UC-01 (MSG04).
 */
public interface UserService extends UserApi {}
