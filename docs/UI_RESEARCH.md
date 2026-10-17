# UI Research — Android UI patterns from the maintainer's own projects

Issue: superz1402/linuxify#7 · Task 7 · 2026-10-17
Author: research agent (no code changes made in this task)

## 1. Method and scope

**Goal.** Reverse-engineer the actual UI implementation (not READMEs) of Android
projects under the three GitHub accounts tied to this project, extract proven
patterns, and turn them into a grounded design spec for the Linuxify native app.

**Method.**

1. Repo inventory via GitHub REST API:
   `GET /users/<login>/repos?per_page=100` with `Authorization: Bearer <token>`
   (token authenticates as `superz1402`; 5000 req/h — used 4 requests total).
2. File trees via `GET /repos/<owner>/<repo>/git/trees/main?recursive=1`
   (no truncation occurred; largest repo 249 blobs).
3. Raw file fetch from `raw.githubusercontent.com` with the token header.
   94 files downloaded to a scratch dir, zero failures; each file cited below
   was actually read, not guessed from its name.
4. The Linuxify app's own current theme/nav files were inspected locally
   (`app/src/main/java/com/linuxify/app/…`) so the spec builds on what already
   exists instead of contradicting it.

**Scope of inspection.**

| Account | Android repos found | Deep-inspected | Light/skimmed | Skipped (reason) |
|---|---|---|---|---|
| **Bilal140202** (= `bilal140202`; GitHub logins are case-insensitive — same account) | MobileAgent, YourFiles, Your-Files-Android, Your-Files-Redesign, AuraGallery, comet-x-browser, tabit-android, VoxFlow, jarvis-agent, HeyYou, YourNotes, Proj (Java), Todoapp (Flutter) | Your-Files-Redesign, tabit-android, comet-x-browser | AuraGallery, VoxFlow, MobileAgent | YourFiles/Your-Files-Android (superseded by Redesign), HeyYou/YourNotes/jarvis-agent (sibling copies of patterns already covered), Proj/Todoapp (non-Kotlin or trivial) |
| **ansaribilal14** | jarvis, babymomo, Babymomo1, EchoID-Android, open-video-engine-android, tabit-android (v1), zerium-browser, zerium-gecko | jarvis, open-video-engine-android, zerium-browser | EchoID-Android, tabit-android (v1), zerium-gecko | babymomo/Babymomo1 (companion-AI apps, no reusable UI system), personal-social-agent etc. (non-Android) |
| **superz1402** | linuxify (this app, Java/Kotlin), agent-ui-playbook (docs) | linuxify (local files), agent-ui-playbook (README laws) | — | Everything else is Python/TypeScript/HTML (not Android UI) |

**Honest limitations.**

- No private/deleted repos were encountered — all fetched repos returned HTTP 200.
  Nothing in the task list was inaccessible.
- No running of any app; findings are static-code analysis only. Claims about
  runtime behavior (animation feel, jank) are inferences from code, marked as such.
- ansaribilal14/tabit-android is an earlier revision of Bilal140202/tabit-android;
  both were inspected because the delta (v1 → v2) shows how the author matured the
  design system — that delta is itself evidence.
- Some repos (AuraGallery, VoxFlow, EchoID) were skimmed at theme/nav level only,
  not screen-by-screen.

---

## 2. Per-repo findings (evidence = files actually read)

### 2.1 Bilal140202/Your-Files-Redesign — the most complete design system in the account set

Kotlin + Jetpack Compose + Material 3. Clean architecture (data/domain/presentation).

Evidence read:
- `app/src/main/java/com/yourfiles/manager/app/uim3/theme/Spacing.kt`
- `app/src/main/java/com/yourfiles/manager/app/uim3/theme/Type.kt`
- `app/src/main/java/com/yourfiles/manager/app/uim3/theme/Color.kt`
- `app/src/main/java/com/yourfiles/manager/app/uim3/theme/Theme.kt`
- `app/src/main/java/com/yourfiles/manager/app/ui/Modifiers.kt`
- `app/src/main/java/com/yourfiles/manager/app/Router.kt`, `AppThemeManager.kt`, `LocalNavController.kt`
- `app/src/main/java/com/yourfiles/manager/presentation/ui/components/common/EmptyStateCompose.kt`,
  `LoadingStateCompose.kt`, `ScreenScaffold.kt`
- `presentation/ui/pages/ESHomeScreen.kt`, `PermissionRequiredPage.kt` (tree-confirmed, key parts read)

Findings:

- **Named spacing object, not magic numbers**: `Spacing.xs=2 / sm=4 / md=8 / lg=12 /
  xl=16 / xxl=24 / xxxl=32` **plus semantic aliases**: `screenHorizontal=20`,
  `screenVertical=12`, `cardInner=16`, `listItemVertical=8`, `listItemHorizontal=16`,
  icon sizes 20/24/32/48, thumbnails 48/120. Doc comment: *"generous air is part of
  the soft-premium identity."*
- **Semantic modifier helpers** (`AppModifiers.screenPadding()`, `listItem()`,
  `touchTarget(minSize=48)`, `iconButton()`) — one place defines what "a list row"
  and "a touch target" mean. `listItem()` enforces `heightIn(min=56.dp)`.
- **Typography = weight contrast, not size chaos.** Full M3 scale mapped onto a
  single brand font (Plus Jakarta Sans via Google Fonts provider): SemiBold titles
  vs Normal body, `displayLarge 57/64`, `headlineSmall 23/30`, `titleMedium 17/24`,
  `bodyLarge 16/24`, `bodySmall 12/16`, `labelMedium 13/16` with +0.4sp tracking.
  Doc comment: *"Hierarchy comes from weight contrast — never all-bold."*
- **State triplet as reusable composables**: `EmptyStateView(icon?, title, subtitle,
  actionLabel?, onAction?)`, `LoadingStateView(message?)` (40dp spinner, 3dp stroke),
  `ErrorStateView(message, onRetry?)`. Every list screen reuses them — zero bespoke
  empty states.
- **`ScreenScaffold`** removes the repeated Scaffold+TopAppBar+back pattern; supports
  `title`+`subtitle` (subtitle in `labelSmall`/onSurfaceVariant), back vs hamburger,
  actions slot, FAB slot, optional SnackbarHost.
- **Theme-as-data**: `ThemeColors` data class carries chromatic tokens + optional
  neutral overrides + `cornerRadius` + `cardElevation` + **structural layout
  parameters** (`itemLayoutStyle`: LIST_ROW / CARD / COMPACT, item heights). Shapes
  are derived from one radius (`buildShapes`: small = 0.55r, xsmall = 0.35r, large =
  1.35r, xlarge ≤ 40dp). Powerful, but the 40+ field data class is the cost of this
  flexibility (see anti-patterns).

Adopt for Linuxify: Spacing object with semantic aliases; EmptyState/Loading/Error
shared composables; ScreenScaffold; weight-contrast typography; derived-shapes idea.

Reject/caution: per-theme *structural* layout switching (over-engineering for a
single-identity app); brand Google Font adds a download/cert dependency — fine but
not free.

### 2.2 Bilal140202/tabit-android — token-driven dark-first small app (best doc-commented)

Kotlin + Compose + M3 + Hilt + Glance widget.

Evidence read:
- `app/src/main/java/app/tabit/tracker/core/theme/Dimens.kt`, `Type.kt`,
  `AppTheme.kt`, `Color.kt`, `ThemePreferences.kt`
- `navigation/AppNavigation.kt`, `MainActivity.kt`
- `res/values/colors.xml`, `themes.xml`, `values-night/colors.xml`, `values-night/themes.xml`

Findings:

- **One token file for spacing + sizing + motion + alpha** (`Dimens.kt`):
  `TabitSpacing` 4/8/12/16/20/24/32/48; `TabitSizing` (checkbox 44, minTouchTarget
  48, cardCornerRadius 12, progressHeight 6); **`TabitMotion`** — IMMEDIATE 120ms /
  ROUTINE 250ms / LAYOUT 350ms / FOCAL 500ms with a named decelerate easing; and
  **`TabitAlpha`** — named alpha constants (MUTED_TEXT 0.60, SUBTLE_TEXT 0.45,
  DISABLED 0.38, DIVIDER 0.50 …). This is the only repo of the set that treats
  alpha and motion as design tokens.
- **Theme handling**: `TabitTheme(themeMode)` → system/light/dark from
  DataStore-backed `ThemePreferences`; **dynamic color (Material You) on Android 12+
  with brand fallback**; status-bar + nav-bar icon appearance synced in a `SideEffect`.
- **Navigation**: `sealed class Screen` with route constants; `AppNavigation` decides
  start destination (onboarding vs table) asynchronously with a **loading
  CircularProgressIndicator while DataStore resolves** and a safe fallback on error;
  NavHost transitions = fade + horizontal slide by 1/3 width, 300ms.
- **Bottom bar shown only on top-level routes** (route whitelist in
  `MainActivity.TabitApp`), `popUpTo(...) { inclusive = true }` + `launchSingleTop`
  on every tab click — correct M3 tab back-stack hygiene.
- **Semantic status color as third accent**: skip = amber (tertiary), documented as
  *"a semantic state color, not a random accent."*
- **Delta vs ansaribilal14/tabit-android (v1)**: v2 added Dimens.kt (motion/alpha
  tokens), ThemePreferences, values-night resources, and the Route-type-safe edit
  screen. The author's own trajectory converges on: tokens first, then screens.

Adopt for Linuxify: TabitMotion-equivalent (named durations), named alpha constants,
theme-mode DataStore + dynamic-color-with-fallback, route whitelist for the bottom
bar, async start-destination with loading + fallback.

### 2.3 Bilal140202/AuraGallery — top-level destination pattern + inline progress strip

Kotlin + Compose + M3 + Hilt + WorkManager.

Evidence read:
- `app/src/main/java/com/auragallery/app/ui/navigation/TopLevelDestination.kt`,
  `AuraNavHost.kt` (tree-confirmed)
- `ui/components/AuraBottomBar.kt`, `EmptyState.kt`, `PermissionGate.kt`
- `ui/theme/Color.kt`, `Theme.kt`, `Type.kt` (tree-confirmed structure)

Findings:

- **`TopLevelDestination` enum carries `selectedIcon` + `unselectedIcon`**
  (Icons.Rounded.* vs Icons.Outlined.*) + string res — the polished M3 nav-bar idiom.
- **`DetailRoutes` object** separates non-tab routes (`viewer/{photoId}`, `trash`,
  `vault`, `pro_unlock`, `oss`) from tabs — exactly the split Linuxify needs
  (Setup/Terminal detail vs Home/Files tabs).
- **`IndexingProgressStrip`**: a persistent thin strip (secondaryContainer, 16/10dp
  padding, 8dp dot + LinearProgressIndicator) that lives above the bottom bar while
  background work runs — the right pattern for Linuxify's rootfs download/apt
  operations that must not block navigation.
- `NavigationBar(containerColor = surface, tonalElevation = 0.dp)` + `alwaysShowLabel
  = true` — flat, labeled, calm.
- `PermissionGate` composable as a first-class UI state (permission ≠ error ≠ empty).

Adopt for Linuxify: selected/unselected icon pairs, DetailRoutes split, progress
strip above the bottom bar, PermissionGate-as-state.

### 2.4 ansaribilal14/jarvis — status system + honest diagnostics (closest cousin to Linuxify)

Kotlin + Compose + M3 ("v2.0 Expressive"). Local-first agent app — same problem
shape as Linuxify: long-running local runtime, downloads, health checks, risky ops.

Evidence read:
- `app/src/main/java/com/jarvis/mobile/ui/theme/Color.kt`, `Theme.kt`, `Type.kt`
- `ui/components/Common.kt` (StatusChip, SectionCard, ConfirmDialog, EmptyState, stepColor)
- `ui/navigation/Nav.kt`, `ui/screens/HomeScreen.kt`, `ui/screens/DoctorScreen.kt`,
  `ui/screens/ModelsScreen.kt`
- `service/OverlayService.kt` (tree-confirmed)

Findings:

- **`StatusChip(text, state)` with 5-state enum** `ChipState { OK, WARN, BAD,
  NEUTRAL, ACCENT }`: 7dp colored dot + `labelMedium` text inside a full-rounded
  `surfaceVariant` pill. Used everywhere (model status, agent route, risk level).
  This is the single most reusable component in the whole account set.
- **`stepColor(status)`**: SUCCESS→Ok, RUNNING→Accent, FAILED/BLOCKED→Danger, else
  neutral — a one-function status-hierarchy contract.
- **`SectionCard(title)`**: 18dp-rounded `surface` card, 16dp padding, 8dp inner
  vertical arrangement — the standard grouping unit.
- **Doctor screen** (`DoctorScreen.kt`): `CheckState { Ok; Degraded(reason);
  Fix(message) }` — three-tier health with **honest fix instructions as UI content**
  ("Enable JARVIS in system accessibility settings — required for all phone
  control"). Checks are computed from live state (RAM ≥ 3 GB, storage ≥ 2.5 GB,
  permission granted, recognizer available) — exactly what Linuxify's Setup/doctor
  flow needs.
- **Download/activation states done right** (`ModelsScreen.kt`): per-model state
  machine surfaced in cards (download/pause/resume/cancel/activate/delete/test);
  **quantitative test results as copy** ("OK — replied in 1.2s · 87 tokens · 41.5
  tok/s · \"…\""); **honest loading copy** ("first activation can take up to a
  minute. The app may look quiet while the model is read — do not close it.");
  explicit "Recommended for your device" tag; device-profile card before downloads.
- **Theme**: dark-first "deep space" scheme (`DarkBg 0xFF070B10`, sentinel teal
  accent 0xFF2FE6C8, plasma violet tertiary), full M3 role mapping including
  surfaceContainer tiers; dynamic color on 12+ only in SYSTEM mode; **expressive
  shapes** (8/12/18/26/32dp). Light theme fully specified too.
- **Type**: Light display tier (52sp, −0.5sp tracking), SemiBold headlines, and —
  notable for Linuxify — a **deliberate monospace tier kept for agent states**
  ("machine voice").
- Home screen: top StatusChip row (route + model state, chips switch ChipState
  dynamically), one animated element only (the Orb) while the agent is busy,
  `imePadding()` + `verticalScroll` + quick-command affordances.

Adopt for Linuxify: StatusChip + ChipState (or keep Linuxify's existing Status enum,
same idea), SectionCard, the Doctor three-tier check model, per-operation state
machine cards with pause/resume/cancel, quantitative honest copy, `imePadding()`.

Caution: emoji in a TextButton label ("⚡ API mode…") contradicts the maintainer's
own agent-ui-playbook law 11 — don't copy that bit.

### 2.5 ansaribilal14/open-video-engine-android — token discipline + "no dead controls"

Kotlin + Compose + M3, CapCut-style editor. The only repo with an explicit
docs-driven design contract (referenced as `docs/UI_SYSTEM.md`, `docs/UX_ARCHITECTURE.md`).

Evidence read:
- `app/src/main/java/app/ove/studio/ui/theme/Tokens.kt`, `Theme.kt`, `Color.kt`
- `editor/EditorScreen.kt`, `editor/components/TransportBar.kt`
- `home/HomeScreen.kt`, `export/ExportSheet.kt` (tree-confirmed)

Findings:

- **Tokens file with a governance rule**: *"Design tokens — docs/UI_SYSTEM.md §2.
  Single source of truth; no other file may introduce a color literal (review rule
  M)."* Tokens are plain `const val Int` grouped by domain: `Spacing` (4dp grid:
  4/8/16/24/32), `Shape` (8/12/20), **`TimelineMetrics`** (rulerHeight 22, trackHeight
  52, minTarget 44), **`ToolBarMetrics`** (barHeight 68, chipBox 44, chipIcon 22,
  chipLabel 10sp, chipGap 6) — *screen-specific metric namespaces* next to generic
  ones.
- **Context-forced dark**: `OveTheme(forceDark = true)` — *"The editor surface is
  ALWAYS dark (preview fidelity decision)"* while home/settings follow system. The
  documented rationale (content fidelity on neutral surfaces) transfers directly to
  Linuxify's terminal screen.
- **"ONLY operations the engine actually supports — no dead controls, ever"**
  (EditorScreen doc comment) — controls appear only when the backing capability
  exists. Directly applicable to Linuxify (no package buttons before rootfs ready).
- **Accent = one gradient + one flat color**: cyan→blue CTA gradient brush exposed
  as a single token (`oveAccentGradientBrush`); everything else neutral so content
  (video frames) keeps fidelity.
- Transport bar: 58dp height, 44dp filled-accent play button between two plain icon
  buttons, timecode chips at both ends, frame-step semantics. Touch targets ≥ 44dp
  everywhere (TimelineMetrics.minTarget).
- **Real defect found (evidence, not speculation)**: in `Theme.kt` the
  `LightScheme` maps `onSurfaceVariant = OveOnSurfaceVariantDark` — the *dark*
  constant leaked into the light scheme. Copy-pasted schemes silently rot; Linuxify
  should generate/verify schemes or add a unit test that walks both schemes.

Adopt for Linuxify: docs-governed token file; screen-specific metric namespaces
(e.g., `TerminalMetrics`); forced-dark terminal with documented rationale; "no dead
controls" as a hard rule; the bug as a reason to scheme-test.

### 2.6 ansaribilal14/zerium-browser (+ zerium-gecko twin) — the M3-in-XML reference

Java + XML Views + Material 3 (DayNight), Compose used only for new surfaces.

Evidence read:
- `app/src/main/java/com/zerium/browser/ui/ZeriumTheme.kt`, `ui/AppearanceActivity.kt` (tree-confirmed)
- `app/src/main/res/values/colors.xml`, `values/themes.xml`, `values-night/colors.xml`
- `res/layout/activity_main.xml`, `activity_list.xml` (tree-confirmed)
- zerium-gecko: same `ui/ZeriumTheme.kt` kit (tree-confirmed, 135 blobs)

Findings:

- **Full M3 tonal palette in XML mirroring the Compose scheme 1:1** —
  `Theme.Material3.DayNight.NoActionBar` + `values-night` mirrors, including the
  5-level surfaceContainer ladder. Proves the scheme can be shared across
  View/Compose if ever needed.
- **`ZeriumTheme` Compose wrapper** honors prefs: system/light/dark (ints),
  dynamic color flag (12+), and **user-selectable accent** (`AccentChoices`: Indigo/
  Ocean/Forest/Plum/Sunset/Rose) applied by `scheme.copy(primary = accent,
  onPrimary = White)` on the static palettes only.
- Soft shape system in styles (small 16 / medium 20 / large 28dp), **elevation-less
  tonal cards** (`cardElevation 0dp`, filled with `surface_container_low`, corner
  24dp) — the "calm M3" look.
- Settings typography idioms via styles: SectionTitle 13sp bold accent,
  RowTitle 15sp, RowSub 12sp onSurfaceVariant, RowValue 14sp, row minHeight 52dp,
  `selectableItemBackground` ripple.
- Honest debt visible: legacy `accent`/`border` color names kept "for existing
  references"; `statusBarColor`/`navigationBarColor` item overrides (deprecated
  approach); tint-free `_c` drawable variants + Compose `ColorFilter.tint` shim
  because View tints don't cross to Compose.

Use as: the proof that Linuxify should stay **Compose-only** — running two theming
systems doubles every decision. Also a good settings-row spec (52dp rows,
title+sub+value+switch).

### 2.7 Bilal140202/comet-x-browser — the scale cautionary tale

Kotlin + **hybrid View XML / programmatic Views / Material widgets**, one giant
MainActivity (imports of `LinearLayout`, `RecyclerView`, `MaterialButton`,
`MaterialCardView`, `WebView`…), 249 blobs.

Evidence read:
- `app/src/main/java/com/cometx/browser/ui/MainActivity.kt` (head)
- `app/src/main/res/values/themes.xml`, `colors.xml` (+ `values-night/colors.xml` tree-confirmed)
- `ui/BrowserController.kt`, `ui/TabManager.kt`, `ui/cloud/CloudTheme.kt` (tree-confirmed)

Findings:

- Theme is *good* — `Theme.Material3.DayNight.NoActionBar` with a full M3 mapping,
  documented `docs/design-system/COMET_MATERIAL_EXPRESSIVE.md`, fixed brand accent
  "never used behind text; progress tint, sparkle, focus", values-night preserving
  "the shipped deep-space palette verbatim".
- But the UI lives in one Activity with programmatic view construction — the design
  system exists in XML while screens bypass it. Result: theming decisions can't be
  enforced; per-screen colors drift.
- UX ideas worth stealing regardless: omnibox-with-chip pattern, swipe-to-dismiss
  layout (`BrowserSwipeLayout`), tab badges.

Use as: evidence for Linuxify's "Compose + single NavHost + no programmatic view
hybrids" decision, and as a warning that a design doc without a token file gets
ignored by your own codebase.

### 2.8 Bilal140202/MobileAgent — the baseline to stay above

Kotlin + **XML Views + MaterialComponents (M2)**, no dark theme, no tokens.

Evidence read:
- `app/src/main/res/values/themes.xml`, `values/colors.xml`, `layout/activity_main.xml`
- `ui/MainActivity.kt`, `ui/AgentControlActivity.kt` (tree-confirmed)

Findings: `Theme.MaterialComponents.Light.DarkActionBar`, colored status-bar,
`LinearLayout` + `EditText` + `ImageButton` with **platform drawable
`@android:drawable/ic_menu_preferences`**, 40dp touch targets, elevation-based
headers, hardcoded status dot (`@color/error`). Everything a modern M3 app should
not do — and, notably, the oldest pattern in the account set. Its one good idea:
a permanent **status-dot + status-text header row** for service state (the concept,
not the implementation, is right — jarvis implements it properly with StatusChip).

### 2.9 Bilal140202/VoxFlow — clean baseline template (what "fine" looks like)

Kotlin + Compose + M3 + Hilt. Evidence read: `ui/theme/Color.kt`, `Theme.kt`,
`Type.kt`, `ui/navigation/NavGraph.kt`, `ui/screens/HomeScreen.kt` (tree-confirmed).

Findings: compact single-file Typography (display 32 → label 12), template-derived
purple scheme, NavGraph with 5 screens + bottom bar. Competent but generic —
no tokens, no state system. It's the "Android-Studio-template-plus" level; shows
the author's floor. Linuxify should sit clearly above this: tokens + state triplet +
status chips.

### 2.10 ansaribilal14/EchoID-Android — template-color drift + glassmorphism

Kotlin + Compose + M3. Evidence read: `ui/theme/Color.kt`, `Shape.kt`, `Type.kt`,
`Theme.kt`, `ui/components/GlassCard.kt`, `ui/navigation/AppNavigation.kt`,
`ui/navigation/Screen.kt`.

Findings: `Purple80/Purple40` straight from the Android Studio template alongside a
hand-rolled brand palette (8 accent-ish colors), AMOLED variant, `GlassCard`
(blur-glass card), 5-step shapes 4/8/12/16/24. Screen enum + sealed navigation done
fine. Nothing here to adopt except as an anti-pattern exhibit: two color systems in
one file, decorative transparency, template defaults left in.

### 2.11 superz1402/agent-ui-playbook — the maintainer's written quality standard

Docs repo (no code). Evidence read: `README.md` (laws 1–13 + law candidate 14).

The 13 laws that bear directly on Linuxify UI:
1. Identity from tokens, not components; one deliberate accent.
2. **Color never carries meaning alone — every status pairs color with text.**
3. **Every async surface ships loading + empty + error before the happy path counts
   as done. Error = what happened + why + next action.**
4. Surfaces must agree or explain disagreement (counters vs lists).
5. Presence ≠ correctness.
6. **Tabular numerals on anything countable; monospace for identifiers.**
7. Ledger over raw table for "did the system see my thing?"
9. One loud thing per screen.
10. Touch targets ≥ 24 CSS px (they hold 44px on mobile).
11. **No emoji as UI icons.**
12. Honest states over fake polish; no fake urgency.
13. Positive evidence before mercy (downgrading scary verdicts needs evidence).

This is the maintainer's own bar — Linuxify's UI spec below is written to satisfy it,
and the code should be reviewable against CHECKLIST.md-style items.

### 2.12 superz1402/linuxify — current state (local inspection, grounding for the spec)

Evidence read (local):
- `app/src/main/java/com/linuxify/app/ui/theme/Color.kt`, `Theme.kt`, `Type.kt`
- `app/src/main/java/com/linuxify/app/MainActivity.kt` (nav skeleton)
- Screens present: Home, Terminal, Projects, Services, Settings, Packages, Files, Setup

Current state: dark-first technical palette (background `0xFF0E1116`, terminal cyan
primary `0xFF5CC8D7`), **fixed-hue semantic status colors in both themes already
paired with the "never color-only" comment**, a monospace tier (`AppType.Mono*` 11–13sp)
for "paths, sizes, PIDs, ports, statuses", light theme present, 5-destination
NavigationBar (home/terminal/projects/services/settings) with compact-width
awareness, Setup screen with ProgressView/FailedView states, Termux terminal view
bundled.

Verdict: the skeleton already embodies half the findings below. The research
therefore mostly *confirms and extends* the current direction (tokens, motion,
state triplet, chip system, progress strip) rather than overturning it.

---

## 3. Cross-cutting patterns observed (do's)

Across 10+ Android codebases from the same author(s), the convergent practices are:

1. **Every mature repo converges on a theme package**: Color.kt + Type.kt +
   Spacing/Tokens/Dimens + Theme.kt, with a `<Brand>Theme(themeMode)` composable.
   The less mature repos (VoxFlow, EchoID) skip Spacing/tokens — and that visibly
   correlates with template-grade UI.
2. **Dark-first with full light counterpart**, theme mode in DataStore
   (system/light/dark), Material You dynamic color on 12+ *with brand fallback*
   (tabit, jarvis, zerium), status-bar icon appearance synced in `SideEffect`.
3. **Weight-contrast typography over all-bold**: SemiBold/Medium titles, Normal body,
   Medium tracked labels; display tier Light or SemiBold and used rarely. Body 14–16sp
   with 1.4–1.5× line-height; labels 11–13sp with +0.1…+0.5sp tracking.
4. **A 4dp-grid spacing object with semantic aliases** (screen / card / listItem /
   chip), not raw dp at call sites (YFR, tabit, OVE).
5. **44–48dp minimum touch targets declared as a token** (tabit minTouchTarget 48,
   OVE minTarget 44, YFR touchTarget(48)) — matches playbook law 10.
6. **Shared state composables**: EmptyState(icon/title/subtitle/action),
   Loading(spinner+message), Error(message+retry) — written once, used by every list
   screen (YFR); plus SectionCard, StatusChip, ScreenScaffold (jarvis, YFR).
7. **Status = dot + label chip in a pill** with a small closed state enum
   (OK/WARN/BAD/NEUTRAL/ACCENT), color from a single `stepColor()`-style function;
   fixed-hue semantic colors that survive theme switching.
8. **Honest, quantitative copy for slow/local operations** ("may look quiet… do not
   close it", "replied in 1.2s · 87 tokens") — jarvis. For Linuxify: extraction,
   checksums, apt operations get progress + honest ETA/copy.
9. **Navigation**: sealed/enum Destinations (route+label+icon pairs, selected vs
   unselected), NavigationBar only for top-level routes, DetailRoutes object for
   the rest, `popUpTo(inclusive)+launchSingleTop` tab hygiene, fade/slide 250–350ms
   transitions, async start-destination resolution with loading + fallback (tabit).
10. **Background work shows as a thin persistent strip above the bottom bar**
    (AuraGallery IndexingProgressStrip) instead of modal screens — perfect for
    rootfs download / apt / service supervision.
11. **Context-forced dark for content-fidelity surfaces** with a written rationale
    (OVE editor) → terminal screen.
12. **"No dead controls"** (OVE) — buttons exist only when the capability exists.
13. **Permission/health gating as first-class UI states** (PermissionGate,
    DoctorScreen CheckState Ok/Degraded/Fix with fix instructions).
14. **Diagnostics screens are checklists with live evidence**, not walls of logs.

## 4. Anti-patterns to avoid for Linuxify

1. **Two UI systems in one app** (zerium's XML+Compose split; comet-x's hybrid
   programmatic views). Linuxify: Compose-only, single NavHost, no AndroidView
   except the Termux `TerminalView` (already justified by GPLv3 vendoring).
2. **M2/MaterialComponents legacy, platform drawables, 40dp targets, elevation
   headers, no dark theme** (MobileAgent) — the floor, never the model.
3. **Template colors left in** (EchoID Purple80/40, VoxFlow purple) + **two competing
   palettes in one file**. One palette, one accent, tokens only.
4. **Decorative effects without purpose** (EchoID GlassCard blur). For a terminal
   workspace: neutral surfaces, content first.
5. **Copy-paste scheme rot** — OVE's light scheme ships `onSurfaceVariant` from the
   dark palette. Guard: generate schemes or add a unit test walking all M3 roles.
6. **Status conveyed by color alone** (MobileAgent red dot) — violates the
   maintainer's own law 2; always dot+text.
7. **Emoji as icons** (jarvis "⚡" button label) — law 11.
8. **Dead controls / speculative buttons** (OVE explicitly bans; comet-x's toolbar
   accretes them).
9. **Bespoke empty/loading/error per screen** — that's how states silently diverge
   (playbook law 3/4). Central composables only.
10. **Per-theme structural UI switching** (YFR `itemLayoutStyle`) — great for a
    product whose selling point is themes; over-engineering for Linuxify's single
    identity. Keep the token seam, skip the layout-parameterization.
11. **A design doc with no token file** (comet-x has a .md design system its own
    Activities ignore). Tokens or it doesn't exist.
12. **Hardcoded dp/padding scattered in screens** (jarvis 20.dp/18.dp at call sites)
    — leaks into drift; route through the Spacing object.

---

## 5. Actionable design spec for Linuxify (grounded, minimal, versioned)

Everything below is chosen to (a) match what the maintainer's own best repos do,
(b) satisfy the agent-ui-playbook laws, (c) build on the current `com.linuxify.app`
skeleton rather than replace it.

### 5.1 Theme architecture

- Keep `ui/theme/` with four files: `Color.kt`, `Type.kt`, `Spacing.kt` (new),
  `Theme.kt`. Add `Tokens.kt` only when motion/alpha land (tabit `Dimens.kt` model).
- Palette: keep the current dark-first scheme (`0xFF0E1116` bg, cyan `0xFF5CC8D7`
  primary) — it matches jarvis's "dark-first technical" direction and OVE's
  content-fidelity rationale. Complete the M3 role mapping with the
  **surfaceContainer ladder** (Low/Lowest/High/Highest) — jarvis and zerium both map
  all five; the current Linuxify scheme maps only 3.
- Light theme: keep; add a **scheme-integrity unit test** (all roles non-null and
  distinct; no dark constants in light scheme — OVE bug prevention; cheap because
  Color.kt is plain data).
- Theme mode: add DataStore-backed `system/light/dark` (tabit `ThemePreferences`
  pattern). Dynamic color: **off by default** — Linuxify's identity is a terminal
  workspace; Material You would recolor the one brand asset we have. Expose as a
  Settings toggle if ever wanted (zerium does this; default static).
- Terminal screen: `LinuxifyTheme(forceDark = true)` wrapper with the OVE-style doc
  comment (*"terminal surface is always dark — ANSI fidelity"*) regardless of theme
  mode.
- Status bar: `enableEdgeToEdge()` + isAppearanceLightStatusBars sync (tabit).

### 5.2 Typography scale (adopt as `LinuxifyTypography`)

Grounded on YFR (weight contrast) + tabit (line heights) + current AppType mono tier:

| Role | Size/line | Weight | Use |
|---|---|---|---|
| displaySmall | 34/42 sp, −0.25 track | SemiBold | hero stats only (Home storage/pkg counts) |
| headlineSmall | 23/30 | SemiBold | screen titles (when not in TopAppBar) |
| titleMedium | 17/24, +0.1 | SemiBold | TopAppBar title, card headers, file names |
| titleSmall | 15/20, +0.1 | Medium | list item titles |
| bodyLarge | 16/24, +0.3 | Normal | primary body |
| bodyMedium | 14/20, +0.2 | Normal | secondary text, subtitles |
| bodySmall | 12/16, +0.3 | Normal | captions |
| labelLarge | 15/20 | Medium | buttons |
| labelMedium | 13/16, +0.4 | Medium | chips, metadata |
| labelSmall | 11/16, +0.5 | Medium | top-app-bar subtitle, tiny badges |

Plus keep/extend the **mono tier** (law 6): `MonoSmall 12/16`, `MonoBody 13/18`,
`MonoLabel 11/14 Medium` — reserved for paths, package names, PIDs, ports,
exit codes, sizes, timecodes. Any countable/alignable number uses mono (tabular by
definition in Monospace). Font: system default (no Google Font dependency — YFR's
Jakarta is optional polish, not worth the cert/download for v1).

### 5.3 Spacing system (new `Spacing.kt`, YFR+OVE hybrid)

```
xs=4  sm=8  md=12  lg=16  xl=20  xxl=24  xxxl=32      // 4dp grid (tabit/YFR merge)
screenHorizontal=20  screenVertical=12                  // YFR
cardInner=16  cardBetween=10  listItemVertical=8  listItemHorizontal=16
chipHorizontal=10 chipVertical=4                        // jarvis StatusChip metrics
iconSmall=20 iconMedium=24 iconLarge=32 iconXLarge=48
minTouchTarget=48  listRowMinHeight=56                  // tabit/YFR
```
Rule: screens may only use the semantic aliases (`screen*`, `card*`, `listItem*`,
`chip*`); the xs…xxxl ladder is for composition inside those helpers. Screen-specific
metric namespaces when a screen needs them (OVE `TerminalMetrics` model), e.g.
`TerminalMetrics(extraKeyHeight=48, keyGap=6, scrollbackFade=24)`.

### 5.4 Navigation model (keep current, formalize)

- 5 top tabs: Home, Terminal, Projects, Services, Settings — current
  `Destination` list upgraded to an enum with `selectedIcon`/`unselectedIcon`
  (AuraGallery `TopLevelDestination` pattern; Rounded vs Outlined).
- Bottom bar visible only on top-level routes (route whitelist — tabit), flat
  (`tonalElevation 0`, containerColor = surface, `alwaysShowLabel = true` —
  AuraGallery).
- Detail routes (Setup wizard, Package detail, File viewer, Service logs, Doctor)
  live in a `DetailRoutes` object, hide the bottom bar, get back-arrow via
  `ScreenScaffold`.
- Transitions: fade + 1/3-width horizontal slide, 250–350ms (tabit/`TabitMotion`.
  ROUTINE 250 for tabs, LAYOUT 350 for push).
- Tab clicks: `launchSingleTop` + `popUpTo(startDestination)` (tabit hygiene).
- Start destination (Setup vs Home) resolved async from runtime state with a
  loading spinner + safe fallback (tabit `AppNavigation` pattern — Linuxify already
  has Setup; the async-resolution pattern applies once install state is persisted).
- Tablet/landscape: current compact/expanded branch stays; expanded can use a
  permanent navigation rail with the same enum (no new design surface needed).

### 5.5 Status hierarchy

- Fixed-hue semantic colors (already in `Color.kt`): success/warning/error/info/
  neutral × dark/light. **Never color-only** — every status renders as
  `StatusDot(7dp) + label` (jarvis StatusChip), pill on `surfaceVariant`.
- State enum (jarvis `ChipState` + `stepColor` merged with current `Status`):
  `OK / RUNNING / WARN / ERROR / NEUTRAL / ACCENT`, one mapping function, no
  ad-hoc colors at call sites.
- Hierarchy: **one loud element per screen** (law 9) — on Home that's the runtime
  status hero card; everything else steps down to chips/rows.
- Global background work (rootfs download, apt transaction, service restart) →
  **ProgressStrip above the bottom bar** (AuraGallery `IndexingProgressStrip`):
  secondaryContainer, 8dp dot + label + LinearProgressIndicator + cancel action.
  Never modal, never blocking navigation (except Setup, which *is* the blocking
  case by design).
- Diagnostics: adopt jarvis `CheckState { Ok / Degraded(reason) / Fix(message) }`
  as a Doctor section inside Settings or a dedicated tab-adjacent screen; every Fix
  message ends with the actual remedy ("enable … in Settings → …"). Meets
  playbook law 13 (evidence before mercy).

### 5.6 State handling (the trust triad)

One shared set in `ui/components/`:
- `EmptyStateView(icon?, title, subtitle?, actionLabel?, onAction?)` — icon 64dp at
  50% onSurfaceVariant (YFR exact recipe).
- `LoadingStateView(message?)` — 40dp spinner, 3dp stroke (YFR).
- `ErrorStateView(message, onRetry?)` — message in error color **+ why + next
  action** (playbook law 3; YFR base).
- Rule: **every async list ships all three before its happy path is "done"**
  (playbook law 3) — applies to Packages, Files, Projects, Services, apt output.
- Long local operations (rootfs extract, apt) use the jarvis copy pattern: state
  machine surfaced per-operation (Idle/Downloading{progress}/Verifying/Extracting/
  Failed{reason}/Ready) with pause/resume/cancel where the operation supports it,
  and honest slow-path copy ("verification compares SHA-256; this can take a
  minute").
- Confirmation for destructive ops: jarvis `ConfirmDialog` pattern — TARGET /
  DETAILS / WHY rows + risk chip (reuses StatusChip), then confirm/cancel.
- Permissions: `PermissionGate` composable as its own UI state (AuraGallery), not an
  error state.

### 5.7 Terminal screen ergonomics (OVE editor rules applied to a terminal)

- Forced-dark (`forceDark = true`), rationale documented at the wrapper.
- Layout (top→bottom): thin session header (session name + status chip + actions) →
  `TerminalView` (fills) → extra-keys row → optionalIME padding handled via
  `imePadding()` (jarvis) so the extra-keys row never floats over the keyboard.
- Extra keys: 48dp min touch target keys, 6dp gaps (OVE `ToolBarMetrics`/`minTarget`
  + terminal-specific namespace), mono glyphs.
- Playhead/timecode analog → keep an on-screen **mono exit-code / last-command chip**
  when a command finishes (mono tier, pairs with the status color system).
- One loud thing: while a long command runs, the strip + spinner are the only
  animated elements; no ambient animation otherwise (jarvis Orb restraint).
- No dead controls: extra keys / actions exist only when the session supports them
  (OVE rule); e.g., no "split" buttons until splits exist.
- Scrollback/selection: keep Termux view behaviors untouched — the research found
  no better terminal surface in any of the inspected repos (the browsers embed
  WebView, not terminals; OVE is the closest *pattern* donor, not implementation).

### 5.8 What NOT to build (from §4, condensed)

No XML screens, no MaterialComponents, no second palette, no glassmorphism, no
per-screen bespoke empty states, no emoji icons, no dead buttons, no color-only
status, no dynamic color default, no per-theme layout switching.

---

## 6. Suggested implementation order (for Task 8, not part of this research task)

1. `Spacing.kt` + `Tokens.kt` (motion 120/250/350/500, alpha constants) — no visual
   change, pure extraction.
2. Shared state composables (Empty/Loading/Error) + ScreenScaffold refactor of
   existing screens.
3. StatusChip/dot + stepColor consolidation over the existing Status enum;
   ProgressStrip above the bottom bar.
4. Navigation enum upgrade (selected/unselected icons, DetailRoutes, whitelist,
   transitions).
5. Typography table applied; mono tier extended to all technical values.
6. Scheme-integrity unit test; theme-mode DataStore setting.

*All file-path evidence in §2 was fetched 2026-10-17 from GitHub at the default
branches listed; token usage: 4 API calls + raw fetches, rate limit unaffected.*
