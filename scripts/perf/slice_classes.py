"""Slice-name classification table for analyze_traces.py.

Every rule below is a name seen in the fixture trace (docs/perf/fixtures, Galaxy S25 Ultra,
API 37, Vulkan renderer). A name no rule matches is reported as unclassified, with its time, so
this table can be extended from a real trace; do not add names from memory.

Rules are (kind, pattern, category); kind is "exact" or "prefix"; the first match wins. Time is
attributed by self time: a slice's own duration minus its direct children.
"""

from __future__ import annotations

import re

EXACT = "exact"
PREFIX = "prefix"

# synthX.* sections take the category of the system slice they sit inside (a composable's
# section inside Compose:recompose is recomposition; a draw block's is draw recording).
INHERIT = "inherit"
UNCLASSIFIED = "unclassified"

# Category -> label, in report order.
CATEGORIES = {
    "main.recomposition": "main: recomposition",
    "main.animation": "main: animation callbacks",
    "main.measure_layout": "main: measure/layout",
    "main.draw_record": "main: draw recording",
    "main.wait_render": "main: waiting on RenderThread",
    "main.traversal": "main: traversal overhead",
    "main.choreographer": "main: choreographer/vsync",
    "main.input": "main: input dispatch",
    "main.accessibility": "main: accessibility (UiAutomator)",
    "rt.sync": "RT: sync frame state",
    "rt.draw": "RT: draw",
    "rt.layers": "RT: layers",
    "rt.gpu_flush": "RT: GPU flush",
    "rt.texture_upload": "RT: texture upload",
    "rt.gpu_memory": "RT: GPU memory alloc/free",
    "rt.buffer_queue": "RT: buffer queue",
    "rt.wait": "RT: waiting on GPU/fence",
    "rt.frame": "RT: frame overhead",
    "other": "other (runtime, binder, kernel)",
    UNCLASSIFIED: "unclassified",
}

_SHARED_RULES = [
    (PREFIX, "synthX.", INHERIT),
    (EXACT, "binder transaction async", "other"),
    (EXACT, "binder async rcv", "other"),
    (EXACT, "wr_ptr_irq", "other"),
    (EXACT, "rd_ptr_irq", "other"),
    (EXACT, "ctl_done_irq", "other"),
    (EXACT, "te_assert_irq", "other"),
    (EXACT, "te_deassert_irq", "other"),
    (EXACT, "encoder_vblank_callback", "other"),
]

MAIN_THREAD_RULES = _SHARED_RULES + [
    (EXACT, "Recomposer:recompose", "main.recomposition"),
    (EXACT, "Compose:recompose", "main.recomposition"),
    (EXACT, "Compose:applyChanges", "main.recomposition"),
    (EXACT, "Compose:sideeffects", "main.recomposition"),
    (EXACT, "Compose:onRemembered", "main.recomposition"),
    (EXACT, "Compose:onForgotten", "main.recomposition"),
    (EXACT, "animation", "main.animation"),
    (EXACT, "Recomposer:animation", "main.animation"),
    (EXACT, "AndroidOwner:measureAndLayout", "main.measure_layout"),
    (EXACT, "TextStringSimpleNode::measure", "main.measure_layout"),
    (EXACT, "TextLayout:initLayout", "main.measure_layout"),
    (EXACT, "Constructing StaticLayout", "main.measure_layout"),
    (EXACT, "Compose:onPositionedCallbacks", "main.measure_layout"),
    (EXACT, "Record View#draw()", "main.draw_record"),
    (EXACT, "AndroidOwner:draw", "main.draw_record"),
    (EXACT, "computePalette", "main.draw_record"),
    (EXACT, "postAndWait", "main.wait_render"),
    (EXACT, "traversal", "main.traversal"),
    (PREFIX, "draw-VRI[", "main.traversal"),
    (PREFIX, "Choreographer#doFrame", "main.choreographer"),
    (PREFIX, "Choreographer#onVsync", "main.choreographer"),
    (EXACT, "Choreographer#scheduleVsyncLocked", "main.choreographer"),
    (PREFIX, "AIDL::cpp::IDisplayEventConnection::requestNextVsync", "main.choreographer"),
    (PREFIX, "mLastFrameTimeNanos:", "main.choreographer"),
    (PREFIX, "deliverInputEvent", "main.input"),
    (PREFIX, "dispatchInputEvent", "main.input"),
    (PREFIX, "ViewPostImeInputStage", "main.input"),
    (PREFIX, "NativePreImeInputStage", "main.input"),
    (PREFIX, "NativePostImeInputStage", "main.input"),
    (PREFIX, "EarlyPostImeInputStage", "main.input"),
    (EXACT, "preDispatchToUnhandledKeyManager", "main.input"),
    (EXACT, "AndroidOwner:onTouch", "main.input"),
    (PREFIX, "sendMessage(inputChannel=", "main.input"),
    (PREFIX, "receiveMessage(inputChannel=", "main.input"),
    (EXACT, "MSG_TOUCH_HINT_TIMEOUT", "main.input"),
    (EXACT, "getAllUncoveredSemanticsNodesToIntObjectMap", "main.accessibility"),
    (EXACT, "Run checkpoint function", "other"),
]

RENDER_THREAD_RULES = _SHARED_RULES + [
    (PREFIX, "DrawFrames ", "rt.frame"),
    (EXACT, "syncFrameState", "rt.sync"),
    (EXACT, "prepareTree", "rt.sync"),
    (PREFIX, "Drawing ", "rt.draw"),
    (PREFIX, "Image sized ", "rt.draw"),
    (EXACT, "flush layers", "rt.layers"),
    (PREFIX, "drawLayer [", "rt.layers"),
    (PREFIX, "alpha caused saveLayer", "rt.layers"),
    (EXACT, "flush commands", "rt.gpu_flush"),
    (EXACT, "Vulkan finish frame", "rt.gpu_flush"),
    (PREFIX, "auto skgpu::ganesh::OpsTask::", "rt.gpu_flush"),
    (EXACT, "FillRectOp", "rt.gpu_flush"),
    (EXACT, "TextureOp", "rt.gpu_flush"),
    (EXACT, "AtlasTextOp", "rt.gpu_flush"),
    (EXACT, "CircularRRectOp", "rt.gpu_flush"),
    (EXACT, "Clear", "rt.gpu_flush"),
    (EXACT, "QueueSubmit", "rt.gpu_flush"),
    (EXACT, "query", "rt.gpu_flush"),
    (PREFIX, "Texture upload(", "rt.texture_upload"),
    (PREFIX, "Bitmap#prepareToDraw ", "rt.texture_upload"),
    (PREFIX, "vkAllocateMemory(", "rt.gpu_memory"),
    (PREFIX, "vkFreeMemory(", "rt.gpu_memory"),
    (PREFIX, "auto skgpu::VulkanAMDMemoryAllocator::", "rt.gpu_memory"),
    (PREFIX, "dequeueBuffer", "rt.buffer_queue"),
    (EXACT, "queueBuffer", "rt.buffer_queue"),
    (PREFIX, "onFrameAvailable - ", "rt.buffer_queue"),
    (PREFIX, "acquireNextBufferLocked - ", "rt.buffer_queue"),
    (EXACT, "acquireBuffer", "rt.buffer_queue"),
    (EXACT, "applyGrallocMetadataLocked", "rt.buffer_queue"),
    (EXACT, "addAndGetFrameTimestamps", "rt.buffer_queue"),
    (EXACT, "getFrameTimestamps", "rt.buffer_queue"),
    (EXACT, "setSurfaceDamage", "rt.buffer_queue"),
    (PREFIX, "VRI[", "rt.buffer_queue"),
    (PREFIX, "setFrameTimelineInfo(", "rt.buffer_queue"),
    (PREFIX, "Transaction::setFrameTimelineInfo", "rt.buffer_queue"),
    (PREFIX, "frameTimelineInfo(", "rt.buffer_queue"),
    (EXACT, "dispatchSetFrameTimelineInfo", "rt.buffer_queue"),
    (EXACT, "waitForever", "rt.wait"),
    (PREFIX, "Trace GPU completion fence", "rt.wait"),
    (PREFIX, "Trace HWC release fence", "rt.wait"),
    (PREFIX, "HWC release fence", "rt.wait"),
    (EXACT, "dumpingMemoryStatistics", "rt.frame"),
    (EXACT, "notifyFramePending", "rt.frame"),
    (EXACT, "reportMetricsWithPresentTime", "rt.frame"),
    (EXACT, "sendCpuLoadResetHint", "rt.frame"),
    (EXACT, "CPU_LOAD_RESET hint sent", "rt.frame"),
    (PREFIX, "AIDL::ndk::android.os.IHintSession::sendHint", "rt.frame"),
]

RULES_BY_ROLE = {"main": MAIN_THREAD_RULES, "render": RENDER_THREAD_RULES}

# One frame on each thread, named by the vsync id that ties the two together.
MAIN_FRAME_ROOT = re.compile(r"^Choreographer#doFrame (\d+)$")
RENDER_FRAME_ROOT = re.compile(r"^DrawFrames (\d+)$")

# Main-thread slices that mean "the app is animating or being touched": the app's own sections
# and input delivery. A dense run of them is an animation window; the rest of the trace is idle.
ACTIVITY_MARKERS = [
    (PREFIX, "synthX."),
    (PREFIX, "deliverInputEvent"),
    (PREFIX, "dispatchInputEvent"),
]

# The sheet's composition section; frames without it were produced by something else.
SHEET_SECTION = "synthX.PlayerSheet"
SECTION_PREFIX = "synthX."

_TEXTURE_SIZE = re.compile(r"^(?:Texture upload\(\d+\)|Bitmap#prepareToDraw) (\d+x\d+)$")
# The word boundary keeps "1440x3064" from reading as the hex number "0x3064".
_HEX = re.compile(r"\b0x[0-9a-fA-F]+")
_NUMBER = re.compile(r"-?\d+(?:\.\d+)?")


def matches(kind: str, pattern: str, name: str) -> bool:
    return name == pattern if kind == EXACT else name.startswith(pattern)


def classify(name: str, role: str) -> str | None:
    """The category for a slice name on a thread role, INHERIT, or None when no rule matches."""
    for kind, pattern, category in RULES_BY_ROLE[role]:
        if matches(kind, pattern, name):
            return category
    return None


def is_activity_marker(name: str) -> bool:
    return any(matches(kind, pattern, name) for kind, pattern in ACTIVITY_MARKERS)


def normalize(name: str) -> str:
    """Collapses ids, addresses, sizes and vsync numbers so repeats of one slice group together."""
    if name.startswith(SECTION_PREFIX):
        return name
    return _NUMBER.sub("<n>", _HEX.sub("<hex>", name))[:120]


def texture_size(name: str) -> str | None:
    """'1440x3064' from a texture upload or bitmap preparation slice, else None."""
    match = _TEXTURE_SIZE.match(name)
    return match.group(1) if match else None
