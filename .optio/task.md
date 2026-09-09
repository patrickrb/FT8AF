# Add WSJT-X-style worked stations flag handling with configurable behavior

Add WSJT-X-style worked stations flag handling with configurable behavior

## Summary
Implement WSJT-X-style handling for the “worked stations” flag so users can control when and how worked stations are indicated or cleared.

## Background
The current behavior in FT8AF should be updated to better match WSJT-X. We should first research exactly how WSJT-X handles worked-station state and clearing behavior, then align FT8AF’s implementation with that model.

Based on current understanding, WSJT-X appears to support:
- different display behaviors for worked stations: hide, ignore, or highlight
- different time scopes such as today
- different context scopes such as before, on band, and from list

Because the exact WSJT-X behavior is not yet fully confirmed, this issue should cover both the research needed to verify the behavior and the implementation work required to bring FT8AF in line with it.

## Proposed work
- Research how WSJT-X tracks and clears worked-station flags
- Document the supported modes and scopes in WSJT-X
- Implement equivalent or closely matching behavior in FT8AF
- Add a user-facing setting to control worked-station handling behavior
- Ensure the setting covers both display mode and scope where applicable

## Expected settings/options
Provide a configurable setting that allows users to choose how worked stations are treated, likely along these lines:
- hide
- ignore
- highlight

And allow users to choose the scope or basis for the worked-state logic, based on the WSJT-X model, such as:
- today
- before
- on band
- from list

## Acceptance criteria
- FT8AF behavior is based on verified WSJT-X behavior rather than assumptions
- Users can configure how worked stations are displayed or filtered
- Users can configure the scope used for worked-station matching/clearing
- The default behavior is sensible and documented
- The implementation is tested against the researched WSJT-X behavior

## Notes
If WSJT-X behavior differs from the assumptions above, the implementation and UI should follow the researched behavior rather than this preliminary list.

---
*Optio Task ID: 9ee11633-19c0-449d-b027-2354acc9008b*
*Source: [github](https://github.com/patrickrb/FT8AF/issues/477)*