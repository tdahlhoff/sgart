# UI patterns — the layout rules the app follows

These are the recurring layout and design decisions made while polishing the list, invite and profile
screens. They sit on top of the design system (`_bmad-output/planning-artifacts/ux-designs/.../DESIGN.md`:
flat-forward, hairlines instead of shadows, warm neutrals, one hue = one meaning) and are what a new or
reworked screen is checked against. Colours always come from the theme tokens (`context.sgartColors`);
no literal hex values outside `theme/tokens`.

## Surfaces and rows

1. **White chrome bars.** Header, filter strips, action-button rows and bottom action bars use the white
   `surface` with a `border` hairline on the edge facing the content. The page behind them is the tinted
   `background`, so bars frame the content. Examples: list-detail action bar, lists filter strip,
   fast-add bar.
2. **Striped rows.** Every list of rows is wrapped in `StripedRow(index:)` — every second row gets a faint
   neutral tint. Rows run edge to edge; each row insets its own content by `SgartShapes.cardPadding`.
   The tint is drawn on a `Material` (a plain `ColoredBox` hides `ListTile` tap ripples, and Flutter
   asserts on it). Terminal and pending rows keep their own semantic colours.
3. **Section headings.** A section starts with a `titleMedium` heading, 8px above its content, with about
   32px between sections. Small grey `labelMedium` labels are for captions, not for headings.

## Actions

4. **Primary action in the thumb zone.** The main create or confirm action of a list screen is pinned in a
   white bar at the bottom (hairline above), not placed under the last row where it moves with the list.
   The bar only exists where the action does (not on read-only tabs, not for roles without the right).
5. **Quiet destructive actions.** Rare or destructive actions (replace code, delete household) use the
   outlined secondary button, sit out of the way of the everyday actions and stay behind a confirmation.
6. **Small controls stay small.** A chip keeps its visual size (beige `chipBackground`, 6px/14px padding);
   only its invisible tap area is enlarged — about 6px around it, never a button-sized box. Icon buttons
   keep the full 48dp.

## Feedback

7. **Errors next to their cause.** A rejected action shows one small line of `onErrorTint` text
   (`InlineActionErrorText`) right above the input or action it belongs to, with 12px above and 6px below.
   It clears when the text is edited. `ActionErrorBanner` (tinted, animated, dismissible) is for errors that
   must stand out. Pink is a fill colour: never as text or hairline.
8. **Floating suggestions.** Suggestion lists attached to an input are overlays anchored to the input's bar
   (they dip 6px into it) and never push the layout; they are exactly as tall as their rows.
9. **Show what was added.** After adding an item the list scrolls to it, and re-checks after the scroll
   extent settles (matters with animations off, e.g. on the emulator).

## Dialogs and sheets

10. **Shrink-wrap.** A `Column` inside an `AlertDialog` or bottom sheet sets `mainAxisSize: MainAxisSize.min`;
    the default fills the whole height the dialog may use. In tests, measure the dialog's visible `Material`
    card, not the `AlertDialog` widget (which always spans the route area).

## Process

11. Layout behaviour is covered by widget tests that describe it in a sentence; colours by contrast tests
    on the token (AA, both modes). The full `flutter test` and `flutter analyze` run before a commit. The
    emulator ships with animations off — check scroll and transition behaviour with them on as well.
