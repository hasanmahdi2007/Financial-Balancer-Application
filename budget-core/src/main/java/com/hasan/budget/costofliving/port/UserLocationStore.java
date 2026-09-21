package com.hasan.budget.costofliving.port;

import com.hasan.budget.costofliving.domain.ManualLocation;
import java.util.Optional;

/**
 * Where users whose city is not in the catalogue are recorded.
 *
 * <p>Needed by resolution rather than only by the signup form: every lookup is asked in the context
 * of a country, and for these users the country is the only geography there is. Without this, the
 * next request after signup would have nothing to resolve their estimates against.
 *
 * <p>There is deliberately no finder by city label. The label is display text.
 */
public interface UserLocationStore {

    Optional<ManualLocation> find(String userId);

    void save(ManualLocation location);
}
