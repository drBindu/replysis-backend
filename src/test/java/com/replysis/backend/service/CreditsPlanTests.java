package com.replysis.backend.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The plan numbers agreed with the owner on 2026-09-29, and the rule that Free is a one-time
 * trial, not a monthly allowance.
 *
 * Free: 5 answers, once (25 credits). Pro: 500 answers a month (2,500 credits). Max: 1,500
 * (7,500 credits). An answer is 5 credits.
 */
class CreditsPlanTests {

    @Test
    void anAnswerCostsFiveCredits() {
        assertEquals(5, FirestoreCreditsService.INTERVIEW_QUESTION_COST);
    }

    @Test
    void theFreeTrialIsFiveAnswers() {
        assertEquals(5, FirestoreCreditsService.FREE_TRIAL_CREDITS / FirestoreCreditsService.INTERVIEW_QUESTION_COST);
    }

    @Test
    void proIsRefilledEveryMonthToFiveHundredAnswers() {
        assertEquals(2_500, FirestoreCreditsService.creditsAfterReset("pro", 0, 0));
        assertEquals(2_500, FirestoreCreditsService.creditsAfterReset("pro", 40, 0), "leftovers do not stack on top");
    }

    @Test
    void maxIsRefilledEveryMonthToFifteenHundredAnswers() {
        assertEquals(7_500, FirestoreCreditsService.creditsAfterReset("max", 0, 0));
    }

    @Test
    void creditPacksSurviveTheMonthlyRefill() {
        assertEquals(2_500 + 500, FirestoreCreditsService.creditsAfterReset("pro", 0, 500));
        assertEquals(7_500 + 5_000, FirestoreCreditsService.creditsAfterReset("max", 0, 5_000));
    }

    @Test
    void freeIsNeverRefilled() {
        assertEquals(0, FirestoreCreditsService.creditsAfterReset("free", 0, 0),
                "a person who used their five answers does not get five more next month");
    }

    @Test
    void anUnusedFreeTrialSurvivesTheMonthEnd() {
        assertEquals(25, FirestoreCreditsService.creditsAfterReset("free", 25, 0),
                "someone who signs up on the 30th must still have their trial on the 2nd");
        assertEquals(10, FirestoreCreditsService.creditsAfterReset("free", 10, 0));
    }

    @Test
    void aFreeUserWhoBoughtAPackKeepsIt() {
        // Their balance already includes the pack; it must not be counted twice or lost.
        assertEquals(525, FirestoreCreditsService.creditsAfterReset("free", 525, 500));
    }

    @Test
    void anUnknownOrMissingPlanIsTreatedAsFreeNotAsPaid() {
        assertEquals(10, FirestoreCreditsService.creditsAfterReset("gold", 10, 0));
        assertEquals(10, FirestoreCreditsService.creditsAfterReset(null, 10, 0));
    }

    @Test
    void aBalanceIsNeverNegative() {
        assertEquals(0, FirestoreCreditsService.creditsAfterReset("free", -5, 0));
    }

    @Test
    void aBrandNewAccountStartsWithTheFreeTrialAndNothingPaid() {
        var fields = FirestoreCreditsService.newAccountFields("abc");
        assertEquals("free", fields.get("plan"));
        assertEquals(25, fields.get("credits"), "five answers, so the first speech key request is never refused for credits");
        assertEquals(0, fields.get("creditsUsed"));
        assertEquals(0, fields.get("purchasedCredits"));
        assertEquals("abc", fields.get("uid"));
        org.junit.jupiter.api.Assertions.assertTrue(fields.containsKey("stripeCustomerId") && fields.get("stripeCustomerId") == null,
                "no billing account until someone buys something");
    }

    // ── Packs. The desktop app charges through this class, and the pack balance was never reduced here.

    @Test
    void spendingWithinThePlanDoesNotTouchAPack() {
        assertEquals(0, FirestoreCreditsService.purchasedPortionOfCharge(100, 5, 2_500));
    }

    @Test
    void spendingPastThePlanComesOutOfThePack() {
        // 2,498 of 2,500 used, a 5 credit answer: 3 from the plan, 2 from the pack.
        assertEquals(3, FirestoreCreditsService.purchasedPortionOfCharge(2_498, 5, 2_500));
        assertEquals(5, FirestoreCreditsService.purchasedPortionOfCharge(2_600, 5, 2_500));
    }

    @Test
    void aRefundGoesBackToThePackFirstWhenThePackWasSpent() {
        assertEquals(5, FirestoreCreditsService.purchasedPortionOfRefund(2_600, 5, 2_500));
        assertEquals(0, FirestoreCreditsService.purchasedPortionOfRefund(100, 5, 2_500));
    }

    @Test
    void aRefundNeverCutsABalanceDownToThePlanSize() {
        // Pro with a 500 credit pack holds 3,000. Refunding one answer used to leave 2,500: the pack vanished.
        assertEquals(3_000, FirestoreCreditsService.balanceAfterRefund(3_000, 5, 2_500, 500, 0));
        assertEquals(3_005, FirestoreCreditsService.balanceAfterRefund(3_000, 5, 2_500, 500, 5));
    }

    @Test
    void aRefundStillCannotMintCreditsPastWhatWasPaidFor() {
        // Nothing spent this month: a refund must not push the balance above plan plus packs.
        assertEquals(2_500, FirestoreCreditsService.balanceAfterRefund(2_500, 5, 2_500, 0, 0));
        assertEquals(2_500, FirestoreCreditsService.balanceAfterRefund(2_498, 5, 2_500, 0, 0));
    }

    @Test
    void aSpentPackIsNotHandedBackAtTheMonthlyRefill() {
        // Bought 500, spent all 500 past the plan: purchased is now 0, so the refill is the plan alone.
        long purchasedAfterSpendingItAll = Math.max(0, 500 - FirestoreCreditsService.purchasedPortionOfCharge(2_500, 500, 2_500));
        assertEquals(0, purchasedAfterSpendingItAll);
        assertEquals(2_500, FirestoreCreditsService.creditsAfterReset("pro", 0, purchasedAfterSpendingItAll));
    }
}
