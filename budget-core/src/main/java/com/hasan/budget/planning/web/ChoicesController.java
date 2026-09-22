package com.hasan.budget.planning.web;

import com.hasan.budget.planning.application.Keys;
import com.hasan.budget.planning.application.Wording;
import com.hasan.budget.planning.domain.Priority;
import com.hasan.budget.planning.domain.decision.SpendBand;
import com.hasan.budget.planning.domain.surplus.ItemScope;
import com.hasan.budget.profile.domain.LifestyleTier;
import com.hasan.budget.shared.Rigidity;
import com.hasan.budget.shared.SpendCategory;
import java.util.Arrays;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Every choice the client can offer, with the words to offer it in.
 *
 * <p>It exists so the client never builds a label table of its own. A second copy of this wording
 * would drift the first time a category or a tier is added, and the version that drifts is always the
 * one a person reads. Each option carries the key to send back and the words to show; the key is a
 * machine identifier and is never rendered.
 */
@RestController
@RequestMapping("/api/v1/choices")
class ChoicesController {

    /** @param covers plain-language examples, so a person recognises the option rather than guessing */
    record Choice(String key, String label, String covers) {}

    record Choices(
            List<Choice> categories,
            List<Choice> howWilling,
            List<Choice> priorities,
            List<Choice> lifestyles,
            List<Choice> mealBands) {}

    @GetMapping
    Choices choices() {
        return new Choices(
                Arrays.stream(SpendCategory.values())
                        .map(category -> new Choice(Keys.of(category), category.label(), category.covers()))
                        .toList(),
                Arrays.stream(Rigidity.values())
                        .map(rigidity -> new Choice(
                                Keys.of(rigidity),
                                Wording.howWilling(rigidity).label(),
                                Wording.howWilling(rigidity).meaning()))
                        .toList(),
                Arrays.stream(Priority.values())
                        .map(priority -> new Choice(
                                Keys.of(priority), Wording.priority(priority).label(), Wording.priority(priority).meaning()))
                        .toList(),
                Arrays.stream(LifestyleTier.values())
                        .map(tier -> new Choice(
                                Keys.of(tier), tier.label(), "Typical for someone " + tier.describesSomeone() + "."))
                        .toList(),
                Arrays.stream(SpendBand.values())
                        .map(band -> new Choice(Keys.of(band), band.label(), band.covers()))
                        .toList());
    }

    /**
     * The two kinds of named commitment, worded for the category the user has just chosen: "Part of
     * what I already spend on Subscriptions" rather than a sentence with a hole in it.
     *
     * <p>Parameterised by category because the wording is built from that category's own label. The
     * distinction is asked rather than guessed: the two look identical in the data, and guessing wrong
     * either counts the money twice or loses it entirely.
     */
    @GetMapping("/item-kinds")
    List<Choice> itemKinds(@RequestParam String category) {
        SpendCategory parent = Keys.parse(SpendCategory.class, category, "kind of spending");
        return Arrays.stream(ItemScope.values())
                .map(scope -> new Choice(Keys.of(scope), scope.labelFor(parent), scope.means()))
                .toList();
    }
}
