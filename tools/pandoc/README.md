# kuml Pandoc Filter

`kuml.lua` is a Pandoc Lua filter that renders ```` ```kuml ```` fenced code
blocks into inline SVG by invoking the `kuml` CLI for each block.

## Requirements

- [Pandoc 3.x](https://pandoc.org/installing.html)
- `kuml` CLI on `PATH` (or set the `KUML` environment variable)

## Usage

```bash
pandoc --lua-filter=tools/pandoc/kuml.lua docs/getting-started.md \
       -o docs/getting-started.html
```

The filter replaces each `kuml`-classed code block with a `RawBlock` containing
the rendered `<svg>` element. The original script is **not** preserved in the
output. If you need both, use `kuml markdown --mode linked-svg` instead.

## Example input

````markdown
# Hello kUML

```kuml
classDiagram(name = "Hello") {
    val a = classOf(name = "A")
    val b = classOf(name = "B")
    association(source = a, target = b)
}
```
````

## Configuration

| Variable | Default | Purpose                                                 |
|----------|---------|---------------------------------------------------------|
| `KUML`   | `kuml`  | Path to the `kuml` CLI binary (useful in CI / sandboxes) |

## Behaviour on failure

If `kuml render` exits non-zero for a block (e.g. invalid DSL), the filter
writes a warning to stderr and leaves the original `CodeBlock` in place. This
keeps `pandoc` from aborting on a single bad block.
