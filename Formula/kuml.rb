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
    # The zip extracts as kuml-<version>/{bin,lib,runtime}/ — strip that prefix.
    libexec.install Dir["kuml-#{version}/*"]
    bin.install_symlink libexec/"bin/kuml"
  end

  test do
    assert_match "Compiles kUML scripts", shell_output("#{bin}/kuml --help")
  end
end
