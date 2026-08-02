# OTOZINE — UI brief

> Paste your own design direction here, then this document below it.
> This file describes **structure only** — pages and the elements on them.
> No visual language is specified on purpose.

**App in one line:** an offline-first music player that stores its library on a
USB drive and uses on-device ML to build queues that never repeat themselves.

**Imagery:** many tracks have no cover art. Use placeholder / generated imagery
wherever artwork appears — treat missing art as the normal case, not an edge case.

**Platform:** Android phone, portrait, one-handed use. Dark and light both needed.

---

## Navigation

- 4 bottom tabs: **Play · Library · Search · Map**
- A persistent mini player sits above the bottom tabs on every tab
- Now Playing is a full-screen overlay that expands from the mini player
- Settings and Drive are reached from a header icon, not a tab

---

## 1. App shell

- Bottom tab bar, 4 items with icons + labels
- Mini player bar: thumbnail, title, subtitle, play/pause, thin progress line
- Top header: status pill (storage/connection state), settings icon

## 2. Now Playing

- Large artwork
- Title, subtitle line, secondary metadata line
- One-line "reason" chip (tappable)
- Seek bar with elapsed / remaining, plus 3 small markers along the track
- Waveform or progress visual
- Transport row: previous, play/pause, next
- Secondary row: shuffle mode, repeat mode
- Row of small metadata badges (4–5 short values)
- Action row: favourite, hide/veto, add to playlist, lyrics, timer, overflow
- Output-device pill (shows which headphones/speaker is active)
- Swipe down to dismiss, swipe left/right to change track

## 3. Play (home)

- Large hero card: continue/resume, with a one-line reason
- Horizontal row of preset chips (6–8, scrollable)
- One labelled slider
- Horizontal card carousel — section A (with heading + "see all")
- Horizontal card carousel — section B
- Horizontal card carousel — section C
- Small inline chart/sparkline
- Empty state variant for first-time users

## 4. Queue

- Two grouped sections with headers
- Reorderable list rows with drag handles
- Swipe-to-remove on rows
- Each row: thumbnail, title, subtitle, small caption tag
- Header info strip
- Primary action button

## 5. Reason / explanation sheet

- Bottom sheet
- 3–4 horizontal bar rows, each with a label, a value bar, and two small
  thumbs-up / thumbs-down buttons
- A grouped block of read-only attribute chips
- Small 2-axis mini-plot
- Two wide buttons side by side
- Footer stat line

## 6. Vibe / mood control sheet

- Bottom sheet
- Large square 2-axis pad with a draggable puck and 4 edge labels
- 5–6 preset markers placed on the pad
- Row of toggle chips (multi-select)
- One range slider
- Segmented control (3 options)
- Full-width primary button

## 7. Library home

- Tab bar with 6 scrollable tabs
- Grid view (2–3 columns) of cover cards with title + caption
- List view alternative, toggleable
- Sort control
- A–Z fast scroll strip
- Count summary line

## 8. Collection detail (used for 3 similar screens)

- Large header image with gradient/overlay treatment
- Title, subtitle, 3–4 stat values
- Two primary buttons + one icon button
- Numbered track list
- One or two horizontal "related" carousels at the bottom
- Collapsing header on scroll

## 9. Playlist detail

- Same header pattern as screen 8
- Editable, reorderable list
- Rule-summary strip (only for auto-generated playlists) with an edit button
- Overflow menu

## 10. Playlist rule builder

- Stacked condition rows, each: field dropdown, operator dropdown, value input
- Add-condition button
- AND/OR group toggles
- Live result count
- Save / cancel

## 11. Track detail / edit

- Header with artwork + title
- 6 editable text fields, each with a small source label and a pin toggle
- Read-only stats block (8–10 label/value pairs)
- Removable tag chips, grouped
- File info block
- 3 action buttons

## 12. Search

- Search field, full width, with clear button
- Row of filter chips below the field
- Toggle between two search modes (or auto, with results marked)
- Results in grouped sections with headers
- Recent searches list (empty state)
- Suggested chips (empty state)

## 13. Map / visualisation

- Full-screen scatter plot of many small dots, pinch + pan
- Overlaid path/trail line
- Floating legend
- Segmented control to change colour mode
- Selection tool
- Floating action button
- Bottom sheet appears on selection

## 14. Storage & sync

- Large status banner
- Two horizontal usage bars with labels and markers
- 3-up stat row
- Timestamp line
- 3 stacked action buttons
- Expandable list section
- Secondary info panel

## 15. Library health

- Sectioned list, worst-first
- Rows with a severity indicator, title, caption, and chevron
- Small stats header
- Per-category progress bars
- Filter tabs

## 16. Settings

- Standard grouped settings list
- Toggles, sliders, dropdowns, navigation rows
- Section headers
- Sub-page: audio profiles — segmented control (3 options) with an EQ curve
  editor, a slider group, and a dropdown per profile

## 17. Lyrics

- Full-screen scrolling text, one line highlighted
- Tap a line to seek
- Small artwork thumbnail
- Toggle for sync on/off

## 18. Timer

- Bottom sheet
- Segmented options (5)
- Custom value picker
- Toggle
- Confirm button

## 19. Stats / recap

- Scrollable report page
- Period selector at top
- Large number cards (3–4)
- Ranked top-5 lists (3 of them, with rank numbers + thumbnails)
- One bar chart, one line chart
- Share button

## 20. Party / shared queue

- QR code block
- Connection status + guest count
- Live-updating queue list with attribution per row
- Host controls row
- Start/stop button

## 21. First run

- 5 sequential steps with a progress indicator
- Step 1: intro, illustration + text + continue
- Step 2: permission request card
- Step 3: folder picker trigger + selected-path display
- Step 4: progress screen with live counts and a log strip
- Step 5: completion + primary CTA

---

## Reusable components

- Track list row (thumbnail, title, subtitle, caption, trailing action)
- Cover card (image, title, caption) — grid and carousel variants
- Metadata badge / pill
- Reason chip
- Chunky primary button, secondary button, icon button
- Filter chip (selectable) and tag chip (removable)
- 2-axis pad control
- Mini 2-axis plot
- Usage bar with budget marker
- Bottom sheet container
- Section header with "see all"
- Collapsing image header
- Segmented control
- Empty / loading / error state blocks

## States needed for every list or content screen

- Loading (skeleton)
- Empty
- Error
- Offline (must read as normal, not as a failure)
- Degraded (content partially unavailable — still usable)
