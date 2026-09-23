package com.hasan.budget.ingestion.plaid;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.hasan.budget.ingestion.domain.Classification;
import com.hasan.budget.shared.SpendCategory;
import com.hasan.budget.shared.TransactionKind;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The committed mapping table, checked for the decisions in it that would be expensive to get wrong.
 *
 * <p>Most of the table is unremarkable and needs no test. These rows are not: each is a case where
 * the obvious mapping loses money, and each was settled by looking at what the sandbox actually
 * sends rather than at the documentation.
 */
class PfcMappingTest {

    private final PfcMapping mapping = PfcMapping.fromClasspath();

    @Test
    @DisplayName("paying a credit-card bill is a transfer, not spending")
    void theDoubleCountRow() {
        // Groceries bought on the card were counted when they were bought. Counting the bill too
        // subtracts $600 for $300 of food.
        assertThat(mapping.classify("LOAN_PAYMENTS_CREDIT_CARD_PAYMENT"))
                .contains(Classification.notSpending(TransactionKind.TRANSFER_INTERNAL));
    }

    @Test
    @DisplayName("a loan instalment is spending; it leaves for a lender and never comes back")
    void debtPaymentsAreRealOutflows() {
        assertThat(mapping.classify("LOAN_PAYMENTS_CAR_PAYMENT"))
                .contains(Classification.spend(SpendCategory.DEBT_PAYMENT));
        assertThat(mapping.classify("LOAN_PAYMENTS_STUDENT_LOAN_PAYMENT"))
                .contains(Classification.spend(SpendCategory.DEBT_PAYMENT));
    }

    @Test
    @DisplayName("borrowed money is not income")
    void disbursementsAreNeitherSpendingNorEarnings() {
        assertThat(mapping.classify("LOAN_DISBURSEMENTS_CASH_ADVANCES"))
                .contains(Classification.notSpending(TransactionKind.TRANSFER_EXTERNAL));
    }

    @Test
    @DisplayName("moving money to savings is not spending")
    void savingsTransfersAreInternal() {
        assertThat(mapping.classify("TRANSFER_OUT_SAVINGS"))
                .contains(Classification.notSpending(TransactionKind.TRANSFER_INTERNAL));
    }

    @Test
    @DisplayName("the weekly shop is capped against the city; eating out is not")
    void foodIsTwoDifferentThings() {
        assertThat(mapping.classify("FOOD_AND_DRINK_GROCERIES"))
                .contains(Classification.spend(SpendCategory.GROCERIES));
        assertThat(mapping.classify("FOOD_AND_DRINK_RESTAURANT"))
                .contains(Classification.spend(SpendCategory.DINING_OUT));
        assertThat(mapping.classify("FOOD_AND_DRINK_COFFEE"))
                .contains(Classification.spend(SpendCategory.DINING_OUT));
    }

    @Test
    @DisplayName("a gym arrives as personal care and is a membership all the same")
    void theRowNobodyWouldHavePredicted() {
        assertThat(mapping.classify("PERSONAL_CARE_GYMS_AND_FITNESS_CENTERS"))
                .contains(Classification.spend(SpendCategory.SUBSCRIPTIONS));
    }

    @Test
    @DisplayName("a mortgage payment is the roof, like rent, and is never capped")
    void mortgagesSitWithRent() {
        assertThat(mapping.classify("LOAN_PAYMENTS_MORTGAGE_PAYMENT"))
                .contains(Classification.spend(SpendCategory.RENT));
    }

    @Test
    @DisplayName("utilities are covered even though the sandbox never sent one")
    void theCategoryRealUsersWouldHaveHitImmediately() {
        // Absent from the fixtures, which is exactly why it is in the table: without the row a real
        // electricity bill would land in Everything else and be treated as discretionary.
        assertThat(mapping.classify("RENT_AND_UTILITIES_GAS_AND_ELECTRICITY"))
                .contains(Classification.spend(SpendCategory.UTILITIES));
        assertThat(mapping.classify("RENT_AND_UTILITIES_WATER"))
                .contains(Classification.spend(SpendCategory.UTILITIES));
    }

    @Test
    @DisplayName("salary under both taxonomy versions is income")
    void bothNamesForPayAreMapped() {
        // v2 renamed this, and an item still on v1 must not have its pay treated as unknown.
        assertThat(mapping.classify("INCOME_SALARY"))
                .contains(Classification.notSpending(TransactionKind.INCOME));
        assertThat(mapping.classify("INCOME_WAGES"))
                .contains(Classification.notSpending(TransactionKind.INCOME));
    }

    @Test
    @DisplayName("a value the table has never heard of is reported as unknown rather than guessed")
    void anUnknownValueIsEmpty() {
        assertThat(mapping.classify("SOMETHING_PLAID_ADDED_LAST_TUESDAY")).isEmpty();
        assertThat(mapping.classify("")).isEmpty();
    }

    @Test
    @DisplayName("the table is big enough to be worth having")
    void theTableCoversTheTaxonomyBroadly() {
        assertThat(mapping.knownCategories()).hasSizeGreaterThan(60);
    }

    @Test
    @DisplayName("comments, blank lines and reasons containing commas are all read correctly")
    void theFileFormatToleratesBeingReadable() {
        PfcMapping parsed = parse("""
                # a comment
                pfc_detailed,kind,category,source,why

                FOOD_AND_DRINK_GROCERIES,SPEND,GROCERIES,observed,the weekly shop, which has commas in it
                TRANSFER_OUT_SAVINGS,TRANSFER_INTERNAL,,observed,
                """);

        assertThat(parsed.classify("FOOD_AND_DRINK_GROCERIES"))
                .contains(Classification.spend(SpendCategory.GROCERIES));
        assertThat(parsed.classify("TRANSFER_OUT_SAVINGS"))
                .contains(Classification.notSpending(TransactionKind.TRANSFER_INTERNAL));
    }

    @Test
    @DisplayName("a malformed table fails on load, before anybody connects a bank")
    void badRowsAreCaughtImmediately() {
        assertThatThrownBy(() -> parse(header() + "FOOD_AND_DRINK_GROCERIES,SPEND,,observed,\n"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("SPEND row needs a category");

        assertThatThrownBy(() -> parse(header() + "TRANSFER_OUT_SAVINGS,TRANSFER_INTERNAL,GROCERIES,observed,\n"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("only SPEND rows may carry a category");

        assertThatThrownBy(() -> parse(header()
                        + "FOOD_AND_DRINK_COFFEE,SPEND,DINING_OUT,observed,\n"
                        + "FOOD_AND_DRINK_COFFEE,SPEND,GROCERIES,observed,\n"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("appears twice");

        assertThatThrownBy(() -> parse(header() + "FOOD_AND_DRINK_COFFEE,NOT_A_KIND,,observed,\n"))
                .isInstanceOf(IllegalArgumentException.class);

        assertThatThrownBy(() -> parse(header())).isInstanceOf(IllegalStateException.class);
    }

    private static String header() {
        return "pfc_detailed,kind,category,source,why\n";
    }

    private static PfcMapping parse(String table) {
        return PfcMapping.parse(new java.io.StringReader(table));
    }
}
