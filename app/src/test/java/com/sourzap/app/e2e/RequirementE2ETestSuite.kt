package com.sourzap.app.e2e

import org.junit.runner.RunWith
import org.junit.runners.Suite

/**
 * Requirement-Driven E2E Test Suite Runner for SourZap.
 * Aggregates all Tier 1-4 tests covering 100% of requirements from ORIGINAL_REQUEST.md.
 */
@RunWith(Suite::class)
@Suite.SuiteClasses(
    StorageAndMetadataE2ETest::class,
    IntentDeepLinkE2ETest::class,
    NotificationSystemE2ETest::class,
    TorrentEngineLifecycleE2ETest::class,
    Tier1FeatureCoverageTest::class,
    Tier2BoundaryCornerCaseTest::class,
    Tier3PairwiseInteractionsTest::class,
    Tier4RealWorldScenariosTest::class,
    Tier5AdversarialCoverageHardeningTest::class
)
class RequirementE2ETestSuite
