package com.hasan.budget.ingestion.domain;

import com.hasan.budget.shared.Money;
import java.util.Objects;

/**
 * One connected account and what was in it when the bank last told us.
 *
 * <p>A stock, not a rate, and the two must never be added: monthly income is what arrives each
 * month, and this is what is sitting there already. The planning side keeps them in separate
 * concepts for exactly that reason.
 *
 * <p>Carried out of the sync that was happening anyway rather than fetched when somebody asks. A
 * balance lookup on a user's request would put a call to somebody else's API in the middle of a
 * page load, which is the thing this module is built to avoid; the cost is that a balance is as
 * fresh as the last sync, which for a monthly plan is fresh enough.
 *
 * @param role which kind of account this is. Only {@link AccountRole#CASH} holds money the user can
 *     spend - a card or loan balance is what they owe, and adding it to their funds would invent
 *     money that is not there.
 * @param available what the bank says is actually usable, which differs from {@code current} while
 *     transactions are uncleared. Null where the bank does not report it, which is normal for
 *     credit and loan accounts.
 */
public record AccountSnapshot(
        String accountId, String label, AccountRole role, Money current, Money available) {

    public AccountSnapshot {
        Objects.requireNonNull(accountId, "accountId");
        Objects.requireNonNull(role, "role");
        Objects.requireNonNull(current, "current");
        if (label == null || label.isBlank()) {
            // Normalised once, here, because this is shown to a person and not every bank sends one.
            label = "Account";
        }
    }

    /** True when this account holds spendable money rather than recording a debt. */
    public boolean holdsMoney() {
        return role == AccountRole.CASH;
    }
}
