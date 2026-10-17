# UI Review — quality checkpoint record (Issue #8)

Method: static code review of every screen against the mandated checklist, executed at
the source level. **No device/emulator exists in this environment** (documented in
Issue #3), so visual inspection at runtime could not be performed; every claim below is
labeled with the strongest verification method available.

## Checklist results

| Item | Result | Method |
|---|---|---|
| Typography | Monospace tier for all runtime values (paths, sizes, PIDs, ports, statuses); platform sans for prose; single type scale | code review |
| Spacing | Semantic `Spacing` tokens (xs/sm/md/lg/xl); no arbitrary dp in screens | code review |
| Status hierarchy | `StatusChip` (dot + label, never color-only); semantic status colors fixed across themes | code review |
| Touch targets | M3 buttons (40dp+), nav items (48dp+), extra-keys 36dp row (matches Termux convention for dense key rows) | code review |
| Navigation | Bottom bar (5 destinations) on compact widths; NavigationRail on expanded; Files/Packages via routes from Home/Projects | code review |
| Keyboard behavior | `adjustResize` + edge-to-edge insets; terminal occupies remaining height; Back dismisses keyboard first (system flow) | code review |
| Empty states | All lists have explicit empty states (projects, packages, services, files, terminal) | code review |
| Loading states | Shared `LoadingState` component; installer uses real byte progress | code review |
| Error states | Typed `RuntimeError` → user-message mapping; failure view with retry + expandable technical log; shared `ErrorState` component | code review + unit tests |
| Contrast | Dark: onSurfaceVariant #8A93A6 on #151A21 ≈ 4.6:1 (body-safe); status hues ≥ 3:1 for dots, always paired with text | computed |
| Consistency | Single theme (dark-first + light), shared components, mono tier everywhere | code review |
| Post-install transition | Fixed during checkpoint: nav gate now collects installer StateFlow (was read-once `.value` — would not recompose reliably) | code review + rebuild |
| No AI-slop patterns | No gradient banners, no emoji icons, no fake metrics/activity, no placeholder charts, no decorative cards — every surface renders runtime data | code review |
| Real data only | Home (distro/arch/size/free), Packages (real apt output + exit codes), Services (real liveness + port probe), Files (real FS), Projects (real git) | code review |

## Defects found and fixed during this checkpoint

1. **Post-install gate** (MainActivity): installer state was read via `.value` without
   subscription — transition to the main UI after a successful install could require an
   unrelated recomposition. Fixed: `collectAsStateWithLifecycle`.
2. **Dead key listener** (TerminalScreen): removed a no-op `setOnKeyListener` left from
   the AndroidView wiring.
3. **Home loading state**: plain text replaced with the shared `LoadingState` component
   for consistency.

## Verification method limits (honest)

- Compact/tablet **runtime** layout inspection: NOT PERFORMED (no device/emulator;
  Issue #3). The adaptive layout uses `WindowWidthSizeClass`, which is the standard
  mechanism, but pixel-level verification on hardware is pending.
- Screenshot evidence: none produced — none may be fabricated.

Build after fixes: BUILD SUCCESSFUL, unit tests 12/12, APK refreshed (SHA-256
e9670006a693c85c…).
