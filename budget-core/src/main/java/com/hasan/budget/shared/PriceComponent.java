package com.hasan.budget.shared;

/**
 * The BEA Regional Price Parities component used to localise a national spending baseline.
 *
 * <p>Lives in {@code shared} rather than {@code costofliving} because {@link SpendCategory} carries
 * its own mapping, and {@code shared} may not depend on any other package.
 */
public enum PriceComponent {
    RENTS,
    GOODS,
    OTHER_SERVICES
}
