package org.lpogosu.pipeline

import static org.assertj.core.api.Assertions.assertThat
import static org.assertj.core.api.Assertions.assertThatThrownBy

import org.junit.Test

class StepPolicyTest {

    @Test
    void everyKnownStepHasATimeoutAndAnAttemptCount() {
        StepPolicy.KNOWN_STEPS.each { String step ->
            StepPolicy policy = StepPolicy.forStep(step)
            assertThat(policy.timeoutMinutes).as(step).isGreaterThan(0)
            assertThat(policy.attempts).as(step).isGreaterThanOrEqualTo(1)
        }
    }

    @Test
    void onlyCheckoutAndTheImageStepRetryByDefault() {
        // Retrying a failing test suite is how a flaky test becomes permanent: the pipeline
        // stops reporting it and nobody is asked to fix it.
        assertThat(StepPolicy.forStep('checkout').attempts).isEqualTo(3)
        assertThat(StepPolicy.forStep('image').attempts).isEqualTo(2)
        assertThat(StepPolicy.forStep('test').attempts).isEqualTo(1)
        assertThat(StepPolicy.forStep('build').attempts).isEqualTo(1)
    }

    @Test
    void overridesReplaceOnlyWhatTheyMention() {
        StepPolicy policy = StepPolicy.forStep('build', [timeoutMinutes: 45])

        assertThat(policy.timeoutMinutes).isEqualTo(45)
        assertThat(policy.attempts).isEqualTo(1)
    }

    @Test
    void aStepThatMustNotBeRetriedRefusesARetryCountHoweverItIsReached() {
        StepPolicy.AT_MOST_ONCE.each { String step ->
            assertThatThrownBy { StepPolicy.forStep(step, [attempts: 2]) }
                .as(step)
                .isInstanceOf(IllegalArgumentException)
                .hasMessageContaining('must not be retried')
        }
    }

    @Test
    void theApprovalGetsHoursAndTheRolloutGetsMinutes() {
        // A human is allowed to be at lunch; a rollout that has not converged in fifteen
        // minutes is not going to.
        assertThat(StepPolicy.forStep('approval').timeoutMinutes).isEqualTo(720)
        assertThat(StepPolicy.forStep('deploy').timeoutMinutes).isEqualTo(15)
    }

    @Test
    void anUnknownStepIsNamedAlongsideTheOnesThatExist() {
        assertThatThrownBy { StepPolicy.forStep('compile') }
            .isInstanceOf(IllegalArgumentException)
            .hasMessageContaining("unknown step 'compile'")
            .hasMessageContaining('analysis, approval, build, checkout')
    }

    @Test
    void validationRejectsValuesThatAreNotWholeNumbersInRange() {
        assertThat(StepPolicy.problemsWith('build', [attempts: 0])).hasSize(1)
        assertThat(StepPolicy.problemsWith('build', [attempts: 6])).hasSize(1)
        assertThat(StepPolicy.problemsWith('build', [attempts: '2'])).hasSize(1)
        assertThat(StepPolicy.problemsWith('build', [timeoutMinutes: 1000])).hasSize(1)
        assertThat(StepPolicy.problemsWith('build', [attempts: 2, timeoutMinutes: 45])).isEmpty()
    }

    @Test
    void validationReportsAnUnknownStepWithoutAlsoComplainingAboutItsContents() {
        List<String> problems = StepPolicy.problemsWith('compile', [attempts: 99, nonsense: true])

        assertThat(problems).hasSize(1)
        assertThat(problems.first()).contains('steps.compile: unknown step')
    }

}
