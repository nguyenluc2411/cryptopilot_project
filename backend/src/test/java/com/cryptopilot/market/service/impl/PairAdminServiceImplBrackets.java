package com.cryptopilot.market.service.impl;

import com.cryptopilot.market.LeverageTier;
import com.cryptopilot.market.dto.request.LeverageBracketRequest;
import java.util.List;

/** Opens the bracket checks of {@link PairAdminServiceImpl} to tests of other packages. */
public final class PairAdminServiceImplBrackets {

    private PairAdminServiceImplBrackets() {}

    public static List<LeverageTier> checked(List<LeverageBracketRequest> rows) {
        return PairAdminServiceImpl.checkedBrackets(rows);
    }
}
