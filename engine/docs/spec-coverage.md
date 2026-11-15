# Spec coverage

Every scenario of `openspec/changes/subscriber-engine/specs/issue-fix-subscriber/spec.md` (46 in all), with what proves it. Test
names are `Class.method` under `src/test/java/dev/subscriber/`. **Run** means the run recorded in [e2e-run.md](e2e-run.md) (real
NATS server, real engine, real windows and the Node publisher of that time, with a stand-in for the Copilot CLI). **Console run** means a
scripted terminal session of the real engine (a pseudo-terminal, described in [operations.md](operations.md)). Where the only
proof is a stand-in and the scenario is about the real Copilot CLI, the row said **pending 9.2** until the manual acceptance with the real CLI and a person at the keyboard
(done 2026-11-12, see e2e-run.md).

Marks: **Proven** = automated test or recorded run. **Proven, partly** = proven with the part that is stated. **Gap** = not proven.

## Issue-report messages

| Scenario | Proof |
|---|---|
| Audit report accepted | Proven. `IntakeCheckerTest.anAuditReportInsideAnAllowedRootIsAcceptedAndTheRepositoryIsDerived`, `IntakeServiceTest.anAcceptedMessageBecomesAQueuedSessionWithItsFirstEventAndNothingStarted`; Run (published audit CSV for `alpha`, session recorded and run). |
| Unsupported subject | Proven. `IntakeServiceTest.aMessageOnAnotherSubjectIsIgnoredAndRecordsNothing`; `NatsLiveTest.messagesOnTheConfiguredSubjectsReachTheIntakeAndOthersDoNot` (live server). |
| Payload that is not a single path | Proven. `IntakeCheckerTest.payloadsThatAreNotASingleExistingRegularFileAreRefused`, `IntakeServiceTest.eachRefusedMessageBecomesARejectedSessionWithItsReasonAndNothingElse`. |
| Report outside the allowed roots | Proven. `IntakeCheckerTest.aReportOutsideEveryAllowedRootIsRefused`, `aPathWithDotDotThatStaysInsideIsAcceptedAndOneThatLeavesIsRefused`, `aLinkInsideTheRootThatLeadsOutsideIsRefused`; `IntakeServiceTest.aReportOutsideTheRootsAndAReportNotAtTheAuditLocationAreRejectedWithTheirReasons`. |
| Repository cannot be determined | Proven. `IntakeCheckerTest.aReportNotAtTheExpectedLocationGivesNoRepository`, `aRepositoryWithoutGitIsRefused`. |
| Report already being worked on | Proven. `IntakeCheckerTest.aReportThatAlreadyHasAQueuedOrRunningSessionIsADuplicateUntilItIsFinished`, `IntakeServiceTest.aSecondMessageForTheSameReportIsRejectedAsADuplicateAndTheFirstStaysQueued`. |

## Agent and repository per subject

| Scenario | Proof |
|---|---|
| Subject routed to its configured agent | Proven. `ContextLoadsTest.everyDocumentedEnvironmentNameReachesItsSetting` (`SUBSCRIBER_AUDIT_AGENT=security-fixer` reaches the audit subject), `IntakeServiceTest` (the session records that agent), `SessionDispatcherTest.eachSessionIsStartedWithItsOwnRecordedAgentAndRepository` (the launcher receives it), `LauncherScriptsTest.theWindowScriptRunsTheAgentInteractivelyAndRecordsHowItEnded` (`--agent security-fixer` in the script). |
| Default agent | Proven. `SubscriberPropertiesTest.aSubjectGetsTheDefaultAgentAndBlankValuesCountAsUnset`; the Run's stand-in logged `--agent appmod-harness`. |

## Interactive session in its own window

| Scenario | Proof |
|---|---|
| A person can type in the session | **Proven (9.2: the person typed `/allow-all` in the real window and the agent went on).** `WindowLauncherTest.aRealWindowOpensShowsTheTitleRunsTheAgentAndClosesWhenItEnds` (opt-in) shows that in the real window the agent's standard input and output are a terminal, not a pipe, which was the defect being replaced. A person typing to the real Copilot CLI in a window opened this way was seen during the earlier spike (the person answered a question and ended it with Ctrl+C); the engine-driven session with the real CLI was seen in task 9.2 (see `e2e-run.md`). |
| Window closes at the end | **Proven (9.2).** `WindowLauncherTest...ClosesWhenItEnds` (the wrapper process is gone when the agent ends), and the Run (four windows opened and ended, each session left `IN_PROGRESS` only while its window ran). With the real CLI (9.2): `/exit` ended the session as `DONE` and the window closed. |
| Path with characters that a shell treats specially | Proven. `LauncherScriptsTest.aHostileRepositoryPathAppearsOnlyInsideQuotedLiterals`, `thePowerShellParserAcceptsEveryPowerShellScriptEvenWithAHostilePath`, `LauncherScriptsGoldenTest`; `WindowLauncherTest` (real window in `r&d 100% ^'x (1) é`); `HeadlessLauncherTest.aRepositoryWithAHostileFolderNameIsEnteredExactlyAndNothingInItRuns`; `PromptWriterTest.thePromptHoldsTheExactPathsEvenWithShellCharactersSpacesAndNonAsciiText`. |
| Session can be reopened | **Proven (9.2).** The session id is the agent's own: `LauncherScriptsTest` and the golden files show `--session-id <id>`; the Run's stand-in logged it. In 9.2 `copilot --resume <id>` resumed the real conversation: the agent listed its earlier fixes. |

## One session at a time

| Scenario | Proof |
|---|---|
| Burst of messages | Proven. `SessionDispatcherTest.aBurstOfThreeRunsOneAtATimeInArrivalOrder`; Run (E1 to E3: three messages, one running, two queued, started in order, no overlap). |
| Same repository with a higher limit | Proven. `SessionDispatcherTest.twoSessionsForOneRepositoryNeverRunTogetherEvenWhenTheLimitAllowsIt`, `theLimitOfTwoRunsTwoAtOnceAndNoMore`. |
| Failure does not stop the queue | Proven. `SessionDispatcherTest.aSessionThatCannotBeStartedBecomesAnErrorAndTheQueueGoesOn`, `aSessionThatEndsWithoutARecordFreesItsSlotAndIsMarkedInterrupted`; Run (E9: the interrupted session did not block). |

## Durable session records

| Scenario | Proof |
|---|---|
| Record before launch | Proven. `IntakeServiceTest.anAcceptedMessageBecomesAQueuedSessionWithItsFirstEventAndNothingStarted`; Run (sessions `QUEUED` before the first window). |
| Events in order | Proven. `FixSessionRepositoryTest.eventsComeBackInTheOrderTheyHappened`, `SessionApiTest.theEventsOfASessionComeInTheOrderTheyHappened`; Run (E7). |
| Records survive a restart | Proven. `DurabilityTest.sessionsAndEventsSurviveARestartOnTheSameDataDirectory`, `MigrationTest`; Run (three restarts). |

## Session states

| Scenario | Proof |
|---|---|
| Launch | Proven. `SessionLifecycleTest.startingRecordsTheProcessAndTheCommitAndWritesTheEvent`, `SessionDispatcherTest.aStartedSessionHasItsRunnerProcessAndStartCommitRecordedAndAnEvent`; Run (head recorded). |

## Final state from the exit and the agent's report

| Scenario | Proof |
|---|---|
| Fix completed | Proven. `SessionOutcomeTest.aCleanExitWithAFreshReportTakesItsStateFromTheReport`, `SessionLifecycleTest.aSessionThatEndedCleanlyWithAFreshSucceededReportIsDoneWithEverythingRecorded`; Run (`DONE`, outcome `succeeded`). |
| Partly fixed | Proven. `SessionOutcomeTest.aCleanExitWithAFreshReportTakesItsStateFromTheReport` (all three statuses). |
| Agent blocked on a decision | Proven. Same test. |
| Ended without a report | Proven. `SessionOutcomeTest.aCleanExitWithoutAUsableReportIsAnErrorNotADone`, `SessionLifecycleTest.aSessionWhoseReportIsOlderThanItsStartIsAnErrorBecauseTheReportIsFromAnEarlierSession`, `AgentReportTest`; a live engine with a stand-in that wrote no report ended `ERROR` "the agent ended without a report" (task 8.2 run). |
| Failure | Proven. `SessionOutcomeTest.aNonZeroExitIsAnErrorWhateverTheReportSaysAndTheReportIsKept`, `aReportThatSaysFailedIsAnError`, `SessionLifecycleTest.aNonZeroExitIsAnErrorWithTheCodeInTheReason`. |
| Window could not be opened | Proven. `SessionLifecycleTest.aLaunchFailureMovesAQueuedSessionDirectlyToErrorWithItsReason`, `SessionDispatcherTest.aSessionThatCannotBeStartedBecomesAnErrorAndTheQueueGoesOn`, `HeadlessLauncherTest.anAgentCommandThatDoesNotExistStillLeavesAnExitRecord`. |

## Recovery after a restart

| Scenario | Proof |
|---|---|
| Queued sessions survive | Proven. `RecoveryTest.queuedSessionsSurviveARestartAndRunInTheirOriginalOrder`; Run (E5). |
| Window still open | Proven. `RecoveryTest.aWindowThatIsStillOpenKeepsItsSessionRunningAndItsRepositoryBusyUntilItEnds`; Run (E4: the engine killed 5 seconds before its window ended, "is still running; following it again", then `DONE`). |
| Window ended while the system was down | Proven. `RecoveryTest.aWindowThatEndedWhileTheEngineWasDownIsFinalizedFromItsExitRecord`; Run (E4, first kill: "ended while the engine was down; finalizing it from its exit record"). |
| Window gone without a record | Proven. `RecoveryTest.aWindowThatIsGoneWithoutARecordMakesItsSessionInterruptedAndTheQueueGoesOn`, `aReusedProcessIdWithAnotherStartTimeIsNotMistakenForTheWindow`; Run (E9). |

## Restricted scope and trusted inputs

| Scenario | Proof |
|---|---|
| No allowed root configured | Proven. `ContextLoadsTest.theApplicationDoesNotStartWithoutAnAllowedRoot`. |
| Credentials stay out of logs | Proven, partly. `NatsMessageSourceTest.credentialsNeverAppearInTheLogsWhateverTheServerDoes`, `SubscriberPropertiesTest.secretsNeverAppearWhenTheSettingsArePrinted`. Session records have no field that could hold a credential (see `data-model.md`), so "nor session records" holds by the schema, not by a test that tries to put one in. Not run against a NATS server that requires credentials: the live server had none. |

## Read-only access to session records

| Scenario | Proof |
|---|---|
| List and filter | Proven. `SessionApiTest.theListCanBeFilteredByStateAndTheStateNameIsCaseInsensitive`, `theListIsNewestFirstWithPagingInformation`, `thePagesDoNotSkipOrRepeat`; Run (E8). |
| One session with its events | Proven. `SessionApiTest.oneSessionShowsItsRecordWithoutTheInternalProcessDetails`, `theEventsOfASessionComeInTheOrderTheyHappened`; Run. |
| Remote bind without a token | Proven. `AccessGuardTest.anythingBeyondThisMachineNeedsAToken`, `theWholeApplicationRefusesToStartOnARemoteBindWithoutAToken`. |
| Missing or wrong token | Proven. `BearerTokenApiTest.aMissingOrWrongTokenIsAnswered401BeforeAnyQuery`; the same tests were shown to fail when the filter is disabled (mutation check). |

## Operator console

| Scenario | Proof |
|---|---|
| List and show | Proven. `ConsoleCommandsTest.sessionsCanBeFilteredByState`, `sessionsListsNewestFirstWithTheTotal`, `sessionShowsOneRecord`, `eventsShowTheTimeline`; Console run. |
| Settings without secrets | Proven. `ConsoleCommandsTest.configShowsTheSettingsButNoSecretValue` (NATS token and access token set; neither appears). The password variant is covered by `DoctorTest.noSecretIsPrinted` and the records' own `toString`, not by a console test with a password set. |
| Readiness check | Proven. `DoctorTest` (every item passing, then each item failing on its own; the other lines still print), `ConsoleCommandsTest.doctorReportsEveryCheckAndPrintsNoSecret`; Console run (all six passed against the real engine, NATS and PATH). |
| Reopen a finished session | **Proven (9.2).** `ReopenServiceTest` (final states, event added, state unchanged), `WindowLauncherTest.aReopenedSessionOpensARealWindowInTheRepositoryAndRecordsNothing` (a real window in a repository with `& % ^ '`, running `--resume <id>`, with a stand-in). With the real CLI (9.2): `reopen <id>` opened the window, the state stayed `DONE`, one `reopened` event was added. |
| Reopen refused | Proven. `ReopenServiceTest` (unknown, queued, running, never started, repository gone, another session active, window failure); `ConsoleCommandsTest.reopenRefusesWhatIsNotAFinishedSessionWithoutOpeningAnything`; mutation check on both rules. |
| No terminal | Proven. `ConsoleModeProcessorTest`, `ConsoleStarterTest`, and every full-start test (none has a terminal and none starts a console); the engine ran and answered health probes with piped input in the Run. |

## Headless runner

| Scenario | Proof |
|---|---|
| Headless session | Proven. `HeadlessLauncherTest.aHeadlessSessionRunsWithoutAWindowAndRecordsACleanExit`, `theShellScriptRunsToo`, `LauncherScriptsTest.theHeadlessScriptsAreNonInteractiveAndKeepATranscript`. |
| Platform without window support | Proven, partly. `SubscriberPropertiesTest.autoPicksAWindowOnWindowsAndHeadlessElsewhereAndAnExplicitChoiceWins`, `LauncherSelectionTest` (a window is refused where none can be opened). Never run on a Linux or macOS machine: only this Windows machine was available. |

## One engine per data directory

| Scenario | Proof |
|---|---|
| Second instance | Proven. `DurabilityTest.aSecondProcessOnTheSameDataDirectoryFailsFastNamingTheDirectory` (a second JVM, exit code non-zero, message names the directory). |

## Publishing audit reports

| Scenario | Proof |
|---|---|
| Publish an audit report | Proven (since 2026-11-15 by the console command `publish`, see "Publishing from the console" below; the Node publisher that proved it before was removed). |
| Missing report | Proven by `ReportPublisherTest.aPathThatCannotBeAReportIsRefusedWithoutConnecting` and `ConsoleCommandsTest.publishRefusesWhatCannotBeAReportAndSaysSoWithoutAStackTrace`; the intake's own rejection test stays. |

## Gaps, stated plainly

1. **The real Copilot CLI inside an engine-driven window** is proven for typing, the window closing and `copilot --resume` of a finished
   session (task 9.2). Not exercised: the agent asking a question and receiving the person's answer (the agent asked none in that run).
2. **Credentials**: exercised against a real `nats-server` (2026-11-14, `NatsAuthLiveTest`, opt-in, 4 tests, each with its own server on a free loopback port): a token
   and a user with password connect and deliver a message to the intake; a wrong token and missing credentials never connect; neither the wrong nor
   the right secret reaches the log. Not covered: credentials in a `.creds` file (needs an operator-mode server) and TLS.
3. **Non-Windows**: the engine's whole test suite was run on Linux (Ubuntu 24.04 under WSL 2, JDK 25, git 2.43; 2026-11-14): 368 tests, 0 failed, 9 skipped
   (the opt-in NATS tests, the Windows-only window tests and a PowerShell check); run again with the Claude Code launcher in place: 397 tests, 0 failed, 12 skipped (the Claude Code
   shell script and the launch tests with a stand-in ran there too). That run found that every test which starts the application failed
   (52 failures): on Linux the `auto` runner is headless, and the headless guard refuses an engine without a permission decision, as designed. The context tests now set
   a mode. It also found a test with a message of the wrong wording that only runs off Windows. Not covered: macOS, a real (not virtualised) Linux machine,
   and the real Copilot CLI on Linux.
4. **The old Node subscriber's tests** are gone with the Node subscriber and publisher (the publisher project was removed on 2026-11-15, see "Publishing from the console").

# Worktrees (change `fix-worktrees`)

Every scenario of `openspec/changes/fix-worktrees/specs/fix-session-worktrees/spec.md` (20 in all). **Run** means the run recorded in
[e2e-worktrees.md](e2e-worktrees.md) (F1 to F10): real NATS, the real engine jar, real git and a stand-in agent that commits.

## A worktree for each fix session

| Scenario | Proof |
|---|---|
| Worktree created before the window opens | Proven. `SessionDispatcherTest.aWorktreeIsCreatedAndRecordedBeforeTheLaunchAndTheAgentWorksInIt` (recorded before the launch, the launcher receives the worktree folder; a mutation that launched in the checkout failed it); `WorktreeServiceTest.theWorktreeIsASiblingOnItsOwnBranchStartedFromTheAuditedCommit`; Run F2, F6. |
| The person's checkout is untouched | Proven. `WorktreeServiceTest.thePersonsCheckoutIsNotSwitchedAndItsFilesAndChangesAreUntouched` (branch, HEAD, index, a staged, an unstaged and an untracked change); Run F3 (identical after three runs, a restart and the cleanup). |
| Worktree outside the allowed roots | Proven. `WorktreeServiceTest.aFolderOutsideTheAllowedRootsIsRefusedAndNothingIsCreated` (a mutation that removed the check failed it); `SessionDispatcherTest.aWorktreeThatCannotBeCreatedEndsTheSessionAsAnErrorAndTheQueueGoesOn` (the next session starts). |
| Isolation turned off | Proven. `SessionDispatcherTest.aSubjectWithIsolationNoneRunsInTheCheckoutAndCreatesNothing`; `SubscriberPropertiesTest.isolationIsAWorktreeUnlessSaidOtherwiseAndASubjectCanOverrideIt`, `anInvalidIsolationStopsTheStartNamingTheSetting`; `ContextLoadsTest` (the environment variable reaches the setting). |
| Worktree cannot be created | Proven. `WorktreeServiceTest` (a folder that exists, a branch that exists, a repository without a commit, and a failure after the folder and branch were made, which leaves no folder, branch or worktree entry; a mutation that skipped the cleanup failed it); Run F1 (a repository without a commit: `ERROR` with the reason, nothing left, the next session started). |

## Base commit of the worktree

| Scenario | Proof |
|---|---|
| Audited commit used | Proven. `ReportCommitTest` (column in any position, quoted, short, upper case, byte order mark, commas and line breaks in other cells); `WorktreeServiceTest.theWorktreeIsASiblingOn...FromTheAuditedCommit` (the worktree held the audited code, not the later change); Run F2, F4. |
| Audit ran on a modified tree | Proven. `ReportCommitTest.theFormTheAuditToolWritesIsReadAndItsDirtyFlagIsReported`, `WorktreeServiceTest.theCommitInTheFormTheAuditToolWritesIsUsedAndADirtyAuditIsSaid` (the commit is still used and the event is written; the labelled form `commit=<id>, dirty=<bool>` is what the audit tool really writes, which was found only when its CSV was read by hand). |
| Audited commit unusable | Proven. `WorktreeServiceTest.anAuditedCommitThatIsNotInTheRepositoryFallsBackToHeadAndSaysWhy`, `aReportWithoutACommitOrThatIsNotACsvStartsFromHeadAndSaysWhy`; `ReportCommitTest` (no column, no rows, empty, not a commit id, not a CSV, larger than the limit); `SessionLifecycleTest.theWorktreeIsRecordedWithOneEventAndOneForEachNote` (the event is written). |

## The worktree is recorded with the session

| Scenario | Proof |
|---|---|
| Recorded at creation | Proven. `SessionLifecycleTest.theWorktreeIsRecordedWithOneEventAndOneForEachNote`, `aWorktreeCanOnlyBeRecordedForAQueuedSession`; `FixSessionRepositoryTest.aStartedSessionKeepsItsWorktreeAndTheCountIsWrittenOnlyWithTheFinalState`; Run F4, F5. |
| Commit count at the end | Proven. `SessionLifecycleTest.aReportInTheWorktreeCountsAndTheCommitsAreCountedWithTheirEvent`, `oneCommitIsSaidInTheSingular`; `WorktreeServiceTest.theCommitCountIsZeroBeforeAnyCommitAndCountsEachCommitOnTheBranch`; `RecoveryTest` (counted with real git); Run F5 (`2 commits on appmod/fix-...`). |
| Count kept for sessions that did not succeed | Proven. `SessionLifecycleTest.theCountIsRecordedForErrorAndInterruptedSessionsToo`; `RecoveryTest.aWindowThatIsGoneWithoutARecordIsInterruptedAndTheCommitsAlreadyMadeAreCounted` (3 commits counted for an `INTERRUPTED` session). |
| Records survive a restart | Proven. `MigrationTest.aSessionRecordedBeforeV2KeepsWorkingAndHasEmptyWorktreeFields`, `FixSessionRepositoryTest`; `RecoveryTest` (the fields are intact after a restart); Run F7, F9 (the console after a restart shows them). |

## Results are read from the worktree

| Scenario | Proof |
|---|---|
| Report in the worktree | Proven. `SessionLifecycleTest.theReportIsReadFromTheWorktreeAndNotFromTheCheckout`; `RecoveryTest.aWindowThatEndedWhileTheEngineWasDownIsFinalizedFromItsWorktree` (a hostile `failed` report left in the checkout is ignored); a mutation that read from the checkout failed six tests; Run F6. |
| Restart while a window is open | Proven. `RecoveryTest.aWindowThatIsStillOpenInItsWorktreeIsFollowedAndFinalizedFromTheWorktree`; Run F7 (killed 4 seconds into the window: "is still running; following it again", then `DONE` with 2 commits). |
| Same repository | Proven. `SessionDispatcherTest.twoSessionsForOneRepositoryStillNeverRunTogetherEachWithItsOwnWorktree` (no worktree is made for the session that waits); `ReopenServiceTest.theSameRepositoryRuleStillLooksAtTheRepositoryNotTheWorktree`. |

## The person is told where the fixes are

| Scenario | Proof |
|---|---|
| Session shown in the console | Proven. `ConsoleCommandsTest.aSessionWithAWorktreeShowsItAndTheCommandsToReviewAndRemoveIt`, `aWorktreeWhoseCommitsWereNotCountedSaysSo`, `controlCharactersInARecordedWorktreeNeverReachTheTerminal`, `aRecordedValueWithAQuoteGetsNoCommandsInsteadOfABrokenOne`; Run F9; Run F10 (the printed commands were followed by hand; the removal command had to change, see e2e-worktrees.md). |
| List marks sessions with fixes | Proven. `ConsoleCommandsTest.theListMarksSessionsWithCommitsToReviewAndNoOthers` (a worktree with no commits is not marked); Run F9. |
| Read API | Proven. `SessionApiTest.aSessionWithAWorktreeShowsItsNameFolderBranchBaseCommitAndCommitCount`, `aSessionWithoutAWorktreeHasNullWorktreeFields`; Run F4. |

## Reopening in the worktree

| Scenario | Proof |
|---|---|
| Reopen in the worktree | Proven. `ReopenServiceTest.aSessionWithAWorktreeIsReopenedInTheWorktreeNotInTheCheckout`; `WindowLauncherTest.aReopenedSessionOpensARealWindowInItsRealWorktree` (opt-in; a real window in a real git worktree, with a stand-in agent). |
| Worktree removed by the person | Proven. `ReopenServiceTest.aWorktreeThePersonRemovedIsReportedAndNothingIsOpened`. |

## Gaps for worktrees, stated plainly

1. **Only Windows was available.** Git is run through argument lists and paths through `java.nio`, so nothing is Windows-specific in the
   service, but no test ran on Linux or macOS. The real-window test is Windows only by nature.
2. **The real Copilot CLI in a worktree** is proven in 9.2: `reopen <id>` resumed the conversation of a session that ran in
   `shop-fix-fe633526`, and the agent remembered its fixes.
3. **Long paths (Windows).** Tried (2026-11-14): git itself fails inside a repository whose path is long (`Filename too long`, `.git/objects` at about 230 characters
   and `.git/worktrees/<name>` at about 280), and the engine used to report only git's first line. Now the worktree service refuses before git runs when the worktree folder
   is over 200 characters or `<repository>/.git/worktrees/<name>` over 230, with the length, the limit and what to do (change `engine-hardening`;
   `WorktreeServiceTest`), and shows git's own `fatal:`/`error:` line otherwise. The limits come from these observations and are conservative; there is no setting for Windows
   long-path support.
4. **Submodules and Git LFS** are not handled in the worktree (a non-goal of the design).
5. **Hooks are disabled for the engine's own git calls only.** The agent runs git itself, in the worktree, and a hook that the
   repository defines can run then; that is the agent's normal behaviour and outside this change.


## Headless permission guard (change `headless-permission-guard`)

Every scenario of `specs/headless-permission-guard/spec.md`; test names are `Class.method` under `src/test/java/dev/subscriber/`.

| Scenario | Proof |
|---|---|
| Headless with the default mode | **Proven.** `config.HeadlessPermissionGuardTest.headlessWithTheDefaultModeIsRefusedAndTheMessageNamesTheSettingAndTheChoices`, `theEnvironmentDecidesWhetherTheModeWasSet` (an empty placeholder is not a decision), and `ContextLoadsTest.aHeadlessStartWithoutAPermissionDecisionIsRefusedAndWithOneItStarts` (a real context). |
| Headless with ask | **Proven.** `HeadlessPermissionGuardTest.headlessWithAskIsRefusedBecauseNobodyCanAnswerWhetherOrNotItWasSet`. |
| Headless with an explicit allow-all-tools | **Proven.** `HeadlessPermissionGuardTest.headlessWithAnExplicitAllowAllToolsStartsAndWarnsOnce` (the warning is in the captured output exactly once). |
| Headless with the allow-list | **Proven.** `headlessWithTheAllowListStartsWithoutAWarning` and the real context in `ContextLoadsTest`. |
| Window runner | **Proven.** `theWindowRunnerStartsWithAnyMode`, `anEngineWithTheWindowRunnerStartsWithNothingSet`. |

Run with the real jar (2026-11-14, headless): mode unset and `ask` exit 1 with the messages; explicit `allow-all-tools` starts with one warning; `allow-list` starts with none.

Gap: the check reads the Spring environment, so a mode set only by an `application.yml` placeholder that resolves to an empty value counts as not set, which is intended.

## Worktree cleanup (change `worktree-cleanup`)

Every scenario of `specs/fix-session-worktrees/spec.md` in the change that is new or modified. Test names are `Class.method` under
`src/test/java/dev/subscriber/`; the git tests run against real repositories.

| Scenario | Proof |
|---|---|
| Nothing is removed on its own | **Proven by construction and by a search.** The only call of `WorktreeProvisioner.remove` is `console.CleanupService`, which the console reaches only through the `cleanup` command; no scheduler, lifecycle or recovery code calls it, and the branch is never deleted by any code path (`WorktreeService.cleanUp` removes a branch only for a worktree that failed to be created). |
| Asking without confirming | **Proven.** `console.CleanupServiceTest.withoutTheConfirmationNothingIsRemovedAndTheAnswerSaysWhatWouldHappen`, and `ConsoleCommandsTest.cleanupWithoutYesOnlySaysWhatItWouldDoAndWithYesReachesTheService` through Spring Shell's parser. |
| Confirmed cleanup | **Proven.** `worktree.WorktreeServiceTest.removingDropsTheFolderAndTheAgentsReportsButKeepsTheBranchAndItsCommits` (real git: folder and listing gone, branch and its commit stay, the checkout untouched) and `CleanupServiceTest.aConfirmedCleanupRemovesTheFolderAddsOneEventAndLeavesTheStateAlone` (every final state; one `worktree removed` event; state unchanged). |
| Uncommitted work is not thrown away | **Proven.** `WorktreeServiceTest.uncommittedWorkOutsideTheAgentsReportsIsNeverThrownAway` (untracked, modified and staged files each refuse; the folder stays; it goes once committed) and `CleanupServiceTest.theRefusalOfTheWorktreeServiceIsShownAndLeavesNoEvent`. |
| Not a worktree of this repository | **Proven.** `WorktreeServiceTest.theRepositorysOwnCheckoutIsNeverRemovedEvenWhenItsNameAndBranchLookLikeAWorktrees`, `aFolderGitDoesNotListIsNotRemoved`, `aNameOrBranchThatIsNotTheEnginesFormIsRefused`, `aFolderOutsideTheAllowedRootsIsRefused`. |
| Session not finished, unknown or without a worktree | **Proven.** `CleanupServiceTest.aSessionThatIsNotFinishedIsRefused`, `anUnknownOrMalformedIdIsRefused`, `aSessionThatRanInTheCheckoutHasNothingToRemove`. |
| Already removed | **Proven.** `CleanupServiceTest.aSecondCleanupSaysTheFolderIsAlreadyGone`, `WorktreeServiceTest.aFolderThatIsAlreadyGoneIsReported`. |
| Another session in the repository | **Proven.** `CleanupServiceTest.anotherSessionInTheSameRepositoryBlocksUntilItHasEnded` and `aSessionInAnotherRepositoryDoesNotBlock`. |
| Reopening after cleanup | **Proven.** `ConsoleCommandsTest.reopenAfterTheFolderIsGoneIsRefused` and `ReopenServiceTest.aWorktreeThePersonRemovedIsReportedAndNothingIsOpened`. |

Gaps, stated plainly:

1. **Run by a person at the real console (2026-11-14):** `session`, `cleanup` (dry run), `cleanup --yes`, a second `cleanup --yes`, `reopen` and `events` on
   the finished session `a58cb704` of the 6.5 run. The folder was gone afterwards, git no longer listed it, the branch kept its 5 commits, `master` was
   untouched, `reopen` refused and the timeline ended with `worktree removed`. The run also showed that `events` ran a microsecond timestamp into the state
   column; fixed (column widened, `ConsoleCommandsTest.anEventTimestampWithMicrosecondsIsStillSeparatedFromTheState`, not mutation-checked).
2. **A folder held open by an editor or a window** on Windows was not exercised; the design says git then fails and the answer carries its reason.

## Claude Code as the coding agent (change `engine-claude-code`)

Every scenario of `specs/agent-tool-selection/spec.md`. Test names are `Class.method` under `src/test/java/dev/subscriber/`.

| Scenario | Proof |
|---|---|
| Default is Copilot | **Proven.** `ContextLoadsTest.withoutTheSettingTheToolIsCopilot`; every pre-existing Copilot test and golden file is unchanged and passes (368 before this change, all still green); `IntakeServiceTest` records `copilot`. |
| Claude Code selected | **Proven.** `IntakeServiceTest.theSessionRecordsTheToolThatWillRunIt`, `ClaudeHeadlessLauncherTest`, and a real engine run: session `ff5383c4` recorded `engine: claude`. |
| Invalid value | **Proven.** `ContextLoadsTest.anUnknownAgentToolOrAClaudeCommandWithShellCharactersStopsTheStartNamingTheSetting` (a real start with `vim` and with `claude; calc`). |
| Reopening after a change of tool | **Proven.** `ReopenServiceTest.theWindowIsOpenedForTheToolThatRanTheSessionWhateverTheConfigurationSaysNow` (the recorded engine is handed to the window) and, in a real window, `WindowLauncherTest.aReopenedClaudeCodeSessionResumesWithClaudeCodeEvenWhenTheEngineIsSetToCopilot` (opt-in). |
| Headless Claude Code | **Proven.** `ClaudeLauncherScriptsTest` and the golden files (`claude-headless.sh`, `.ps1`), `ClaudeHeadlessLauncherTest` (arguments, exit record, a failing exit code, with a stand-in), and **the real Claude Code**: the engine on 2026-11-14 with `SUBSCRIBER_AGENT_TOOL=claude`, headless, an allow-list, the fixture of `tests/security-fixer`: session `DONE`, outcome `succeeded`, 5 fix commits in 1.7 minutes, **37 of 37 checks** of the fixer checker (27 turns, $0.28, one verifier sub-agent call, 4 tool calls denied by the allow-list and recovered from). |
| Window Claude Code | **Proven, with a stand-in and with the real Claude Code.** `WindowLauncherTest.aRealWindowRunsClaudeCodeInteractivelyAndClosesWhenItEnds` (opt-in; a real window, terminal input and output, the title, the arguments, the exit record, the window closes). With the real Claude Code and a person (2026-11-14, session `17cb72ec`): the window opened, the agent worked unattended under the allow-list, the person left it with `/exit`, the session was `DONE`, `succeeded`, 5 commits, **37 of 37 checks**. |
| Reopen Claude Code | **Proven, with a stand-in and with the real Claude Code.** `WindowLauncherTest.aReopenedClaudeCodeSessionResumesWithClaudeCodeEvenWhenTheEngineIsSetToCopilot` and the `claude-reopen.ps1` golden; and through the engine with a person (session `17cb72ec`): `reopen` opened a window, `claude --resume` brought the conversation back, the agent answered "what did you fix?" correctly, and the timeline got one `reopened` event. The first try failed, see the next row. |
| Claude Code does not inherit another Claude Code session | **Proven.** `ClaudeLauncherScriptsTest.everyClaudeCodeScriptForgetsTheVariablesOfAParentClaudeCodeSessionButNotTheCredentials`, `ClaudeHeadlessLauncherTest.aClaudeCodeStartedFromInsideAnotherClaudeCodeSessionDoesNotSeeThatSessionButKeepsItsCredentials` (a real launch; it fails when the clearing is removed, checked by hand), the goldens. **Found by the real run:** with the engine started from inside a Claude Code session, the window's `claude` saved no conversation file and `reopen` said "no conversation found" (session `b080632a`, 5 commits and 37 of 37 otherwise); started from a clean environment the same run saved it and `reopen` worked. |
| Allow-list for Claude Code | **Proven.** `ClaudeLauncherScriptsTest.theAllowListIsOneCommaJoinedQuotedArgumentForEachOfTheTwoLists`, `ClaudeHeadlessLauncherTest.theAllowListReachesClaudeCodeAsTwoSingleArgumentsAndNoBlanketPermission`, the goldens, and the real run above (the agent could run `git`, `node` and `npm` and was refused `curl`-class commands). |
| Blanket permission for Claude Code | **Proven with a stand-in** (`ClaudeLauncherScriptsTest.permissionModesBecomeClaudeCodesOwnArguments`, `ClaudeHeadlessLauncherTest`); the guard is shared with Copilot (`HeadlessPermissionGuardTest`, `ContextLoadsTest.theHeadlessGuardSeesAModeSetUnderTheNeutralName`). `--permission-mode bypassPermissions` was **not** run for real: starting an unattended agent with every tool allowed was refused by the environment's safety check, as it was for Copilot, and was not worked around. |
| Ask in a window | **Proven.** `ClaudeLauncherScriptsTest.permissionModesBecomeClaudeCodesOwnArguments`. |
| Neutral names | **Proven.** `ContextLoadsTest.theAgentToolAndTheNeutralPermissionNamesReachTheirSettingsAndTheNeutralNameWins` (real binding; the neutral name wins over `SUBSCRIBER_COPILOT_PERMISSION_MODE=ask`). |
| Doctor with Claude Code | **Proven.** `DoctorTest.withClaudeCodeSelectedTheClaudeCommandIsTheOneThatIsChecked`; `ConsoleCommandsTest.configShowsTheSelectedToolAndItsCommand`. |

Gaps, stated plainly:

1. **The window and `reopen` with the real Claude Code were run once each with a person** (2026-11-14), and the first attempt found the inherited-variables defect; nobody has run them on another machine or with `ask` (a person approving each tool in the window).
2. **`--permission-mode bypassPermissions`** was not run for real (see above).
3. **Flags of Claude Code 2.1.x only.** A later version may rename one; the real run is the proof for the version used (2.1.322).
4. **One run is one run:** the model is not deterministic; the checker, not the agent's report, is the proof.

## Publishing from the console (change `engine-publish-command`)

Every scenario of `specs/report-publishing/spec.md`. Test names are `Class.method` under `src/test/java/dev/subscriber/`. **Real run** means the real engine jar in a real terminal, the report
published by typing `publish ...` at its `$>` prompt (the keystrokes were written into the console of that process), a real `nats-server` that needs a token.

| Scenario | Proof |
|---|---|
| A report is published | **Proven.** `ReportPublisherTest.aReportIsPublishedOnTheFirstConfiguredSubjectAsItsAbsolutePath` (a stand-in connection records subject and body); `ReportPublisherLiveTest.aPublishedReportReachesTheEnginesSubscriberThroughARealServerThatNeedsAToken` (a real server, the engine's own subscriber receives it and hands it to the intake); **real run** (2026-11-15): `publish C:\se-fixture-k\shop\.appmod\security-audit\issues.csv` created session `6afb7fc5` with agent `security-fixer`, which the engine ran to the end. |
| Another configured subject | **Proven.** `ReportPublisherTest.anotherConfiguredSubjectCanBeNamed`; real runs: `--subject coverity.issuefixesAgent` (session `cea58424`) and `--subject bdh.issuefixesAgent` (sessions `ce8a085d`, `addb6e0b`). |
| A subject the engine does not listen to | **Proven.** `ReportPublisherTest.aSubjectTheEngineDoesNotListenToIsRefusedWithoutConnecting` (other subject, two wildcards, a space, an extra token; no connection made; the message names the configured subjects); `ConsoleCommandsTest.publishRefusesASubjectTheEngineDoesNotListenToAndNamesTheConfiguredOnes`. |
| A report that is not a file | **Proven.** `ReportPublisherTest.aPathThatCannotBeAReportIsRefusedWithoutConnecting` (null, empty, blank, missing, a directory, a line break, a carriage return, a null character; no connection made); `ConsoleCommandsTest.publishRefusesWhatCannotBeAReportAndSaysSoWithoutAStackTrace`. |
| A relative path | **Proven.** `ReportPublisherTest.aRelativePathIsMadeAbsolute`. |
| NATS is not reachable | **Proven.** `ReportPublisherTest.anUnreachableServerIsSaidInOneSentenceWithoutTheCredentials`; `ConsoleCommandsTest.publishToAnUnreachableServerSaysSoWithoutTheTokenAndWithoutRecordingASession` (the real command bean through Spring Shell's parser, the engine's settings point at a closed port; no session recorded). |
| Credentials | **Proven.** `ReportPublisherTest.nothingTheCommandSaysContainsTheToken`, `theConnectionUsesTheEnginesSettingsAndOnlyForThisCall`; `ReportPublisherLiveTest.aWrongTokenIsRefusedInOneSentenceThatShowsNeitherToken` (a real server, the wrong token, neither token in the message); the real runs used the engine's own token (the engine log shows the connection and never the token). |
| No Node publisher | **Proven.** The project `copilot-mordernize-harness/nats/javascript-int` is deleted (the two sample reports moved to `samples/reports/`); a search of the repository finds no instruction to run `npm`, `npm run publish` or `node` outside the archived changes and the historical run records (`e2e-run.md`, `e2e-worktrees.md`, which now say the publisher of that time was removed). |

Gaps, stated plainly:

1. **Typing at the prompt was automated**: the keystrokes were written into the console of the engine's process (`AttachConsole` and `WriteConsoleInput`), not typed by a person; the earlier console commands were typed by a person on 2026-11-13.
2. **The NATS command-line client** (`nats pub`) mentioned in the docs for another machine was not run: no `nats` client is installed here.
3. **Build:** the full engine build on Windows with the opt-in window and NATS tests on is 410 tests, 0 skipped, 0 failed (397 before this change, 13 added: 8 + 2 + 3).

## Source branch, push and pull request (change `source-branch-and-pull-request`)

Scenarios of `specs/source-branch-selection`, `branch-publishing`, `git-preflight` and the changed ones of `fix-session-worktrees`, `report-publishing` and
`issue-fix-subscriber`. Test names are `Class.method` under `src/test/java/dev/subscriber/`; "kit" rows are in `kit/tests/installer/GitStepTests.java`. All use real git in
temporary folders (a bare repository as the remote); none ran against a hosted git service. Usage and limits: [publishing.md](publishing.md).

| Scenario | Proof |
|---|---|
| Branch given when publishing | **Proven.** `SourceBranchTest.aBranchGivenWhenPublishingIsTheBase`; `SampleRepositoriesTest.aBranchGivenAtPublishWinsOverTheSavedOne` (Spring, Node.js, Python samples). |
| Saved branch beats the report's commit | **Proven.** `SourceBranchTest.theSavedBranchBeatsTheReportsCommitAndAnEventSaysSo`, `theGivenBranchBeatsTheSavedOne`. |
| No choice made | **Proven.** `SourceBranchTest.withNoChoiceTheDefaultBranchIsUsedNotTheCheckedOutFeatureBranch`; `SampleRepositoriesTest.withNothingSaidTheRemotesDefaultBranchIsTheBaseNotTheFeatureBranchTheCheckoutIsOn`; `WorktreeServiceTest.anAuditedCommitThatIsNotInTheRepositoryFallsBackToTheDefaultBranchAndSaysWhy`. |
| Unusable branch | **Proven.** `SourceBranchTest.anUnusableGivenOrSavedBranchIsAnErrorAndCreatesNothing`, `SampleRepositoriesTest.aBranchThatDoesNotExistFailsBeforeAnythingIsCreated`. |
| Default branch: remote default known locally / asked from the remote / no remote | **Proven.** `SourceBranchTest.theRemotesDefaultKnownLocallyIsTheDefault`, `theRemoteIsAskedWhenOriginHeadIsNotKnownLocally`, `withoutARemoteMainIsALocalGuess`, `masterIsUsedWhenThereIsNoMainAndTheCheckedOutBranchWhenThereIsNeither`, `detectionNeverFetches`. |
| Local changes: included / option not used / nothing to include / ignored files stay out | **Proven.** `SourceBranchTest.localChangesAreIncludedAndTheCheckoutIsUntouched` (also staged, untracked, ignored and `.appmod/`; branch, status and index unchanged), `withoutTheOptionTheWorktreeHasNoLocalChanges`, `aCleanCheckoutStartsFromTheTipAndSaysThereWasNothingToInclude`, `aSourceBranchAndLocalChangesCannotBeCombined`, `stagedChangesAreIncludedToo`; `SampleRepositoriesTest.localChangesOfTheCheckoutAreSnapshottedAndTheCheckoutStaysAsItWas`. |
| Two repositories / invalid setting | **Proven.** `SourceBranchTest.twoRepositoriesWithDifferentSavedBranches`, `anInvalidSettingsFileMakesTheSessionFailWithItsReason`; `RepositorySettingsTest` (every kind of bad file); `SampleRepositoriesTest.theThreeRepositoriesKeepSeparateSavedBranches`. |
| Push after a succeeded session / mode none / session that did not succeed / push fails | **Proven.** `BranchPublisherTest.aSucceededSessionIsPushedInPushMode`, `aPartiallySucceededSessionIsPushedToo`, `modeNoneMakesNoCallToTheRemoteAndRecordsThatNothingWasPublished`, `theRepositorysOwnModeBeatsTheEnginesDefault`, `nothingIsPushedForAnotherFinalStateOrForASessionWithoutCommits`, `aRemoteThatRefusesTheBranchIsAFailureWithTheReasonAndTheLocalBranchIsKept`, `aRepositoryWithoutAnOriginIsAFailureNotACrash`, `credentialsInAUrlNeverReachAnEventOrTheOutcome`. Mutation-checked: `--force` added, any final state allowed, credentials not removed. |
| Pull-request command: succeeds / fails / no command / text from a report cannot become part of it | **Proven with a stand-in program, not a host.** `BranchPublisherTest.theCommandRunsOnceWithEngineMadeValuesAndTheFirstLinkIsRecorded`, `aFailingCommandKeepsTheBranchPushedAndRecordsTheFailure`, `aMissingProgramAnUnknownPlaceholderOrNoTemplateFallBackToPushAndSayWhy`, `textFromAReportOrTheTitleCannotBecomeAnArgumentOrACommand`, `theTemplateIsSplitByQuotesAndValidated`. |
| Local-change sessions: unsafe session in push mode / confirmed / not finished | **Proven.** `BranchPublisherTest.aLocalChangesSessionWarnsInTheDescriptionAndWaitsForConfirmation`, `refusalExplainsWhyASessionCannotBePublishedByHand`; `ConsoleCommandsTest.publishBranchPushesAfterConfirmationAndOnlyThen`, `publishBranchRefusesAnUnknownAnUnfinishedAndAnErroredSession`. |
| Git readiness: everything works / no remote / credentials refused / unreachable host / mode none | **Proven.** `GitPreflightTest.aLocalBareRemoteMakesEveryCheckPassAndNothingIsPushed`, `inModeNoneTheRemoteIsNotLookedAtAndTheReportSaysWhyButAMissingRemoteFailsWhenPushIsOn`, `aHostThatRefusesTheCredentialIsAFailureNamingTheReason` (a local stand-in answering 401, git run without the machine's configuration), `anUnreachableRemoteIsReportedWithoutItsCredentials`, `aRemoteThatNeverAnswersHitsTheTimeLimit`, `thePullRequestProgramMustBeOnThePathInPullRequestMode`. Not shown: *which* credential source git used (git does not say). |
| Installer: interactive init / non-interactive init / git problem at init | **Proven** (kit): `interactiveChoiceIsSaved`, `withoutATerminalNothingIsSaved`, `remoteUnreachableStillInstalls`, `upgradeKeepsTheSavedFile`, `badAnswersAndOtherFolders`. The "terminal" is an answers file (`-Dappmod.init.answers`); a person typing at a real terminal was not run. |
| The installer's copy of the checks equals the engine's | **Proven.** `GitPreflightParityTest` (kit `GitSetup.java` and `GitPreflight` on the same repositories; mutation-checked). |
| Doctor with two repositories / config shows settings only / publish with a missing branch | **Proven.** `DoctorTest.twoRepositoriesGetOneGitBlockEachAndTheOneWithoutARemoteFailsWithoutHidingTheOther`, `aSubjectWithoutAFixedRepositoryOrWithoutWorktreesAddsNoGitBlock`; `ConsoleCommandsTest.configShowsThePublishAndBranchSettingsFromSettingsOnlyEvenWhenTheRemoteIsUnreachable`; `ReportPublisherSourceBranchTest.aBranchThatDoesNotExistOrIsNotAValidNameOrBothOptionsPublishNothing`, `anInvalidSavedSettingStopsThePublishWithItsReason`. |
| `publish` sends the options as headers / local changes / invalid name / both options | **Proven.** `ReportPublisherSourceBranchTest` (headers, body still only the path, branch said before publishing, confirmation warning); `ConsoleCommandsTest.publishTakesTheSourceBranchAndLocalChangesOptionsAndRefusesBothTogether`; with a real NATS server in Docker: `ReportPublisherLiveTest.theTwoHeadersCrossARealServerIntoTheIntake`. |
| Headers are read and validated / invalid header / duplicate rule / same report on another branch | **Proven.** `IntakeServiceTest.aBranchHeaderIsRecordedWithTheSession`, `theLocalChangesHeaderIsRecordedWithTheSession`, `invalidUnknownRepeatedOrCombinedHeadersAreRejectedAndNothingIsQueued`, `theSameReportOnAnotherBranchIsAcceptedButTheSameBranchIsADuplicate`; `NatsMessageSourceTest.theTwoSourceHeadersAreHandedToTheIntakeAsData`; `SessionDispatcherTest.twoBranchesOfOneRepositoryStillRunOneAfterTheOtherAndEachGetsItsRequest`. |
| Session shows how its branch was chosen and published; a queued session says what it waits behind | **Proven.** `ConsoleCommandsTest.aSessionShowsItsSourceBranchHowItWasChosenLocalChangesAndThePublishOutcome`, `aQueuedSessionSaysWhichSessionOfTheSameRepositoryItWaitsBehind`; `SessionApiTest.theSourceBranchLocalChangesAndPublishOutcomeAreInTheView`. |
| The agent keeps "never push" and the files say who may push | **Proven.** `PromptWriterTest.everyPromptTellsTheAgentThatTheReportIsDataAndWhatToWriteWhenItFinishes`; kit `InstallerTests.agentsSayWhoPushesAndHowTheBaseIsChosen`. |

Gaps, stated plainly:

1. **No hosted git service** (GitHub, GitLab, Bitbucket, cloud or on-premises) and no `gh` or `glab` was available: pushes went to local bare repositories and the pull-request command was a stand-in program.
2. **A person at a terminal** answering the `appmod init` question was not run (the tests read the answer from a file). The `nats pub -H` command line was run: [real run](../../docs/evidence/source-branch-real-engine-run.txt).
3. The sample Spring Boot, Node.js and Python repositories are files only; no build tool was run on them.
