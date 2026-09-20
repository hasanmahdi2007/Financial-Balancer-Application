package com.hasan.budget.shared;

import java.util.Optional;

/**
 * A spending category and the policy attached to it.
 *
 * <p>The policy lives here, as table-shaped metadata, rather than in conditionals spread across the
 * engine. Adding a category is one constant; no calculation needs editing, because the surplus
 * calculation switches on {@link BaselinePolicy} (three arms) and never on the category itself.
 *
 * <p>Commitment and cut speed are deliberately independent axes. A streaming subscription is as
 * recurring as rent — it is owed this month either way — yet it can be cancelled this afternoon,
 * while a lease cannot. A single "flexibility" rank cannot express that, which is why
 * {@code SUBSCRIPTIONS} below is both {@link BaselinePolicy#TAKE_AS_IS} and a genuine cut candidate.
 */
public enum SpendCategory {

    RENT(Commitment.FIXED, CutSpeed.NOT_SHORT_TERM, BaselinePolicy.TAKE_AS_IS,
            PriceComponent.RENTS, Rigidity.LOCKED, SpendCategory.NO_CUT, false),

    /** A loan or credit instalment owed to a lender. Distinct from paying off a credit-card balance,
     * which settles spending already counted and is a {@link TransactionKind#TRANSFER_INTERNAL}. */
    DEBT_PAYMENT(Commitment.FIXED, CutSpeed.NOT_SHORT_TERM, BaselinePolicy.TAKE_AS_IS,
            null, Rigidity.LOCKED, SpendCategory.NO_CUT, false),

    /** Money held back for tax by users whose income arrives untaxed. Off unless opted into. */
    TAX_RESERVE(Commitment.FIXED, CutSpeed.NOT_SHORT_TERM, BaselinePolicy.TAKE_AS_IS,
            null, Rigidity.LOCKED, SpendCategory.NO_CUT, false),

    HEALTHCARE(Commitment.FIXED, CutSpeed.SLOW, BaselinePolicy.TAKE_AS_IS,
            PriceComponent.OTHER_SERVICES, Rigidity.ESSENTIAL, SpendCategory.NO_CUT, false),

    SUBSCRIPTIONS(Commitment.FIXED, CutSpeed.IMMEDIATE, BaselinePolicy.TAKE_AS_IS,
            PriceComponent.OTHER_SERVICES, Rigidity.ESSENTIAL, 4, true),

    UTILITIES(Commitment.VARIABLE, CutSpeed.PARTIAL, BaselinePolicy.CAP_AT_BASELINE,
            PriceComponent.OTHER_SERVICES, Rigidity.ESSENTIAL, SpendCategory.NO_CUT, false),

    GROCERIES(Commitment.VARIABLE, CutSpeed.PARTIAL, BaselinePolicy.CAP_AT_BASELINE,
            PriceComponent.GOODS, Rigidity.ESSENTIAL, SpendCategory.NO_CUT, false),

    TRANSPORT_FUEL(Commitment.VARIABLE, CutSpeed.PARTIAL, BaselinePolicy.CAP_AT_BASELINE,
            PriceComponent.GOODS, Rigidity.ESSENTIAL, SpendCategory.NO_CUT, false),

    DINING_OUT(Commitment.VARIABLE, CutSpeed.IMMEDIATE, BaselinePolicy.DISCRETIONARY,
            PriceComponent.OTHER_SERVICES, Rigidity.FLEXIBLE, 2, true),

    ENTERTAINMENT(Commitment.VARIABLE, CutSpeed.IMMEDIATE, BaselinePolicy.DISCRETIONARY,
            PriceComponent.OTHER_SERVICES, Rigidity.DISPOSABLE, 1, true),

    CLOTHING(Commitment.VARIABLE, CutSpeed.IMMEDIATE, BaselinePolicy.DISCRETIONARY,
            PriceComponent.GOODS, Rigidity.FLEXIBLE, 3, true),

    /**
     * Unidentified spending. Counted as discretionary, but never proposed as a cut: "reduce Other by
     * $150" is advice nobody can act on, so the engine stays silent rather than unhelpful.
     */
    OTHER(Commitment.VARIABLE, CutSpeed.IMMEDIATE, BaselinePolicy.DISCRETIONARY,
            PriceComponent.OTHER_SERVICES, Rigidity.FLEXIBLE, SpendCategory.NO_CUT, false);

    /** Tie-break value for categories the engine never proposes cutting. */
    private static final int NO_CUT = Integer.MAX_VALUE;

    private final Commitment commitment;
    private final CutSpeed cutSpeed;
    private final BaselinePolicy baselinePolicy;
    private final PriceComponent priceComponent;
    private final Rigidity defaultRigidity;
    private final int cutOrder;
    private final boolean autoSuggestCuts;

    SpendCategory(
            Commitment commitment,
            CutSpeed cutSpeed,
            BaselinePolicy baselinePolicy,
            PriceComponent priceComponent,
            Rigidity defaultRigidity,
            int cutOrder,
            boolean autoSuggestCuts) {
        this.commitment = commitment;
        this.cutSpeed = cutSpeed;
        this.baselinePolicy = baselinePolicy;
        this.priceComponent = priceComponent;
        this.defaultRigidity = defaultRigidity;
        this.cutOrder = cutOrder;
        this.autoSuggestCuts = autoSuggestCuts;
    }

    public Commitment commitment() {
        return commitment;
    }

    public CutSpeed cutSpeed() {
        return cutSpeed;
    }

    public BaselinePolicy baselinePolicy() {
        return baselinePolicy;
    }

    /**
     * The regional price component used to localise this category's national baseline. Empty where
     * no price index applies, as for debt instalments and tax, whose amounts are not set by local
     * prices at all.
     */
    public Optional<PriceComponent> priceComponent() {
        return Optional.ofNullable(priceComponent);
    }

    /** The rigidity applied unless the user has overridden it for their own line item. */
    public Rigidity defaultRigidity() {
        return defaultRigidity;
    }

    /** Tie-break within a rigidity tier; lower is cut first. */
    public int cutOrder() {
        return cutOrder;
    }

    /** Whether the engine may propose a numeric cut here at all. */
    public boolean autoSuggestCuts() {
        return autoSuggestCuts;
    }
}
