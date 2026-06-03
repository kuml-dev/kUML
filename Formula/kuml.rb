class Kuml < Formula
  desc "Kotlin-based UML/C4 modelling and rendering tool"
  homepage "https://github.com/kuml-dev/kuml"

  # url, sha256 and version are rewritten automatically by the release workflow
  # in kuml-dev/kuml on every `v*.*.*` tag (see .github/workflows/release.yml
  # there; the dispatch event is consumed by update-formula.yml in this tap).
  url "https://github.com/kuml-dev/kuml/releases/download/v0.1.0/kuml-runtime-0.1.0.zip"
  sha256 "REPLACE_ON_RELEASE_WITH_ACTUAL_SHA256"
  version "0.1.0"
  license "Apache-2.0"

  # The kuml-runtime-<version>.zip is a self-contained bundle: app jars +
  # a jlink-built Java 21 runtime. No external JDK dependency.

  def install
    # The zip extracts as kuml-<version>/{bin,lib,runtime}/, but Homebrew's
    # extract step already CDs into that single top-level directory before
    # running this method — so the bin/lib/runtime tree is right here in `.`.
    libexec.install Dir["*"]
    # Defensive chmod: pre-v0.2.0 release artefacts shipped without exec
    # bits inside the zip. Newer builds set 0755 on bin/kuml and the
    # runtime/bin/* binaries already, so this is a no-op for them.
    chmod 0755, libexec/"bin/kuml"
    Dir[libexec/"runtime/bin/*"].each { |f| chmod 0755, f }
    chmod 0755, libexec/"runtime/lib/jspawnhelper" if File.exist?(libexec/"runtime/lib/jspawnhelper")
    bin.install_symlink libexec/"bin/kuml"
  end

  test do
    assert_match "Compiles kUML scripts", shell_output("#{bin}/kuml --help")
  end
end
