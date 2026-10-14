# Player sheet layer discovery

Read-only discovery on run `perf-output/20261013-191357` (SM-S938U, Android 17, 60 Hz, commit
`f8b5121`, dirty tree). Scenarios `expandCollapse` and `queueExpandCollapse`, 10 iterations each
(998 and 1000 drawn frames). No code was changed and the phone was not touched. The trace
queries were ad hoc TraceProcessor scripts run from the perf venv and are not committed;
section 8 has the one query needed to re-check the main result in the Perfetto UI.

Geometry on this phone: density 3.75, so the 64 dp mini bar is 240 px, the 71 dp nav is 266 px,
the 24 dp corner is 90 px, the sheet is 1440 px wide and 3064 px tall when expanded.

Two kinds of statement appear below. "Measured" comes from the traces. "Inferred" rests on how
HWUI and Skia work in AOSP as I remember the source; it was not checked against this Samsung
build and is marked where it matters.

## 1. Findings in short

1. The near-full-screen "texture" is not a layer. It is a one-byte-per-pixel coverage mask of
   exactly the sheet's bounds (1440 x sheet height), drawn on the CPU on the `hwuiTask` threads
   and uploaded once per frame while the sheet moves. Measured.
2. Its height equals the sheet height frame by frame: identical to the queue list layer's
   height in 280 of 280 frames, and within 8 px of the height implied by the bottom nav in 824
   more. Measured.
3. The owner is the sheet's top-rounded clip, `PlayerSheetSkeleton.kt:252`. The trace cannot
   name it; the attribution rests on six independent signatures (section 2.3) and on that clip
   being the only non-rectangular clip in the sheet. Inferred, strongly supported.
4. The mask is only needed while the corner radius is a fractional pixel value. At rest (radius
   exactly 90 px or 0) there is none, and 55 moving frames with none line up with the radii that
   happen to survive a float round trip (expected 28.0 in `expandCollapse`, found 29).
5. Cost in `expandCollapse`: 2006 ms of 8774 ms RenderThread time is the upload itself, 981 ms of
   that a `vkAllocateMemory` nested in it. Once the mask passes 4 MiB (sheet taller than 2912 px)
   every upload allocates and frees a staging buffer: upload p50 5.6 ms against 0.5 ms below.
6. Queue mode adds a real varying-size layer: the queue list's `Offscreen` layer
   (`PlayerSkeletonQueueContent.kt:261`), whose bounds are 1440 x sheet height. It is
   reallocated 12 times per iteration (640 ms of `vkAllocateMemory`, up to 10.8 ms each).
7. Queue mode's extra main-thread time is the list being re-measured every frame, rows composed
   as the viewport grows, and every visible row recomposing in the one frame where `enabled`
   flips at progress 0.7 (5.9 ms of recomposition in that frame).
8. The background layer is a constant 1440x3064. It is not the varying layer, but its blur chain
   is re-run on every frame in which the sheet's pixel height changes.

## 2. Trace side

A frame here is one `DrawFrames <vsync>` slice on the RenderThread, joined to
`trace-frames.csv` by vsync id.

### 2.1 What the RenderThread slices expose

| Slice | What it gives |
|---|---|
| `Texture upload(<id>) WxH` | CPU pixels written into a GPU texture. `<id>` is a Skia resource id, not a view. |
| `drawLayer [graphicsLayer] W x H` | An offscreen layer re-rendered this frame, with the node's size. Every Compose layer is named `graphicsLayer`, so size is the only identity. |
| `alpha caused saveLayer WxH` | A node with alpha between 0 and 1 drawn through its own offscreen layer, with the node's size. |
| `flush layers` | GPU work for the layers redrawn this frame. |
| `Vulkan finish frame` | The main flush. |
| `vkAllocateMemory`, `allocateImageMemory`, `vkFreeMemory` | Driver memory calls; the parent slice says what for. |
| `OpsTask::onPrepare` / `onExecute` | One pair per render pass. |
| `Bitmap#prepareToDraw WxH` | A bitmap uploaded between frames. |

There are no slices for `saveLayer` from a draw call, no RenderNode ids, and none of Skia's
internal mask or path-renderer events.

### 2.2 Upload height against sheet height

Three signals in the same traces give the sheet's progress independently of the upload:

| Signal | Relation to progress | Result against the 1440xH upload |
|---|---|---|
| Queue list layer, `drawLayer 1440 x H` (queue run, progress 0.7 and up) | H is the sheet height, same frame | Equal in 280 of 280 frames |
| Bottom nav layer, `alpha caused saveLayer 1440xN` | N = 266 x (1 - progress), one frame late (see 7.1) | Upload H minus 240 + 2824 x (1 - N/266) taken from the next frame: mean +1.5 px, p5 -2.6, p95 +5.8, max 8, over 410 and 414 frames. One nav pixel is 10.6 sheet pixels, so this is at the limit of the signal. |
| Art glow layer, `drawLayer NxN` | N follows the art size, 44 dp + 276 dp x progress squared, in 15 px steps | Agrees within its 15 px quantisation (median -3.9 px, 251 frames) |

First expand of `expandCollapse` iteration 3:

| Frame | Upload | Upload ms | Next frame's nav height | Sheet height that implies | RenderThread ms |
|---|---|---|---|---|---|
| 0 | 1440x357 | 0.15 | 255 | 357 | 13.0 |
| 2 | 1440x986 | 0.18 | 196 | 983 | 7.6 |
| 4 | 1440x1636 | 0.34 | 135 | 1631 | 10.8 |
| 6 | 1440x2143 | 0.45 | 87 | 2140 | 13.6 |
| 8 | 1440x2493 | 0.61 | 54 | 2491 | 16.1 |
| 10 | 1440x2725 | 0.44 | 32 | 2724 | 8.2 |
| 13 | 1440x2913 | 7.52 | 14 | 2915 | 16.1 |
| 20 | 1440x3043 | 4.95 | 2 | 3043 | 12.0 |
| 36 | 1440x3064 | 14.10 | none | 3064 | 28.3 |

Both scenarios show 59 distinct upload heights and 64 frames with an upload per iteration (640
and 644 frames). There is one upload per moving frame, plus a second 1440x240 one while the mini
content is fading.

### 2.3 What the upload is

- **One byte per pixel.** A `vkAllocateMemory` nested in the upload appears when width x height
  passes 4 MiB, which 1440x2913 does by 416 bytes:

  | Upload height | Size as 1 byte/px | Uploads | Upload p50 / p90 / max ms | With nested allocation |
  |---|---|---|---|---|
  | 241-1024 | 0.33-1.41 MiB | 169 | 0.13 / 0.41 / 2.58 | 10% |
  | 1025-2048 | 1.41-2.81 MiB | 61 | 0.30 / 1.61 / 3.34 | 11% |
  | 2049-2912 | 2.81-4.00 MiB | 90 | 0.49 / 0.80 / 5.61 | 8% |
  | 2913-3064 | 4.00-4.21 MiB | 320 | 5.63 / 8.45 / 14.27 | 93%, allocation p50 2.95, max 8.36 |

  (`expandCollapse`. In the queue run only 18% of the tall uploads allocate; the buffer is
  reused more often there, and I could not tell why from the trace.)
- **Drawn on the CPU.** The `hwuiTask` threads run for 3.17 ms on average inside frames that
  have the upload (1.1 ms for heights up to 1024, 2.7 ms up to 2048, 4.1 ms above) and for
  0.000 ms inside every one of the 358 frames that do not.
- **Uploaded at the flush's upload point.** All 819 uploads sit directly under
  `Vulkan finish frame`, after the last `onPrepare` and before the first `onExecute`, never
  inside an op. Every upload there is 1440 wide.
- **A pooled texture.** One texture id serves each of Skia's size bins (up to 512, 1024, 1536,
  2048, 3072 high) and is reused frame after frame, with different heights written into it. A
  view's own layer would have one size and one owner.
- **Sized to a draw, twice.** The 1440x240 upload occurs in exactly the frames where the mini
  content's alpha layer is on screen and the sheet mask is uploaded: 179 of 179 and 180 of 180,
  with no upload in the 100 frames per run where that layer is on screen at rest. Only two
  things in the sheet touch its top corners: the background (whole sheet) and the mini row
  (top 240 px).
- **Absent at rest.** No upload while expanded or collapsed, including the four frames per
  iteration at the expanded anchor in which the background was redrawn under the same clip.
- **Dependent on the exact radius, not the bounds.** Inside the animation, 29 of 669 moving
  frames (26 of 670 in the queue run) have no upload, at the same frame indexes each time:
  29 and 35 in every iteration, 57 in 9 of 10 player iterations and 6 of 10 queue iterations.
  The sheet moved in them: in the queue run the list layer goes 3062 to 3063 px at frame 29
  with no mask, and frame 30 has one at the same 3063 px.

Inferred mechanism. `RoundedCornerShape(topStart, topEnd)` has unequal corners, so Compose hands
the platform a path outline instead of a round rect. Skia recognises such a path as a rounded
rectangle and rebuilds the radii from the path's points; the top-right corner's x radius comes
back as 1440 - (1440 - r), which in 32-bit floats differs from r unless r is a multiple of
1/8192 px. Skia's analytic rounded-clip requires the two top radii to be exactly equal and
circular, so it falls back to a software mask covering the draw. Simulating that round trip
over the radii of the 640 mask frames predicts 28.0 frames that pass by chance; the trace has
29. Every integer radius from 1 to 90 passes.

### 2.4 Where the RenderThread time goes

Totals over 10 iterations, in ms:

| | `expandCollapse` | `queueExpandCollapse` |
|---|---|---|
| RenderThread frame time (`DrawFrames`) | 8774 | 9397 |
| 1440-wide mask uploads | 2006 (819 uploads) | 693 (824) |
| of which nested `vkAllocateMemory` | 981 (348) | 242 (169) |
| `vkFreeMemory` in the main flush | 329 (241) | 141 (81) |
| `flush layers` | 572 | 1927 |
| of which `vkAllocateMemory` | 98 (77) | 779 (174), max 18.6 |
| of which `vkFreeMemory` | 128 (133) | 397 (434) |
| Image allocations in the main flush | 54 (30) | 325 (170) |
| `hwuiTask` CPU while drawing masks (not frame time) | about 2030 | about 2040 |

Frame time by whether the frame has a mask (`expandCollapse`; cpu ms is main start to
RenderThread end, animation windows only):

| Frame class | Frames | RenderThread p50 / p90 / p99 | cpu p50 / p90 / p99 |
|---|---|---|---|
| No mask | 358 | 4.1 / 7.2 / 11.7 | 7.2 / 11.7 / 17.6 |
| Mask under 4 MiB | 320 | 7.9 / 13.3 / 18.0 | 12.2 / 20.1 / 31.6 |
| Mask over 4 MiB | 320 | 13.8 / 19.7 / 31.7 | 16.8 / 28.0 / 39.1 |

The moving frames that happen to need no mask are a natural A/B (medians over iterations):

| Frame | What differs from its neighbours | RenderThread ms | cpu ms | Neighbours |
|---|---|---|---|---|
| 35 | No mask; sheet pixel height unchanged | 2.1 | 3.9 | 14.0 / 16.3 |
| 29 | No mask; height changed, background filter re-run | 3.3 | 4.9 | 12.7 / 14.2 |
| 57 | No mask; mid-collapse, about 2 MiB mask avoided | 5.8 | 7.8 | 8.2 / 11.3 |

So late in the expand the mask costs about 10 ms of a 14 ms RenderThread frame, and the main
thread's "waiting on RenderThread" follows from it.

### 2.5 The layers HWUI reports

Per 10 iterations. "Surface changes" counts frames in which the layer's size, rounded up to 64
px (HWUI's layer allocation step, inferred), differs from its previous one.

| Layer (identified by size) | Run | Redraws | Distinct sizes | Surface changes | `flush layers` allocation ms on those frames |
|---|---|---|---|---|---|
| Queue list, 1440 x sheet height, `Offscreen` | queue | 290 | 29 | 120 | 640 |
| Art glow, square 195-1215, blur | player | 299 | 29 | 210 | 56 |
| Bottom nav, 1440 x 1-266, alpha | both | 439 | 45 | 80 | 13 to 31 |
| Background, 1440x3064, RenderEffect | player / queue | 219 / 360 | 1 | 10 | 6 |
| Controls overlay, 1440x1125, alpha | both | 30 | 1 | 10 | 21 (player run) |
| Mini content 1440x240, title row 1320x161, queue header 1260x510, alpha | both | 20 each | 1 | 10 | under 1 |
| Transport icons, 210 x 7-210, alpha | both | 190 | 9 | 50 | 2 |

The main flush runs 18 render passes in frames where the sheet's pixel height changed and 1 in
frames where it did not, whether or not the background's own content was redrawn (11 passes for
a redraw at rest). The background's filtered image is therefore recomputed whenever the clip
around it changes (inferred: HWUI caches it per clip bounds). Frame 29 against frame 35 puts
that at about 1.2 ms of RenderThread CPU; GPU time is not visible in these slices.

### 2.6 What the trace can and cannot attribute

Can: the size, timing, pixel format and CPU origin of the upload; that it tracks the sheet
height; that it is a pooled mask rather than a view's layer; every offscreen layer's size per
frame and which ones are reallocated; which memory calls belong to uploads, layers or the main
flush.

Cannot: which node or clip requested the mask (the slice carries a Skia resource id and a size,
nothing else); whether Skia took its clip-mask or its software path route (those events are not
emitted; the draw-sized 1440x240 twin points to a clip mask); which composable a `graphicsLayer`
is, other than by its size; GPU-side cost of the blur.

## 3. Source side

Bounds are in px on this phone. "Near full screen" means comparable to 1440x3064. All paths are
under `app/src/main/java/com/v1per/synthx/ui/`.

| # | Where | What | Bounds | Near full screen | Changes with sheet progress | Seen in the trace as |
|---|---|---|---|---|---|---|
| 1 | `SynthXBottomNav.kt:229-232` | `offset`, `height`, `clipToBounds`, `graphicsLayer { alpha = 1 - progress }` on the nav | 1440 x 266 to 0 | No | Height, offset and alpha, one frame late | Alpha layer `1440x1..266` |
| 2 | `player/PlayerSheetSkeleton.kt:246` | `padding(bottom = bottomInset)` | n/a | n/a | 266 to 0 px | None |
| 3 | `player/PlayerSheetSkeleton.kt:250` | `height(sheetHeight)` | 1440 x 240 to 3064 | Yes | Yes | Drives rows 4, 18-20 |
| 4 | `player/PlayerSheetSkeleton.kt:252` (radius at `:179`) | `clip(RoundedCornerShape(topStart, topEnd))`, radius 0 to 24 dp | Same as the sheet | Yes | Size and radius | No layer; the `1440xH` mask upload |
| 5 | `player/PlayerSheetSkeleton.kt:172` | `animateFloatAsState` for the controls' queue-scroll hide | n/a | n/a | No | None |
| 6 | `player/PlayerAltStageBackground.kt:76-82` | `fillMaxWidth`, `requiredHeight(fullHeight)`, bottom-anchored | 1440x3064 | Yes | No | Constant layer size |
| 7 | `player/PlayerAltStageBackground.kt:87-90`, effect in `PlayerAltStageEffects.kt:55-73` | `graphicsLayer { renderEffect; clip = true }`: 120 dp blur, AGSL twist, saturation | 1440x3064 | Yes | No; the visible part is cut by row 4 | `drawLayer 1440x3064` at 15 fps; 18 passes per moving frame |
| 8 | `player/PlayerAltStageBackground.kt:85, 107-115` | `drawWithContent` scrim | 1440x3064 | Yes | No | Inactive, `ScrimAlpha` is 0 |
| 9 | `player/PlayerSkeletonMiniContent.kt:64` | `alpha(miniContentAlpha)`, 1 to 0 over progress 0-0.33 | 1440x240 | No | Alpha only | Alpha layer `1440x240` and its own mask |
| 10 | `player/PlayerAlbumArtWithGlow.kt:53`, offset at `PlayerSheetSkeleton.kt:410` | `size(size)`, `offset` | Square 165 to 1200 | No | Size and position | None |
| 11 | `player/PlayerAlbumArtWithGlow.kt:63-70` | `requiredSize(quantised)`, `scale`, `blur(20.dp, Unbounded)`, `background` | Square 195 to 1215 in 15 px steps | No (a third of the screen at most) | Size | `drawLayer NxN`, 29 sizes |
| 12 | `components/PlayerAlbumArt.kt:28-31` | `size`, `scale`, `clip(shapes.small)` | Same as the art | No | Size | None (equal corners) |
| 13 | `player/PlayerSkeletonExpandedContent.kt:46-49` | `offset(y = titleRowY)`, `alpha`, 0 to 1 over 0.8-1 | 1320x161 | No | Alpha and position | Alpha layer `1320x161` |
| 14 | `player/PlayerSkeletonQueueContent.kt:170-177` | Header `offset`, `alpha(queueContentAlpha)`, 0 to 1 over 0.7-1 | 1260x510 | No | Alpha and a 40 dp slide | Alpha layer `1260x510` |
| 15 | `player/PlayerSkeletonQueueContent.kt:229-232` | Empty-queue box: `offset`, `height`, `alpha` | 1440 x (sheet height minus header and controls) | Partly | Height, alpha | Not exercised |
| 16 | `player/PlayerSkeletonQueueContent.kt:163` | `BoxWithConstraints(fillMaxSize)` | Sheet size | Yes | Constraints change every frame | Subcomposition each frame |
| 17 | `player/PlayerSkeletonQueueContent.kt:252-259` | `QueueList`: `offset`, `fillMaxWidth`, `fillMaxHeight` | 1440 x sheet height | Yes | Height equals the sheet height | Row 18's layer size |
| 18 | `player/PlayerSkeletonQueueContent.kt:261-267, 298-299` | `graphicsLayer { alpha; compositingStrategy }`, `Offscreen` while alpha is above 0 | 1440 x sheet height | Yes | Yes | `drawLayer 1440xH`, H = sheet height |
| 19 | `player/PlayerSkeletonQueueContent.kt:268-291` | `drawWithCache`, gradient `drawRect(blendMode = DstIn)` | Same | Yes | Size, slide and strength | `synthX.QueueMaskLayer.draw` every frame |
| 20 | `player/PlayerSkeletonControlsOverlay.kt:161-164` | `offset(y = slide + bottomInset)`, `height(300.dp)`, `alpha`, 0 to 1 over 0.9-1 | 1440x1125 | No (37% of the screen) | Alpha and position; squeezed while the sheet is shorter than 300 dp | Alpha layer `1440x1125` |
| 21 | `player/PlayerSkeletonControlsOverlay.kt:241, 283` | Icon `alpha(enabled or Alpha.Disabled)` | 210x210 | No | No, but squeezed by row 20 | Alpha layer `210xN`; icon bitmaps redrawn |
| 22 | `components/QueueRow.kt:213-225` | `drawWithContent` with `clipRect`, `background(Error)` for the swipe reveal | One row | No | No | None |
| 23 | `components/SongIdentity.kt:53`, `components/DragResistantClickable.kt:133` | `clip(shapes.small)`, `clip(CircleShape)` | Small | No | No | None |
| 24 | `player/PlayerSkeletonMiniContent.kt:82`, `PlayerSkeletonExpandedContent.kt:65, 73`, `PlayerSkeletonQueueContent.kt:195` | `basicMarquee` | Text width | No | No | Not separable |

Not present in these files: `Modifier.shadow`, any `BlendMode` other than row 19's, any
`RenderEffect` other than rows 7 and 11.

## 4. Hypotheses for the varying-size layer, ranked

| Rank | Hypothesis | Evidence for | Evidence against | Verdict |
|---|---|---|---|---|
| 1 | The sheet's top-rounded clip (row 4) forces a software clip mask sized to the sheet | Upload is 1 byte/px, CPU-drawn, pooled, 1440 x sheet height in every moving frame; a second mask sized to the mini row and present only with it; none at rest; presence depends on the sub-pixel radius and matches the float round-trip count (28.0 expected, 29 found); it is the only non-rectangular clip around the background | The trace never names the owner; the Skia mechanism is from memory of the source | Explains every upload in both runs. Experiment 1 tests it directly. |
| 2 | The queue list's `Offscreen` layer (row 18) is reallocated as it resizes | `drawLayer 1440xH` equals the sheet height in 280 of 280 frames; 120 surface changes; 640 of the 779 ms of layer allocations fall on those frames; `flush layers` 1927 ms against 572 ms in player mode | It cannot be the upload: the uploads are identical in player mode, where the list is not composed, and a layer is a render target, not a CPU upload | Confirmed as a second, queue-only varying layer. Experiment 3. |
| 3 | The background's RenderEffect layer (row 7) | 18 render passes instead of 1 whenever the sheet's pixel height changes, so its filter chain re-runs per frame | Its layer is 1440x3064 in every one of its 579 redraws; it redraws at 15 fps while the upload happens at 60; 494 mask frames have no background redraw | Not the varying layer. A secondary per-frame GPU cost, about 1.2 ms of CPU. |
| 4 | The art glow's blur layer (row 11) | 29 sizes, 210 surface changes | At most 1215 px square; 56 ms of allocations in 10 iterations | Real but small. |
| 5 | The bottom nav's alpha layer (row 1) | 45 sizes, 80 surface changes | 266 px tall at most; 13 to 31 ms of allocations | Real but small. Experiment 4. |
| 6 | The alpha fades (rows 9, 13, 14, 20) | Each becomes an offscreen layer while its alpha is between 0 and 1 | Fixed sizes; one allocation per appearance | Not varying. The controls' 1440x1125 layer costs about 2.6 ms once per expand. |
| 7 | A bitmap re-upload (album art or background art) | None | No main-flush upload at any bitmap's size; bitmap uploads are all in `prepareTree` and are icon-sized | Rejected. |

## 5. Queue mode on the main thread

Self time per frame that composes the sheet (939 and 940 frames):

| Slice | Player ms | Queue ms | Difference | Note |
|---|---|---|---|---|
| `postAndWait` | 1.394 | 1.712 | +0.318 | Waiting for the RenderThread; follows from 2.4 |
| `AndroidOwner:measureAndLayout` | 0.489 | 0.710 | +0.220 | Total including nested composition: 0.60 to 1.00 |
| `Compose:recompose` | 0.136 | 0.280 | +0.144 | 3.64 to 4.29 runs per frame |
| `synthX.QueueMaskLayer.draw` | 0 | 0.069 | +0.069 | Once per frame, max 0.82 |
| `Compose:applyChanges` | 0.204 | 0.267 | +0.063 | |
| `synthX.QueueRow` | 0 | 0.057 | +0.057 | 32 compositions per iteration |
| `Compose:onPositionedCallbacks` | 0.013 | 0.049 | +0.036 | |
| Everything else | | | +0.09 | |
| Sum | 4.18 | 5.18 | +1.00 | |

By sheet progress, queue run:

| Progress | Frames | `measureAndLayout` total ms | `Compose:recompose` runs | `QueueMaskLayer.draw` ms | Row compositions per frame |
|---|---|---|---|---|---|
| Under 0.33 | 180 | 1.48 | 4.2 | 0.089 | 0.17 |
| 0.33 to 0.7 | 64 | 2.11 | 8.8 | 0.282 | 2.50 |
| 0.7 and up | 400 | 0.90 | 4.3 | 0.063 | 0.33 |
| Tail and rest | 296 | 0.61 | 3.4 | 0.026 | 0 |

What drives it:

1. **The list's bounds follow the sheet.** `fillMaxHeight()` at
   `PlayerSkeletonQueueContent.kt:259` gives the `LazyColumn` the sheet height as its viewport,
   so it is re-measured on every frame and composes rows as the viewport grows: 10 new rows per
   expand, one or two per frame over frames 1 to 13, most of them while the list is still
   invisible (its alpha is 0 below progress 0.7).
2. **Every visible row recomposes in one frame, twice per cycle.** `queueContentEnabled`
   (`PlayerSkeletonQueueContent.kt:133`) flips where progress crosses 0.7 and reaches every row
   as a value parameter (`QueueList.kt:102`, also captured at `:93`, `:100`, `:148`, `:153`).
   That is 10 rows at frame 7 and 12 at frame 55, 22 of the 32 compositions per iteration. Frame
   7 has 5.9 ms of recomposition and 3.1 ms of layout against 1.9 and 1.6 ms in its neighbours
   (player mode: 1.6 and 0.9); frame 55 has 3.1 ms of recomposition. Main-thread time excluding
   the RenderThread wait is p99 10.2 ms in queue mode and 6.4 ms in player mode.
3. **`QueueMaskLayer.draw` runs in every sheet frame**, 940 of 940, including the frames below
   progress 0.7 where the list's alpha is 0.
4. **More waiting on the RenderThread**, from the layer reallocation in 2.4.

The per-frame recomposition of `PlayerSkeletonQueueContent` itself and its
`BoxWithConstraints` subcomposition (row 16) happen in both modes and are not part of the
difference.

What `QueueMaskLayer.draw` does. It is the `onDrawWithContent` block at
`PlayerSkeletonQueueContent.kt:281-290`: it records the list's content into the list's layer
(`drawContent()`), then, when the mask strength is above 0, draws a rectangle over the whole
list with a vertical gradient and `BlendMode.DstIn`, which fades the rows out where the controls
sit. It re-runs every frame for two reasons: the list's size changes every frame (reason 1),
which invalidates the `drawWithCache` cache and rebuilds the gradient brush, and the block
captures values that change every frame (the slide, the bottom inset, the alphas), so each
recomposition installs a new one. On the main thread that is 0.03 to 0.28 ms. The larger cost
is on the RenderThread: because the layer is `Offscreen` and its content was re-recorded, HWUI
re-renders the whole list into the offscreen surface every frame (290 `drawLayer` in 10
iterations) and reallocates that surface 12 times per iteration.

## 6. Experiments

Each is a local source change, one run of
`scripts/perf/run-benchmark.fish <serial> --scenario expandCollapse,queueExpandCollapse`, then
`analyze_traces.py` and `compare.py` against this run. The benchmark app is the `benchmark`
build type, so `BuildConfig.DEBUG` toggles do not reach it and no debug toggle is proposed.
Expected numbers are estimates from frame classes inside this run, not measurements. Current
values, animation windows: `expandCollapse` cpu p50 / p90 / p99 10.57 / 21.33 / 34.37,
`queueExpandCollapse` 11.92 / 23.66 / 35.34. None of these touch `PlayerSheetMotion.kt`.

### 1. Whole-pixel corner radius

- **Change.** `player/PlayerSheetSkeleton.kt`: keep line 179, round the radius to whole pixels
  (`with(density) { cornerRadius.toPx() }.roundToInt().toFloat()`) and build the shape at line
  252 from that pixel value with the `RoundedCornerShape(topStart: Float, topEnd: Float)`
  overload. Pass pixels, not a dp converted back, so no fraction returns.
- **Expect.** 1440-wide uploads 819 to about 0 per 10 iterations; the report's distinct upload
  sizes 67 to about 8; `hwuiTask` CPU in sheet frames to 0. `RT: texture upload` 1.00 to about
  0.1 ms per frame. `RT: GPU memory alloc/free` 1.50 to about 0.3 (`expandCollapse`) and 1.75
  to about 1.4 (`queueExpandCollapse`). `main: waiting on RenderThread` 1.23 to under 0.5. cpu
  p50 / p90 / p99 about 6-8 / 11-13 / 17-20 in `expandCollapse` and about 8-10 / 17-20 / 25-30
  in `queueExpandCollapse`, where the queue layer still reallocates.
- **Visuals.** The radius moves in 90 whole-pixel steps instead of continuously, at most half a
  pixel from today's value. Not expected to be visible.
- **If uploads remain.** Run once with `clip(RectangleShape)` at line 252 as a control (square
  corners, never ship). No uploads then means the rounded clip is the owner and the shape needs
  equal corners on a clip box that extends below the visible bottom; uploads still present
  means hypothesis 1 is wrong.

### 2. Queue rows do not take `enabled` as a value

- **Change.** `components/QueueList.kt`: read `enabled` through `rememberUpdatedState` and use
  that inside the lambdas at lines 93, 100, 138, 148 and 153; pass rows a `() -> Boolean`
  instead of the Boolean at line 102. `components/QueueRow.kt:117, 130`: take that lambda in
  place of the Boolean (the row already reads it only inside its settle watcher).
- **Expect.** Row compositions per iteration 32 to 10. Frame 7 recomposition 5.9 to about 2 ms
  and layout 3.1 to about 1.6 ms; frame 55 recomposition 3.1 to about 1.2 ms. Queue main-thread
  p99 excluding the RenderThread wait 10.2 to about 7 ms. cpu p50 and p90 unchanged, p99 down by
  1 to 2 ms. `expandCollapse` and all RenderThread numbers unchanged.
- **Visuals.** None. The move-up and move-down accessibility actions stay listed while the list
  is disabled mid-fade and do nothing then; today they are removed.
- **Gesture rules.** `anchoredDraggable` stays ungated; this is the `rememberUpdatedState`
  pattern the rules ask for.

### 3. Queue list keeps the full sheet height

- **Change.** `player/PlayerSkeletonQueueContent.kt:259`: replace `fillMaxHeight()` with
  `wrapContentHeight(align = Alignment.Top, unbounded = true).requiredHeight(fullHeight)`, the
  pattern the background uses, with `fullHeight` passed from `PlayerSheetSkeleton.kt:421` as at
  line 392. At lines 272-273 `size.height` stands for the sheet height; replace it with the
  enclosing `BoxWithConstraints`' `maxHeight` in px so the mask stays where it is.
- **Expect, queue run.** Queue list layer sizes 29 to 1 and surface changes 120 to 10 per 10
  iterations. `vkAllocateMemory` in `flush layers` 779 to about 150-250 ms; `flush layers` 1927
  to about 900-1100 ms (the layer is still re-rendered every frame). `RT: GPU memory
  alloc/free` 1.75 to about 1.0 ms per frame, or about 0.3 with experiment 1.
  `main: measure/layout` 0.72 to about 0.5. Row compositions from viewport growth 10 to 0 per
  iteration. cpu p50 / p90 / p99 about 10.5-11 / 20-21 / 30 alone. `expandCollapse` unchanged.
- **Visuals.** None intended: rows are placed from the top and the sheet's clip still cuts them
  at its bottom edge. One 18 MB layer is still allocated once per expand at progress 0.7.
- **Gesture rules.** The list stays conditionally composed (line 241). Check by hand that a
  touch below the collapsed mini bar does not reach the taller list; the sheet's clip should
  bound hit-testing as it does today.

### 4. Bottom nav is cropped, not squeezed

- **Change.** `SynthXBottomNav.kt:228-232`: leave `offset`, `height` and `clipToBounds` on the
  outer `Column`; move the divider and `NavigationBar` into an inner `Column` that carries
  `wrapContentHeight(align = Alignment.Top, unbounded = true).requiredHeight(BottomNavHeight)`
  and the `graphicsLayer { alpha }`.
- **Expect.** Nav layer sizes 45 to 1 and its redraws 439 to about 20 per 10 iterations.
  `Bitmap#prepareToDraw 105x105` 377 to about 0 (the three nav icons are redrawn on the CPU each
  frame today because their box is squeezed), and fewer `Constructing StaticLayout`. Frame-time
  percentiles within run-to-run noise, under 0.3 ms. This removes a varying layer; it is not a
  frame-time fix.
- **Visuals.** Small but real: the bar keeps its layout and is cut off from the bottom as it
  slides down and fades, instead of compressing. Look at it on the phone.

### 5. Read sheet progress in layout and draw only

- **Change.** `MainScaffold.kt:182-183, 287`: make `homeAmbientPaused` a `derivedStateOf` and
  pass progress down as `() -> Float`. `SynthXBottomNav.kt:61, 106, 180, 221-232`: read it only
  inside `Modifier.layout` or `offset { }` and the `graphicsLayer { }` block.
- **Expect.** `synthX.MainShell`, `MainShell.content`, `BottomChrome` and `BottomNav` sections in
  939 of 1086 animation frames to about 0. `main: recomposition` 1.29 to about 0.8 ms per frame
  (`expandCollapse`) and 1.55 to about 1.05 (`queueExpandCollapse`); main p50 2.99 to about
  2.5. cpu p50 down by 0.4 to 0.5 ms, p90 and p99 within noise. The nav layer's height in a
  frame matches that frame's sheet height instead of the previous frame's.
- **Visuals.** The nav moves in the same frame as the sheet (see 7.1).
- **Risk.** Three call sites and a change in how state flows, so it goes last.

Not proposed: composing the queue list only once its alpha is above 0. It would remove the
invisible work below progress 0.7 but move all row composition into the single frame where
progress crosses 0.7, the frame that is already the slowest on the main thread.

## 7. Other observations

1. **The nav trails the sheet by one frame.** `onProgressChanged(progress)` is called during
   the sheet's composition (`PlayerSheetSkeleton.kt:156`) and writes state that the nav,
   composed earlier in the same pass, has already read (`MainScaffold.kt:182, 288`). The nav
   layer's height in frame n+1 matches the sheet height of frame n in 824 frames. Mid-animation
   that is up to about 33 px. On expand the sheet covers the difference; on collapse it should
   leave a strip between the sheet's bottom edge and the nav for one frame at a time. I have
   not confirmed it is visible.
2. **The shell recomposes on every sheet frame.** `NormalShell` reads `playerProgress` in its
   body, so `synthX.MainShell` costs 0.50 ms per frame in both modes, 0.25 ms of it in
   `SynthXBottomNav`. Experiment 5 covers it.
3. **The controls overlay is squeezed while the sheet is shorter than 300 dp.** Its transport
   icons shrink through 210 x 203, 140, 94, 56, 27 and 7 px in the last frames of a collapse,
   and each step redraws the icon bitmaps on the CPU. Small, and invisible because the overlay's
   alpha is 0 there.
4. **The staging buffer.** In player mode the over-4-MiB upload allocates in 93% of frames and
   in queue mode in 18%. The mask is the same; something keeps the buffer alive in one case and
   not the other. If experiment 1 works this stops mattering.
5. **One unattributed allocation in queue mode.** Frame 32 of every queue iteration has a
   single 10 to 18.6 ms `vkAllocateMemory` in `flush layers` (102 ms in total, and the largest
   allocations of the run) while only the queue list layer is redrawn and its size has not
   changed. It falls just after the queue header's fade completes. The trace does not say what
   is allocated.

## 8. Limits and how to re-check

- One run, one phone, 10 iterations per scenario. `slowDrag`, `fling` and `homeExpandCollapse`
  were not re-analysed; their size tables in `trace-analysis.md` show the same 1440xN family.
- The frame classes in 2.4 are observational. Mask size and position in the animation are
  correlated, so the mask-less frames 29, 35 and 57 are the cleanest estimate of its cost.
- The Compose, HWUI and Skia behaviour in 2.3 and 2.5 is inferred as stated there.

Mask uploads per frame, for one trace in the Perfetto UI:

```sql
SELECT s.ts, s.dur / 1e6 AS ms, s.name
FROM slice s
JOIN slice parent ON parent.id = s.parent_id
JOIN thread_track tt ON s.track_id = tt.id
JOIN thread t USING (utid)
JOIN process p USING (upid)
WHERE p.name = 'com.v1per.synthx.benchmark'
  AND t.name = 'RenderThread'
  AND parent.name = 'Vulkan finish frame'
  AND s.name GLOB 'Texture upload(*) 1440x*'
ORDER BY s.ts
```

After experiment 1 this should return no rows for the animation frames, and the "RenderThread
texture uploads by size" table in `trace-analysis.md` should lose its 1440xN rows.
