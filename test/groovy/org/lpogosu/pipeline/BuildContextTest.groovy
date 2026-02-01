package org.lpogosu.pipeline

import static org.assertj.core.api.Assertions.assertThat
import static org.assertj.core.api.Assertions.assertThatThrownBy

import org.junit.Test

class BuildContextTest {

    @Test
    void classifiesTheFourKindsOfBuild() {
        assertThat(from(BRANCH_NAME: 'main').type).isEqualTo(BranchType.TRUNK)
        assertThat(from(BRANCH_NAME: 'master').type).isEqualTo(BranchType.TRUNK)
        assertThat(from(BRANCH_NAME: 'feature/x').type).isEqualTo(BranchType.FEATURE)
        assertThat(from(BRANCH_NAME: 'PR-42', CHANGE_ID: '42').type).isEqualTo(BranchType.PULL_REQUEST)
        assertThat(from(BRANCH_NAME: 'v1.4.2', TAG_NAME: 'v1.4.2').type).isEqualTo(BranchType.RELEASE_TAG)
    }

    @Test
    void aChangeRequestIsAChangeRequestWhateverItsBranchIsCalled() {
        // Jenkins sets BRANCH_NAME on a change request too, and for one opened from a fork's
        // default branch it can be literally 'main'. Reading the branch first would publish
        // a fork's code to the registry and roll it out to staging.
        BuildContext context = from(BRANCH_NAME: 'main', CHANGE_ID: '42', CHANGE_TARGET: 'main')

        assertThat(context.type).isEqualTo(BranchType.PULL_REQUEST)
        assertThat(context.publishing).isFalse()
        assertThat(context.buildingImage).isTrue()
    }

    @Test
    void aTagThatIsNotAReleaseGetsTheTopicBranchTreatment() {
        // Annotated tags get pushed for all sorts of reasons; failing the build for one, or
        // treating it as a release, are both worse than building and publishing nothing.
        BuildContext context = from(BRANCH_NAME: 'nightly-2026-02-14', TAG_NAME: 'nightly-2026-02-14')

        assertThat(context.type).isEqualTo(BranchType.FEATURE)
        assertThat(context.promotion).isFalse()
        assertThat(context.buildingImage).isFalse()
    }

    @Test
    void aBranchCalledMainOnATagBuildIsStillATagBuild() {
        // Jenkins sets BRANCH_NAME to the tag on a tag build, but a repository can also have
        // a branch and a tag with the same name.
        assertThat(from(BRANCH_NAME: 'main', TAG_NAME: 'weekly').type).isEqualTo(BranchType.FEATURE)
    }

    @Test
    void onlyTrunkPublishesAndOnlyAReleaseTagPromotes() {
        assertThat(from(BRANCH_NAME: 'main').publishing).isTrue()
        assertThat(from(BRANCH_NAME: 'feature/x').publishing).isFalse()
        assertThat(from(BRANCH_NAME: 'v1.4.2', TAG_NAME: 'v1.4.2').promotion).isTrue()
        assertThat(from(BRANCH_NAME: 'main').promotion).isFalse()
    }

    @Test
    void aPlainPipelineJobIsToldWhatIsMissingAndWhatToDoAboutIt() {
        assertThatThrownBy { BuildContext.fromEnvironment([BUILD_NUMBER: '1']) }
            .isInstanceOf(ConfigurationException)
            .hasMessageContaining('BRANCH_NAME is not set')
            .hasMessageContaining('multibranch pipeline')

        assertThatThrownBy { BuildContext.fromEnvironment([BRANCH_NAME: 'main']) }
            .isInstanceOf(ConfigurationException)
            .hasMessageContaining('BUILD_NUMBER is not set')
    }

    @Test
    void theShortCommitIsOnlyAvailableOnceTheCheckoutHasHappened() {
        BuildContext context = from(BRANCH_NAME: 'main')
        assertThat(context.shortCommit).isNull()

        context.commit = 'b17a4d0c9e2f5813a6c04d7e9b25f1c8a3d60e47'
        assertThat(context.shortCommit).isEqualTo('b17a4d0')
    }

    @Test
    void describesItselfInOneLineForTheBuildLog() {
        assertThat(from(BRANCH_NAME: 'PR-42', CHANGE_ID: '42', CHANGE_TARGET: 'main').describe())
            .isEqualTo('change request 42 into main (build 87)')
        assertThat(from(BRANCH_NAME: 'v1.4.2', TAG_NAME: 'v1.4.2').describe())
            .isEqualTo('release tag v1.4.2 (build 87)')
        assertThat(from(BRANCH_NAME: 'main').describe()).isEqualTo('trunk main (build 87)')
        assertThat(from(BRANCH_NAME: 'feature/x').describe()).isEqualTo('topic branch feature/x (build 87)')
    }

    private static BuildContext from(Map environment) {
        return BuildContext.fromEnvironment([BUILD_NUMBER: '87'] + environment)
    }

}
