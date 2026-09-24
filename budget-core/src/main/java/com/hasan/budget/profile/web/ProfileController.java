package com.hasan.budget.profile.web;

import com.hasan.budget.planning.application.Keys;
import com.hasan.budget.planning.application.NotFoundException;
import com.hasan.budget.planning.application.PlanService;
import com.hasan.budget.planning.application.PlanService.ProfileChange;
import com.hasan.budget.planning.application.PlanView.KeyLabel;
import com.hasan.budget.planning.application.PlanningProfile;
import com.hasan.budget.planning.application.Places;
import com.hasan.budget.planning.application.QuestionService;
import com.hasan.budget.planning.application.StatedMoney;
import com.hasan.budget.profile.domain.LifestyleTier;
import com.hasan.budget.shared.CountryCode;
import com.hasan.budget.shared.MetroId;
import com.hasan.budget.shared.Money;
import com.hasan.budget.web.CurrentUser;
import com.hasan.budget.web.NameLength;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Who the user is, what money the plan may work with, and what we still have to ask them.
 *
 * <p>The city is chosen from the catalogue's list and sent back as its id, or - when their city is not
 * listed - typed, and then only ever shown back to them. A typed name never selects a city, which is
 * what keeps a near-match from quietly planning someone's life against the wrong city's prices.
 */
@RestController
@RequestMapping("/api/v1")
class ProfileController {

    private final PlanService plans;
    private final QuestionService questions;

    ProfileController(PlanService plans, QuestionService questions) {
        this.plans = plans;
        this.questions = questions;
    }

    /**
     * @param city the id of a listed city, or null when the user's city is not listed
     * @param cityNotListed what they call their city in that case. Display only.
     * @param leastForEnjoyingLife their own answer to the enjoying-life question, or null to leave it
     *     to the suggested figure
     */
    record ProfileRequest(
            String country,
            String city,
            String cityNotListed,
            String lifestyle,
            Boolean incomeArrivesTaxed,
            BigDecimal leastForEnjoyingLife) {}

    record CountryView(String code, String name) {}

    record CityView(String id, String name, String basis, String explanation, LocalDate gathered) {}

    record ProfileView(
            CountryView country,
            CityView city,
            String cityNotListed,
            KeyLabel lifestyle,
            boolean incomeArrivesTaxed,
            String leastForEnjoyingLife) {}

    record MoneyRequest(BigDecimal monthlyIncome, BigDecimal balance, BigDecimal alreadySaving) {}

    record MoneyView(
            String monthlyIncome,
            String balance,
            String setAside,
            String explanation,
            String alreadySaving,
            String alreadySavingExplanation) {}

    @GetMapping("/profile")
    ProfileView profile(@CurrentUser String userId) {
        return view(plans.profile(userId)
                .orElseThrow(() -> new NotFoundException("You have not told us where you live yet.")));
    }

    @PutMapping("/profile")
    ProfileView saveProfile(@CurrentUser String userId, @RequestBody ProfileRequest request) {
        if (request.country() == null || request.country().isBlank()) {
            throw new IllegalArgumentException("Choose the country you live in.");
        }
        if (request.incomeArrivesTaxed() == null) {
            throw new IllegalArgumentException("Tell us whether your pay arrives with tax already taken off.");
        }
        NameLength.check(request.cityNotListed(), "your city's name");
        return view(plans.saveProfile(userId, new ProfileChange(
                new CountryCode(request.country()),
                request.city() == null || request.city().isBlank() ? null : new MetroId(request.city().strip()),
                request.cityNotListed(),
                request.lifestyle() == null ? null : Keys.parse(LifestyleTier.class, request.lifestyle(), "way of living"),
                request.incomeArrivesTaxed(),
                money(request.leastForEnjoyingLife()))));
    }

    @GetMapping("/money")
    MoneyView money(@CurrentUser String userId) {
        return view(plans.money(userId)
                .orElseThrow(() -> new NotFoundException("You have not told us what money to plan with yet.")));
    }

    /**
     * The whole figure is taken as given: there is no share question on this path, because the user
     * already chose what is in scope by choosing what to type. Lowering it is how money comes back out.
     */
    @PutMapping("/money")
    MoneyView saveMoney(@CurrentUser String userId, @RequestBody MoneyRequest request) {
        if (request.monthlyIncome() == null || request.balance() == null) {
            throw new IllegalArgumentException(
                    "Tell us both what arrives each month and how much you already have. Enter 0 if there is none.");
        }
        // Optional, and left out is not the same as zero: left out lets a connected bank supply the
        // figure, where zero is the user telling us they save nothing.
        return view(plans.saveMoney(userId, new StatedMoney(
                new Money(request.monthlyIncome()),
                new Money(request.balance()),
                money(request.alreadySaving()))));
    }

    @GetMapping("/questions")
    List<QuestionService.Question> questions(@CurrentUser String userId) {
        return questions.stillToAsk(userId);
    }

    private ProfileView view(PlanningProfile profile) {
        Places.City city = plans.cityOf(profile).orElse(null);
        return new ProfileView(
                new CountryView(
                        profile.country().value(),
                        plans.countryNameOf(profile).orElse(profile.country().value())),
                city == null
                        ? null
                        : new CityView(city.id().slug(), city.name(), city.basis(), city.explanation(), city.gathered()),
                profile.cityNotListed(),
                profile.lifestyle() == null
                        ? null
                        : new KeyLabel(Keys.of(profile.lifestyle()), profile.lifestyle().label()),
                profile.incomeArrivesTaxed(),
                profile.leastForEnjoyingLife() == null ? null : profile.leastForEnjoyingLife().toString());
    }

    private static MoneyView view(StatedMoney money) {
        return new MoneyView(
                money.monthlyIncome().toString(),
                money.balance().toString(),
                Money.ZERO.toString(),
                "We plan with exactly what you told us you have. Nothing is set aside, because nothing "
                        + "beyond this figure was ever visible to us.",
                money.alreadySaving() == null ? null : money.alreadySaving().toString(),
                "What you already move into savings every month. We show it beside your plan and never "
                        + "take it off what you have left, because putting money away is not spending it. "
                        + "Leave it empty and, once a bank is connected, we read it from there.");
    }

    private static Money money(BigDecimal amount) {
        return amount == null ? null : new Money(amount);
    }
}
