--[[
kuml.lua — Pandoc Lua filter for kUML

Transforms `kuml`-classed fenced code blocks into rendered SVG by invoking
the `kuml render` CLI for each block. The resulting SVG is inlined as a
`RawBlock` (HTML format) so it survives further Pandoc processing.

Usage:
    pandoc --lua-filter=tools/pandoc/kuml.lua input.md -o output.html

Requirements:
    - `kuml` on PATH (or override via $KUML environment variable)
    - Pandoc 3.x (no external Lua deps)

Block attributes (optional):
    ```kuml {.kuml name="hello"}
    classDiagram(name = "Hello") { … }
    ```
    The `name` attribute is used as a suggested file stem (logging only;
    Pandoc consumes the SVG inline).

Exit semantics:
    If `kuml render` fails for a block, the original code block is left
    in place and a warning is written to stderr.
]]

local function kuml_bin()
    return os.getenv("KUML") or "kuml"
end

local function write_tempfile(content, suffix)
    local tmp = os.tmpname() .. suffix
    local fh = assert(io.open(tmp, "w"))
    fh:write(content)
    fh:close()
    return tmp
end

local function read_file(path)
    local fh = io.open(path, "r")
    if not fh then return nil end
    local data = fh:read("*a")
    fh:close()
    return data
end

local function run(cmd)
    -- Pandoc's pandoc.pipe / pandoc.system.with_temporary_directory are
    -- preferred, but os.execute keeps this filter dependency-free.
    return os.execute(cmd)
end

function CodeBlock(block)
    if not block.classes:includes("kuml") then
        return nil
    end

    local script_path = write_tempfile(block.text, ".kuml.kts")
    local svg_path = script_path:gsub("%.kuml%.kts$", ".svg")
    local bin = kuml_bin()
    local cmd = string.format("%s render -f svg -o %q %q 2>/dev/null", bin, svg_path, script_path)

    local ok = run(cmd)
    if not ok then
        io.stderr:write("[kuml.lua] render failed for block — leaving original in place\n")
        os.remove(script_path)
        return nil
    end

    local svg = read_file(svg_path)
    os.remove(script_path)
    os.remove(svg_path)

    if not svg then
        io.stderr:write("[kuml.lua] could not read rendered SVG — leaving original block\n")
        return nil
    end

    return pandoc.RawBlock("html", svg)
end
