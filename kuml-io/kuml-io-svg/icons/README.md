# Note kind symbols

The 14 symbols in `NoteIcons.kt` (24 x 24 grid, filled with `fill-rule="evenodd"`) were drawn by hand as polygons.
A small generator script computed the circle and stroke outlines as polygon points; no icon set or third-party artwork is used.
VectorCraft was not used for the final paths (time box). Each symbol is one `d` string with several `M ... Z` sub-paths;
glyphs (the "i", "!", check, ...) are separate sub-paths that `evenodd` punches out of the body.

Rules the tests enforce: every pair of symbols differs in at least 12 percent of the pixels of a 16 x 16 mask,
and every symbol fills between 15 and 85 percent of its box.
