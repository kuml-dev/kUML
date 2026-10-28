# ADR-0016: SysML 2 packages, imports and views in diagrams

Status: proposed (concept note, no implementation in wave B)

## Context
SysML 2 models are organised in packages, with imports and views. kUML SysML 2 diagrams project a flat
model by element id lists.

## Options
1. Keep flat ids; packages only as naming prefixes (current).
2. Package nodes as layout groups in BDD (like UML packages), imports as dashed dependency edges.
3. Views as named filters (expose + filter) evaluated into the existing id lists.

## Decision
Defer. Option 3 is the cheapest next step because it only produces `elementIds`; option 2 needs
compound layout and is tied to the multi-level IBD work (wave B2).
