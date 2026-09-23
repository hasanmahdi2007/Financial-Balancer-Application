package com.hasan.budget.planning.application;

import com.hasan.budget.costofliving.domain.ResolvedBaseline;
import com.hasan.budget.planning.application.PlanView.KeyLabel;
import com.hasan.budget.profile.domain.LifestyleTier;
import com.hasan.budget.profile.domain.SpendingQuestion;
import com.hasan.budget.shared.SpendCategory;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * The questions a user still has to answer, each explaining itself and pre-filled wherever we can.
 *
 * <p>A question the user has to guess at is a defect. So each one says what it is asking, why, what it
 * covers, and what a suggested figure is based on - and the money questions are assembled from
 * {@link SpendingQuestion} and the category table rather than written out per category, so they cannot
 * drift when the taxonomy changes. No internal term reaches a person from here: the floor is "the least
 * you would want to spend on enjoying life", never a floor.
 */
public final class QuestionService {

    private static final DateTimeFormatter MONTH_AND_YEAR = DateTimeFormatter.ofPattern("MMMM yyyy", Locale.ENGLISH);

    private final PlanService plans;
    private final Places places;

    public QuestionService(PlanService plans, Places places) {
        this.plans = Objects.requireNonNull(plans, "plans");
        this.places = Objects.requireNonNull(places, "places");
    }

    /**
     * @param key what the question is about, stable across calls
     * @param answerWith the request, and the field in it, that answers this question
     * @param suggested the pre-filled answer, or null where there is nothing honest to suggest
     * @param choices the options to pick from, for a question answered by choosing; empty otherwise
     */
    public record Question(
            String key,
            String answerWith,
            String question,
            String why,
            List<String> covers,
            String suggested,
            String basis,
            List<KeyLabel> choices) {

        public Question {
            covers = List.copyOf(covers);
            choices = List.copyOf(choices);
        }
    }

    public List<Question> stillToAsk(String userId) {
        List<Question> questions = new ArrayList<>();
        Optional<PlanningProfile> profile = plans.profile(userId);

        if (profile.isEmpty()) {
            questions.add(taxed());
        }
        if (profile.map(PlanningProfile::lifestyle).isEmpty()) {
            questions.add(lifestyle());
        }
        if (plans.money(userId).isEmpty()) {
            questions.add(income());
            questions.add(balance());
            questions.add(alreadySaving());
        }
        profile.ifPresent(known -> {
            if (known.leastForEnjoyingLife() == null) {
                leastForEnjoyingLife(userId).ifPresent(questions::add);
            }
            questions.addAll(spending(userId, known));
        });
        return questions;
    }

    private static Question taxed() {
        return new Question(
                "income-arrives-taxed",
                "PUT /api/v1/profile incomeArrivesTaxed",
                "Does your pay arrive with tax already taken off?",
                "If it does - most jobs work this way - we will not take tax off a second time. If it "
                        + "does not, as with freelance work, we keep some aside each month so the bill does "
                        + "not arrive as a surprise.",
                List.of(),
                null,
                null,
                List.of(new KeyLabel("true", "Yes, tax is already taken off"),
                        new KeyLabel("false", "No, I pay my own tax")));
    }

    private static Question lifestyle() {
        return new Question(
                "lifestyle",
                "PUT /api/v1/profile lifestyle",
                "How often do you go out?",
                "It gives us a sensible starting point for the least you would want to spend on enjoying "
                        + "life. You can always set that figure yourself.",
                List.of(),
                null,
                null,
                Arrays.stream(LifestyleTier.values()).map(tier -> new KeyLabel(Keys.of(tier), tier.label())).toList());
    }

    private static Question income() {
        return new Question(
                "monthly-income",
                "PUT /api/v1/money monthlyIncome",
                "How much arrives in your account each month?",
                "Your plan is built from what you actually receive, so use the amount that lands in your "
                        + "account rather than your salary before deductions.",
                List.of(),
                null,
                null,
                List.of());
    }

    private static Question balance() {
        return new Question(
                "balance",
                "PUT /api/v1/money balance",
                "How much money do you already have that this plan may use?",
                "Only what you enter here is used. It goes toward your goals first, so they need less "
                        + "each month. Lower it at any time and the next plan hands the money back.",
                List.of(),
                null,
                null,
                List.of());
    }

    private static Question alreadySaving() {
        return new Question(
                "already-saving",
                "PUT /api/v1/money alreadySaving",
                "How much do you already put into savings each month?",
                "We show it beside your plan and never take it off what you have left, because putting "
                        + "money away is not spending it. It is here so your plan reflects what you are "
                        + "already doing rather than starting from nothing.",
                List.of(),
                null,
                null,
                List.of());
    }

    /** The derived figure is only ever a suggestion: the one person who knows it is the person living on it. */
    private Optional<Question> leastForEnjoyingLife(String userId) {
        try {
            SpendingQuestion asked = plans.assemble(userId).floor().asQuestion();
            return Optional.of(fromSpendingQuestion(
                    "least-for-enjoying-life", "PUT /api/v1/profile leastForEnjoyingLife", asked));
        } catch (NeedsMoreInformationException notYet) {
            // Cannot be suggested until income is known; the income question above covers that.
            return Optional.empty();
        }
    }

    private List<Question> spending(String userId, PlanningProfile profile) {
        Map<SpendCategory, ResolvedBaseline> baselines = places.baselinesFor(profile);
        Map<SpendCategory, ?> stated = plans.spending(userId);
        String where = plans.cityOf(profile).map(Places.City::name).orElse(profile.cityNotListed());
        List<Question> questions = new ArrayList<>();
        for (SpendCategory category : SpendCategory.values()) {
            ResolvedBaseline figure = baselines.get(category);
            if (figure == null || stated.containsKey(category) || category == SpendCategory.TAX_RESERVE) {
                continue;
            }
            SpendingQuestion asked = new SpendingQuestion(
                    "About how much do you spend on " + category.label().toLowerCase(Locale.ENGLISH) + " each month?",
                    "If you leave this, we assume the typical figure for " + where
                            + ". Your own number makes the plan fit your life rather than an average one.",
                    List.of(category),
                    figure.amount(),
                    figure.confidence().meaning() + " Gathered " + figure.asOf().format(MONTH_AND_YEAR) + ".");
            questions.add(fromSpendingQuestion(
                    "spending:" + Keys.of(category), "PUT /api/v1/spending " + Keys.of(category), asked));
        }
        return questions;
    }

    private static Question fromSpendingQuestion(String key, String answerWith, SpendingQuestion asked) {
        return new Question(
                key,
                answerWith,
                asked.question(),
                asked.why(),
                asked.explainedCoverage(),
                asked.suggested().toString(),
                asked.basis(),
                List.of());
    }
}
