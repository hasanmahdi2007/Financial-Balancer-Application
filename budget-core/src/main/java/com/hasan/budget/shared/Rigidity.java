package com.hasan.budget.shared;

/**
 * How willing the user is to have an expense reduced.
 *
 * <p>This is the one axis that belongs to the user rather than to the category. Rigid versus
 * flexible is not a property of spending in general — a gym membership is discretionary for most
 * people and untouchable for someone who trains daily. Every category carries a sensible default
 * and the user may override it for any line item.
 *
 * <p>Declaration order is cut order: the engine takes from {@link #DISPOSABLE} first and never
 * touches {@link #LOCKED} at all. That ordering is why this enum subsumes the separate numeric cut
 * rank it replaced, rather than sitting alongside it.
 */
public enum Rigidity {
    /** "Not very important." Cut first, and may be taken to zero. */
    DISPOSABLE,
    /** "Flexible." A normal cut candidate, down to its floor. */
    FLEXIBLE,
    /** "Very important." Cut only as a last resort, and only down to its floor. */
    ESSENTIAL,
    /**
     * "Cannot be changed." Never cut and never raised by rebalancing. The engine may only offer a
     * qualitative hint about making the thing itself cheaper.
     */
    LOCKED
}
