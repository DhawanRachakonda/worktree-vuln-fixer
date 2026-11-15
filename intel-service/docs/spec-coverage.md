# Spec coverage

Every scenario of `openspec/changes/intel-service/specs/vulnerability-intel/spec.md` and the test (or recorded manual
check) that proves it. Walked on 2026-11-10 after the last task; all 36 scenarios are covered.

Test classes are in `src/test/java/dev/intel/`. "Live check" means a run of the real service against the real feed on
2026-11-10 (see tasks 5.4, 6.4, 9.1 and 9.2), recorded here because it cannot be repeated deterministically.

## Daily feed ingestion

| Scenario | Proven by |
|---|---|
| Scheduled run stores new items | `ingest.IngestionServiceTest#theFirstRunStoresEveryItemAndRecordsTheRunAndTheValidators`; end to end with the background server: `ingest.ManualTriggerTest#triggerNowOnTheDashboardRunsTheIngestionAndRecordsARunLikeAScheduledOne`; live check: 8 new items from the real feed |
| Feed unchanged since last run | `IngestionServiceTest#anUnchangedFeedChangesNothingAndIsStillASuccessfulRun`, `#aNotModifiedAnswerChangesNothingAndKeepsTheValidators`; live check: second run `NO_CHANGE - seen 8, unchanged 8` |
| Fetch fails | `IngestionServiceTest#aFetchFailureIsRecordedAndNothingIsStored`, `#aRefusedDestinationIsRecordedAsRefused`, `#anInvalidDocumentIsRecordedAndStoredDataIsUntouched`, `#aFailureInTheMiddleOfTheWritesLeavesStoredDataExactlyAsItWas`; the retry: `ManualTriggerTest#aFailedRunIsRetriedByJobRunrAndTheRetrySucceeds`, `#aFailedRunLeavesTheJobFailedWhenItHasNoRetries` |

## Catch-up after downtime

| Scenario | Proven by |
|---|---|
| First start with an empty store | `ingest.IngestionSchedulingTest#anEmptyStoreNeedsOneCatchUpAndAskingAgainInTheSameHourDoesNotAddAnother`, `ingest.IngestionStartupTest#startingOnAnEmptyDatabaseRegistersTheDailyJobAndEnqueuesOneCatchUp`; live check: catch-up ingested 8 items two seconds after start |
| Missed scheduled run | `IngestionSchedulingTest#aSuccessOlderThanTheThresholdNeedsACatchUp`, `#theThresholdIsExclusive`, `#historyWithOnlyFailuresStillNeedsACatchUp` |
| Recent restart | `IngestionSchedulingTest#aRecentSuccessNeedsNoCatchUp`, `ingest.IngestionRestartTest#startingTwiceOnTheSameDataDirectoryLeavesOneScheduleAndOneCatchUp`; live check: the restart in 9.2 enqueued no catch-up and ran no ingestion |
| Interrupted run repeats | `IngestionServiceTest#anInterruptedRunThatIsRepeatedLeavesTheSameRecordsAsOneCompleteRun` |

## Record identity and change handling

| Scenario | Proven by |
|---|---|
| Duplicate item ignored | `store.VulnerabilityRepositoryTest#unchangedDuplicateLeavesTheRecordUntouched`; `IngestionServiceTest#anUnchangedFeedChangesNothingAndIsStillASuccessfulRun` |
| Corrected advisory | `VulnerabilityRepositoryTest#correctedItemKeepsFirstSeenPublishedAndSortPosition`; `IngestionServiceTest#correctedItemsAreUpdatedAndTheRestLeftAlone` |

## Extracted and preserved record content

| Scenario | Proven by |
|---|---|
| Item with CVE identifiers and sections | `feed.FeedItemNormalizerTest#anItemWithCveIdsAndSectionsKeepsThemAsFields`, `#cveIdsAreSortedUniqueAndComeFromCategoriesAndText`; `feed.RawPayloadTest#everyRealItemIsKeptByteForByteAsReceived` |
| Item without CVE identifiers | `FeedItemNormalizerTest#anItemWithoutCveIdsIsStillNormalized` (a real item of the feed: the eMudhra briefing) |

## Safe handling of feed content

| Scenario | Proven by |
|---|---|
| Non-HTTPS or foreign origin | `feed.FeedFetcherTest#anHttpFeedUrlIsRefusedWithoutAnyRequest`, `#aRedirectToHttpIsRefused`, `#aRedirectToAForeignHostIsRefused`, `#aRedirectToAnAllowedHostThatResolvesToAPrivateAddressIsRefused`; `feed.DestinationValidatorTest`, `feed.AddressPolicyTest`; recorded as refused: `IngestionServiceTest#aRefusedDestinationIsRecordedAsRefused` |
| Oversized response | `FeedFetcherTest#aResponseDeclaredLargerThanTheLimitFailsWithoutReadingIt`, `#aStreamedResponseLargerThanTheLimitFails`; nothing stored: `IngestionServiceTest#aFetchFailureIsRecordedAndNothingIsStored` |
| Feed text contains instructions | `feed.HostileItemStorageTest#aHostileItemIsStoredAsInertDataAndNothingIsFetchedBecauseOfIt`; `FeedItemNormalizerTest#textAddressedToAnAgentIsStoredVerbatimAsData`; `feed.SectionSplitterTest#hostileInstructionsAreKeptAsPlainTextData`; logs: `IngestionServiceTest#aHostileFeedLeavesNoFeedTextInTheLogs` |

Also covered, though not a named scenario: documents declaring a DOCTYPE or external entities are rejected in every
encoding (`feed.FeedDocumentReaderTest#anExternalEntityIsRejectedAndTheFileIsNeverRead`,
`#aDoctypeHiddenInAnotherEncodingIsStillRejected`, `#anEntityExpansionBombIsRejected`), as are over-deep and over-large
documents (`#tooDeepNestingIsRejected`, `#tooManyItemsAreRejected`).

## List vulnerabilities tool

| Scenario | Proven by |
|---|---|
| Recent window | `query.VulnerabilityQueryServiceTest#recentMatchesPublishedOrLastReviewedTimeInsideTheWindow`, `#anOldItemThatWasReviewedRecentlyCountsAsRecent`; `mcp.ListVulnerabilitiesToolTest#recentWithAWindowFiltersByPublishedOrReviewedTime`; live check with the Claude CLI: 7 of 8 items for 30 days |
| All records | `VulnerabilityQueryServiceTest#allReturnsEveryRecordNewestFirstWithItsFirstSeenTime`; `ListVulnerabilitiesToolTest#returnsTheDocumentedShapeForTheWholeStore`; live check: the tool's 8 guids and 10 CVE ids are identical to a plain `grep` of the live feed |
| CVE filter | `VulnerabilityQueryServiceTest#cveFilterIsExactAndCaseInsensitiveInput`; `ListVulnerabilitiesToolTest#filtersByCveAndProduct`; live check with the Claude CLI |
| Product or tag filter | `VulnerabilityQueryServiceTest#productFilterMatchesTagsAndTextIgnoringCase`; `ListVulnerabilitiesToolTest#filtersByCveAndProduct`; `store.VulnerabilityRepositoryTest` (escaping of `%` and `_`) |
| Invalid arguments | `VulnerabilityQueryServiceTest#anUnknownScopeIsRejectedNamingTheArgument`, `#recentWithoutALookBackIsRejected`, `#aLookBackOutsideOneToThreeHundredSixtyFiveIsRejected`, `#aMalformedCveIdIsRejected`; `ListVulnerabilitiesToolTest#invalidArgumentsComeBackAsToolErrorsNamingTheArgumentAndReturningNoRecords`; live check with the Claude CLI quoting the error |

## Bounded, paginated results

| Scenario | Proven by |
|---|---|
| More records than a page | `VulnerabilityQueryServiceTest#aCursorContinuesTheSameQueryWithoutSkippingOrRepeating`, `#aRecentWindowStaysFixedAcrossPagesEvenWhenTimePasses`, `#aCursorSurvivesAnItemBeingCorrectedBetweenPages`; `ListVulnerabilitiesToolTest#pagesThroughEverythingWithTheCursor` |
| Page size above the maximum | `VulnerabilityQueryServiceTest#thePageSizeDefaultsToTwentyAndIsCappedAtFifty` (60 records, request 500, 50 returned) |

## Data freshness is reported

| Scenario | Proven by |
|---|---|
| Fresh data and no match | `VulnerabilityQueryServiceTest#freshDataWithNoMatchIsAnEmptyAnswerThatSaysWhenTheFeedWasLastRead`; live check: empty answer with `status OK` |
| Stale store | `VulnerabilityQueryServiceTest#dataOlderThanTheThresholdIsReportedStaleAlongsideTheRecords`, `#theStalenessThresholdIsExclusive`; `ingest.IngestionHealthIndicatorTest#dataOlderThanTheThresholdIsDown` |
| Never ingested | `VulnerabilityQueryServiceTest#neverIngestedIsNotPresentedAsAnEmptyCleanStore`; `IngestionHealthIndicatorTest#neverIngestedIsDownAndSaysSo` |

## Read-only access over HTTP

| Scenario | Proven by |
|---|---|
| Authorized client | `security.McpAccessTokenTest#aClientWithTheTokenSeesTheToolAndGetsRecords`; `ListVulnerabilitiesToolTest#offersExactlyOneReadOnlyToolAndNothingElse`, `#aToolThatWouldChangeDataDoesNotExist`; live checks with the Claude CLI, with and without a token |
| Unauthorized client | `McpAccessTokenTest#aMissingTokenIsRejectedBeforeAnyToolCodeRuns`, `#wrongOrMalformedCredentialsAreRejectedAndReturnNoRecords`, `#theTokenInTheQueryStringIsNotAccepted`, `#everyMethodOnTheEndpointIsProtected`; a disallowed address: `security.AccessGuardTest#byDefaultTheServiceListensOnTheLoopbackAddressOnly`, `#aNonLoopbackBindWithoutATokenStopsStartupAndNothingListens`; live check: 401 without or with a wrong token |

## Protected operations dashboard

| Scenario | Proven by |
|---|---|
| Off by default | `ingest.DashboardAccessTest#theDashboardIsNotServedUnlessEnabled`; `config.DashboardSettingsTest#theDashboardIsOffByDefaultAndNeedsNoCredentialsThen`; `security.SurfaceIndependenceTest#aTokenOnMcpDoesNotSwitchTheDashboardOn` |
| Enabled with credentials | `DashboardAccessTest#requestsWithoutOrWithWrongCredentialsAreRejectedAndRightOnesSeeTheJobs` (recurring job with its cron and `nextRun`, job lists by state); `ManualTriggerTest#triggerNowOnTheDashboardRunsTheIngestionAndRecordsARunLikeAScheduledOne`; live check: dashboard API listed the jobs, their log lines and the servers |
| Enabled without credentials | `DashboardAccessTest#enablingTheDashboardWithoutCredentialsStopsStartup`; `DashboardSettingsTest#enabledWithoutAUsernameStopsStartupNamingTheSetting`, `#enabledWithoutAPasswordStopsStartupNamingTheSetting` |
| Weak password | `DashboardAccessTest#aWeakPasswordStopsStartupWithoutEchoingIt`; `DashboardSettingsTest#aWeakPasswordStopsStartupWithoutEchoingIt`, `#thePasswordBoundaryIsExactlyTwelveCharacters` |
| Request without valid credentials | `DashboardAccessTest#requestsWithoutOrWithWrongCredentialsAreRejectedAndRightOnesSeeTheJobs`; `ManualTriggerTest#triggerNowWithoutCredentialsIsRejectedAndRunsNothing`; `SurfaceIndependenceTest#neitherSecretOpensTheOtherSurface` |
| Credentials stay out of the logs | `DashboardAccessTest#thePasswordNeverAppearsInTheLogs`; `McpAccessTokenTest#theTokenNeverAppearsInTheLogs`; live check: the service log of the token run contained neither token |
