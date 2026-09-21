package com.hasan.budget.costofliving.web;

import com.hasan.budget.costofliving.application.CityCatalogService;
import com.hasan.budget.costofliving.domain.CityListing;
import com.hasan.budget.costofliving.domain.CountryProfile;
import com.hasan.budget.profile.domain.SpendingQuestion;
import com.hasan.budget.shared.CountryCode;
import java.time.LocalDate;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The cascading location picker: country, then city, then the manual form for everyone else.
 *
 * <p>Cascading rather than a search box, because a search box implies matching a typed name to a
 * place - and a near-match silently selects the wrong city's cost of living, which produces a
 * confident plan full of wrong numbers and nothing to notice it by. Every response here is built
 * from a slug the user picked from a list.
 *
 * <p>No response carries a constant name. {@code CROWDSOURCED} is an internal word; the wording a
 * user reads comes from the enum itself, so a new tier cannot ship without one.
 */
@RestController
@RequestMapping("/api/catalogue")
class CatalogueController {

    private final CityCatalogService catalogue;

    CatalogueController(CityCatalogService catalogue) {
        this.catalogue = catalogue;
    }

    /** What we can be honest about knowing, which is the only list worth offering. */
    record CountryView(String code, String name, String note) {}

    /**
     * @param basis what this city's figures actually are, in a user's words
     * @param explanation the sentence behind the badge, for when they want to know what it means
     * @param gathered when the oldest of this city's figures was recorded
     */
    record CityView(
            String id,
            String name,
            String region,
            String basis,
            String explanation,
            LocalDate gathered) {}

    /**
     * @param covers one rendered line per category, e.g. "Rent - your rent or mortgage payment"
     * @param suggested the pre-filled value, so the user corrects rather than composes
     */
    record QuestionView(
            String question, String why, List<String> covers, String suggested, String basis) {}

    @GetMapping("/countries")
    List<CountryView> countries() {
        return catalogue.countries().stream().map(CatalogueController::toView).toList();
    }

    @GetMapping("/countries/{code}/cities")
    List<CityView> cities(@PathVariable String code) {
        return catalogue.cities(new CountryCode(code)).stream()
                .map(CatalogueController::toView)
                .toList();
    }

    /**
     * The escape hatch, pre-filled. Eleven blank boxes is where people abandon signup, so this
     * arrives already answered from the country estimate and asks them to correct what they know.
     */
    @GetMapping("/countries/{code}/manual-form")
    List<QuestionView> manualForm(@PathVariable String code) {
        return catalogue.manualForm(new CountryCode(code)).stream()
                .map(CatalogueController::toView)
                .toList();
    }

    private static CityView toView(CityListing listing) {
        return new CityView(
                listing.metro().slug(),
                listing.displayName(),
                listing.admin1(),
                listing.confidence().label(),
                listing.confidence().meaning(),
                listing.asOf());
    }

    private static QuestionView toView(SpendingQuestion question) {
        return new QuestionView(
                question.question(),
                question.why(),
                question.explainedCoverage(),
                question.suggested().toString(),
                question.basis());
    }

    private static CountryView toView(CountryProfile country) {
        return new CountryView(country.code().value(), country.name(), country.dataNote());
    }
}
