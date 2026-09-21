package com.hasan.budget.costofliving.persistence;

import com.hasan.budget.costofliving.domain.Confidence;
import com.hasan.budget.costofliving.domain.Contribution;
import com.hasan.budget.costofliving.domain.ContributionState;
import com.hasan.budget.costofliving.port.ContributionStore;
import com.hasan.budget.shared.CountryCode;
import com.hasan.budget.shared.MetroId;
import com.hasan.budget.shared.Money;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/** The Postgres adapter for the contribution queue. */
@Repository
class JpaContributionStore implements ContributionStore {

    /**
     * What a published contribution cites as its provenance. It is a real source row rather than a
     * borrowed one, so the interface can say "another user, checked against their own bank" instead
     * of implying the figure came from wherever the curated data came from.
     */
    private static final String CONTRIBUTED_SOURCE_ID = "user-contributed";

    private final CityContributionRepository contributions;
    private final CityCategoryBaselineRepository baselines;
    private final MetroAreaRepository metros;

    JpaContributionStore(
            CityContributionRepository contributions,
            CityCategoryBaselineRepository baselines,
            MetroAreaRepository metros) {
        this.contributions = contributions;
        this.baselines = baselines;
        this.metros = metros;
    }

    @Override
    @Transactional
    public Contribution save(Contribution contribution) {
        CityContributionEntity saved = contributions.save(toEntity(contribution));
        return toDomain(saved);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<Contribution> find(long id) {
        return contributions.findById(id).map(this::toDomain);
    }

    @Override
    @Transactional(readOnly = true)
    public List<Contribution> queued() {
        return contributions
                .findByValidationStateOrderBySubmittedAt(ContributionState.QUEUED)
                .stream()
                .map(this::toDomain)
                .toList();
    }

    /**
     * Both writes in one transaction, which is the whole reason this is a single method on the port.
     * A contribution marked published whose city default never appeared would look approved to its
     * author and be invisible to everyone else - and nothing would ever surface the discrepancy,
     * because neither side looks wrong on its own.
     */
    @Override
    @Transactional
    public Contribution publish(Contribution approved) {
        MetroId metro = approved.listedCity()
                .orElseThrow(() -> new IllegalStateException(
                        "a contribution with no listed city cannot become that city's default"));
        Long metroId = metros.findBySlug(metro.slug())
                .map(MetroAreaEntity::id)
                .orElseThrow(() -> new IllegalStateException("unknown city " + metro.slug()));

        baselines.deleteByMetroIdAndCategoryAndConfidence(
                metroId, approved.category(), Confidence.CONTRIBUTED);
        baselines.save(new CityCategoryBaselineEntity(
                metroId,
                approved.category(),
                null,
                approved.amount().amount(),
                Confidence.CONTRIBUTED,
                CONTRIBUTED_SOURCE_ID,
                approved.submittedAt(),
                null));
        return toDomain(contributions.save(toEntity(approved)));
    }

    private CityContributionEntity toEntity(Contribution contribution) {
        Long metroId = contribution.listedCity()
                .flatMap(metro -> metros.findBySlug(metro.slug()))
                .map(MetroAreaEntity::id)
                .orElse(null);
        return new CityContributionEntity(
                contribution.id() == Contribution.UNSAVED ? null : contribution.id(),
                metroId,
                contribution.cityLabel(),
                contribution.country().value(),
                contribution.userId(),
                contribution.category(),
                contribution.amount().amount(),
                contribution.submittedAt(),
                contribution.state(),
                contribution.corroboration(),
                contribution.notes());
    }

    private Contribution toDomain(CityContributionEntity entity) {
        return new Contribution(
                entity.id(),
                entity.userId(),
                new CountryCode(entity.countryCode()),
                metroOf(entity),
                entity.cityLabel(),
                entity.category(),
                new Money(entity.monthlyAmount()),
                entity.submittedAt(),
                entity.validationState(),
                entity.corroboration(),
                entity.validationNotes());
    }

    /**
     * The stored row keys the city by its surrogate id; the domain knows cities only by slug, so the
     * id is translated here and never escapes. A second small read rather than a join, because the
     * contribution table is written far more often than it is listed and the queue stays short.
     */
    private MetroId metroOf(CityContributionEntity entity) {
        if (entity.metroId() == null) {
            return null;
        }
        return metros.findById(entity.metroId())
                .map(area -> new MetroId(area.slug()))
                .orElseThrow(() -> new IllegalStateException(
                        "contribution " + entity.id() + " references unknown city " + entity.metroId()));
    }
}
